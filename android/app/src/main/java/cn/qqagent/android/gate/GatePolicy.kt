package cn.qqagent.android.gate

/** Missing artifacts and skipped device checks must never be converted to success. */
enum class Stage { DEVICE, COMPONENTS, INITIALIZE, LOGIN, CONTACTS, MESSAGING, AGENT_REPLY }
enum class Outcome { PASSED, FAILED, BLOCKED, NOT_RUN }

data class GateResult(val stage: Stage, val outcome: Outcome, val detail: String)
data class DeviceFacts(
    val sdk: Int,
    val abis: List<String>,
    val manufacturer: String,
    val model: String,
    val build: String,
    val pageSize: Long,
    val availableBytes: Long,
)

data class ComponentPin(
    val id: String,
    val version: String,
    val url: String,
    val sha256: String,
    val license: String,
    val licenseEvidence: String,
    val maxDownloadBytes: Long,
    val installedBytes: Long,
) {
    fun validate() {
        require(id.matches(Regex("[a-z][a-z0-9-]{0,40}"))) { "非法组件 ID" }
        require(version.isNotBlank() && version != "latest") { "$id 未固定版本" }
        val uri = java.net.URI(url)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null) {
            "$id 必须使用无凭据的 HTTPS 来源"
        }
        require(sha256.matches(Regex("[a-fA-F0-9]{64}")) && sha256.any { it != '0' }) { "$id 缺少有效 SHA-256" }
        require(license.isNotBlank() && licenseEvidence.isNotBlank()) { "$id 缺少许可证据" }
        require(maxDownloadBytes > 0 && installedBytes > 0) { "$id 缺少空间上限" }
    }
}

object GatePolicy {
    val requiredComponents = setOf("proot", "debian", "node", "linux-qq", "napcat")
    const val SPACE_RESERVE_BYTES = 512L * 1024 * 1024

    fun preflight(device: DeviceFacts, pins: List<ComponentPin>, bundledProot: Boolean): List<GateResult> {
        val results = mutableListOf<GateResult>()
        val supported = device.sdk >= 31 && "arm64-v8a" in device.abis
        results += GateResult(Stage.DEVICE, if (supported) Outcome.PASSED else Outcome.FAILED,
            if (supported) "Android 12+ / ARM64；页大小 ${device.pageSize}（兼容性仍需真机验证）" else "需要 Android 12+ 和 ARM64")
        if (!supported) return stop(results)
        val problem = runCatching {
            require(pins.map { it.id }.toSet() == requiredComponents && pins.size == requiredComponents.size) {
                "组件锁不完整，需要 PRoot、Debian、Node、Linux QQ、NapCat 的固定版本、来源、SHA-256 和许可证据"
            }
            pins.forEach { it.validate() }
            require(bundledProot) { "APK 未内置独立包名适用的 ARM64 PRoot；不使用外部 Termux" }
            // Old+new complete installations, download staging and a safety reserve.
            val required = pins.fold(SPACE_RESERVE_BYTES) { total, pin ->
                Math.addExact(total, Math.addExact(pin.maxDownloadBytes, Math.multiplyExact(pin.installedBytes, 2)))
            }
            require(device.availableBytes >= required) { "空间不足：需要 $required，可用 ${device.availableBytes} 字节" }
        }.exceptionOrNull()
        results += GateResult(Stage.COMPONENTS, if (problem == null) Outcome.PASSED else Outcome.BLOCKED,
            problem?.message ?: "组件锁和空间检查通过；这不代表运行成功")
        return stop(results)
    }

    private fun stop(results: MutableList<GateResult>): List<GateResult> {
        Stage.entries.filter { stage -> results.none { it.stage == stage } }.forEach {
            results += GateResult(it, Outcome.NOT_RUN, "未执行；第一关未通过，禁止推进后续迁移")
        }
        return results
    }

    fun passed(results: List<GateResult>): Boolean =
        Stage.entries.all { stage -> results.count { it.stage == stage && it.outcome == Outcome.PASSED } == 1 } &&
            results.size == Stage.entries.size
}

