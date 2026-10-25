package cn.qqagent.android.gate

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Runs fixed, bounded bootstrap checks under the application UID. */
class RuntimeBootstrap(private val context: Context) {
    private val home = File(context.filesDir, "runtime")
    private val bin = File(home, "bin")
    private val tmp = File(home, "tmp")

    fun launchConsole(): Boolean = synchronized(probeLock) {
        val root = File(home, "rootfs")
        require(File(root, "opt/agent/src/app.js").isFile) { "请先初始化原核心" }
        val link = JSONObject(File(home, "state/protocol-link.json").readText())
        val account = link.getString("userId")
        require(account.matches(Regex("\\d{5,15}")))
        val scripts = File(home, "scripts").apply { mkdirs() }
        for (name in listOf("android-console.mjs", "android-console-policy.mjs", "android-reply-guard.mjs", "model-response-adapter.mjs",
            "subscription-sdk.mjs", "android-subscription-oauth.mjs", "android-subscription-google.mjs", "android-subscription-adapter.mjs", "android-subscriptions.mjs"))
            context.assets.open("runtime/$name").use { input -> File(scripts, name).outputStream().use { input.copyTo(it) } }
        prepareAndroidPlugin(root)
        val random = ByteArray(32); SecureRandom().nextBytes(random)
        val token = random.joinToString("") { "%02x".format(it) }
        val preferences = context.getSharedPreferences(RuntimeForegroundService.PREFERENCES,Context.MODE_PRIVATE)
        val deviceId = preferences.getString("subscriptionHostId",null) ?: java.util.UUID.randomUUID().toString().also {
            check(preferences.edit().putString("subscriptionHostId",it).commit())
        }
        val manual = ModelSettingsStore(context).read().put("provider", "")
        val bootstrap = JSONObject().put("token", token).put("account", account).put("protocolToken", link.getString("accessToken"))
            .put("api", SubscriptionSelection.api(context,manual)).put("manualApi",manual)
            .put("subscriptionHostId",deviceId).put("credentialBridge",SubscriptionCredentialBridge.start(context))
            .put("repliesEnabled", context.getSharedPreferences(RuntimeForegroundService.PREFERENCES, Context.MODE_PRIVATE).getBoolean("repliesEnabled", false))
            .put("timeZone", java.util.TimeZone.getDefault().id)
        File(home, "state/console-link.json").writeText(JSONObject().put("token", token).put("port", 3312).toString())
        ConsoleSession.start(builder(guest(root, listOf("/usr/bin/env", "QQ_AGENT_DATA_DIR=/qqagent-state/data-account-$account",
            "/opt/node/bin/node", "/qqagent-scripts/android-console.mjs"))), home, bootstrap)
    }

    private fun prepareAndroidPlugin(root: File) {
        for (plugin in listOf("android-video-frames", "android-mention-identity", "hd2-join-review")) {
            val target = File(root, "opt/agent/plugins/$plugin").apply { mkdirs() }
            copyPluginAssets("runtime/plugins/$plugin", target)
        }
    }

    private fun copyPluginAssets(sourcePath: String, target: File) {
        // Helpers are installed before the manifest; plugin source contains no user data.
        val names = context.assets.list(sourcePath)?.sortedBy { it == "plugin.json" }.orEmpty()
        for (name in names) {
            val asset = "$sourcePath/$name"
            val destination = File(target, name)
            if (!context.assets.list(asset).isNullOrEmpty()) {
                check(destination.mkdirs() || destination.isDirectory)
                copyPluginAssets(asset, destination)
            } else {
                val staging = File(target, "$name.partial")
                context.assets.open(asset).use { source -> staging.outputStream().use { source.copyTo(it) } }
                check(staging.renameTo(destination))
            }
        }
    }

