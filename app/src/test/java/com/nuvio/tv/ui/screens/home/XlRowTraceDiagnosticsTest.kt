package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class XlRowTraceDiagnosticsTest {
    @Test fun onlyCompactXlAndTwoFollowingRegularRowsAreInstrumented() {
        assertEquals("XL", compactXlDiagnosticRole(true, true, false, false, 0))
        assertEquals("R1", compactXlDiagnosticRole(true, false, true, true, 1))
        assertEquals("R2", compactXlDiagnosticRole(true, false, true, true, 2))
        assertEquals(null, compactXlDiagnosticRole(true, false, true, true, 3))
        assertEquals(null, compactXlDiagnosticRole(true, true, true, false, 0))
        assertEquals(null, compactXlDiagnosticRole(true, false, true, false, 2))
        assertEquals(null, compactXlDiagnosticRole(false, true, false, false, 0))
        assertEquals(null, compactXlDiagnosticRole(false, false, false, true, 2))
    }
}
