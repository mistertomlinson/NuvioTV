package com.nuvio.tv.data.local

import org.junit.Assert.assertTrue
import org.junit.Test

/** Intentional exception for the isolated 2026-11-13 iPad/VPS device-test branch. */
class RewatchRolloutGateTest {
    @Test fun androidRewatchVpsSyncIsEnabledOnIsolatedDeviceTestBranch() {
        assertTrue(
            "The isolated Android test APK must attempt authenticated VPS reconciliation",
            RewatchCrossDeviceGate.ENABLED
        )
    }
}
