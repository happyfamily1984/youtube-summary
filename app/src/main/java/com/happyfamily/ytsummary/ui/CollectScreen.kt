package com.happyfamily.ytsummary.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.happyfamily.ytsummary.data.Task

/** 수집함: 저장소 inbox/ 글에서 읽어온 영상들 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectScreen(st: UiState, vm: AppViewModel, ui: ArchiveUi, onOpenResult: (String, Task) -> Unit) {
    val ctx = LocalContext.current
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmHide by remember { mutableStateOf(false) }

    val all = st.items.filter { it.collected }
    val topics = all.map { it.topic.ifBlank { "기타" } }.distinct()
    val list = all.filter { topic == null || it.topic.ifBlank { "기타" } == topic }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (ui.selecting) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { ui.back() }) { Icon(Icons.Outlined.Close, "선택 취소") }
                Text("${ui.selected.size}개 선택", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { ui.selected = list.map { it.id }.toSet() }) { Text("전체") }
                IconButton(onClick = { if (ui.selected.isNotEmpty()) confirmHide = true }) {
                    Icon(Icons.Outlined.VisibilityOff, "수집함에서 빼기", tint = C.sub)
                }
            }
            // 선택한 영상 일괄 실행
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Task.entries.forEach { t ->
                    Text(
                        "모두 ${t.label}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.brand)
                            .clickable(enabled = ui.selected.isNotEmpty()) { vm.runMany(ui.selected, t); ui.back() }
                            .padding(vertical = 9.dp),
                    )
                }
            }
        } else {
            ScreenHeader("수집함", subtitle = "저장소 inbox 폴더의 글에서 읽은 영상 ${all.size}개") {
                CircleIconButton(Icons.Outlined.Add, "링크 추가") { adding = true }
                if (st.syncing) CircularProgressIndicator(Modifier.size(44.dp).padding(10.dp), color = C.brand, strokeWidth = 2.5.dp)
                else CircleIconButton(Icons.Outlined.Refresh, "불러오기") { vm.sync() }
            }
        }

        if (topics.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TopicChip("전체 ${all.size}", topic == null) { topic = null }
                topics.forEach { t -> TopicChip("$t ${all.count { it.topic.ifBlank { "기타" } == t }}", topic == t) { topic = t } }
            }
        }

        if (list.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("수집된 영상이 없습니다", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "저장소 data 브랜치의 inbox 폴더에 영상 주소가 적힌 글(.md)을 올리면 여기로 들어옵니다. 오른쪽 위 +로 직접 붙여넣어도 됩니다.",
                    fontSize = 13.sp, color = C.sub, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.id }) { v ->
                val sel = v.id in ui.selected
                Card(
                    Modifier
                        .then(if (sel) Modifier.border(2.dp, C.brand, RoundedCornerShape(20.dp)) else Modifier)
                        .combinedClickable(
                            onClick = {
                                if (ui.selecting) ui.selected = if (sel) ui.selected - v.id else ui.selected + v.id
                                else Task.entries.firstOrNull { v.has(it) }?.let { onOpenResult(v.id, it) } ?: openVideo(ctx, v)
                            },
                            onLongClick = { ui.selecting = true; ui.selected = ui.selected + v.id },
                        )
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.Top) {
                            Thumb(v, 96.dp, 54.dp, 12.dp)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(v.displayTitle.ifBlank { v.id }, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (v.memo.isNotBlank()) Text(v.memo, fontSize = 12.5.sp, color = C.ink, lineHeight = 17.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                                Text(v.topic.ifBlank { "기타" } + " · " + v.platform, fontSize = 11.5.sp, color = C.sub,
                                    maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                            }
                            if (ui.selecting) Icon(Icons.Outlined.CheckCircle, null, tint = if (sel) C.brand else C.dashed,
                                modifier = Modifier.padding(start = 6.dp).size(22.dp))
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Task.entries.forEach { t ->
                                val state = when {
                                    st.isRunning(v.id, t) -> BtnState.RUNNING
                                    v.has(t) -> BtnState.DONE
                                    else -> BtnState.NONE
                                }
                                ChipButton(t.label, state, Modifier.weight(1f)) {
                                    if (state == BtnState.NONE) vm.run(v.id, t) else onOpenResult(v.id, t)
                                }
                            }
                            ChipButton("바로가기", BtnState.NONE, Modifier.weight(0.75f), youtube = true) { openVideo(ctx, v) }
                        }
                    }
                }
            }
        }
    }

    if (adding) AddLinksDialog(onDismiss = { adding = false }) { text, t -> adding = false; vm.addLinks(text, t) }

    if (confirmHide) AlertDialog(
        onDismissRequest = { confirmHide = false },
        title = { Text("${ui.selected.size}개를 수집함에서 빼기") },
        text = { Text("이미 만든 대본·요약 등은 보관함에 남습니다. 저장소의 수집 글은 그대로 둡니다.") },
        confirmButton = { TextButton(onClick = { confirmHide = false; vm.dismiss(ui.selected); ui.back() }) { Text("빼기") } },
        dismissButton = { TextButton(onClick = { confirmHide = false }) { Text("취소") } },
    )
}

@Composable
private fun TopicChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
        color = if (on) Color.White else C.ink, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) C.brand else C.surface)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun AddLinksDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("링크 여러 개 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(topic, { topic = it }, label = { Text("주제 (선택)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    label = { Text("영상 주소 (한 줄에 하나, 뒤에 | 메모)") },
                )
                Text("저장소 inbox/app-added.md 에도 기록됩니다.", fontSize = 12.sp, color = C.sub)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(text, topic) }) { Text("추가") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

