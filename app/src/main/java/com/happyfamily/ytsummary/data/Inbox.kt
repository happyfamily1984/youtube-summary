package com.happyfamily.ytsummary.data

/**
 * 저장소 data 브랜치 inbox/ 폴더의 수집 글(.md/.txt)을 읽는다.
 *
 * 형식은 느슨하다. 영상 주소가 있는 줄은 모두 수집하고,
 *  - 가장 가까운 위쪽 제목(# …)을 주제로,
 *  - 같은 줄에서 주소를 뺀 나머지 글을 메모로 쓴다.
 *
 * 예)
 *   # 주제: 30초 홈트
 *   - https://www.instagram.com/reel/AbCdEf123/ | 전신 루틴, 조회수 120만, 빠른 컷 편집
 */
object Inbox {
    const val DIR = "inbox"

    data class Entry(val id: String, val url: String, val topic: String, val memo: String, val file: String)

    private val urlRe = Regex("""https?://[^\s<>()\[\]"'|]+""")

    fun parse(text: String, file: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var topic = file.substringAfterLast('/').substringBeforeLast('.')
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("#")) {
                topic = line.trimStart('#').trim().removePrefix("주제:").removePrefix("주제 :").trim().ifBlank { topic }
                continue
            }
            val urls = urlRe.findAll(line).map { it.value.trimEnd('.', ',', ';') }.toList()
            if (urls.isEmpty()) continue
            var memo = line
            urls.forEach { memo = memo.replace(it, " ") }
            memo = memo.replace(Regex("""^[\s\-*•\d.)]+"""), "")
                .replace(Regex("""\[\s*]\(\s*\)"""), "")
                .replace(Regex("""^[\s|:\-–—]+|[\s|:\-–—]+$"""), "")
                .replace(Regex("""\s{2,}"""), " ")
                .removePrefix("메모:").trim()
            for (u in urls) {
                val (id, url) = YouTube.parseLink(u) ?: continue
                if (!isVideo(url, id)) continue
                out += Entry(id, url, topic, memo, file)
            }
        }
        return out.distinctBy { it.id }
    }

    /** 일반 웹페이지 링크는 빼고 영상 주소만 */
    private fun isVideo(url: String, id: String) =
        id.length == 11 || id.startsWith("ig_") || id.startsWith("tt_") ||
            Regex("""(facebook\.com|fb\.watch|vimeo\.com|x\.com|twitter\.com|threads\.net|douyin|bilibili|naver|kakao)""")
                .containsMatchIn(url)
}
