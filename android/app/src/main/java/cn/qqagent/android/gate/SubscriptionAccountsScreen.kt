package cn.qqagent.android.gate

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable fun SubscriptionAccountsScreen(context: Context, onClose: () -> Unit, onChanged: () -> Unit) {
    val api = remember { ConsoleApi(context) }; val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(JSONObject()) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var callback by remember { mutableStateOf("") }
    var autoOpen by remember { mutableStateOf(false) }
    var openedUrl by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    fun browser(url: String) {
        val uri = Uri.parse(url)
        check(uri.scheme == "https" && uri.host in listOf("auth.openai.com", "accounts.google.com", "myaccount.google.com"))
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,uri)) }.onFailure { error = "手机没有可用浏览器，请先安装浏览器。" }
    }
    fun action(name: String, body: JSONObject = JSONObject(), after: (JSONObject) -> Unit = {}) {
        scope.launch {
            busy = true; error = ""; notice = ""
            runCatching { withContext(Dispatchers.IO) { api.request("/api/android/subscriptions/$name","POST",body) } }
                .onSuccess { result -> runCatching { after(result); revision++ }.onFailure { error = it.message ?: "账号操作未完成" } }
                .onFailure { error = it.message ?: "账号操作未完成" }
            busy = false
        }
    }
    fun close() { scope.launch {
        runCatching { withContext(Dispatchers.IO) { api.request("/api/android/subscriptions/cancel","POST",JSONObject()) } }
        onClose()
    } }
    LaunchedEffect(revision) {
        while (true) {
            runCatching { withContext(Dispatchers.IO) { api.request("/api/android/subscriptions") } }
                .onSuccess { status = it }
                .onFailure { error = "请先在首页启动运行服务，再连接订阅账号。" }
            delay(2000)
        }
    }
    val pending = status.optJSONObject("pending")
    val url = pending?.optString("url").orEmpty()
    LaunchedEffect(url,autoOpen) {
        if (autoOpen && url.isNotBlank() && url != openedUrl) { openedUrl = url; autoOpen = false; browser(url) }
    }
    Dialog(onDismissRequest = { if (!busy) close() },properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("模型账号",style = MaterialTheme.typography.headlineSmall)
                    TextButton(enabled = !busy,onClick = { close() }) { Text("返回") }
                }
                Text("连接订阅账号后，可将它选为 QQ Agent 的回复模型。账号登录与模型测试不会发送 QQ 消息。")
                Text("当前连接：${when (status.optString("active")) { "chatgpt-plus" -> "ChatGPT 订阅"; "google-ai-pro" -> "Google AI 订阅"; else -> "API Key" }}")
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error,color = MaterialTheme.colorScheme.error)
                if (notice.isNotBlank()) Text(notice)
                if (pending?.optString("status") == "waiting") {
                    Text("${pending.optString("stage","等待官方授权")}……")
                    if (url.isNotBlank()) TextButton(onClick = { browser(url) }) { Text("打开官方登录页") }
                    Text("在本手机浏览器完成授权后，返回此页面查看结果。如果使用电脑登录，可粘贴浏览器最后的本地返回地址。")
                    OutlinedTextField(callback,{callback = it},label = { Text("登录返回地址（通常无需填写）") },modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = callback.isNotBlank() && !busy,onClick = {
                        action("callback",JSONObject().put("id",pending.optString("id")).put("url",callback),after = { callback = "" })
                    }) { Text("提交返回地址") }
                    TextButton(enabled = !busy,onClick = { autoOpen = false; action("cancel") }) { Text("取消本次连接") }
                }
                if (pending?.optString("status") == "error") Text("${pending.optString("stage")}：${pending.optString("error")}",color = MaterialTheme.colorScheme.error)
                val providers = status.optJSONArray("providers")
                if (providers != null) for (index in 0 until providers.length()) {
                    val provider = providers.getJSONObject(index); val id = provider.getString("id")
                    val array = provider.optJSONArray("models")
                    val models = if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }
                    var model by remember(id) { mutableStateOf(context.getSharedPreferences(RuntimeForegroundService.PREFERENCES,Context.MODE_PRIVATE).getString("subscriptionModel-$id",null).orEmpty()) }
                    var expanded by remember { mutableStateOf(false) }
                    var projectId by remember(id) { mutableStateOf("") }
                    LaunchedEffect(id,models.map { it.optString("id") }) {
                        if (models.isNotEmpty() && models.none { it.optString("id") == model }) model = listOf("gpt-6.1-sol","gpt-5-mini","gemini-2.5-flash").firstOrNull { candidate -> models.any { it.optString("id") == candidate } } ?: models.first().getString("id")
                    }
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(provider.optString("name"),style = MaterialTheme.typography.titleLarge)
                        Text(if (provider.optBoolean("connected")) "已连接 ${provider.optString("email")}" else if (provider.optBoolean("authorized")) "授权已保存，模型项目尚未就绪" else "尚未连接")
                        if (id == "google-ai-pro" && provider.optBoolean("authorized") && !provider.optBoolean("connected")) {
                            if (provider.optString("setupError").isNotBlank()) Text(provider.optString("setupError"),color = MaterialTheme.colorScheme.error)
                            if (provider.optString("validationUrl").isNotBlank()) TextButton(onClick = { browser(provider.getString("validationUrl")) }) { Text("完成 Google 账号验证") }
                            OutlinedTextField(projectId,{ projectId = it },label = { Text("Cloud Project ID（仅账号要求时填写）") },modifier = Modifier.fillMaxWidth(),singleLine = true)
                            Button(enabled = !busy && pending?.optString("status") != "waiting",onClick = {
                                autoOpen = false; action("setup-google",JSONObject().put("projectId",projectId.trim()))
                            }) { Text("重试模型项目设置") }
                        }
                        if (provider.optString("modelError").isNotBlank()) Text(provider.optString("modelError"),color = MaterialTheme.colorScheme.error)
                        Button(enabled = !busy && pending?.optString("status") != "waiting",onClick = {
                            callback = ""; openedUrl = ""; autoOpen = true; action("login",JSONObject().put("provider",id))
                        }) { Text(if (provider.optBoolean("connected") || provider.optBoolean("authorized")) "重新连接账号" else if (id == "chatgpt-plus") "Continue with ChatGPT" else "使用 Google 账号连接") }
                        Box {
                            OutlinedButton(onClick = { expanded = true },enabled = !busy && models.isNotEmpty()) { Text(model.ifBlank { if (id == "chatgpt-plus") "登录后读取可用模型" else "选择模型" }) }
                            DropdownMenu(expanded = expanded,onDismissRequest = { expanded = false },modifier = Modifier.heightIn(max = 300.dp)) {
                                models.forEach { item -> DropdownMenuItem(text = { Text(item.optString("name")) },onClick = { model = item.getString("id"); expanded = false }) }
                            }
                        }
                        if (id == "chatgpt-plus" && provider.optBoolean("connected")) TextButton(enabled = !busy,onClick = {
                            action("refresh-models",JSONObject().put("provider",id),after = { notice = "已刷新此账号的可用模型" })
                        }) { Text("刷新账号模型列表") }
                        Button(enabled = !busy && provider.optBoolean("connected") && models.any { it.optString("id") == model },onClick = {
                            action("select",JSONObject().put("provider",id).put("model",model),after = {
                                check(context.getSharedPreferences(RuntimeForegroundService.PREFERENCES,Context.MODE_PRIVATE).edit()
                                    .putString("modelConnection",id).putString("subscriptionModel-$id",model).putBoolean("repliesEnabled",false).commit())
                                notice = "已切换模型，自动回复保持暂停"; onChanged()
                            })
                        }) { Text("使用此账号和模型") }
                        TextButton(enabled = !busy && provider.optBoolean("connected") && models.any { it.optString("id") == model },onClick = {
                            action("test",JSONObject().put("provider",id).put("model",model),after = { notice = "模型测试回复：${it.optString("text").take(300)}" })
                        }) { Text("测试模型连接") }
                        if (provider.optBoolean("connected") || provider.optBoolean("authorized")) TextButton(enabled = !busy,onClick = {
                            context.getSharedPreferences(RuntimeForegroundService.PREFERENCES,Context.MODE_PRIVATE).edit().putBoolean("repliesEnabled",false).apply()
                            action("disconnect",JSONObject().put("provider",id),after = { notice = "已断开账号，自动回复保持暂停"; onChanged() })
                        }) { Text("断开此账号") }
                    } }
                }
                TextButton(enabled = !busy && status.length() > 0,onClick = {
                    action("manual",after = {
                        context.getSharedPreferences(RuntimeForegroundService.PREFERENCES,Context.MODE_PRIVATE).edit().remove("modelConnection").putBoolean("repliesEnabled",false).apply()
                        notice = "已恢复原 API Key 连接，自动回复保持暂停"; onChanged()
                    })
                }) { Text("切回原 API Key 连接") }
                Text("订阅接入使用账号可用的模型权限和额度。登录凭据在本手机加密保存，原 API Key 配置会保留。")
            }
        }
    }
}
