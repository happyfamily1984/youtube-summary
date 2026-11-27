package com.happyfamily.ytsummary.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.happyfamily.ytsummary.data.Task

@Composable
fun ResultScreen(st: UiState, vm: AppViewModel, id: String, initial: Task, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var task by rememberSaveable { mutableStateOf(initial) }
    var confirmRegen by remember { mutableStateOf(false) }
    val item = st.item(id)
    LaunchedEffect(id, task, item?.remote) { vm.ensureContent(id, task) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로") }
            Text(task.label, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (item?.has(task) == true && !st.isRunning(id, task)) {
                IconButton(onClick = { confirmRegen = true }) { Icon(Icons.Outlined.Refresh, "다시 만들기") }
            }
        }
        if (item == null) return@Column

        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { openYouTube(ctx, id) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(item, 64.dp, 36.dp, 7.dp)
                Spacer(Modifier.size(10.dp))
                Text(item.title.ifBlank { id }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 18.sp)
            }
            // 대본 / 요약 / 지시문 전환
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFE9E5FA)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Task.entries.forEach { t ->
                    val on = t == task
                    Box(
                        Modifier.weight(1f).then(if (on) Modifier.shadow(2.dp, RoundedCornerShape(10.dp)) else Modifier)
                            .clip(RoundedCornerShape(10.dp)).background(if (on) C.surface else Color.Transparent)
                            .clickable { task = t }.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(t.label, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            color = if (on) C.ink else C.sub)
                    }
                }
            }
        }

        val content = item.contents[task]
        val running = st.running["$id/${task.key}"]
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                content != null -> {
                    val blocks = remember(content) { parseMarkdown(content) }
                    SelectionContainer {
                        LazyColumn(
                            Modifier.fillMaxSize().padding(horizontal = 16.dp).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                                .background(C.surface),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                        ) { items(blocks) { MdBlockView(it) } }
                    }
                    Row(
                        Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Fab("복사", Icons.Outlined.ContentCopy, light = true) { copy(ctx, content) }
                        Fab("공유", Icons.Outlined.Share, light = false) { share(ctx, item.title, content) }
                    }
                    if (running != null) Box(Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp)) { Progress(running.message) }
                }
                running != null -> Column(Modifier.padding(16.dp)) { Progress(running.message) }
                "$id/${task.key}" in st.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = C.brand)
                }
                else -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(task.icon(), null, tint = C.brand, modifier = Modifier.size(40.dp))
                    Text("아직 ${task.label}이(가) 없습니다", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    Button(onClick = { vm.run(id, task) }, modifier = Modifier.padding(top = 16.dp)) { Text("${task.label} 만들기") }
                }
            }
        }
    }

    if (confirmRegen) AlertDialog(
        onDismissRequest = { confirmRegen = false },
        title = { Text("${task.label} 다시 만들기") },
        text = { Text("Gemini로 새로 만들어 기존 내용을 바꿉니다.") },
        confirmButton = { TextButton(onClick = { confirmRegen = false; vm.run(id, task, regenerate = true) }) { Text("다시 만들기") } },
        dismissButton = { TextButton(onClick = { confirmRegen = false }) { Text("취소") } },
    )
}

@Composable
private fun Fab(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, light: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(48.dp).shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
            .background(if (light) C.surface else C.ink).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (light) C.ink else Color.White
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(6.dp))
        Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
    }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("result", text))
    Toast.makeText(ctx, "복사했습니다", Toast.LENGTH_SHORT).show()
}

private fun share(ctx: Context, title: String, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, "공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
