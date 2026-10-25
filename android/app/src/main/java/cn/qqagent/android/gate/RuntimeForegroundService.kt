package cn.qqagent.android.gate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Native lifetime owner for the single private QQ runtime; does not start an auto-reply bot. */
class RuntimeForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching = false
    private var restarts = 0
    private var consoleRestarts = 0
    private var mediaBusy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ActivitySettingsStore.migrateToPaused(this)
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        if (intent?.action == ACTION_STOP) {
            preferences.edit().putBoolean("enabled", false).apply()
            record("STOPPING")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START && !preferences.getBoolean("enabled", false)) {
            stopSelf(); return START_NOT_STICKY
        }
        val gate = runCatching { JSONObject(File(filesDir, "gate-report.json").readText()).optBoolean("gatePassed") }.getOrDefault(false)
        if (!gate) { record("FIRST_GATE_REQUIRED"); stopSelf(); return START_NOT_STICKY }
        preferences.edit().putBoolean("enabled", true).apply()
        if (intent?.action == ACTION_MEDIA && !mediaBusy) {
            mediaBusy = true
            scope.launch { try { RuntimeBootstrap(this@RuntimeForegroundService).prepareMedia() } finally { mediaBusy = false } }
        }
        if (intent?.action in setOf(ACTION_PAUSE, ACTION_RESUME, ACTION_RELOAD)) scope.launch {
            runCatching {
                when (intent?.action) {
                    ACTION_PAUSE -> ConsoleApi(this@RuntimeForegroundService).setReplies(false)
                    ACTION_RESUME -> ConsoleApi(this@RuntimeForegroundService).setReplies(true)
                    ACTION_RELOAD -> ConsoleSession.stop()
                }
            }.onFailure { record("CONTROL_FAILED", it.javaClass.simpleName) }
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "QQ 运行服务", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, RuntimeForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pause = PendingIntent.getService(this, 2, Intent(this, RuntimeForegroundService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val resume = PendingIntent.getService(this, 3, Intent(this, RuntimeForegroundService::class.java).setAction(ACTION_RESUME), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_gate)
            .setContentTitle("QQ Agent 运行服务").setContentText("点击打开控制台；回复范围由设置中的名单决定")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "暂停回复", pause).build())
            .addAction(Notification.Action.Builder(null, "恢复回复", resume).build())
            .addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(301, notification, type)
        if (!watching) {
            watching = true
            scope.launch {
                var stableSince = 0L
                while (isActive && preferences.getBoolean("enabled", false)) {
                    if (!RuntimeSession.isRunning()) {
                        stableSince = 0L
                        if (restarts >= 3) {
                            preferences.edit().putBoolean("enabled", false).apply()
                            record("RESTART_LIMIT_REACHED"); stopSelf(); break
                        }
                        restarts++
                        record("STARTING")
                        val launched = runCatching { RuntimeBootstrap(this@RuntimeForegroundService).launchQQ() }
                        if (!preferences.getBoolean("enabled", false)) { RuntimeSession.stop(); break }
                        if (launched.isFailure || !RuntimeSession.isRunning()) {
                            record("RETRY_WAIT", launched.exceptionOrNull()?.javaClass?.simpleName)
                            delay(2000L shl (restarts - 1)); continue
                        }
                    }
                    if (stableSince == 0L) stableSince = System.currentTimeMillis()
                    if (!ConsoleSession.isRunning()) {
                        if (consoleRestarts >= 3) { preferences.edit().putBoolean("enabled", false).apply(); record("CORE_RESTART_LIMIT_REACHED"); stopSelf(); break }
                        consoleRestarts++
                        val launched = runCatching { RuntimeBootstrap(this@RuntimeForegroundService).launchConsole() }
                        if (launched.isFailure) { record("CORE_RETRY_WAIT", launched.exceptionOrNull()?.javaClass?.simpleName); delay(2000L shl (consoleRestarts - 1)); continue }
                    }
                    if (System.currentTimeMillis() - stableSince >= 120000 && ConsoleSession.reachable()) { restarts = 0; consoleRestarts = 0 }
                    record("RUNNING")
                    delay(15000)
                }
            }
        }
        return START_STICKY
    }

    @Synchronized private fun record(state: String, errorType: String? = null) {
        val onebot = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 3000), 300) }; true }.getOrDefault(false)
        val report = JSONObject().put("schema", 1).put("appVersion", BuildConfig.VERSION_NAME).put("timestamp", System.currentTimeMillis())
            .put("state", state).put("qqProcessRunning", RuntimeSession.isRunning()).put("oneBotHttpReachable", onebot)
            .put("restartAttempts", restarts).put("coreRestartAttempts", consoleRestarts)
            .put("coreProcessRunning", ConsoleSession.isRunning()).put("coreHttpReachable", ConsoleSession.reachable())
            .put("notificationsEnabled", getSystemService(NotificationManager::class.java).areNotificationsEnabled())
            .put("mediaSetupRunning", mediaBusy)
            .put("automaticRepliesEnabled", getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean("repliesEnabled", false)).put("twentyFourHourTestPassed", false)
        if (errorType != null) report.put("errorType", errorType)
        val partial = File(filesDir, "foreground-service-report.json.partial")
        partial.writeText(report.toString(2))
        check(partial.renameTo(File(filesDir, "foreground-service-report.json")))
        val stateFile = File(filesDir, "runtime/state/android-runtime.json")
        if (stateFile.parentFile!!.isDirectory) { val temp = File(stateFile.parentFile, "android-runtime.json.partial"); temp.writeText(report.toString()); check(temp.renameTo(stateFile)) }
    }

    override fun onDestroy() {
        scope.cancel()
        RuntimeBootstrap.stopMediaSetup()
        Thread { RuntimeBootstrap(this).stopMessagingTest(); ConsoleSession.stop(); RuntimeSession.stop(); record("STOPPED") }.start()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val PREFERENCES = "runtime-service"
        const val CHANNEL = "qqagent-runtime"
        const val ACTION_START = "cn.qqagent.android.START_RUNTIME"
        const val ACTION_STOP = "cn.qqagent.android.STOP_RUNTIME"
        const val ACTION_PAUSE = "cn.qqagent.android.PAUSE_REPLIES"
        const val ACTION_RESUME = "cn.qqagent.android.RESUME_REPLIES"
        const val ACTION_RELOAD = "cn.qqagent.android.RELOAD_CORE"
        const val ACTION_MEDIA = "cn.qqagent.android.PREPARE_MEDIA"
    }
}
