package com.happyfamily.ytsummary.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import coil.compose.AsyncImage
import com.happyfamily.ytsummary.data.Task
import com.happyfamily.ytsummary.data.VideoItem

fun Task.icon(): ImageVector = when (this) {
    Task.TRANSCRIPT -> Icons.Outlined.Description
    Task.SUMMARY -> Icons.Outlined.Notes
    Task.INSTRUCTIONS -> Icons.Outlined.Checklist
}

/** 유튜브 앱으로 해당 영상 열기 (없으면 브라우저) */
fun openYouTube(ctx: Context, id: String) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(app)
    } catch (_: ActivityNotFoundException) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun Thumb(item: VideoItem, w: Dp, h: Dp, radius: Dp = 10.dp) {
    AsyncImage(
        model = item.thumbnail,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(w, h).clip(RoundedCornerShape(radius)).background(Color(0xFF2A2D3A)),
    )
}

@Composable
fun VideoHeader(item: VideoItem, thumbW: Dp = 120.dp, titleSize: Float = 14.5f, sub: String? = null) {
    Row(verticalAlignment = Alignment.Top) {
        Thumb(item, thumbW, thumbW * 9 / 16)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title.ifBlank { "제목 불러오는 중…" },
                fontSize = titleSize.sp, fontWeight = FontWeight.Bold, lineHeight = (titleSize * 1.38f).sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val s = sub ?: item.channel
            if (s.isNotBlank()) Text(s, fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

enum class BtnState { NONE, DONE, RUNNING }

/** 홈 화면의 큰 정사각형 버튼 */
@Composable
fun BigButton(
    label: String, icon: ImageVector, state: BtnState, modifier: Modifier = Modifier,
    youtube: Boolean = false, onClick: () -> Unit,
) {
    val (bg, fg) = when {
        youtube -> C.ytBg to C.yt
        state == BtnState.DONE -> C.brand to Color.White
        state == BtnState.RUNNING -> C.brand2 to C.brand
        else -> C.brand2 to C.brand
    }
    Box(
        modifier.height(72.dp).clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (state == BtnState.RUNNING) CircularProgressIndicator(Modifier.size(20.dp), color = fg, strokeWidth = 2.5.dp)
            else Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp))
            Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        if (state == BtnState.DONE) Box(
            Modifier.align(Alignment.TopEnd).padding(7.dp).size(7.dp).clip(RoundedCornerShape(50)).background(Color(0xFF7CF2B0))
        )
    }
}

/** 보관함 카드의 작은 버튼. 없는 항목은 점선 "+ 대본" */
@Composable
fun ChipButton(label: String, state: BtnState, modifier: Modifier = Modifier, youtube: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(34.dp).clip(shape)
            .then(
                when {
                    youtube -> Modifier.background(C.ytBg)
                    state == BtnState.DONE -> Modifier.background(C.brand)
                    state == BtnState.RUNNING -> Modifier.background(C.warnBg)
                    else -> Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (!youtube && state == BtnState.NONE) {
            Canvas(Modifier.matchParentSize()) {
                drawRoundRect(
                    color = C.dashed, cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            }
        }
        when {
            youtube -> Icon(Icons.Outlined.SmartDisplay, "바로가기", tint = C.yt, modifier = Modifier.size(20.dp))
            state == BtnState.RUNNING -> CircularProgressIndicator(Modifier.size(16.dp), color = C.warn, strokeWidth = 2.dp)
            state == BtnState.DONE -> Text(label, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            else -> Text("+ $label", color = C.muted, fontSize = 12.5.sp)
        }
    }
}

@Composable
fun Badge(text: String) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = C.ok,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(C.okBg).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x225B3BF0), spotColor = Color(0x225B3BF0))
            .clip(RoundedCornerShape(20.dp)).background(C.surface).padding(14.dp)
    ) { content() }
}
