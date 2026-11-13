package com.nuvio.tv.data.local

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This assertion belongs ONLY to the isolated public-HTTPS device-test
 * branch, where activation is deliberate. The unchanged development
 * branch retains its original assertFalse rollout-protection test.
 */
class RewatchRolloutGateTest {
    @Test fun crossDeviceReplayEnabledOnlyInExplicitDeviceTestBranch() {
        assertTrue(
            "The isolated Android device-test build must exercise live replay sync",
            RewatchCrossDeviceGate.ENABLED
        )
    }
}
