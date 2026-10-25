package cn.qqagent.android.gate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Combines actual app-generated evidence; preflight success alone never passes an execution stage. */
class GateEvidence(private val context: Context) {
    fun collect(facts: DeviceFacts): List<GateResult> {
        val results = mutableListOf<GateResult>()
        val device = facts.sdk >= 31 && "arm64-v8a" in facts.abis
        results += result(Stage.DEVICE, device, "Android API ${facts.sdk} / ${facts.pageSize} 字节页；ARM64 检查")
        val componentError = runCatching { verifyComponents() }.exceptionOrNull()
        results += GateResult(Stage.COMPONENTS, if (componentError == null) Outcome.PASSED else Outcome.BLOCKED,
            if (componentError == null) "内置引导和四个下载归档的实际大小、SHA-256 已核验" else "组件校验未通过：${componentError.javaClass.simpleName}")
        val runtime = read("runtime-report.json")
        val initialize = device && componentError == null && listOf("nativeBootstrapPassed", "debianNodePassed", "originalCoreHttpPassed", "qqComponentsPassed").all { runtime.optBoolean(it) }
        results += result(Stage.INITIALIZE, initialize, "PRoot、Debian/Node、原核心 HTTP 和 QQ 依赖的实机报告")
        val contacts = read("contacts-report.json")
        val login = initialize && contacts.optBoolean("loginVerified")
        results += result(Stage.LOGIN, login, "原 OneBotClient get_login_info 的手机验证结果")
        val lists = login && contacts.optBoolean("contactsPassed")
        val details = contacts.optJSONObject("details") ?: JSONObject()
        results += result(Stage.CONTACTS, lists, "好友 ${details.optInt("friendCount")}，群 ${details.optInt("groupCount")}；包括无效令牌拒绝检查")
        val messaging = read("messaging-report.json")
        val sent = lists && messaging.optBoolean("messagingPassed") && messaging.optJSONObject("stage")?.optBoolean("passed") == true
        results += result(Stage.MESSAGING, sent, "指定好友和群的固定文字发送、真实入站与回复发送回执")
        val model = read("model-connectivity-report.json")
        val agent = sent && model.optBoolean("modelConnectivityPassed") && messaging.optBoolean("agentQqReplyPassed")
        results += result(Stage.AGENT_REPLY, agent, "手机原 Agent 调用真实模型，私聊与群聊均发送 pong")
        return results
    }

    private fun result(stage: Stage, passed: Boolean, detail: String) = GateResult(stage, if (passed) Outcome.PASSED else Outcome.NOT_RUN, detail)
    private fun read(name: String) = runCatching { JSONObject(File(context.filesDir, name).readText()) }.getOrDefault(JSONObject())

    private fun verifyComponents() {
        val native = JSONArray(context.assets.open("native/native-artifacts.json").bufferedReader().use { it.readText() })
        for (i in 0 until native.length()) {
            val pin = native.getJSONObject(i)
            val name = pin.getString("file")
            require(name in setOf("proot", "loader64", "loader32"))
            verify(File(context.filesDir, "runtime/bin/$name"), pin)
        }
        require(native.length() == 3)
        val components = JSONArray(context.assets.open("runtime/component-manifest.json").bufferedReader().use { it.readText() })
        val required = setOf("debian", "node", "linux-qq", "napcat")
        require((0 until components.length()).map { components.getJSONObject(it).getString("id") }.toSet() == required && components.length() == 4)
        for (i in 0 until components.length()) {
            val pin = components.getJSONObject(i)
            val name = pin.getString("filename")
            require(name == File(name).name && !name.contains(".."))
            verify(File(context.filesDir, "component-downloads/$name"), pin)
        }
    }

    private fun verify(file: File, pin: JSONObject) {
        require(file.isFile && file.length() == pin.getLong("size"))
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        require(hash.digest().joinToString("") { "%02x".format(it) } == pin.getString("sha256"))
    }
}
