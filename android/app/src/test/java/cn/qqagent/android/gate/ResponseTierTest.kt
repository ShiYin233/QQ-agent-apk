package cn.qqagent.android.gate
import org.junit.Assert.*
import org.junit.Test

class ResponseTierTest {
    @Test fun normalGroupResponseBoundariesMatchOriginalContract() {
        assertEquals(1, ResponseTier.tier(10.0)); assertEquals(2, ResponseTier.tier(10.01))
        assertEquals(2, ResponseTier.tier(20.0)); assertEquals(3, ResponseTier.tier(20.01))
        assertEquals(3, ResponseTier.tier(90.0)); assertEquals(4, ResponseTier.tier(90.01))
    }
    @Test fun screenshotFifteenPercentDoesNotBecomeFullResponse() {
        assertEquals(3, ResponseTier.tier(30.5))
        assertEquals(15.0, ResponseTier.probability(30.5), 0.0001)
        assertEquals(0.0, ResponseTier.probability(5.0), 0.0001)
    }
}
