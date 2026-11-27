package com.happyfamily.ytsummary.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 기기 저장소: files/videos/{id}/meta.json + 결과 .md 파일. 설정은 SharedPreferences */
class Store(context: Context) {
    private val root = File(context.filesDir, "videos").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun loadAll(): List<VideoItem> = root.listFiles().orEmpty()
        .filter { it.isDirectory }
        .mapNotNull { load(it.name) }

    fun load(id: String): VideoItem? {
        val dir = File(root, id)
        val meta = File(dir, "meta.json")
        if (!meta.exists()) return null
        val o = runCatching { JSONObject(meta.readText()) }.getOrNull() ?: return null
        val base = VideoItem.fromMeta(o)
        val contents = Task.entries.mapNotNull { t ->
            File(dir, t.file).takeIf { it.exists() }?.let { t to it.readText() }
        }.toMap()
        val remoteArr = o.optJSONArray("remote") ?: JSONArray()
        val remote = (0 until remoteArr.length()).mapNotNull { Task.fromKey(remoteArr.getString(it)) }.toSet()
        return base.copy(contents = contents, remote = remote)
    }

    fun saveMeta(v: VideoItem) {
        val dir = File(root, v.id).apply { mkdirs() }
        val o = v.metaJson().put("remote", JSONArray(v.remote.map { it.key }))
        File(dir, "meta.json").writeText(o.toString())
    }

    fun saveContent(id: String, task: Task, text: String) {
        File(File(root, id).apply { mkdirs() }, task.file).writeText(text)
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    // ---------- 설정 ----------
    fun settings() = Settings(
        owner = prefs.getString("owner", null) ?: Settings().owner,
        repo = prefs.getString("repo", null) ?: Settings().repo,
        token = prefs.getString("token", "") ?: "",
        workflowRef = prefs.getString("workflowRef", null) ?: Settings().workflowRef,
        dataBranch = prefs.getString("dataBranch", null) ?: Settings().dataBranch,
        deviceTranscriptFirst = prefs.getBoolean("deviceFirst", true),
        language = prefs.getString("language", null) ?: "ko",
    )

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("owner", s.owner.trim()).putString("repo", s.repo.trim())
            .putString("token", s.token.trim()).putString("workflowRef", s.workflowRef.trim())
            .putString("dataBranch", s.dataBranch.trim())
            .putBoolean("deviceFirst", s.deviceTranscriptFirst)
            .putString("language", s.language)
            .apply()
    }

    var lastSync: Long
        get() = prefs.getLong("lastSync", 0)
        set(v) = prefs.edit().putLong("lastSync", v).apply()
}
