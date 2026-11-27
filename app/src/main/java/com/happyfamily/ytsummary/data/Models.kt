package com.happyfamily.ytsummary.data

import org.json.JSONObject

/** 영상별로 만들 수 있는 작업 종류. [file]은 저장소/기기에 저장되는 파일 이름. */
enum class Task(val key: String, val label: String, val file: String) {
    TRANSCRIPT("transcript", "대본", "transcript.md"),
    SUMMARY("summary", "요약", "summary.md"),
    INSTRUCTIONS("instructions", "지시문", "instructions.md");

    companion object {
        fun fromKey(k: String) = entries.firstOrNull { it.key == k }
    }
}

data class VideoItem(
    val id: String,
    val title: String = "",
    val channel: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** 기기에 저장된 결과 내용 */
    val contents: Map<Task, String> = emptyMap(),
    /** GitHub 저장소에 존재하는 결과 (내용은 열 때 내려받음) */
    val remote: Set<Task> = emptySet(),
) {
    val url get() = "https://www.youtube.com/watch?v=$id"
    val thumbnail get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    fun has(t: Task) = contents.containsKey(t) || remote.contains(t)
    val hasAny get() = Task.entries.any { has(it) }

    fun metaJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("channel", channel)
        .put("url", url).put("createdAt", createdAt)

    companion object {
        fun fromMeta(o: JSONObject) = VideoItem(
            id = o.getString("id"),
            title = o.optString("title"),
            channel = o.optString("channel"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}

/** 진행 중인 작업 표시용 */
data class Running(val message: String, val startedAt: Long = System.currentTimeMillis())

data class Settings(
    val owner: String = "happyfamily1984",
    val repo: String = "youtube-summary",
    val token: String = "",
    /** 처리 워크플로가 있는 브랜치 (기본 브랜치) */
    val workflowRef: String = "main",
    /** 결과가 저장되는 브랜치 */
    val dataBranch: String = "data",
    val deviceTranscriptFirst: Boolean = true,
    val language: String = "ko",
) {
    val connected get() = token.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()
}

/** 저장소 state/gemini.json 내용 (어떤 키를 쓰고 있는지) */
data class KeyStatus(
    val total: Int,
    val lastKey: Int,
    val exhausted: Set<Int>,
    val lastModel: String,
    val updatedAt: String,
)
