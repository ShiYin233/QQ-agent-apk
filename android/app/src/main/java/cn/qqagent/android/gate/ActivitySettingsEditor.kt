package cn.qqagent.android.gate

import android.content.Context
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable fun ActivitySettingsEditor(context: Context, config: JSONObject, api: ConsoleApi, onClose: () -> Unit, onSaved: (JSONObject) -> Unit) {
    val scope = rememberCoroutineScope()
    var store by remember { mutableStateOf(JSONObject(config.optJSONObject("store")?.toString() ?: "{}")) }
    var active by remember { mutableStateOf(config.optJSONObject("chatActive")?.optBoolean("enabled") == true) }
    var ttl by remember { mutableStateOf((config.optJSONObject("chatActive")?.optInt("ttlMinutes", 30) ?: 30).toString()) }
    var history by remember { mutableStateOf(store.optInt("historyCount", 80).toString()) }
    var keywords by remember { mutableStateOf(store.optJSONArray("keywords")?.let { a -> (0 until a.length()).joinToString("\n") { a.optString(it) } }.orEmpty()) }
    var minute by remember { mutableStateOf((config.optJSONObject("send")?.optInt("maxPerMinute", 3) ?: 3).toString()) }
    var hour by remember { mutableStateOf((config.optJSONObject("send")?.optInt("maxPerHour", 30) ?: 30).toString()) }
    var selectedGroup by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun edit(block: (JSONObject) -> Unit) { store = JSONObject(store.toString()).also(block) }
    fun peak(): JSONObject = store.optJSONObject("peakSchedule") ?: JSONObject().put("enabled", false).put("peak", JSONObject().put("start", "09:00").put("end", "18:00").put("sliderPos", 5)).put("valley", JSONObject().put("sliderPos", 5))
    fun position(phase: String?): Double {
        if (phase == null) return if (selectedGroup.isEmpty()) ResponseTier.position(store) else store.optJSONObject("groupSliderPos")?.optDouble(selectedGroup, ResponseTier.position(store)) ?: ResponseTier.position(store)
        val fallback = peak().optJSONObject(phase)?.optDouble("sliderPos", 5.0) ?: 5.0
        return if (selectedGroup.isEmpty()) fallback else store.optJSONObject("groupPeakPos")?.optJSONObject(selectedGroup)?.optDouble(if (phase == "peak") "peak" else "valley", fallback) ?: fallback
    }
    fun changePosition(phase: String?, value: Double) { edit { draft ->
        if (phase == null) {
            if (selectedGroup.isEmpty()) draft.put("contextSliderPos", value)
            else draft.put("groupSliderPos", (draft.optJSONObject("groupSliderPos") ?: JSONObject()).put(selectedGroup, value))
        } else if (selectedGroup.isEmpty()) {
            val schedule = JSONObject(peak().toString()); val part = schedule.optJSONObject(phase) ?: JSONObject()
            schedule.put(phase, part.put("sliderPos", value)); draft.put("peakSchedule", schedule)
        } else {
            val groups = draft.optJSONObject("groupPeakPos") ?: JSONObject()
            val group = groups.optJSONObject(selectedGroup) ?: JSONObject().put("peak", position("peak")).put("valley", position("valley"))
            groups.put(selectedGroup, group.put(if (phase == "peak") "peak" else "valley", value)); draft.put("groupPeakPos", groups)
        }
    } }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text("活跃设置", style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("改动先保留在草稿；保存后仍保持暂停，不会发送 QQ 消息。")
                    Text("群聊档位控制普通群消息。私聊按原核心始终响应；回复名单和整体暂停对私聊也有效。")
                    Row { Text("统一设置", Modifier.weight(1f)); Switch(checked = store.optBoolean("unifiedTier", true), onCheckedChange = { selectedGroup = ""; edit { d -> d.put("unifiedTier", it) } }) }
                    if (!store.optBoolean("unifiedTier", true)) {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }) { Text(if (selectedGroup.isEmpty()) "当前编辑：全局设置" else "当前编辑：群 $selectedGroup") }
                            DropdownMenu(expanded, { expanded = false }) {
                                DropdownMenuItem(text = { Text("全局设置") }, onClick = { selectedGroup = ""; expanded = false })
                                config.optJSONObject("allow")?.optJSONArray("groups")?.let { groups -> for (i in 0 until groups.length()) {
                                    val id = groups.optString(i); DropdownMenuItem(text = { Text("群 $id") }, onClick = { selectedGroup = id; expanded = false })
                                } }
                            }
                        }
                        if (selectedGroup.isNotEmpty()) TextButton(onClick = { edit { d ->
                            d.put("groupSliderPos", (d.optJSONObject("groupSliderPos") ?: JSONObject()).put(selectedGroup, JSONObject.NULL))
                            d.put("groupPeakPos", (d.optJSONObject("groupPeakPos") ?: JSONObject()).put(selectedGroup, JSONObject.NULL))
                        } }) { Text("该群跟随全局") }
                    }
                    Row { Text("峰谷时段", Modifier.weight(1f)); Switch(checked = peak().optBoolean("enabled"), onCheckedChange = { edit { d -> d.put("peakSchedule", JSONObject(peak().toString()).put("enabled", it)) } }) }
                    if (peak().optBoolean("enabled")) {
                        Text("使用手机时区；高峰以外均为低谷，支持跨零点。")
                        for (key in listOf("start", "end")) OutlinedTextField(peak().optJSONObject("peak")?.optString(key).orEmpty(), { value -> edit { d ->
                            val schedule = JSONObject(peak().toString()); schedule.put("peak", (schedule.optJSONObject("peak") ?: JSONObject()).put(key, value)); d.put("peakSchedule", schedule)
                        } }, label = { Text(if (key == "start") "高峰开始 HH:MM" else "高峰结束 HH:MM") }, singleLine = true)
                    }
                    val phases = if (peak().optBoolean("enabled")) listOf("peak", "valley") else listOf(null)
                    for (phase in phases) {
                        Text(if (phase == "peak") "高峰档位" else if (phase == "valley") "低谷档位" else "响应档位")
                        Text(ResponseTier.description(position(phase)))
                        Slider(value = position(phase).toFloat(), onValueChange = { changePosition(phase, it.toDouble()) }, valueRange = 0f..100f)
                        Row { listOf(5.0, 15.0, 30.5, 95.0).forEachIndexed { index, value -> TextButton(onClick = { changePosition(phase, value) }) { Text(if (index == 2) "3档15%" else "${index + 1}档") } } }
                    }
                    OutlinedTextField(history, { history = it }, label = { Text("响应时读取的历史条数（1–500）") })
                    OutlinedTextField(keywords, { keywords = it }, label = { Text("关键词：每行一个") }, minLines = 2, maxLines = 5)
                    Row { Text("被召唤后持续参与", Modifier.weight(1f)); Switch(active, { active = it }) }
                    if (active) { Text("持续参与期间会跳过群聊档位判定。") ; OutlinedTextField(ttl, { ttl = it }, label = { Text("持续参与最长分钟数（1–240）") }) }
                    Text("发送频率上限", style = MaterialTheme.typography.titleMedium)
                    Text("每个聊天单独计数；达到上限后停止该聊天的后续发送。")
                    OutlinedTextField(minute, { minute = it }, label = { Text("每分钟最多发送条数（1–120）") })
                    OutlinedTextField(hour, { hour = it }, label = { Text("每小时最多发送条数（1–1000）") })
                    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                }
                Row {
                    TextButton(enabled = !busy, onClick = onClose) { Text("取消") }
                    Button(enabled = !busy, onClick = { scope.launch {
                        busy = true
                        runCatching { withContext(Dispatchers.IO) {
                            fun number(raw: String, range: IntRange, label: String) = raw.toIntOrNull()?.takeIf { it in range } ?: error("$label 超出范围")
                            val schedule = peak()
                            for (key in listOf("start", "end")) require(schedule.optJSONObject("peak")?.optString(key)?.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")) == true) { "请填写有效的 HH:MM 时段" }
                            val changed = JSONObject(store.toString()).put("contextSliderPos", ResponseTier.position(store)).put("peakSchedule", schedule)
                                .put("historyCount", number(history, 1..500, "历史条数")).put("keywords", JSONArray(keywords.lines().map { it.trim() }.filter { it.isNotEmpty() }))
                            val words = changed.getJSONArray("keywords")
                            require(words.length() <= 128 && (0 until words.length()).all { words.getString(it).length <= 128 }) { "关键词最多 128 个，每项最多 128 字" }
                            val patch = JSONObject().put("store", changed).put("chatActive", JSONObject().put("enabled", active).put("ttlMinutes", number(ttl, 1..240, "持续参与时长")))
                                .put("send", JSONObject(config.optJSONObject("send")?.toString() ?: "{}").put("maxPerMinute", number(minute, 1..120, "每分钟上限")).put("maxPerHour", number(hour, 1..1000, "每小时上限")))
                            if (ConsoleSession.isRunning()) {
                                val result = api.request("/api/android/activity", "POST", patch)
                                context.getSharedPreferences(RuntimeForegroundService.PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean("repliesEnabled", false).commit()
                                result.getJSONObject("config")
                            } else ActivitySettingsStore(context).saveOffline(patch)
                        } }.onSuccess(onSaved).onFailure { error = it.message ?: "保存失败" }
                        busy = false
                    } }) { Text("保存活跃设置并保持暂停") }
                }
            }
        }
    }
}
