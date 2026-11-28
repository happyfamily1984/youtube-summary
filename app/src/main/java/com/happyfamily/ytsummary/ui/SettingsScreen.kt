package com.happyfamily.ytsummary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(st: UiState, vm: AppViewModel, onBack: () -> Unit) {
    val s = st.settings
    var owner by rememberSaveable { mutableStateOf(s.owner) }
    var repo by rememberSaveable { mutableStateOf(s.repo) }
    var token by rememberSaveable { mutableStateOf("") }
    var ref by rememberSaveable { mutableStateOf(s.workflowRef) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로") }
            Text("설정", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Group("GitHub 저장소 (보관·처리)") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("연결 상태", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (s.connected) Badge("연결됨") else Text("미연결", color = C.yt, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                OutlinedTextField(owner, { owner = it }, label = { Text("소유자") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(repo, { repo = it }, label = { Text("저장소") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    token, { token = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (s.token.isNotBlank()) "토큰 (저장됨 · 바꿀 때만 입력)" else "GitHub 토큰") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(ref, { ref = it }, label = { Text("워크플로 브랜치") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "토큰은 이 휴대폰에만 저장됩니다. GitHub → Settings → Developer settings → Fine-grained tokens에서 " +
                        "이 저장소만 선택하고 권한 Contents: Read and write, Actions: Read and write 로 만드세요.",
                    fontSize = 12.sp, color = C.sub, lineHeight = 17.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        vm.saveSettings(s.copy(owner = owner, repo = repo, workflowRef = ref,
                            token = token.ifBlank { s.token }), test = true)
                        token = ""
                    }) { Text("저장하고 연결 확인") }
                    OutlinedButton(onClick = { vm.sync() }) { Text(if (st.syncing) "동기화 중…" else "지금 동기화") }
                }
                if (st.lastSync > 0) Text("마지막 동기화: ${java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.KOREA).format(java.util.Date(st.lastSync))}",
                    fontSize = 12.sp, color = C.sub)
            }

            Group("Gemini 처리") {
                Text("API 키 (GitHub 시크릿)", fontWeight = FontWeight.SemiBold)
                Text("GEMINI_API_KEYS 시크릿에 키를 줄바꿈으로 여러 개 등록하면, 한도가 차면 다음 키로 자동 전환합니다.",
                    fontSize = 12.sp, color = C.sub, lineHeight = 17.sp)
                val ks = st.keyStatus
                if (ks != null && ks.total > 0) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..ks.total).forEach { n ->
                            val cur = n == ks.lastKey
                            val used = n in ks.exhausted
                            Text(
                                if (cur) "키 $n 사용 중" else "키 $n",
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal,
                                color = when { cur -> C.brand; used -> C.muted; else -> C.ink },
                                textDecoration = if (used && !cur) TextDecoration.LineThrough else null,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (cur) C.brand2 else C.codeBg)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (ks.lastModel.isNotBlank()) Text("최근 모델: ${ks.lastModel}", fontSize = 12.sp, color = C.sub)
                } else {
                    Text("아직 사용 기록이 없습니다", fontSize = 12.sp, color = C.muted)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("대본은 휴대폰에서 먼저 추출", fontWeight = FontWeight.SemiBold)
                        Text("자막이 없을 때만 Gemini가 영상을 듣고 받아쓰기", fontSize = 12.sp, color = C.sub)
                    }
                    Switch(s.deviceTranscriptFirst, { vm.saveSettings(s.copy(deviceTranscriptFirst = it), test = false) })
                }
            }

            Group("결과 언어") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ko" to "한국어", "en" to "English").forEach { (code, name) ->
                        if (s.language == code) Button(onClick = {}) { Text(name) }
                        else OutlinedButton(onClick = { vm.saveSettings(s.copy(language = code), test = false) }) { Text(name) }
                    }
                }
            }
            Text("버전 0.3.0", fontSize = 12.sp, color = C.muted, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, fontSize = 13.sp, color = C.sub, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
        Card { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } }
    }
}
