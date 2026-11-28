package com.happyfamily.ytsummary

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.happyfamily.ytsummary.data.Task
import com.happyfamily.ytsummary.data.YouTube
import com.happyfamily.ytsummary.ui.AppBackground
import com.happyfamily.ytsummary.ui.AppTheme
import com.happyfamily.ytsummary.ui.AppViewModel
import com.happyfamily.ytsummary.ui.ArchiveScreen
import com.happyfamily.ytsummary.ui.ArchiveUi
import com.happyfamily.ytsummary.ui.CollectScreen
import com.happyfamily.ytsummary.ui.C
import com.happyfamily.ytsummary.ui.HomeScreen
import com.happyfamily.ytsummary.ui.ResultScreen
import com.happyfamily.ytsummary.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent { AppTheme { App(vm) { finish() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            vm.open(text)
            vm.goHome.value = true
            YouTube.parseLink(text)?.let { rememberClip(it.first) }
        }
    }

    /**
     * 유튜브 공유창에서 "링크 복사" 후 이 앱을 열면 복사한 영상을 자동으로 불러온다.
     * (안드로이드 10+는 앱이 화면에 보일 때만 클립보드를 읽을 수 있어 포커스를 받을 때 확인)
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        if (clip.isNullOrBlank() || !Regex("youtu|instagram|tiktok").containsMatchIn(clip)) return // 영상 링크만
        val id = YouTube.parseLink(clip)?.first ?: return
        val prefs = getSharedPreferences("clip", MODE_PRIVATE)
        if (prefs.getString("last", null) == id) return
        rememberClip(id)
        vm.open(clip)
        vm.goHome.value = true
    }

    private fun rememberClip(id: String) =
        getSharedPreferences("clip", MODE_PRIVATE).edit().putString("last", id).apply()
}

@Composable
fun App(vm: AppViewModel, exit: () -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val goHome by vm.goHome.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var resultId by rememberSaveable { mutableStateOf<String?>(null) }
    var resultTask by rememberSaveable { mutableStateOf(Task.SUMMARY) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var askExit by remember { mutableStateOf(false) }
    val archiveUi = remember { ArchiveUi() }
    val collectUi = remember { ArchiveUi() }

    // 공유로 들어오면 홈으로
    LaunchedEffect(goHome) {
        if (goHome) { tab = 0; resultId = null; settings = false; vm.goHome.value = false }
    }
    LaunchedEffect(st.message) {
        st.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.messageShown() }
    }

    // 뒤로가기: 열린 화면/모드 취소 → 홈 탭 → 종료 확인
    BackHandler {
        when {
            resultId != null -> resultId = null
            settings -> settings = false
            tab == 1 && archiveUi.back() -> Unit
            tab == 2 && collectUi.back() -> Unit
            tab != 0 -> tab = 0
            else -> askExit = true
        }
    }

    val openResult: (String, Task) -> Unit = { id, t -> resultId = id; resultTask = t }

    AppBackground {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when {
                resultId != null -> ResultScreen(st, vm, resultId!!, resultTask) { resultId = null }
                settings -> SettingsScreen(st, vm) { settings = false }
                else -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        if (tab == 0) HomeScreen(st, vm, openResult, onSettings = { settings = true }, onShowAll = { tab = 1 })
                        else if (tab == 1) ArchiveScreen(st, vm, archiveUi, openResult)
                        else CollectScreen(st, vm, collectUi, openResult)
                    }
                    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp_()) {
                        listOf("홈" to Icons.Outlined.Home, "보관함" to Icons.Outlined.Inventory2, "수집함" to Icons.Outlined.TravelExplore).forEachIndexed { i, (label, icon) ->
                            NavigationBarItem(
                                selected = tab == i, onClick = { tab = i },
                                icon = { Icon(icon, label) },
                                label = { Text(label, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Medium) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = C.brand, selectedTextColor = C.brand, indicatorColor = C.brand2,
                                    unselectedIconColor = C.sub, unselectedTextColor = C.sub,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    if (askExit) AlertDialog(
        onDismissRequest = { askExit = false },
        title = { Text("앱을 종료하겠습니까?") },
        confirmButton = { TextButton(onClick = { askExit = false; exit() }) { Text("예") } },
        dismissButton = { TextButton(onClick = { askExit = false }) { Text("아니오") } },
    )
}

private fun Int.dp_() = androidx.compose.ui.unit.Dp(this.toFloat())
