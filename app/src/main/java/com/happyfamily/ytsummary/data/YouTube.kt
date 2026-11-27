package com.happyfamily.ytsummary.data

import android.text.Html
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object YouTube {
    private val idRegexes = listOf(
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtube\.com/(?:shorts|live|embed|v)/([A-Za-z0-9_-]{11})"""),
    )

    /** 공유 텍스트/URL에서 영상 ID 추출. 11자리 ID만 넣어도 인식. */
    fun parseId(text: String?): String? {
        if (text.isNullOrBlank()) return null
        idRegexes.forEach { r -> r.find(text)?.let { return it.groupValues[1] } }
        val t = text.trim()
        return if (Regex("^[A-Za-z0-9_-]{11}$").matches(t)) t else null
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    /** 제목·채널명 (oEmbed, 키 불필요) */
    fun fetchInfo(id: String): Pair<String, String> {
        val req = Request.Builder()
            .url("https://www.youtube.com/oembed?format=json&url=https://www.youtube.com/watch?v=$id")
            .header("User-Agent", UA).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return "" to ""
            val o = JSONObject(r.body!!.string())
            return o.optString("title") to o.optString("author_name")
        }
    }

    /**
     * 휴대폰에서 자막을 직접 받아 대본으로 만든다.
     * 실패하면 예외 → 호출 측에서 GitHub(Gemini) 처리로 넘긴다.
     */
    fun fetchTranscript(id: String, preferLang: String = "ko"): String {
        // 1) 시청 페이지에서 INNERTUBE 키 확보
        val page = http.newCall(
            Request.Builder().url("https://www.youtube.com/watch?v=$id&hl=$preferLang")
                .header("User-Agent", UA)
                .header("Accept-Language", "$preferLang,en;q=0.8")
                .build()
        ).execute().use { it.body?.string().orEmpty() }
        val apiKey = Regex(""""INNERTUBE_API_KEY":\s*"([^"]+)"""").find(page)?.groupValues?.get(1)
            ?: throw IllegalStateException("유튜브 페이지를 읽지 못했습니다")

        // 2) 플레이어 API로 자막 목록 조회 (안드로이드 클라이언트)
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "ANDROID").put("clientVersion", "20.10.38")
                .put("hl", preferLang)))
            .put("videoId", id)
        val player = http.newCall(
            Request.Builder().url("https://www.youtube.com/youtubei/v1/player?key=$apiKey")
                .header("User-Agent", "com.google.android.youtube/20.10.38 (Linux; U; Android 14)")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { JSONObject(it.body?.string().orEmpty().ifBlank { "{}" }) }

        val tracks: JSONArray = player.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")
            ?: throw IllegalStateException("이 영상에는 자막이 없습니다")

        val list = (0 until tracks.length()).map { tracks.getJSONObject(it) }
        fun isAsr(o: JSONObject) = o.optString("kind") == "asr"
        val pick = list.firstOrNull { it.optString("languageCode").startsWith(preferLang) && !isAsr(it) }
            ?: list.firstOrNull { !isAsr(it) }
            ?: list.firstOrNull { it.optString("languageCode").startsWith(preferLang) }
            ?: list.first()

        val url = pick.getString("baseUrl").replace("&fmt=srv3", "")
        val xml = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build())
            .execute().use { it.body?.string().orEmpty() }
        val lines = parseCaptionXml(xml)
        if (lines.isEmpty()) throw IllegalStateException("자막 내용을 받지 못했습니다")
        return formatTranscript(lines)
    }

    /** (시작초, 문장) 목록. 구형 <text start=..> / 신형 <p t=ms ..> 모두 처리 */
    private fun parseCaptionXml(xml: String): List<Pair<Double, String>> {
        val out = mutableListOf<Pair<Double, String>>()
        Regex("""<text start="([\d.]+)"[^>]*>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).forEach { out += it.groupValues[1].toDouble() to clean(it.groupValues[2]) }
        if (out.isEmpty()) {
            Regex("""<p t="(\d+)"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).forEach { out += it.groupValues[1].toDouble() / 1000.0 to clean(it.groupValues[2]) }
        }
        return out.filter { it.second.isNotBlank() }
    }

    private fun clean(s: String): String {
        val noTags = s.replace(Regex("<[^>]+>"), "")
        // 이중 이스케이프(&amp;#39;)까지 풀기
        val once = Html.fromHtml(noTags, Html.FROM_HTML_MODE_LEGACY).toString()
        return Html.fromHtml(once, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace('\n', ' ').trim()
    }

    /** 약 30초 단위 문단 + [mm:ss] 타임스탬프 */
    fun formatTranscript(lines: List<Pair<Double, String>>): String {
        val sb = StringBuilder()
        var blockStart = -1.0
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotEmpty()) {
                sb.append('[').append(ts(blockStart)).append("] ").append(cur.toString().trim()).append("\n\n")
                cur.clear()
            }
        }
        for ((t, text) in lines) {
            if (blockStart < 0 || t - blockStart >= 30) { flush(); blockStart = t }
            cur.append(text).append(' ')
        }
        flush()
        return sb.toString().trim()
    }

    private fun ts(sec: Double): String {
        val s = sec.toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }
}
