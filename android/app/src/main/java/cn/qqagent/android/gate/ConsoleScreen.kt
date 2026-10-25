package cn.qqagent.android.gate

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val consolePages = listOf("会话", "存档", "记忆", "用量", "技能", "插件", "协议管理", "设置")
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun date(value: Long) = if (value == 0L) "暂无记录" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(value))
private fun chatName(key: String) = key.replace("group:", "群聊 · ").replace("private:", "私聊 · ")
private fun stateName(value: String) = when (value) { "running" -> "处理中"; "waiting" -> "等待中"; "done", "finished", "completed" -> "已回复"; "noreply" -> "模型未回复"; "failed", "error" -> "失败"; "aborted" -> "已中止"; else -> value }

@Composable fun ConsoleScreen(context: Context, onDiagnostics: () -> Unit, onStart: () -> Unit) {
    val api = remember { ConsoleApi(context) }
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(JSONObject()) }
    var runtime by remember { mutableStateOf(JSONObject()) }
    var config by remember { mutableStateOf(JSONObject()) }
    var payload by remember { mutableStateOf(JSONObject()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var detailTitle by remember { mutableStateOf("") }
    var detailType by remember { mutableStateOf("") }
    var detailPath by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var activityOpen by remember { mutableStateOf(false) }
    var loginOpen by remember { mutableStateOf(false) }
    var accountsOpen by remember { mutableStateOf(false) }
    fun service(action: String) { context.startForegroundService(Intent(context, RuntimeForegroundService::class.java).setAction(action)) }
    fun action(path: String, method: String = "POST", body: JSONObject? = null) {
        scope.launch {
            loading = true
            runCatching { withContext(Dispatchers.IO) { api.request(path, method, body) } }
                .onSuccess { notice = "已保存"; revision++ }.onFailure { error = it.message ?: "操作失败" }
            loading = false
        }
    }
    fun open(path: String, title: String, type: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.request(path) } }.onSuccess {
                detail = it; detailTitle = title; detailType = type; detailPath = path
            }.onFailure { error = it.message ?: "暂时无法读取详情" }
        }
    }
    LaunchedEffect(Unit) {
        config = withContext(Dispatchers.IO) { ActivitySettingsStore(context).readConfig() }
        api.events().collect { event ->
            if (event != "log" && event != "disconnected") { delay(250); revision++ }
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(15000); revision++ } }
    LaunchedEffect(page, revision) {
        loading = true
        val result = withContext(Dispatchers.IO) { runCatching {
            val currentStatus = api.request("/api/status")
            val currentRuntime = api.request("/api/android/runtime")
            val currentConfig = api.request("/api/config")
            val data = when (page) {
                0 -> api.request("/api/sessions?limit=100")
                1 -> api.request("/api/chats")
                2 -> api.request("/api/memory-files")
                3 -> api.request("/api/usage/stats?range=7")
                4, 5 -> api.request("/api/skills").put("tools", api.request("/api/tools").optJSONArray("tools"))
                    .put("availability", api.request("/api/tools/availability").optJSONArray("tools"))
                    .put("joinReview", if (page == 5) api.request("/api/android/join-review") else JSONObject())
                6 -> JSONObject().put("groups", api.request("/api/onebot/groups").optJSONArray("groups"))
                    .put("friends", api.request("/api/onebot/friends").optJSONArray("friends"))
                else -> api.request("/api/android/capabilities").put("media", api.request("/api/android/media"))
            }
            listOf(currentStatus, currentRuntime, currentConfig, data)
        } }
        result.onSuccess { status = it[0]; runtime = it[1]; config = it[2]; payload = it[3]; error = "" }
            .onFailure { error = if (it is java.io.IOException) "控制台正在启动或已停止，请稍后刷新。" else it.message ?: "读取失败" }
        loading = false
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("QQ Agent", style = MaterialTheme.typography.headlineSmall)
                Text(if (!RuntimeSession.isRunning()) "QQ 运行已停止 · 回复已暂停" else if (runtime.optBoolean("onebotConnected")) "QQ 已连接 · ${if (runtime.optBoolean("automaticRepliesEnabled")) "自动回复中" else "回复已暂停"}" else "等待 QQ 连接 · ${if (runtime.optBoolean("automaticRepliesEnabled")) "自动回复中" else "回复已暂停"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { loginOpen = true }) { Text("QQ 登录") }
                    TextButton(onClick = onStart) { Text("启动") }
                    TextButton(onClick = { revision++ }) { Text("刷新") }
                    TextButton(onClick = onDiagnostics) { Text("实机记录") }
                }
                TextButton(onClick = { accountsOpen = true }) { Text("模型账号 · ChatGPT / Google") }
            }
            ScrollableTabRow(selectedTabIndex = page, edgePadding = 12.dp) {
                consolePages.forEachIndexed { index, label -> Tab(selected = page == index, onClick = { page = index }, text = { Text(label) }) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (notice.isNotBlank()) Text(notice)
                when (page) {
                    0 -> {
                        Text("模型处理记录", style = MaterialTheme.typography.titleLarge)
                        val sessions = payload.optJSONArray("sessions").objects()
                        if (sessions.isEmpty()) Text("还没有模型会话。完成设置并启用指定聊天的回复后，这里显示原核心的处理记录。")
                        sessions.forEach { item -> InfoCard(chatName(item.optString("chatKey")), "${stateName(item.optString("status"))} · ${date(item.optLong("startedAt"))}\n${item.optString("triggerSummary")}\n${item.optJSONObject("usage")?.optLong("totalTokens") ?: 0} tokens") {
                            TextButton(onClick = { open("/api/sessions/${item.optString("id")}", "会话详情", "session") }) { Text("查看处理过程") }
                        } }
                    }
                    1 -> {
                        Text("聊天存档", style = MaterialTheme.typography.titleLarge)
                        val chats = payload.optJSONArray("chats").objects()
                        if (chats.isEmpty()) Text("此账号的正式配置从空白开始。已完成的收发验证记录保存在独立测试目录。")
                        chats.forEach { item ->
                            val key = item.optString("key")
                            InfoCard(item.optString("chatName").ifBlank { chatName(key) }, "最近消息：${date(item.optLong("lastTs"))}") {
                                TextButton(onClick = { open("/api/chats/${key.replace(':', '_')}/messages?limit=200", "聊天记录", "messages") }) { Text("查看消息") }
                                TextButton(onClick = { action("/api/chats/${key.replace(':', '_')}/mark-read", body = JSONObject()) }) { Text("标为已读") }
                            }
                        }
                    }
                    2 -> {
                        Text("长期记忆", style = MaterialTheme.typography.titleLarge)
                        val files = payload.optJSONArray("files").objects()
                        if (files.isEmpty()) Text("还没有记忆；原核心会在启用相关功能后按聊天保存。")
                        files.forEach { item -> val key = item.optString("chatKey"); InfoCard(chatName(key), "${item.optInt("memberCount")} 位成员 · ${item.optInt("impressionCount")} 条印象") {
                            TextButton(onClick = { open("/api/memory-files/${key.replace(':', '_')}", "成员记忆", "memory") }) { Text("查看记忆") }
                        } }
                    }
                    3 -> {
                        Text("模型用量", style = MaterialTheme.typography.titleLarge)
                        val usage = status.optJSONObject("usage") ?: JSONObject()
                        InfoCard("今天", "运行 ${usage.optInt("runs")} 次\n输入 ${usage.optLong("promptTokens")} · 输出 ${usage.optLong("completionTokens")}\n总计 ${usage.optLong("totalTokens")} tokens")
                        val cost = status.optJSONObject("cost")
                        Text("费用按当前模型价格估算；网关实际账单以服务商为准。")
                        if (cost != null) Text("估算费用：${cost.optDouble("cost", 0.0)} 元")
                        val totals = payload.optJSONObject("totals") ?: JSONObject()
                        InfoCard("最近 7 天", "运行 ${totals.optInt("runs")} 次 · ${totals.optLong("totalTokens")} tokens\n估算费用：${totals.optDouble("cost", 0.0)} 元")
                        payload.optJSONArray("days").objects().forEach { Text("${it.optString("day")} · ${it.optLong("totalTokens")} tokens · ${it.optDouble("cost", 0.0)} 元") }
                    }
                    4, 5 -> {
                        Text(if (page == 4) "技能" else "插件", style = MaterialTheme.typography.titleLarge)
                        val items = payload.optJSONArray("skills").objects().filter { (it.optString("kind") == "plugin") == (page == 5) }
                        if (items.isEmpty()) Text("暂无已安装项目。扩展安装与社区登录入口仍在移植。")
                        if (page == 5) Text("这里只显示原核心识别的插件；依赖未满足时会显示原因。")
                        items.forEach { item -> InfoCard(item.optString("name", item.optString("id")), item.optString("description")) {
                            Text(if (item.optBoolean("active")) "已生效" else item.optString("reason").ifBlank { item.optString("loadError").ifBlank { "未启用" } })
                            Row { Text("启用"); Switch(checked = item.optBoolean("enabled"), enabled = !loading, onCheckedChange = { enabled -> action("/api/skills/${item.optString("id")}", body = JSONObject().put("enabled", enabled)) }) }
                            if (item.optString("id") == "hd2-join-review") {
                                JoinReviewEditor(item.optJSONObject("settings") ?: JSONObject(), payload.optJSONObject("joinReview") ?: JSONObject(), loading) { settings ->
                                    action("/api/skills/hd2-join-review", body = JSONObject().put("settings", settings))
                                }
                            }
                        } }
                        if (page == 4) {
                            Text("原核心内置工具", style = MaterialTheme.typography.titleMedium)
                            val availability = payload.optJSONArray("availability").objects().associateBy { it.optString("id") }
                            payload.optJSONArray("tools").objects().filter { it.isNull("skillId") }.forEach { tool ->
                                val id = tool.optString("id"); val current = availability[id]
                                InfoCard(tool.optString("name", id), tool.optString("description").take(200)) {
                                    Text(if (current?.optBoolean("enabled") == true) "当前可用" else current?.optString("reason").orEmpty())
                                    Row { Text("启用工具"); Switch(checked = config.optJSONObject("tools")?.optJSONObject("overrides")?.optBoolean(id, tool.optBoolean("defaultEnabled", true)) ?: tool.optBoolean("defaultEnabled", true), enabled = !loading,
                                        onCheckedChange = { enabled -> action("/api/config", body = JSONObject().put("tools", JSONObject().put("overrides", JSONObject().put(id, enabled)))) }) }
                                }
                            }
                        }
                        Button(enabled = !loading, onClick = { action("/api/skills/reload", body = JSONObject()) }) { Text("重新扫描技能与插件") }
                        InstallCodeEditor(api, onInstalled = { revision++ }, onError = { error = it })
                    }
                    6 -> {
                        Text("手机 QQ 协议", style = MaterialTheme.typography.titleLarge)
                        InfoCard("Linux QQ + NapCat", if (runtime.optBoolean("onebotConnected")) "已连接；好友和群列表来自手机上的 QQ。" else "正在连接；可到实机记录页查看登录二维码。")
                        Button(onClick = { scope.launch { runCatching { withContext(Dispatchers.IO) { api.setReplies(!runtime.optBoolean("automaticRepliesEnabled")) } }.onSuccess { revision++ }.onFailure { error = it.message ?: "无法更改回复状态" } } }) { Text(if (runtime.optBoolean("automaticRepliesEnabled")) "暂停自动回复" else "启用名单内的自动回复") }
                        Text("空名单不放行。只对你在下面选择并保存的聊天启用回复，启用时忽略之前的未读消息。")
                        AllowListEditor(config, payload, loading) { groups, friends -> action("/api/config", body = JSONObject().put("allowAllWhenEmpty", false).put("allow", JSONObject().put("groups", JSONArray(groups)).put("private", JSONArray(friends)))) }
                        OutlinedButton(onClick = { context.startService(Intent(context, RuntimeForegroundService::class.java).setAction(RuntimeForegroundService.ACTION_STOP)); notice = "已请求停止 QQ 和机器人核心" }) { Text("停止全部运行") }
                    }
                    7 -> {
                        if (config.length() > 0) InfoCard("活跃设置", ResponseTier.description(ResponseTier.position(config.optJSONObject("store") ?: JSONObject()))) {
                            Text(if (config.optJSONObject("store")?.optJSONObject("peakSchedule")?.optBoolean("enabled") == true) "峰谷时段已开启；实际档位随时段和分群设置变化。" else "峰谷时段关闭")
                            Text("QQ 停止时也可编辑。设置不会自动启用回复。")
                            Button(onClick = { activityOpen = true }) { Text("编辑活跃设置") }
                        }
                        InfoCard("后台运行", "熄屏后需要持续连接 QQ 时，请在手机的耗电管理中允许本应用后台运行。OPPO 可选择“完全允许后台行为”；持续运行会增加耗电。") {
                            OutlinedButton(onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}")))
                                }.onFailure { error = "无法打开应用设置，请到手机设置中的应用管理查看。" }
                            }) { Text("打开手机后台设置") }
                        }
                        if (config.length() > 0) SettingsEditor(context, config, loading, onModelSaved = { service(RuntimeForegroundService.ACTION_RELOAD); notice = "模型配置已加密保存，核心正在重新加载"; revision++ }, onError = { error = it }, onSave = { action("/api/config", body = it) })
                        Button(onClick = { service(RuntimeForegroundService.ACTION_MEDIA); notice = "正在安装媒体依赖并运行本机压缩、转码和抽帧检查。进度保存在实机报告中。" }) { Text("安装并验证媒体依赖") }
                        val media = payload.optJSONObject("media")
                        Text(if (runtime.optBoolean("mediaSetupRunning")) "媒体组件正在安装与检查……" else if (media?.optBoolean("passed") == true) "本机图片压缩、GIF 转视频、视频抽帧检查已通过" else "媒体检查尚未通过，可安装后查看实测结果。")
                        Text("平台能力", style = MaterialTheme.typography.titleLarge)
                        payload.optJSONArray("capabilities").objects().forEach { InfoCard(it.optString("name"), "${if (it.optBoolean("available")) "可用" else "待完成"} · ${it.optString("reason")}") }
                        Text("遥测默认关闭。24 小时后台、重启恢复、媒体回归与正式签名仍待验证。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (activityOpen) ActivitySettingsEditor(context, config, api, onClose = { activityOpen = false }, onSaved = {
        config = it; activityOpen = false; notice = "活跃设置已保存，自动回复保持暂停"; revision++
    })
    if (loginOpen) QQLoginScreen(context, onStart, onClose = { loginOpen = false; revision++ })
    if (accountsOpen) SubscriptionAccountsScreen(context,onClose = { accountsOpen = false; revision++ },onChanged = { revision++ })
    detail?.let { current ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(detailTitle) }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (detailType) {
                    "messages" -> current.optJSONArray("messages").objects().forEach { Text("${it.optString("senderName").ifBlank { it.optString("senderId") }} · ${date(it.optLong("ts"))}\n${it.optString("text")}") }
                    "memory" -> current.optJSONArray("members").objects().forEach { member ->
                        Text(member.optString("name").ifBlank { member.optString("userId") }, style = MaterialTheme.typography.titleMedium)
                        member.optJSONArray("impressions").objects().forEach { Text(it.optString("content")) }
                    }
                    else -> {
                        Text("${chatName(current.optString("chatKey"))} · ${stateName(current.optString("status"))}")
                        Text("模型：${current.optString("model")}\n总用量：${current.optJSONObject("usage")?.optLong("totalTokens") ?: 0} tokens")
                        if (!current.isNull("finishReason")) Text("收尾原因：${current.optString("finishReason")}")
                        current.optJSONArray("messages").objects().forEach { Text("${it.optString("role")}\n${it.optString("content").take(3000)}") }
                        if (!current.isNull("error")) Text(current.optString("error"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } }, dismissButton = {
            if (detailType == "session") TextButton(onClick = { pendingDelete = detailPath }) { Text("删除此记录") }
        })
    }
    pendingDelete?.let { path -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除这条会话记录？") }, text = { Text("只删除这条已结束的模型处理记录。") }, confirmButton = { TextButton(onClick = { pendingDelete = null; detail = null; action(path, "DELETE") }) { Text("删除") } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }) }
}

