package cn.qqagent.android.gate

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.floor

object ResponseTier {
    fun tier(position: Double): Int = when { position <= 10 -> 1; position <= 20 -> 2; position <= 90 -> 3; else -> 4 }
    fun probability(position: Double): Double = when (tier(position)) { 3 -> floor((position - 20) / 70 * 1000 + 0.5) / 10; 4 -> 100.0; else -> 0.0 }
    fun position(store: JSONObject): Double = if (store.has("contextSliderPos") && !store.isNull("contextSliderPos")) store.optDouble("contextSliderPos", 5.0) else when (store.optInt("contextTier", 1)) { 2 -> 15.0; 3 -> 20 + store.optDouble("randomPercent", 0.0) * 0.7; 4 -> 95.0; else -> 5.0 }
    fun description(position: Double): String = when (tier(position)) { 1 -> "1 档 · 仅被 @ 或拍一拍时响应"; 2 -> "2 档 · 被 @ 或命中关键词时响应"; 3 -> "3 档 · 被 @、关键词，以及普通群消息 ${probability(position)}% 概率响应"; else -> "4 档 · 名单内的每条普通群消息都会触发响应" }
}

/** Settings remain editable with QQ stopped. Never starts QQ or enables replies. */
class ActivitySettingsStore(private val context: Context) {
    private fun profile(): File {
        val link = JSONObject(File(context.filesDir, "runtime/state/protocol-link.json").readText())
        val account = link.getString("userId")
        require(account.matches(Regex("\\d{5,15}")))
        return File(context.filesDir, "runtime/state/data-account-$account/config.json")
    }
    fun readConfig(): JSONObject = runCatching { JSONObject(profile().readText()) }.getOrDefault(JSONObject())
    fun saveOffline(patch: JSONObject): JSONObject {
        check(!ConsoleSession.isRunning()) { "控制台正在运行，请通过控制台保存设置" }
        val file = profile(); val original = JSONObject(file.readText())
        val store = patch.getJSONObject("store")
        val position = store.getDouble("contextSliderPos")
        require(position.isFinite() && position in 0.0..100.0)
        store.put("contextTier", ResponseTier.tier(position)).put("randomPercent", ResponseTier.probability(position))
        original.put("store", store).put("chatActive", patch.getJSONObject("chatActive")).put("send", patch.getJSONObject("send"))
            .put("androidActivityReviewed", true)
        val temp = File(file.parentFile, "config.json.android-partial")
        temp.outputStream().use { it.write(original.toString(2).toByteArray()); it.fd.sync() }
        check(temp.renameTo(file))
        context.getSharedPreferences(RuntimeForegroundService.PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean("repliesEnabled", false).commit()
        return original
    }
    companion object {
        fun migrateToPaused(context: Context) {
            val preferences = context.getSharedPreferences(RuntimeForegroundService.PREFERENCES, Context.MODE_PRIVATE)
            if (preferences.getInt("activityUiVersion", 0) < 1)
                check(preferences.edit().putBoolean("enabled", false).putBoolean("repliesEnabled", false).putInt("activityUiVersion", 1).commit())
        }
    }
}
