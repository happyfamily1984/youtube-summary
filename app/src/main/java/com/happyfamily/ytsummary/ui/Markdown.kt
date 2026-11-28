package com.happyfamily.ytsummary.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Gemini 결과(간단한 마크다운)를 블록 단위로 나눈다 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Numbered(val num: String, val text: String) : MdBlock
    data class Stamp(val time: String, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Gap : MdBlock
}

fun parseMarkdown(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val heading = Regex("^(#{1,4})\\s+(.*)")
    val bullet = Regex("^\\s*[-*•]\\s+(.*)")
    val numbered = Regex("^\\s*(\\d+)[.)]\\s+(.*)")
    val stamp = Regex("^\\[(\\d{1,2}:\\d{2}(?::\\d{2})?)]\\s*(.*)")
    var code: StringBuilder? = null
    for (raw in src.lines()) {
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (code == null) code = StringBuilder()
            else { out += MdBlock.Code(code.toString().trimEnd()); code = null }
            continue
        }
        if (code != null) { code.append(raw).append('\n'); continue }
        when {
            line.isBlank() -> if (out.lastOrNull() !is MdBlock.Gap && out.isNotEmpty()) out += MdBlock.Gap
            line.trim() == "---" -> if (out.lastOrNull() !is MdBlock.Gap) out += MdBlock.Gap
            heading.matches(line) -> heading.find(line)!!.let {
                out += MdBlock.Heading(it.groupValues[1].length, it.groupValues[2])
            }
            stamp.matches(line) -> stamp.find(line)!!.let { out += MdBlock.Stamp(it.groupValues[1], it.groupValues[2]) }
            bullet.matches(line) -> out += MdBlock.Bullet(bullet.find(line)!!.groupValues[1])
            numbered.matches(line) -> numbered.find(line)!!.let {
                out += MdBlock.Numbered(it.groupValues[1], it.groupValues[2])
            }
            else -> out += MdBlock.Para(line.trim())
        }
    }
    code?.let { out += MdBlock.Code(it.toString().trimEnd()) }
    return out
}

/** **굵게**, `코드` 처리 */
fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val r = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
    r.findAll(text).forEach { m ->
        append(text.substring(i, m.range.first))
        if (m.groupValues[1].isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = C.codeBg, fontSize = 13.sp)) {
            append(m.groupValues[2])
        }
        i = m.range.last + 1
    }
    append(text.substring(i))
}

@Composable
fun MdBlockView(b: MdBlock) {
    val body = 15.sp
    val lh = 24.sp
    when (b) {
        is MdBlock.Heading -> Text(
            inline(b.text),
            fontSize = if (b.level <= 2) 17.sp else 15.5.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (b.level >= 3) C.brand else C.ink,
            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
        )
        is MdBlock.Bullet -> Row(Modifier.padding(start = 4.dp, bottom = 4.dp)) {
            Text("•", fontSize = body, lineHeight = lh, color = C.brand)
            Spacer(Modifier.width(8.dp))
            Text(inline(b.text), fontSize = body, lineHeight = lh)
        }
        is MdBlock.Numbered -> Row(Modifier.padding(start = 4.dp, bottom = 4.dp)) {
            Text("${b.num}.", fontSize = body, lineHeight = lh, fontWeight = FontWeight.Bold, color = C.brand,
                modifier = Modifier.width(22.dp))
            Text(inline(b.text), fontSize = body, lineHeight = lh)
        }
        is MdBlock.Stamp -> Row(Modifier.padding(bottom = 2.dp)) {
            Text(b.time, fontSize = 12.sp, lineHeight = lh, color = C.brand, fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(52.dp))
            Text(inline(b.text), fontSize = body, lineHeight = lh)
        }
        is MdBlock.Para -> Text(inline(b.text), fontSize = body, lineHeight = lh, modifier = Modifier.padding(bottom = 2.dp))
        is MdBlock.Code -> CodeBox(b.text)
        MdBlock.Gap -> Spacer(Modifier.height(8.dp))
    }
}

/** 영상 생성용 프롬프트 블록: 눌러서 바로 복사 */
@Composable
private fun CodeBox(text: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.foundation.layout.Column(
        Modifier.padding(vertical = 6.dp).fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(C.codeBg).padding(12.dp)
    ) {
        Text(text, fontSize = 13.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace)
        Text(
            "복사", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = C.brand,
            modifier = Modifier.align(androidx.compose.ui.Alignment.End).padding(top = 6.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .clickable {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("prompt", text))
                    android.widget.Toast.makeText(ctx, "프롬프트를 복사했습니다", android.widget.Toast.LENGTH_SHORT).show()
                }
                .background(C.brand2).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
