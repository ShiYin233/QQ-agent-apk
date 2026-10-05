package cn.qqagent.android.gate

import org.junit.Assert.*
import org.junit.Test

class GatePolicyTest {
    private val device = DeviceFacts(31, listOf("arm64-v8a"), "test", "test", "test", 4096, 20_000_000_000)
    private fun pins() = GatePolicy.requiredComponents.map {
        ComponentPin(it, "1.0", "https://example.com/$it", "a".repeat(64), "test-license", "test-fixture", 100, 100)
    }

    @Test fun missingComponentsStopBeforeInitialization() {
        val results = GatePolicy.preflight(device, emptyList(), false)
        assertEquals(Outcome.BLOCKED, results.first { it.stage == Stage.COMPONENTS }.outcome)
        assertTrue(results.filter { it.stage.ordinal >= Stage.INITIALIZE.ordinal }.all { it.outcome == Outcome.NOT_RUN })
        assertFalse(GatePolicy.passed(results))
    }

    @Test fun unsupportedDevicesCannotAdvance() {
        for (unsupported in listOf(device.copy(sdk = 30), device.copy(abis = listOf("x86_64")))) {
            val result = GatePolicy.preflight(unsupported, pins(), true)
            assertEquals(Outcome.FAILED, result.first().outcome)
            assertEquals(Outcome.NOT_RUN, result[1].outcome)
        }
    }

    @Test fun prootMustBeBundledEvenWithCompletePins() {
        assertEquals(Outcome.BLOCKED, GatePolicy.preflight(device, pins(), false)[1].outcome)
    }

    @Test fun pinsRequireVersionsHashesHttpsAndLicenseEvidence() {
        val base = pins().first()
        for (bad in listOf(base.copy(version = "latest"), base.copy(sha256 = ""),
            base.copy(url = "http://example.com/a"), base.copy(url = "https://key@example.com/a"),
            base.copy(licenseEvidence = ""))) {
            assertTrue(runCatching { bad.validate() }.isFailure)
        }
    }

    @Test fun insufficientSpaceAndDuplicateComponentsCannotAdvance() {
        assertEquals(Outcome.BLOCKED, GatePolicy.preflight(device.copy(availableBytes = 1), pins(), true)[1].outcome)
        assertEquals(Outcome.BLOCKED, GatePolicy.preflight(device, pins() + pins().first(), true)[1].outcome)
    }

    @Test fun preflightAndDuplicatePassesAreNotEndToEndSuccess() {
        assertFalse(GatePolicy.passed(GatePolicy.preflight(device, pins(), true)))
        val all = Stage.entries.map { GateResult(it, Outcome.PASSED, "test fixture") }
        assertTrue(GatePolicy.passed(all))
        assertFalse(GatePolicy.passed(all + all.first()))
        assertFalse(GatePolicy.passed(all.dropLast(1)))
    }
}
