package cn.qqagent.android.gate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Normal BOOT_COMPLETED arrives after credential storage is available; force-stop suppresses it. */
class RuntimeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ActivitySettingsStore.migrateToPaused(context)
        if (context.getSharedPreferences(RuntimeForegroundService.PREFERENCES, Context.MODE_PRIVATE).getBoolean("enabled", false)) {
            context.startForegroundService(Intent(context, RuntimeForegroundService::class.java).setAction(RuntimeForegroundService.ACTION_START))
        }
    }
}
