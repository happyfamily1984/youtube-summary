package com.happyfamily.ytsummary.data

import org.json.JSONObject

/** 영상별로 만들 수 있는 작업 종류. [file]은 저장소/기기에 저장되는 파일 이름. */
enum class Task(val key: String, val label: String, val file: String) {
    TRANSCRIPT("transcript", "대본", "transcript.md"),
    SUMMARY("summary", "요약", "summary.md"),
    INSTRUCTIONS("instructions", "지시문", "instructions.md"),
    PROMPT("prompt", "프롬프트", "prompt.md");

    companion object {
        fun fromKey(k: String) = entries.firstOrNull { it.key == k }
    }
}

data class VideoItem(
    val id: String,
    val title: String = "",
    val channel: String = "",
    /** 유튜브가 아닌 영상(인스타·틱톡 등)의 원본 주소 */
    val sourceUrl: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** 저장소 inbox/ 수집 글에서 들어온 영상 */
    val collected: Boolean = false,
    val topic: String = "",
    val memo: String = "",
    /** 기기에 저장된 결과 내용 */
    val contents: Map<Task, String> = emptyMap(),
    /** GitHub 저장소에 존재하는 결과 (내용은 열 때 내려받음) */
    val remote: Set<Task> = emptySet(),
) {
    /** 유튜브 영상 ID는 정확히 11자, 그 외는 "ig_", "tt_", "v_" 접두어 */
    val isYouTube get() = id.length == 11
    val url get() = if (isYouTube) "https://www.youtube.com/watch?v=$id" else sourceUrl
    val thumbnail: String? get() = if (isYouTube) "https://i.ytimg.com/vi/$id/mqdefault.jpg" else null
    val platform get() = when {
        isYouTube -> "YouTube"
        id.startsWith("ig_") -> "Instagram"
        id.startsWith("tt_") -> "TikTok"
        else -> "영상"
    }
    val displayTitle get() = title.ifBlank { if (isYouTube) "" else "$platform 영상" }
    fun has(t: Task) = contents.containsKey(t) || remote.contains(t)
    val hasAny get() = Task.entries.any { has(it) }

    fun metaJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("channel", channel)
        .put("url", url).put("platform", platform).put("createdAt", createdAt)
        .put("collected", collected).put("topic", topic).put("memo", memo)

    companion object {
        fun fromMeta(o: JSONObject) = VideoItem(
            id = o.getString("id"),
            title = o.optString("title"),
            channel = o.optString("channel"),
            sourceUrl = o.optString("url"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            collected = o.optBoolean("collected"),
            topic = o.optString("topic"),
            memo = o.optString("memo"),
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
