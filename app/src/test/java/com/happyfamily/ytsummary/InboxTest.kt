package com.happyfamily.ytsummary

import com.happyfamily.ytsummary.data.Inbox
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxTest {
    @Test
    fun parsesTopicsMemosAndPlatforms() {
        val md = """
            # 주제: 30초 홈트
            - https://www.instagram.com/reel/AbCdEf123/?igsh=xyz | 전신 루틴, 조회수 120만
            1. [링크](https://www.instagram.com/p/ZZZ_111/) 메모: 빠른 컷
            ## 엑셀 팁
            * https://youtu.be/dQw4w9WgXcQ?si=abc — 중복 제거
            https://www.tiktok.com/@user/video/7300000000000000000 틱톡
            https://example.com/blog 일반 글은 무시
            - https://www.instagram.com/reel/AbCdEf123/ 중복
        """.trimIndent()
        val e = Inbox.parse(md, "inbox/2026-11-28.md")
        assertEquals(listOf("ig_AbCdEf123", "ig_ZZZ_111", "dQw4w9WgXcQ", "tt_7300000000000000000"), e.map { it.id })
        assertEquals("30초 홈트", e[0].topic)
        assertEquals("전신 루틴, 조회수 120만", e[0].memo)
        assertEquals("엑셀 팁", e[2].topic)
        assertEquals("중복 제거", e[2].memo)
        assertEquals("https://www.instagram.com/reel/AbCdEf123/", e[0].url)
    }
}
