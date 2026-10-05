package cn.qqagent.android.gate

import android.os.Build
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var results by remember { mutableStateOf<List<GateResult>>(emptyList()) }
                var message by remember { mutableStateOf("") }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("QQ Agent · 第一关检查", style = MaterialTheme.typography.headlineSmall)
                        Text("检查原型 · 尚不具备机器人运行能力")
                        Text("独立包名和应用私有目录，不安装 Termux，不访问日常 QQ 数据。组件未完成验证前不会下载、执行或提示登录。")
                        Button(onClick = {
                            val facts = deviceFacts()
                            // No verified runtime release is available yet. Never fabricate pins or a PRoot binary.
                            results = GatePolicy.preflight(facts, emptyList(), bundledProot = false)
                            runCatching {
                                writeReport(facts, results)
                            }.onSuccess { message = "检查结果已保存，可通过 ADB 导出 gate-report.json。" }
                                .onFailure { message = "报告保存失败：${it.javaClass.simpleName}" }
                        }) { Text("检查第一关并记录设备信息") }
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

    private fun writeReport(facts: DeviceFacts, results: List<GateResult>) {
        val report = JSONObject().put("schema", 1).put("appVersion", "0.1.0-gate-check")
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

