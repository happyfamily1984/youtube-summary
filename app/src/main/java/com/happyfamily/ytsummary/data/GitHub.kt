package com.happyfamily.ytsummary.data

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class GitHubException(val code: Int, msg: String) : Exception(msg)

/** GitHub REST API — 결과 저장(data 브랜치)과 처리 워크플로 실행 */
class GitHub(private val s: Settings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
    private val base = "https://api.github.com/repos/${s.owner}/${s.repo}"
    private val json = "application/json".toMediaType()

    private fun req(path: String) = Request.Builder()
        .url(if (path.startsWith("http")) path else base + path)
        .header("Authorization", "Bearer ${s.token}")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("Cache-Control", "no-cache")

    private fun call(b: Request.Builder): Pair<Int, String> =
        http.newCall(b.build()).execute().use { it.code to (it.body?.string().orEmpty()) }

    private fun ok(r: Pair<Int, String>, what: String): String {
        if (r.first !in 200..299) {
            val msg = runCatching { JSONObject(r.second).optString("message") }.getOrNull().orEmpty()
            throw GitHubException(r.first, "$what 실패 (${r.first}) $msg".trim())
        }
        return r.second
    }

    private fun enc(p: String) = p.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** 연결 확인: 저장소 접근 가능 여부 */
    fun check(): String {
        val o = JSONObject(ok(call(req("")), "저장소 확인"))
        return o.optString("full_name")
    }

    // ---------- data 브랜치 ----------

    fun branchExists(): Boolean = call(req("/branches/${s.dataBranch}")).first == 200

    /** data 브랜치가 없으면 빈(고아) 브랜치로 만든다 */
    fun ensureDataBranch() {
        if (branchExists()) return
        val tree = JSONObject(ok(call(req("/git/trees").post(JSONObject().put("tree", JSONArray().put(
            JSONObject().put("path", "README.md").put("mode", "100644").put("type", "blob")
                .put("content", "# 유튜브 요약 보관함\n\n앱이 자동으로 저장하는 브랜치입니다.\n")
        )).toString().toRequestBody(json))), "트리 생성")).getString("sha")
        val commit = JSONObject(ok(call(req("/git/commits").post(JSONObject()
            .put("message", "보관함 브랜치 생성").put("tree", tree).put("parents", JSONArray())
            .toString().toRequestBody(json))), "커밋 생성")).getString("sha")
        val r = call(req("/git/refs").post(JSONObject()
            .put("ref", "refs/heads/${s.dataBranch}").put("sha", commit).toString().toRequestBody(json)))
        if (r.first != 422) ok(r, "브랜치 생성") // 422 = 동시에 이미 생성됨
    }

    /** 파일 내용과 sha. 없으면 null */
    fun getFile(path: String): Pair<String, String>? {
        val r = call(req("/contents/${enc(path)}?ref=${s.dataBranch}"))
        if (r.first == 404) return null
        val o = JSONObject(ok(r, "파일 읽기"))
        val sha = o.getString("sha")
        val content = if (o.optString("encoding") == "base64" && o.optString("content").isNotEmpty()) {
            String(Base64.decode(o.getString("content"), Base64.DEFAULT), Charsets.UTF_8)
        } else {
            // 1MB 초과 파일은 content가 비어 있음 → blob API
            val b = JSONObject(ok(call(req("/git/blobs/$sha")), "큰 파일 읽기"))
            String(Base64.decode(b.getString("content"), Base64.DEFAULT), Charsets.UTF_8)
        }
        return content to sha
    }

    fun fileSha(path: String): String? {
        val r = call(req("/contents/${enc(path)}?ref=${s.dataBranch}"))
        if (r.first == 404) return null
        return JSONObject(ok(r, "파일 확인")).optString("sha")
    }

    fun putFile(path: String, content: String, message: String) {
        repeat(3) { attempt ->
            val sha = fileSha(path)
            val body = JSONObject()
                .put("message", message)
                .put("branch", s.dataBranch)
                .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            if (sha != null) body.put("sha", sha)
            val r = call(req("/contents/${enc(path)}").put(body.toString().toRequestBody(json)))
            if (r.first in 200..299) return
            if ((r.first == 409 || r.first == 422) && attempt < 2) { Thread.sleep(1500); return@repeat }
            ok(r, "저장")
        }
    }

    fun deleteFile(path: String, message: String) {
        val sha = fileSha(path) ?: return
        val body = JSONObject().put("message", message).put("branch", s.dataBranch).put("sha", sha)
        ok(call(req("/contents/${enc(path)}").method("DELETE", body.toString().toRequestBody(json))), "삭제")
    }

    /** data 브랜치의 전체 파일 경로 (한 번의 호출) */
    fun listAll(): List<String> {
        val r = call(req("/git/trees/${s.dataBranch}?recursive=1"))
        if (r.first == 404 || r.first == 409) return emptyList()
        val arr = JSONObject(ok(r, "목록 읽기")).getJSONArray("tree")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("type") == "blob" }.map { it.getString("path") }
    }

    // ---------- 처리 워크플로 ----------

    fun dispatch(videoId: String, task: Task, requestId: String, language: String) {
        val body = JSONObject().put("ref", s.workflowRef).put("inputs", JSONObject()
            .put("video_id", videoId).put("task", task.key)
            .put("request_id", requestId).put("language", language))
        ok(call(req("/actions/workflows/process.yml/dispatches").post(body.toString().toRequestBody(json))),
            "GitHub 처리 시작")
    }

    /** request_id로 실행을 찾아 (status, conclusion) 반환 */
    fun findRun(requestId: String): Pair<String, String>? {
        val r = call(req("/actions/workflows/process.yml/runs?event=workflow_dispatch&per_page=20"))
        if (r.first !in 200..299) return null
        val runs = JSONObject(r.second).optJSONArray("workflow_runs") ?: return null
        for (i in 0 until runs.length()) {
            val run = runs.getJSONObject(i)
            if (run.optString("display_title").contains(requestId)) {
                return run.optString("status") to run.optString("conclusion")
            }
        }
        return null
    }
}
