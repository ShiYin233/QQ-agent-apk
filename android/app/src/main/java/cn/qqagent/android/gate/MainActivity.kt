package cn.qqagent.android.gate

import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivitySettingsStore.migrateToPaused(this)
        runCatching { TestCredentialStore(this).exportPublicKey() }.onFailure {
            File(filesDir, "model-provision-error.json").writeText(JSONObject().put("errorType", it.javaClass.simpleName).toString())
        }
        // Debug-only fixed smoke probe, never accepts arbitrary commands.
        if (BuildConfig.DEBUG && intent.getBooleanExtra("run_runtime_probe", false)) {
            Thread { RuntimeBootstrap(this).probe(intent.getBooleanExtra("run_debian_probe", false), intent.getBooleanExtra("run_setup_qq", false)) }.start()
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("run_qq_probe", false)) {
            Thread {
                runCatching { RuntimeBootstrap(this).launchQQ() }.onFailure { error ->
                    File(filesDir, "qq-launch-report.json").writeText(JSONObject().put("running", false).put("error", error.message).toString(2))
                }
            }.start()
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("run_model_probe", false)) {
            Thread { RuntimeBootstrap(this).probeModel() }.start()
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("run_contacts_probe", false)) {
            Thread { RuntimeBootstrap(this).probeContacts() }.start()
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("run_messaging_probe", false)) {
            Thread { RuntimeBootstrap(this).probeMessaging() }.start()
        }
        if ((BuildConfig.DEBUG && intent.getBooleanExtra("run_foreground_probe", false)) ||
            getSharedPreferences(RuntimeForegroundService.PREFERENCES, MODE_PRIVATE).getBoolean("enabled", false)) {
            startForegroundService(Intent(this, RuntimeForegroundService::class.java).setAction(RuntimeForegroundService.ACTION_START))
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("run_media_probe", false))
            startForegroundService(Intent(this, RuntimeForegroundService::class.java).setAction(RuntimeForegroundService.ACTION_MEDIA))
        setContent {
            MaterialTheme {
                var diagnostics by remember { mutableStateOf(runCatching { !JSONObject(File(filesDir, "gate-report.json").readText()).optBoolean("gatePassed") }.getOrDefault(true)) }
                if (!diagnostics) {
                    ConsoleScreen(this@MainActivity, onDiagnostics = { diagnostics = true }, onStart = {
                        ensureNotifications()
                        startForegroundService(Intent(this@MainActivity, RuntimeForegroundService::class.java).setAction(RuntimeForegroundService.ACTION_START))
                    })
                    return@MaterialTheme
                }
                var results by remember { mutableStateOf<List<GateResult>>(emptyList()) }
                var message by remember { mutableStateOf("") }
                var runtimeBusy by remember { mutableStateOf(false) }
                var loginOpen by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val recorded = withContext(Dispatchers.IO) {
                        val facts = deviceFacts()
                        GateEvidence(this@MainActivity).collect(facts).also { writeReport(facts, it) }
                    }
                    results = recorded
                }
                if (loginOpen) QQLoginScreen(this@MainActivity, onStart = {
                    ensureNotifications()
                    startForegroundService(Intent(this@MainActivity, RuntimeForegroundService::class.java).setAction(RuntimeForegroundService.ACTION_START))
                }, onClose = { loginOpen = false })
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("QQ Agent · 第一关检查", style = MaterialTheme.typography.headlineSmall)
                        TextButton(onClick = { diagnostics = false }) { Text("打开原生控制台") }
                        Button(onClick = { loginOpen = true }) { Text("QQ 登录") }
                        Text("开发测试版 · 实测进度见下方核验记录")
                        Text("内置 PRoot、Debian、Node 原核心和 Linux QQ/NapCat。逐项记录真实手机结果。")
                        Button(onClick = {
                            ensureNotifications()
                            startForegroundService(Intent(this@MainActivity, RuntimeForegroundService::class.java).setAction(RuntimeForegroundService.ACTION_START))
                            message = "运行服务已启动，请查看常驻通知。自动回复测试不会重复启动。"
                        }) { Text("保持 QQ 运行并显示通知") }
                        Button(onClick = {
                            startService(Intent(this@MainActivity, RuntimeForegroundService::class.java).setAction(RuntimeForegroundService.ACTION_STOP))
                            message = "已请求停止运行服务"
                        }) { Text("停止运行服务") }
                        Button(enabled = !runtimeBusy, onClick = {
                            runtimeBusy = true
                            message = "正在运行 PRoot 引导验证……"
                            Thread {
                                val report = RuntimeBootstrap(this@MainActivity).probe()
                                runOnUiThread { message = report.toString(2); runtimeBusy = false }
                            }.start()
                        }) { Text("验证内置 PRoot 引导") }
                        Button(enabled = !runtimeBusy, onClick = {
                            runtimeBusy = true
                            message = "正在校验组件、初始化 Debian 并测试 Node……"
                            Thread {
                                val report = RuntimeBootstrap(this@MainActivity).probe(components = true)
                                runOnUiThread { message = report.toString(2); runtimeBusy = false }
                            }.start()
                        }) { Text("初始化已下载组件并验证 Node") }
                        Button(enabled = !runtimeBusy, onClick = {
                            runtimeBusy = true
                            message = "正在安装 Linux QQ 依赖和 NapCat，日志已写入应用私有目录……"
                            Thread {
                                val report = RuntimeBootstrap(this@MainActivity).probe(components = true, qq = true)
                                runOnUiThread { message = report.toString(2); runtimeBusy = false }
                            }.start()
                        }) { Text("准备 Linux QQ 和 NapCat") }
                        Button(enabled = !runtimeBusy, onClick = {
                            runtimeBusy = true
                            message = "正在启动 QQ/NapCat，等待登录二维码……"
                            Thread {
                                val result = runCatching { RuntimeBootstrap(this@MainActivity).launchQQ().toString(2) }
                                    .getOrElse { "启动失败：${it.message}" }
                                runOnUiThread { message = result; runtimeBusy = false }
                            }.start()
                        }) { Text("启动 QQ 登录验证") }
                        Button(onClick = { Thread { RuntimeSession.stop(); runOnUiThread { message = "QQ 测试进程已停止" } }.start() }) { Text("停止 QQ 测试进程") }
                        Button(enabled = !runtimeBusy, onClick = {
                            runtimeBusy = true
                            message = "正在使用已导入的测试 API 查询模型并请求一条短回复……"
                            Thread {
                                val report = RuntimeBootstrap(this@MainActivity).probeModel()
                                runOnUiThread { message = report.toString(2); runtimeBusy = false }
                            }.start()
                        }) { Text("测试手机模型连接") }
                        Button(enabled = !runtimeBusy, onClick = {
                            runtimeBusy = true
                            message = "正在连接手机上的 QQ，查询好友和群列表……"
                            Thread {
                                val report = RuntimeBootstrap(this@MainActivity).probeContacts()
                                runOnUiThread { message = report.toString(2); runtimeBusy = false }
                            }.start()
                        }) { Text("验证 QQ 登录、好友和群列表") }
                        Button(enabled = !runtimeBusy, onClick = {
                            runtimeBusy = true
                            message = "正在进行已授权目标的收发测试，收到测试消息后会等待你的安卓验证回复……"
                            Thread {
                                val report = RuntimeBootstrap(this@MainActivity).probeMessaging()
                                runOnUiThread { message = report.toString(2); runtimeBusy = false }
                            }.start()
                        }) { Text("测试已授权好友和群的 Agent 回复") }
                        Button(onClick = { Thread { RuntimeBootstrap(this@MainActivity).stopMessagingTest() }.start() }) { Text("停止 Agent 收发测试") }
                        Button(onClick = {
                            runtimeBusy = true
                            message = "正在校验实际组件并读取手机实测记录……"
                            Thread {
                                val facts = deviceFacts()
                                val recorded = GateEvidence(this@MainActivity).collect(facts)
                                val saved = runCatching { writeReport(facts, recorded) }
                                runOnUiThread {
                                    results = recorded
                                    message = if (saved.isFailure) "报告保存失败" else if (GatePolicy.passed(recorded)) "第一关实机链路已通过。长期后台和完整功能仍需继续验证。" else "记录已保存，未通过的步骤仍需实测。"
                                    runtimeBusy = false
                                }
                            }.start()
                        }, enabled = !runtimeBusy) { Text("核验第一关实测记录") }
                        results.forEach { result ->
                            Text("${stageLabel(result.stage)}：${outcomeLabel(result.outcome)}", style = MaterialTheme.typography.titleMedium)
                            Text(result.detail)
                        }
                        if (message.isNotEmpty()) Text(message)
                        Text("通过条件：初始化 → QQ 登录 → 群/好友列表 → 私聊和群聊双向收发 → 原 Agent 模型回复。全部需要真机实测，本应用不会模拟通过。")
                    }
                }
            }
        }
    }

    private fun deviceFacts() = DeviceFacts(
        Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(), Build.MANUFACTURER,
        Build.MODEL, Build.FINGERPRINT,
        Os.sysconf(OsConstants._SC_PAGESIZE), filesDir.usableSpace,
    )

    private fun ensureNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun writeReport(facts: DeviceFacts, results: List<GateResult>) {
        val report = JSONObject().put("schema", 1).put("appVersion", BuildConfig.VERSION_NAME)
            .put("gatePassed", GatePolicy.passed(results))
            .put("timestamp", System.currentTimeMillis())
            .put("device", JSONObject().put("sdk", facts.sdk).put("abis", JSONArray(facts.abis))
                .put("manufacturer", facts.manufacturer).put("model", facts.model).put("build", facts.build)
                .put("pageSize", facts.pageSize).put("availableBytes", facts.availableBytes))
            .put("results", JSONArray(results.map { JSONObject().put("stage", it.stage.name)
                .put("outcome", it.outcome.name).put("detail", it.detail) }))
        File(filesDir, "gate-report.json").writeText(report.toString(2))
    }
}

private fun stageLabel(stage: Stage) = when (stage) {
    Stage.DEVICE -> "设备前置检查"
    Stage.COMPONENTS -> "组件准备"
    Stage.INITIALIZE -> "首次初始化"
    Stage.LOGIN -> "QQ 登录"
    Stage.CONTACTS -> "群与好友列表"
    Stage.MESSAGING -> "双向收发"
    Stage.AGENT_REPLY -> "模型回复"
}

private fun outcomeLabel(outcome: Outcome) = when (outcome) {
    Outcome.PASSED -> "检查通过"
    Outcome.FAILED -> "失败"
    Outcome.BLOCKED -> "阻断"
    Outcome.NOT_RUN -> "未执行"
}

