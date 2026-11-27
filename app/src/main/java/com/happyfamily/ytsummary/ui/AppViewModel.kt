package com.happyfamily.ytsummary.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.happyfamily.ytsummary.data.GitHub
import com.happyfamily.ytsummary.data.GitHubException
import com.happyfamily.ytsummary.data.KeyStatus
import com.happyfamily.ytsummary.data.Running
import com.happyfamily.ytsummary.data.Settings
import com.happyfamily.ytsummary.data.Store
import com.happyfamily.ytsummary.data.Task
import com.happyfamily.ytsummary.data.VideoItem
import com.happyfamily.ytsummary.data.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

data class UiState(
    val items: List<VideoItem> = emptyList(),
    val currentId: String? = null,
    val running: Map<String, Running> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val settings: Settings = Settings(),
    val syncing: Boolean = false,
    val lastSync: Long = 0,
    val keyStatus: KeyStatus? = null,
    val message: String? = null,
) {
    fun item(id: String?) = items.firstOrNull { it.id == id }
    fun isRunning(id: String, t: Task) = running.containsKey("$id/${t.key}")
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val lock = Mutex()
    private val _state = MutableStateFlow(UiState(settings = store.settings(), lastSync = store.lastSync))
    val state: StateFlow<UiState> = _state
    /** 공유로 열렸을 때 홈 화면으로 이동 신호 */
    val goHome = MutableStateFlow(false)

    init {
        reload()
        if (_state.value.settings.connected) sync(quiet = true)
    }

    private fun reload() {
        val all = store.loadAll().sortedByDescending { it.createdAt }
        _state.update { it.copy(items = all) }
    }

    private fun toast(msg: String) = _state.update { it.copy(message = msg) }
    fun messageShown() = _state.update { it.copy(message = null) }

    private fun gh() = GitHub(_state.value.settings)

    // ---------- 영상 불러오기 ----------

    /** 공유받은 텍스트 또는 붙여넣은 링크 */
    fun open(text: String?): Boolean {
        val id = YouTube.parseId(text)
        if (id == null) {
            if (!text.isNullOrBlank()) toast("유튜브 링크를 찾지 못했습니다")
            return false
        }
        _state.update { it.copy(currentId = id) }
        if (store.load(id) == null) {
            store.saveMeta(VideoItem(id = id))
            reload()
        }
        val item = store.load(id)
        if (item != null && item.title.isBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { YouTube.fetchInfo(id) }.onSuccess { (title, channel) ->
                    lock.withLock {
                        store.load(id)?.let { store.saveMeta(it.copy(title = title, channel = channel)) }
                    }
                    reload()
                }
            }
        }
        return true
    }

    // ---------- 작업 실행 ----------

    private fun setRunning(id: String, t: Task, msg: String?) = _state.update {
        val k = "$id/${t.key}"
        val prev = it.running[k]
        it.copy(running = if (msg == null) it.running - k
        else it.running + (k to Running(msg, prev?.startedAt ?: System.currentTimeMillis())))
    }

    fun run(id: String, task: Task, regenerate: Boolean = false) {
        if (_state.value.isRunning(id, task)) return
        val s = _state.value.settings
        viewModelScope.launch(Dispatchers.IO) {
            setRunning(id, task, "${task.label} 준비 중…")
            try {
                if (task == Task.TRANSCRIPT) {
                    if (s.deviceTranscriptFirst) {
                        setRunning(id, task, "자막 추출 중…")
                        val text = runCatching { YouTube.fetchTranscript(id, s.language) }.getOrNull()
                        if (text != null) {
                            saveResult(id, task, text)
                            pushTranscript(id, text, s)
                            toast("대본을 추출했습니다")
                            return@launch
                        }
                        setRunning(id, task, "자막이 없어 Gemini가 받아쓰는 중…")
                    }
                    remote(id, task, regenerate)
                } else {
                    // 요약 품질을 위해 대본을 먼저 확보해 저장소에 올려둔다
                    val local = store.load(id)
                    var transcript = local?.contents?.get(Task.TRANSCRIPT)
                    if (transcript == null && s.deviceTranscriptFirst && local?.remote?.contains(Task.TRANSCRIPT) != true) {
                        setRunning(id, task, "자막 확인 중…")
                        transcript = runCatching { YouTube.fetchTranscript(id, s.language) }.getOrNull()
                        if (transcript != null) saveResult(id, Task.TRANSCRIPT, transcript)
                    }
                    if (transcript != null && local?.remote?.contains(Task.TRANSCRIPT) != true) {
                        pushTranscript(id, transcript, s)
                    }
                    remote(id, task, regenerate)
                }
                toast("${task.label} 완료")
            } catch (e: Exception) {
                toast(e.message ?: "${task.label} 실패")
            } finally {
                setRunning(id, task, null)
            }
        }
    }

    private suspend fun saveResult(id: String, task: Task, text: String, remote: Boolean = false) {
        lock.withLock {
            store.saveContent(id, task, text)
            val item = store.load(id) ?: VideoItem(id = id)
            store.saveMeta(item.copy(remote = if (remote) item.remote + task else item.remote))
        }
        reload()
    }

    private suspend fun markRemote(id: String, task: Task) {
        lock.withLock { store.load(id)?.let { store.saveMeta(it.copy(remote = it.remote + task)) } }
        reload()
    }

    private suspend fun pushMeta(gh: GitHub, id: String) {
        val item = store.load(id) ?: return
        if (gh.fileSha("videos/$id/meta.json") == null) {
            gh.putFile("videos/$id/meta.json", item.metaJson().toString(2), "메타: ${item.title.ifBlank { id }}")
        }
    }

    private suspend fun pushTranscript(id: String, text: String, s: Settings) {
        if (!s.connected) return
        runCatching {
            val gh = GitHub(s)
            gh.ensureDataBranch()
            pushMeta(gh, id)
            gh.putFile("videos/$id/transcript.md", text, "대본: $id")
            markRemote(id, Task.TRANSCRIPT)
        }.onFailure { toast("저장소 업로드 실패: ${it.message}") }
    }

    /** GitHub Actions(시크릿의 Gemini 키)로 처리하고 결과가 저장소에 올라올 때까지 기다린다 */
    private suspend fun remote(id: String, task: Task, regenerate: Boolean) {
        val s = _state.value.settings
        if (!s.connected) throw IllegalStateException("설정에서 GitHub 토큰을 먼저 입력하세요")
        val gh = GitHub(s)
        val path = "videos/$id/${task.file}"
        val errPath = "videos/$id/error-${task.key}.json"

        gh.ensureDataBranch()
        pushMeta(gh, id)
        val oldSha = gh.fileSha(path)
        if (oldSha != null && !regenerate) {
            // 이미 저장소에 있음 → 내려받기만
            gh.getFile(path)?.let { saveResult(id, task, it.first, remote = true) }
            return
        }

        val requestId = UUID.randomUUID().toString().take(8)
        try {
            gh.dispatch(id, task, requestId, s.language)
        } catch (e: GitHubException) {
            if (e.code == 404) throw IllegalStateException(
                "처리 워크플로(process.yml)를 찾지 못했습니다. '${s.workflowRef}' 브랜치에 있는지, 토큰에 Actions 권한이 있는지 확인하세요")
            throw e
        }

        val start = System.currentTimeMillis()
        var loop = 0
        var runDoneAt = 0L
        while (System.currentTimeMillis() - start < 15 * 60_000) {
            delay(5_000)
            loop++
            val sec = (System.currentTimeMillis() - start) / 1000
            setRunning(id, task, "${task.label} 만드는 중… ${sec}초 (GitHub · Gemini)")

            val sha = runCatching { gh.fileSha(path) }.getOrNull()
            if (sha != null && sha != oldSha) {
                gh.getFile(path)?.let { saveResult(id, task, it.first, remote = true) }
                if (task != Task.TRANSCRIPT) refreshTranscriptFlag(gh, id)
                loadKeyStatus()
                return
            }
            if (loop % 3 == 0) {
                runCatching { gh.getFile(errPath) }.getOrNull()?.let { (txt, _) ->
                    val o = runCatching { JSONObject(txt) }.getOrNull()
                    if (o?.optString("request_id") == requestId) {
                        throw IllegalStateException(o.optString("message").ifBlank { "처리 실패" })
                    }
                }
                val run = runCatching { gh.findRun(requestId) }.getOrNull()
                if (run != null && run.first == "completed") {
                    if (run.second != "success") throw IllegalStateException("GitHub 처리 실패 (Actions 로그 확인)")
                    // 성공했는데 아직 파일이 안 보이면 잠시 더 기다림 (캐시)
                    if (runDoneAt == 0L) runDoneAt = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - runDoneAt > 60_000) throw IllegalStateException("결과 파일을 찾지 못했습니다")
                }
            }
        }
        throw IllegalStateException("시간이 너무 오래 걸립니다. 잠시 후 보관함에서 확인하세요")
    }

    private suspend fun refreshTranscriptFlag(gh: GitHub, id: String) {
        if (runCatching { gh.fileSha("videos/$id/transcript.md") }.getOrNull() != null) markRemote(id, Task.TRANSCRIPT)
    }

    /** 결과 화면을 열 때: 기기에 없으면 저장소에서 내려받기 */
    fun ensureContent(id: String, task: Task) {
        val item = _state.value.item(id) ?: return
        if (item.contents.containsKey(task) || !item.remote.contains(task)) return
        val key = "$id/${task.key}"
        if (key in _state.value.loading) return
        _state.update { it.copy(loading = it.loading + key) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = gh().getFile("videos/$id/${task.file}")
                if (f != null) saveResult(id, task, f.first, remote = true)
                else {
                    lock.withLock { store.load(id)?.let { store.saveMeta(it.copy(remote = it.remote - task)) } }
                    reload()
                }
            } catch (e: Exception) {
                toast("불러오기 실패: ${e.message}")
            } finally {
                _state.update { it.copy(loading = it.loading - key) }
            }
        }
    }

    // ---------- 동기화 ----------

    fun sync(quiet: Boolean = false) {
        val s = _state.value.settings
        if (!s.connected) { if (!quiet) toast("설정에서 GitHub 토큰을 먼저 입력하세요"); return }
        if (_state.value.syncing) return
        _state.update { it.copy(syncing = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val gh = GitHub(s)
                val paths = gh.listAll()
                val byId = paths.mapNotNull { p ->
                    val parts = p.split('/')
                    if (parts.size == 3 && parts[0] == "videos") parts[1] to parts[2] else null
                }.groupBy({ it.first }, { it.second })

                for ((id, files) in byId) {
                    val remote = Task.entries.filter { it.file in files }.toSet()
                    val local = store.load(id)
                    if (local == null) {
                        val meta = runCatching { gh.getFile("videos/$id/meta.json") }.getOrNull()
                        val base = meta?.let { runCatching { VideoItem.fromMeta(JSONObject(it.first)) }.getOrNull() }
                            ?: VideoItem(id = id)
                        lock.withLock { store.saveMeta(base.copy(remote = remote)) }
                    } else {
                        lock.withLock { store.saveMeta(local.copy(remote = remote)) }
                    }
                }
                // 저장소에서 지워진 항목은 원격 표시만 해제 (기기 내용은 유지)
                store.loadAll().filter { it.id !in byId && it.remote.isNotEmpty() }.forEach {
                    lock.withLock { store.saveMeta(it.copy(remote = emptySet())) }
                }
                store.lastSync = System.currentTimeMillis()
                _state.update { it.copy(lastSync = store.lastSync) }
                reload()
                loadKeyStatus()
                if (!quiet) toast("동기화 완료 · 영상 ${byId.size}개")
            } catch (e: Exception) {
                if (!quiet) toast("동기화 실패: ${e.message}")
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        }
    }

    private fun loadKeyStatus() {
        val s = _state.value.settings
        if (!s.connected) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val txt = GitHub(s).getFile("state/gemini.json")?.first ?: return@runCatching null
                val o = JSONObject(txt)
                val ex = o.optJSONObject("exhausted")
                val today = o.optString("today")
                val exhausted = ex?.keys()?.asSequence()
                    ?.filter { ex.optString(it) == today }?.mapNotNull { it.toIntOrNull() }?.toSet().orEmpty()
                KeyStatus(
                    total = o.optInt("total"), lastKey = o.optInt("last_key"),
                    exhausted = exhausted, lastModel = o.optString("last_model"),
                    updatedAt = o.optString("updated_at"),
                )
            }.getOrNull()?.let { ks -> _state.update { it.copy(keyStatus = ks) } }
        }
    }

    // ---------- 삭제 ----------

    fun delete(ids: Set<String>) {
        val s = _state.value.settings
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { store.delete(it) }
            if (_state.value.currentId in ids) _state.update { it.copy(currentId = null) }
            reload()
            if (s.connected) {
                runCatching {
                    val gh = GitHub(s)
                    val all = gh.listAll()
                    ids.forEach { id ->
                        all.filter { it.startsWith("videos/$id/") }.forEach { gh.deleteFile(it, "삭제: $id") }
                    }
                }.onFailure { toast("저장소에서 삭제 실패: ${it.message}") }
            }
            toast("${ids.size}개 삭제했습니다")
        }
    }

    // ---------- 설정 ----------

    fun saveSettings(s: Settings, test: Boolean) {
        store.saveSettings(s)
        _state.update { it.copy(settings = store.settings()) }
        if (!test) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val gh = GitHub(store.settings())
                val name = gh.check()
                gh.ensureDataBranch()
                toast("연결됨: $name")
                sync(quiet = true)
            } catch (e: Exception) {
                toast("연결 실패: ${e.message}")
            }
        }
    }
}
