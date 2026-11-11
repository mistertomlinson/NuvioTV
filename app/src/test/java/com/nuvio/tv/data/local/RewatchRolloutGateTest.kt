package com.nuvio.tv.data.local

import org.junit.Assert.assertFalse
import org.junit.Test

class RewatchRolloutGateTest {
    @Test fun crossDeviceReplayMustRemainDisabledUntilFullServerAndIpadValidation() {
        assertFalse(
            "Cross-device rewatch may not be enabled without explicit rollout review",
            RewatchCrossDeviceGate.ENABLED
        )
    }
}
