package cn.qqagent.android.gate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun QQLoginScreen(context: Context, onStart: () -> Unit, onClose: () -> Unit) {
    val api = remember { QQLoginApi(context) }
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var fingerprint by remember { mutableStateOf("") }
    var blockedFingerprint by remember { mutableStateOf("") }
    var qrUrl by remember { mutableStateOf("") }
    var loggedIn by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision) {
        while (true) {
            if (!RuntimeSession.isRunning()) { bitmap = null; loggedIn = false; phase = "stopped" }
            else {
                val result = withContext(Dispatchers.IO) { runCatching { api.status() } }
                result.onSuccess { status ->
                    loggedIn = status.optBoolean("isLogin")
                    phase = status.optString("loginPhase")
                    error = if (status.optString("loginError").isBlank()) "" else "QQ 登录暂未成功，请在 QQ 中查看提示后重试。"
                    val url = status.optString("qrcodeurl")
                    if (url != qrUrl && qrUrl.isNotBlank()) { blockedFingerprint = fingerprint; bitmap = null }
                    qrUrl = url
                    if (loggedIn || refreshing || phase !in listOf("waiting_qrcode", "qrcode_scanned")) bitmap = null
                    else {
                        val qr = withContext(Dispatchers.IO) { LoginQr.read(File(context.filesDir, "runtime")) }
                        if (qr == null || qr.fingerprint == blockedFingerprint) bitmap = null
                        else if (qr.fingerprint != fingerprint || bitmap == null) {
                            bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(qr.bytes, 0, qr.bytes.size) }
                            if (bitmap != null) fingerprint = qr.fingerprint
                        }
                    }
                }.onFailure { bitmap = null; loggedIn = false; error = "正在等待 QQ 登录服务，请稍后同步。" }
            }
            delay(1000)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("QQ 登录", style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onClose) { Text("返回") }
                }
                Text(when {
                    loggedIn -> "QQ 已登录"
                    !RuntimeSession.isRunning() -> "QQ 尚未启动"
                    phase == "qrcode_scanned" -> "已扫码，请在 QQ 中确认登录"
                    phase == "initializing" -> "登录已确认，正在连接"
                    phase == "reconnecting" -> "QQ 正在恢复连接"
                    bitmap != null -> "请使用 QQ 扫描下方二维码"
                    else -> "正在获取最新二维码……"
                }, style = MaterialTheme.typography.titleLarge)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                bitmap?.let { Image(it.asImageBitmap(), "QQ 登录二维码", Modifier.size(280.dp)) }
                if (!loggedIn) Text("二维码每秒自动同步。使用同一台手机时，可截图后在 QQ 扫一扫中从相册选择。")
                if (!RuntimeSession.isRunning()) Button(enabled = !refreshing, onClick = {
                    refreshing = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) {
                            check(context.getSharedPreferences(RuntimeForegroundService.PREFERENCES, Context.MODE_PRIVATE)
                                .edit().putBoolean("repliesEnabled", false).commit()) { "回复暂停状态保存失败" }
                            if (ConsoleSession.isRunning()) ConsoleApi(context).setReplies(false)
                        } }.onSuccess { onStart() }.onFailure { error = "暂时无法暂停回复，请稍后重新启动登录。" }
                        refreshing = false
                    }
                }) { Text("启动 QQ 登录") }
                if (RuntimeSession.isRunning() && !loggedIn) {
                    Button(enabled = !refreshing && phase !in listOf("qrcode_scanned", "initializing", "reconnecting"), onClick = {
                        refreshing = true; blockedFingerprint = fingerprint; bitmap = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { api.refresh() } }
                                .onSuccess { error = "" }.onFailure { error = it.message ?: "二维码刷新失败，请稍后重试" }
                            refreshing = false; revision++
                        }
                    }) { Text(if (refreshing) "正在刷新……" else "刷新登录二维码") }
                }
                TextButton(onClick = { revision++ }) { Text("同步登录状态") }
                Text("登录入口不会启用自动回复。回复开关和活跃档位在协议管理及设置中调整。")
            }
        }
    }
}
