package com.happyfamily.ytsummary.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** 보관함의 선택 모드·검색 상태 (뒤로가기 처리를 위해 바깥에서 보관) */
class ArchiveUi {
    var selecting by mutableStateOf(false)
    var selected by mutableStateOf(setOf<String>())
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")

    /** 뒤로가기로 취소할 것이 있으면 취소하고 true */
    fun back(): Boolean = when {
        selecting -> { selecting = false; selected = emptySet(); true }
        searching -> { searching = false; query = ""; true }
        else -> false
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArchiveScreen(st: UiState, vm: AppViewModel, ui: ArchiveUi, onOpenResult: (String, Task) -> Unit) {
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    val list = st.items.filter { it.hasAny }
        .filter { ui.query.isBlank() || it.displayTitle.contains(ui.query, ignoreCase = true) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (ui.selecting) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { ui.back() }) { Icon(Icons.Outlined.Close, "선택 취소") }
                Text("${ui.selected.size}개 선택", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { if (ui.selected.isNotEmpty()) confirmDelete = true }) {
                    Icon(Icons.Outlined.Delete, "삭제", tint = C.yt)
                }
            }
        } else {
            ScreenHeader("보관함", subtitle = syncText(st, list.size)) {
                CircleIconButton(Icons.Outlined.Search, "검색") { ui.searching = !ui.searching; if (!ui.searching) ui.query = "" }
                if (st.syncing) CircularProgressIndicator(Modifier.size(44.dp).padding(10.dp), color = C.brand, strokeWidth = 2.5.dp)
                else CircleIconButton(Icons.Outlined.Refresh, "동기화") { vm.sync() }
            }
        }
        if (ui.searching && !ui.selecting) {
            OutlinedTextField(
                value = ui.query, onValueChange = { ui.query = it }, singleLine = true,
                placeholder = { Text("제목 검색") }, shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = C.surface, focusedContainerColor = C.surface, unfocusedBorderColor = Color.Transparent,
                ),
            )
        }

        if (list.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(if (ui.query.isBlank()) "아직 보관된 영상이 없습니다" else "검색 결과가 없습니다", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("홈에서 대본·요약·지시문을 만들면 여기에 쌓입니다", fontSize = 13.sp, color = C.sub, modifier = Modifier.padding(top = 6.dp))
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
                                else Task.entries.firstOrNull { v.has(it) }?.let { onOpenResult(v.id, it) }
                            },
                            onLongClick = { ui.selecting = true; ui.selected = ui.selected + v.id },
                        )
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.Top) {
                            Thumb(v, 112.dp, 63.dp, 12.dp)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(v.displayTitle.ifBlank { v.id }, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(dateText(v.createdAt) + if (v.channel.isNotBlank()) " · ${v.channel}" else "",
                                    fontSize = 12.sp, color = C.sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                            if (ui.selecting) Icon(
                                Icons.Outlined.CheckCircle, null, tint = if (sel) C.brand else C.dashed,
                                modifier = Modifier.padding(start = 6.dp).size(22.dp),
                            )
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

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("${ui.selected.size}개 삭제") },
        text = { Text("기기와 GitHub 저장소에서 모두 삭제합니다.") },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; vm.delete(ui.selected); ui.back() }) { Text("삭제", color = C.yt) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
    )
}

private fun syncText(st: UiState, n: Int): String = when {
    !st.settings.connected -> "기기에만 저장 중 · 설정에서 GitHub 연결  ·  영상 ${n}개"
    st.syncing -> "GitHub와 동기화 중…"
    else -> "GitHub와 동기화됨 · 영상 ${n}개"
}

