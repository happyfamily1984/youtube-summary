package com.happyfamily.ytsummary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.happyfamily.ytsummary.data.Task

@Composable
fun HomeScreen(
    st: UiState, vm: AppViewModel,
    onOpenResult: (String, Task) -> Unit, onSettings: () -> Unit, onShowAll: () -> Unit,
) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var input by rememberSaveable { mutableStateOf("") }
    val current = st.item(st.currentId)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenHeader("유튜브 요약") {
            CircleIconButton(Icons.Outlined.Settings, "설정", onSettings)
        }

        OutlinedTextField(
            value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("유튜브 링크 붙여넣기") },
            leadingIcon = { Icon(Icons.Outlined.Link, null, tint = C.sub) },
            trailingIcon = {
                Button(
                    onClick = { if (vm.open(input)) { input = ""; focus.clearFocus() } },
                    shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(end = 6.dp),
                ) { Text("불러오기", fontWeight = FontWeight.Bold) }
            },
            singleLine = true, shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (vm.open(input)) { input = ""; focus.clearFocus() } }),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = C.surface, focusedContainerColor = C.surface,
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )

        Section(Icons.Outlined.Share, if (current != null) "공유받은 영상" else "시작하기")
        if (current == null) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("유튜브 앱에서 영상의 [공유] → [유튜브 요약]을 누르세요.", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    Text("또는 위 칸에 링크를 붙여넣고 [불러오기]를 누르세요.", fontSize = 13.sp, color = C.sub)
                }
            }
        } else {
            Card {
                Column {
                    VideoHeader(current)
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Task.entries.forEach { t ->
                            val state = when {
                                st.isRunning(current.id, t) -> BtnState.RUNNING
                                current.has(t) -> BtnState.DONE
                                else -> BtnState.NONE
                            }
                            BigButton(t.label, t.icon(), state, Modifier.weight(1f)) {
                                if (state == BtnState.NONE) vm.run(current.id, t) else onOpenResult(current.id, t)
                            }
                        }
                        BigButton("바로가기", Icons.Outlined.SmartDisplay, BtnState.NONE, Modifier.weight(1f), youtube = true) {
                            openYouTube(ctx, current.id)
                        }
                    }
                    Task.entries.forEach { t ->
                        st.running["${current.id}/${t.key}"]?.let { r -> Progress(r.message) }
                    }
                }
            }
        }

        val recent = st.items.filter { it.hasAny && it.id != current?.id }.take(6)
        if (recent.isNotEmpty()) {
            Section(Icons.Outlined.Schedule, "최근 작업", action = "전체보기", onAction = onShowAll)
            recent.forEach { v ->
                Card(Modifier.padding(bottom = 10.dp).clickable { vm.open(v.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Thumb(v, 88.dp, 50.dp, 10.dp)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                v.title.ifBlank { v.id }, fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp,
                            )
                            Text(dateText(v.createdAt), fontSize = 12.sp, color = C.sub, modifier = Modifier.padding(top = 3.dp))
                        }
                        Spacer(Modifier.size(8.dp))
                        val last = Task.entries.lastOrNull { v.has(it) }
                        if (last != null) Badge(last.label)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun Label(text: String) {
    Text(text, fontSize = 13.sp, color = C.sub, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 2.dp, top = 18.dp, bottom = 8.dp))
}

@Composable
fun Progress(message: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(C.warnBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.AutoAwesome, null, tint = C.brand, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(message, fontSize = 12.5.sp, color = C.warn)
            Box(Modifier.padding(top = 6.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)), color = C.warn, trackColor = androidx.compose.ui.graphics.Color(0xFFE0DAF7))
            }
        }
    }
}

fun dateText(t: Long): String = java.text.SimpleDateFormat("M월 d일", java.util.Locale.KOREA).format(java.util.Date(t))

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(com.happyfamily.ytsummary.R.drawable.app_logo), null,
                Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)),
            )
            Spacer(Modifier.size(12.dp))
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
        }
        if (subtitle != null) Text(subtitle, fontSize = 13.5.sp, color = C.sub, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun CircleIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).androidxShadow().clip(RoundedCornerShape(50)).background(C.surface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = C.ink, modifier = Modifier.size(22.dp)) }
}

private fun Modifier.androidxShadow() = this.then(
    androidx.compose.ui.Modifier.shadow(6.dp, RoundedCornerShape(50), ambientColor = C.brand, spotColor = androidx.compose.ui.graphics.Color(0x335B3BF0))
)

@Composable
fun Section(
    icon: androidx.compose.ui.graphics.vector.ImageVector, text: String,
    action: String? = null, onAction: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.brand, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) Text(
            "$action ›", fontSize = 13.sp, color = C.brand, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(4.dp),
        )
    }
}