@Composable private fun InfoCard(title: String, text: String, content: @Composable ColumnScope.() -> Unit = {}) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(text); content() } }
}

@Composable private fun JoinReviewEditor(settings: JSONObject, status: JSONObject, busy: Boolean, onSave: (JSONObject) -> Unit) {
    val saved = settings.toString()
    var group by remember(saved) { mutableStateOf(settings.optString("targetGroup", "")) }
    var interval by remember(saved) { mutableStateOf(settings.optInt("checkInterval", 30).toString()) }
    var ai by remember(saved) { mutableStateOf(settings.optBoolean("aiAssist", true)) }
    Text("问题：绝地潜兵2中你最不喜欢的战备是？")
    Text("合格自动通过，其余保留人工。审核有独立开关，暂停聊天回复不会关闭审核。")
    OutlinedTextField(group, { group = it }, label = { Text("审核群号") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(interval, { interval = it }, label = { Text("检查间隔（秒）") }, modifier = Modifier.fillMaxWidth())
    Row { Text("AI 辅助识别"); Switch(checked = ai, enabled = !busy, onCheckedChange = { ai = it }) }
    val seconds = interval.toIntOrNull()
    val valid = group.matches(Regex("[1-9]\\d{4,14}")) && seconds != null && seconds in 5..3600
    Button(enabled = !busy && valid, onClick = {
        onSave(JSONObject().put("targetGroup", group).put("checkInterval", seconds).put("aiAssist", ai))
    }) { Text("保存审核设置") }
    Text("检查状态：${status.optString("lastCheckResult", "未开始")}\n群身份：${status.optString("botRole", "未知")}")
    val stats = status.optJSONObject("stats") ?: JSONObject()
    Text("已通过 ${stats.optLong("approved")} 条 · 留人工 ${stats.optLong("retained")} 条 · AI 用量 ${stats.optJSONObject("aiTokens")?.optLong("total_tokens") ?: 0} tokens")
    if (status.optInt("uncertainApprovals") > 0) Text("有批准结果不确定的申请，请在 QQ 中人工核对。")
}

@Composable private fun InstallCodeEditor(api: ConsoleApi, onInstalled: () -> Unit, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var verifiedCode by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    Text("社区安装口令", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(code, { code = it; entry = null }, label = { Text("技能或插件安装口令") }, modifier = Modifier.fillMaxWidth())
    Button(enabled = !busy && code.isNotBlank(), onClick = { scope.launch {
        busy = true
        runCatching { withContext(Dispatchers.IO) { api.request("/api/market/verify", "POST", JSONObject().put("codes", JSONArray().put(code.trim().uppercase()))) } }
            .onSuccess { result ->
                val candidate = result.optJSONArray("results")?.optJSONObject(0)
                if (candidate?.optBoolean("ok") == true) { entry = candidate; verifiedCode = code.trim().uppercase() }
                else onError(candidate?.optString("error").orEmpty().ifBlank { "口令未通过校验" })
            }.onFailure { onError(it.message ?: "社区暂时不可用") }
        busy = false
    } }) { Text("查询安装内容") }
    entry?.let { item ->
        Text("${item.optString("name", item.optString("id"))} · ${item.optString("type")}\n${item.optString("description")}")
        Text("安装后将加载并运行该扩展，请只安装可信来源。")
        Button(enabled = !busy, onClick = { scope.launch {
            busy = true
            runCatching { withContext(Dispatchers.IO) { api.request("/api/market/install", "POST", JSONObject().put("code", verifiedCode).put("entry", item)) } }
                .onSuccess { entry = null; code = ""; onInstalled() }.onFailure { onError(it.message ?: "安装失败") }
            busy = false
        } }) { Text("安装此扩展") }
    }
}

@Composable private fun AllowListEditor(config: JSONObject, payload: JSONObject, busy: Boolean, save: (List<String>, List<String>) -> Unit) {
    fun ids(name: String) = config.optJSONObject("allow")?.optJSONArray(name)?.let { array -> (0 until array.length()).map { array.optString(it) }.toSet() } ?: emptySet()
    val savedGroups = ids("groups")
    val savedFriends = ids("private")
    var groups by remember(savedGroups) { mutableStateOf(savedGroups) }
    var friends by remember(savedFriends) { mutableStateOf(savedFriends) }
    val dirty = groups != savedGroups || friends != savedFriends
    Text("已选择 ${groups.size} 个群、${friends.size} 位好友 · ${if (dirty) "有未保存的改动" else "名单已保存"}")
    Button(enabled = !busy && dirty, onClick = { save(groups.toList(), friends.toList()) }) { Text("保存回复名单") }
    payload.optJSONArray("groups").objects().forEach { group -> Row(Modifier.fillMaxWidth()) {
        Checkbox(checked = group.optString("id") in groups, onCheckedChange = { chosen -> groups = if (chosen) groups + group.optString("id") else groups - group.optString("id") })
        Text("${group.optString("name")}\n群 ${group.optString("id")}", Modifier.padding(top = 8.dp))
    } }
    payload.optJSONArray("friends").objects().forEach { friend -> Row(Modifier.fillMaxWidth()) {
        Checkbox(checked = friend.optString("id") in friends, onCheckedChange = { chosen -> friends = if (chosen) friends + friend.optString("id") else friends - friend.optString("id") })
        Text("${friend.optString("name")}\nQQ ${friend.optString("id")}", Modifier.padding(top = 8.dp))
    } }
    Button(enabled = !busy, onClick = { save(groups.toList(), friends.toList()) }) { Text("保存回复名单") }
}

@Composable private fun SettingsEditor(context: Context, config: JSONObject, busy: Boolean, onModelSaved: () -> Unit, onError: (String) -> Unit, onSave: (JSONObject) -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(config.optJSONObject("api")?.optString("baseUrl").orEmpty()) }
    var model by remember { mutableStateOf(config.optJSONObject("api")?.optString("model").orEmpty()) }
    var key by remember { mutableStateOf("") }
    var modelFieldsReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { ModelSettingsStore(context).read() } }.onSuccess {
            address = it.optString("baseUrl"); model = it.optString("model"); modelFieldsReady = true
        }.onFailure { onError("原 API Key 配置暂时无法读取，请重试") }
    }
    var role by remember { mutableStateOf(config.optJSONObject("persona")?.optString("roleText").orEmpty()) }
    var botName by remember { mutableStateOf(config.optJSONObject("persona")?.optString("botName").orEmpty()) }
    Text("模型连接", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(address, { address = it }, label = { Text("API 地址（HTTPS）") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(model, { model = it }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(key, { key = it }, label = { Text("API Key（留空保留现有密钥）") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Text("密钥保存在手机加密存储中，不写入原核心的配置文件。")
    Button(enabled = !busy && modelFieldsReady, onClick = { scope.launch {
        runCatching { withContext(Dispatchers.IO) {
            val store = ModelSettingsStore(context); val previous = store.read()
            store.save(JSONObject().put("baseUrl", address.trim()).put("model", model.trim()).put("apiKey", key.ifBlank { previous.optString("apiKey") }))
        } }.onSuccess {
            context.getSharedPreferences(RuntimeForegroundService.PREFERENCES,Context.MODE_PRIVATE).edit().remove("modelConnection").putBoolean("repliesEnabled",false).apply()
            key = ""; onModelSaved()
        }.onFailure { onError(it.message ?: "模型配置保存失败") }
    } }) { Text("保存模型连接") }
    Text("人设", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(botName, { botName = it }, label = { Text("机器人名字") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(role, { role = it }, label = { Text("角色描述") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
    Button(enabled = !busy, onClick = { onSave(JSONObject().put("persona", JSONObject().put("botName", botName).put("roleText", role))) }) { Text("保存人设") }
}