    fun prepareMedia(): JSONObject = synchronized(probeLock) {
        val report = JSONObject().put("timestamp", System.currentTimeMillis()).put("passed", false)
        try {
            val root = File(home, "rootfs"); prepareScriptsAndDns(root); prepareAndroidPlugin(root)
            for (name in listOf("setup-media.sh", "probe-media.mjs")) context.assets.open("runtime/$name").use { source ->
                File(home, "scripts/$name").outputStream().use { source.copyTo(it) }
            }
            val stage = run("android-media", guest(root, listOf("/usr/bin/env", "QQ_AGENT_DATA_DIR=/qqagent-state/media-probe-data", "/bin/sh", "/qqagent-scripts/setup-media.sh")), "QQAGENT_ANDROID_MEDIA_OK", 2400)
            report.put("stage", stage)
            val details = JSONObject(File(home, "state/media-report.json").readText())
            report.put("details", details).put("passed", stage.optBoolean("passed") && details.optBoolean("passed"))
        } catch (error: Exception) { report.put("errorType", error.javaClass.simpleName) }
        File(context.filesDir, "media-runtime-report.json").writeText(report.toString(2)); report
    }

    fun probeModel(): JSONObject = synchronized(probeLock) {
        val report = JSONObject().put("schema", 1).put("appVersion", BuildConfig.VERSION_NAME)
            .put("timestamp", System.currentTimeMillis()).put("modelConnectivityPassed", false)
            .put("agentQqReplyPassed", false).put("qqMessagesSent", 0)
        val state = File(home, "state")
        val input = File(state, "model-probe-input.json")
        val profileConfig = File(state, "model-probe-data/config.json")
        try {
            installNative()
            val root = File(home, "rootfs")
            require(File(root, "opt/agent/src/llm.js").isFile) { "Initialize the original Agent core first" }
            prepareScriptsAndDns(root)
            check(state.mkdirs() || state.isDirectory)
            input.writeText(TestCredentialStore(context).read().toString())
            context.assets.open("runtime/probe-model.mjs").use { source ->
                File(home, "scripts/probe-model.mjs").outputStream().use { source.copyTo(it) }
            }
            val nodeReport = File(state, "model-connectivity.json")
            if (nodeReport.exists()) check(nodeReport.delete())
            val stage = run("model-connectivity", guest(root, listOf("/usr/bin/env",
                "QQ_AGENT_DATA_DIR=/qqagent-state/model-probe-data", "/opt/node/bin/node", "/qqagent-scripts/probe-model.mjs")),
                "QQAGENT_MODEL_CONNECTIVITY_OK", 100)
            report.put("stage", stage)
            if (nodeReport.isFile) {
                val details = JSONObject(nodeReport.readText())
                report.put("details", details).put("modelConnectivityPassed",
                    stage.getBoolean("passed") && details.optBoolean("modelConnectivityPassed"))
            }
        } catch (error: Exception) {
            // Credential parsing and crypto errors must never include plaintext.
            report.put("errorType", error.javaClass.simpleName)
        } finally {
            input.delete()
            profileConfig.delete()
        }
        File(context.filesDir, "model-connectivity-report.json").writeText(report.toString(2))
        report
    }

    fun launchQQ(): JSONObject = synchronized(probeLock) {
        val root = File(home, "rootfs")
        require(File(home, "state/qq-dependencies.ready").isFile && File(root, "opt/QQ/qq").isFile) { "Initialize Linux QQ dependencies first" }
        val webConfig = File(root, "opt/napcat/config/webui.json")
        val config = if (webConfig.isFile) JSONObject(webConfig.readText()) else JSONObject()
        if (config.optString("token").isBlank()) {
            val random = ByteArray(32); SecureRandom().nextBytes(random)
            config.put("token", random.joinToString("") { "%02x".format(it) })
        }
        config.put("host", "127.0.0.1").put("port", 6099).put("disableWebUI", false)
        webConfig.writeText(config.toString(2))
        val account = configuredAccount(root)
        val command = guest(root, listOf("/usr/bin/xvfb-run", "-a", "-s", "-screen 0 640x480x24 -nolisten tcp",
            "/opt/QQ/qq", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu") +
            if (account != null) listOf("-q", account) else emptyList())
        val result = RuntimeSession.start(builder(command), home)
        File(context.filesDir, "qq-launch-report.json").writeText(result.toString(2))
        result
    }

    fun probeContacts(): JSONObject = synchronized(probeLock) {
        val report = JSONObject().put("schema", 1).put("timestamp", System.currentTimeMillis())
            .put("loginVerified", false).put("contactsPassed", false).put("qqMessagesSent", 0).put("gatePassed", false)
        try {
            val root = File(home, "rootfs")
            val account = configuredAccount(root) ?: error("Complete QQ login first")
            val state = File(home, "state")
            check(state.mkdirs() || state.isDirectory)
            val linkFile = File(state, "protocol-link.json")
            val link = if (linkFile.isFile) JSONObject(linkFile.readText()) else JSONObject()
            if (link.optString("accessToken").isBlank()) {
                val random = ByteArray(32); SecureRandom().nextBytes(random)
                link.put("accessToken", random.joinToString("") { "%02x".format(it) })
            }
            link.put("userId", account)
            linkFile.writeText(link.toString())
            val configFile = File(root, "opt/napcat/config/onebot11_$account.json")
            val config = JSONObject(configFile.readText())
            val network = config.optJSONObject("network") ?: JSONObject()
            val http = JSONObject().put("name", "qqagent-http").put("enable", true).put("host", "127.0.0.1")
                .put("port", 3000).put("token", link.getString("accessToken")).put("enableCors", false)
                .put("enableWebsocket", false).put("messagePostFormat", "array").put("debug", false)
            val ws = JSONObject().put("name", "qqagent-ws").put("enable", true).put("host", "127.0.0.1")
                .put("port", 3001).put("token", link.getString("accessToken")).put("messagePostFormat", "array")
                .put("reportSelfMessage", false).put("enableForcePushEvent", true).put("debug", false).put("heartInterval", 30000)
            network.put("httpServers", JSONArray().put(http)).put("websocketServers", JSONArray().put(ws))
            config.put("network", network)
            val expected = config.toString(2)
            val changed = configFile.readText() != expected
            if (changed) { configFile.writeText(expected); RuntimeSession.stop() }
            launchQQ()
            context.assets.open("runtime/probe-contacts.mjs").use { source ->
                File(home, "scripts/probe-contacts.mjs").outputStream().use { source.copyTo(it) }
            }
            val nodeReport = File(state, "contacts-report.json")
            if (nodeReport.exists()) check(nodeReport.delete())
            val stage = run("qq-contacts", guest(root, listOf("/opt/node/bin/node", "/qqagent-scripts/probe-contacts.mjs")),
                "QQAGENT_CONTACTS_OK", 145)
            report.put("stage", stage)
            if (nodeReport.isFile) {
                val details = JSONObject(nodeReport.readText())
                report.put("details", details).put("loginVerified", details.optBoolean("loginVerified"))
                    .put("contactsPassed", stage.getBoolean("passed") && details.optBoolean("contactsPassed"))
            }
        } catch (error: Exception) { report.put("errorType", error.javaClass.simpleName) }
        File(context.filesDir, "contacts-report.json").writeText(report.toString(2))
        report
    }

    private fun configuredAccount(root: File): String? {
        val accounts = File(root, "opt/napcat/config").listFiles().orEmpty()
            .mapNotNull { Regex("onebot11_([0-9]{5,12})\\.json").matchEntire(it.name)?.groupValues?.get(1) }
        require(accounts.size <= 1) { "Select a single test account before starting" }
        return accounts.singleOrNull()
    }

    fun probeMessaging(): JSONObject = synchronized(probeLock) {
        val report = JSONObject().put("schema", 1).put("timestamp", System.currentTimeMillis())
            .put("messagingPassed", false).put("agentQqReplyPassed", false).put("gatePassed", false)
        val state = File(home, "state")
        val input = File(state, "messaging-probe-input.json")
        var profileConfig: File? = null
        try {
            val root = File(home, "rootfs")
            val contacts = JSONObject(File(state, "contacts-report.json").readText())
            require(contacts.optBoolean("contactsPassed") && contacts.optBoolean("loginVerified")) { "Verify login and contacts first" }
            val targets = JSONObject(File(context.filesDir, "messaging-test-targets.json").readText())
            require(targets.getBoolean("authorized") && Regex("[a-f0-9-]{36}").matches(targets.getString("runId")))
            for (field in listOf("friend", "group")) require(Regex("[0-9]{5,12}").matches(targets.getString(field)))
            val privateContacts = JSONObject(File(state, "contacts-private.json").readText())
            val friends = privateContacts.getJSONArray("friends")
            val groups = privateContacts.getJSONArray("groups")
            require((0 until friends.length()).any { friends.getJSONObject(it).optString("user_id") == targets.getString("friend") }) { "Test friend is not in the retrieved friend list" }
            require((0 until groups.length()).any { groups.getJSONObject(it).optString("group_id") == targets.getString("group") }) { "Test group is not in the retrieved group list" }
            val runId = targets.getString("runId")
            val previousFile = File(state, "messaging-report-$runId.json")
            if (previousFile.isFile) {
                val previous = JSONObject(previousFile.readText())
                require(previous.getJSONObject("private").optBoolean("modelReplySent") &&
                    previous.getJSONObject("group").optBoolean("incomingReceived") &&
                    !previous.getJSONObject("group").optBoolean("modelReplySent") && previous.optInt("resumeAttempts") < 2) {
                    "This test cannot be safely resumed without duplicate replies"
                }
                targets.put("resume", true)
            }
            File(state, "messaging-targets.json").writeText(targets.toString())
            val modelReport = JSONObject(File(context.filesDir, "model-connectivity-report.json").readText())
            require(modelReport.optBoolean("modelConnectivityPassed"))
            val credentials = TestCredentialStore(context).read().put("model", modelReport.getJSONObject("details").getString("requestedModel"))
            input.writeText(credentials.toString())
            for (name in listOf("probe-messaging.mjs", "model-response-adapter.mjs")) {
                context.assets.open("runtime/$name").use { source ->
                    File(home, "scripts/$name").outputStream().use { source.copyTo(it) }
                }
            }
            profileConfig = File(state, "messaging-probe-data-$runId/config.json")
            val stage = run("qq-agent-messaging", guest(root, listOf("/usr/bin/env",
                "QQ_AGENT_DATA_DIR=/qqagent-state/messaging-probe-data-$runId", "QQ_AGENT_PORT=3311",
                "/opt/node/bin/node", "/qqagent-scripts/probe-messaging.mjs")), "QQAGENT_MESSAGING_OK", 1230)
            report.put("stage", stage)
            val detailsFile = File(state, "messaging-report-$runId.json")
            if (detailsFile.isFile) {
                val details = JSONObject(detailsFile.readText())
                report.put("details", details).put("agentQqReplyPassed", details.optBoolean("agentQqReplyPassed"))
                    .put("messagingPassed", stage.getBoolean("passed") && details.optBoolean("messagingPassed"))
            }
        } catch (error: Exception) { report.put("errorType", error.javaClass.simpleName) }
        finally { input.delete(); profileConfig?.delete() }
        File(context.filesDir, "messaging-report.json").writeText(report.toString(2))
        report
    }

    fun stopMessagingTest() { messagingProcess.get()?.destroy() }

    fun probe(components: Boolean = false, qq: Boolean = false): JSONObject = synchronized(probeLock) { probeLocked(components, qq) }

    private fun probeLocked(components: Boolean, qq: Boolean): JSONObject {
        val stages = JSONArray()
        val report = JSONObject().put("schema", 1).put("appVersion", BuildConfig.VERSION_NAME)
            .put("timestamp", System.currentTimeMillis()).put("runtimeReady", false).put("stages", stages)
        try {
            installNative()
            stages.put(run("proot-version", listOf(File(bin, "proot").path, "--version"), "5.1.107.96"))
            stages.put(run("android-ptrace", listOf(File(bin, "proot").path, "--kill-on-exit", "-r", "/", "-w", home.path,
                "/system/bin/sh", "-c", "printf QQAGENT_ANDROID_PTRACE_OK"), "QQAGENT_ANDROID_PTRACE_OK"))
            val passed = (0 until stages.length()).all { stages.getJSONObject(it).getBoolean("passed") }
            report.put("nativeBootstrapPassed", passed)
            report.put("blockedStage", if (passed) "DEBIAN_AND_QQ_COMPONENTS" else "NATIVE_BOOTSTRAP")
            if (passed && components) {
                val root = RuntimeComponents(context).installDebianAndNode()
                stages.put(run("debian-shell", guest(root, listOf("/bin/sh", "-c",
                    "cat /etc/debian_version && /usr/bin/id && printf QQAGENT_DEBIAN_OK")), "QQAGENT_DEBIAN_OK"))
                stages.put(run("node-version", guest(root, listOf("/opt/node/bin/node", "--version")), "v22.20.0"))
                stages.put(run("node-http-loopback", guest(root, listOf("/opt/node/bin/node", "-e",
                    "const http=require('node:http');const s=http.createServer((q,r)=>r.end('QQAGENT_NODE_HTTP_OK'));s.listen(0,'127.0.0.1',()=>{http.get({host:'127.0.0.1',port:s.address().port},r=>{let b='';r.on('data',x=>b+=x);r.on('end',()=>{console.log(b);s.close();if(b!=='QQAGENT_NODE_HTTP_OK')process.exitCode=1})}).on('error',e=>{console.error(e);s.close();process.exitCode=1})});setTimeout(()=>process.exit(2),15000).unref()")), "QQAGENT_NODE_HTTP_OK"))
                val guestPassed = (2 until stages.length()).all { stages.getJSONObject(it).getBoolean("passed") }
                report.put("debianNodePassed", guestPassed)
                    .put("blockedStage", if (guestPassed) "LINUX_QQ_NAPCAT_LOGIN" else "DEBIAN_NODE")
                save(report)
                if (guestPassed) {
                    RuntimeComponents(context).prepareCore(root)
                    val scripts = File(home, "scripts")
                    check(scripts.mkdirs() || scripts.isDirectory)
                    context.assets.open("runtime/probe-agent.mjs").use { input -> File(scripts, "probe-agent.mjs").outputStream().use { input.copyTo(it) } }
                    val core = run("original-core-http", guest(root, listOf("/usr/bin/env", "QQ_AGENT_DATA_DIR=/qqagent-state/core-probe-data",
                        "QQ_AGENT_PORT=3310", "/opt/node/bin/node", "/qqagent-scripts/probe-agent.mjs")), "QQAGENT_ORIGINAL_CORE_OK")
                    stages.put(core)
                    report.put("originalCoreHttpPassed", core.getBoolean("passed"))
                    save(report)
                }
                if (guestPassed && qq) {
                    RuntimeComponents(context).prepareQQ(root)
                    prepareScriptsAndDns(root)
                    report.put("operation", "QQ_COMPONENT_INITIALIZATION").put("operationRunning", true)
                    save(report)
                    val setup = run("qq-components", guest(root, listOf("/bin/sh", "/qqagent-scripts/setup-qq.sh")), "QQAGENT_QQ_COMPONENTS_OK", 2400)
                    stages.put(setup)
                    report.put("qqComponentsPassed", setup.getBoolean("passed")).put("operationRunning", false)
                        .put("blockedStage", if (setup.getBoolean("passed")) "LINUX_QQ_NAPCAT_LOGIN" else "QQ_COMPONENT_INITIALIZATION")
                }
            }
        } catch (e: Exception) {
            if (!report.optBoolean("nativeBootstrapPassed")) report.put("nativeBootstrapPassed", false).put("blockedStage", "NATIVE_BOOTSTRAP")
            report.put("error", "${e.javaClass.simpleName}: ${e.message}")
        }
        report.put("operationRunning", false)
        save(report)
        return report
    }

    private fun save(report: JSONObject) {
        val staging = File(context.filesDir, "runtime-report.json.partial")
        staging.writeText(report.toString(2))
        check(staging.renameTo(File(context.filesDir, "runtime-report.json")))
    }

    private fun prepareScriptsAndDns(root: File) {
        val scripts = File(home, "scripts")
        check(scripts.mkdirs() || scripts.isDirectory)
        for (name in listOf("setup-qq.sh", "configure-napcat.cjs")) {
            context.assets.open("runtime/$name").use { input -> File(scripts, name).outputStream().use { input.copyTo(it) } }
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val dns = connectivity.getLinkProperties(connectivity.activeNetwork)?.dnsServers.orEmpty()
        require(dns.isNotEmpty()) { "Connect the phone to the internet before installing QQ libraries" }
        // Atomic rename replaces any guest absolute symlink without following it on Android.
        val resolv = File(root, "etc/resolv.conf.qqagent")
        resolv.writeText(dns.joinToString("\n", postfix = "\n") { "nameserver ${it.hostAddress}" })
        check(resolv.renameTo(File(root, "etc/resolv.conf")))
    }

    private fun guest(root: File, command: List<String>): List<String> {
        val shm = File(tmp, "shm")
        check(shm.mkdirs() || shm.isDirectory)
        val state = File(home, "state")
        val scripts = File(home, "scripts")
        check(state.mkdirs() || state.isDirectory)
        check(scripts.mkdirs() || scripts.isDirectory)
        return listOf(File(bin, "proot").path, "--kill-on-exit", "--link2symlink", "-0", "-r", root.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${tmp.path}:/tmp", "-b", "${shm.path}:/dev/shm",
            "-b", "${File(home, "node").path}:/opt/node", "-w", "/root",
            "-b", "${File(context.filesDir, "component-downloads").path}:/qqagent-downloads",
            "-b", "${scripts.path}:/qqagent-scripts", "-b", "${state.path}:/qqagent-state",
            "/usr/bin/env", "-i", "HOME=/root", "LANG=C.UTF-8", "TMPDIR=/tmp",
            "PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin") + command
    }

    private fun installNative() {
        check(home.mkdirs() || home.isDirectory)
        check(bin.mkdirs() || bin.isDirectory)
        check(tmp.mkdirs() || tmp.isDirectory)
        val manifest = JSONArray(context.assets.open("native/native-artifacts.json").bufferedReader().use { it.readText() })
        for (i in 0 until manifest.length()) {
            val pin = manifest.getJSONObject(i)
            val name = pin.getString("file")
            require(name in setOf("proot", "loader64", "loader32"))
            val file = File(bin, name)
            if (!file.isFile || digest(file) != pin.getString("sha256")) {
                val stage = File.createTempFile("native-", ".partial", bin)
                try {
                    context.assets.open("native/$name").use { input -> stage.outputStream().use { input.copyTo(it) } }
                    require(stage.length() == pin.getLong("size") && digest(stage) == pin.getString("sha256")) { "Native hash mismatch: $name" }
                    check(stage.setExecutable(true, true))
                    check(stage.renameTo(file))
                } finally { stage.delete() }
            }
            check(file.setExecutable(true, true))
        }
    }

    private fun run(stage: String, args: List<String>, required: String, timeoutSeconds: Long = 90): JSONObject {
        val builder = builder(args).redirectErrorStream(true)
        val start = System.nanoTime()
        val process = builder.start()
        if (stage == "qq-agent-messaging") messagingProcess.set(process)
        if (stage == "android-media") mediaProcess.set(process)
        val reader = Executors.newSingleThreadExecutor()
        val readError = AtomicReference<String?>(null)
        try {
            val output = reader.submit<String> {
                val text = StringBuilder()
                val log = File(home, "$stage.log").bufferedWriter()
                try { process.inputStream.bufferedReader().use { input ->
                    val chars = CharArray(4096)
                    while (true) {
                        val n = input.read(chars)
                        if (n < 0) break
                        log.write(chars, 0, n); log.flush()
                        if (text.length < 1024 * 1024) text.append(chars, 0, minOf(n, 1024 * 1024 - text.length))
                    }
                } } catch (e: IOException) { readError.set(e.message) } finally { log.close() }
                text.toString()
            }
            val done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!done) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            val text = output.get(10, TimeUnit.SECONDS)
            val exit = if (done) process.exitValue() else -1
            return JSONObject().put("stage", stage).put("exitCode", exit).put("timedOut", !done)
                .put("durationMs", (System.nanoTime() - start) / 1_000_000).put("output", text)
                .put("readError", readError.get() ?: JSONObject.NULL)
                .put("passed", done && exit == 0 && readError.get() == null && text.contains(required))
        } finally {
            if (process.isAlive) process.destroyForcibly()
            reader.shutdownNow()
            if (stage == "qq-agent-messaging") messagingProcess.compareAndSet(process, null)
            if (stage == "android-media") mediaProcess.compareAndSet(process, null)
        }
    }

    private fun builder(args: List<String>): ProcessBuilder {
        val builder = ProcessBuilder(args).directory(home)
        builder.environment().apply {
            put("HOME", home.path); put("TMPDIR", tmp.path); put("PROOT_TMP_DIR", tmp.path)
            put("PROOT_LOADER", File(bin, "loader64").path)
            put("PROOT_LOADER_32", File(bin, "loader32").path)
        }
        return builder
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val probeLock = Any()
        private val messagingProcess = AtomicReference<Process?>(null)
        private val mediaProcess = AtomicReference<Process?>(null)
        fun stopMediaSetup() { mediaProcess.getAndSet(null)?.destroy() }
    }
}
