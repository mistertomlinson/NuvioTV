package com.nuvio.tv.ui.screens.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class XlEntryGeometryTest {
    @Test fun landscapeAboveXlReducesOnlyTheInitialHeadroom() {
        val scene = XlSceneState()
        scene.screenHeight = 540.dp
        scene.estimatedXlHeight = 440.dp
        scene.landscapeAboveXlHeadroomReduction = 72.dp
        val portrait = HeroCarouselRow("portrait", "Portrait", 0, emptyList())
        val landscape = portrait.copy(landscapePosters = true)
        val xl = "next-xl"
        val nominal = (scene.xlTop(xl) - 23.dp).coerceAtLeast(0.dp)
        assertEquals(nominal, scene.xlEntryHeadroom(xl, portrait))
        assertEquals((nominal - 72.dp).coerceAtLeast(0.dp), scene.xlEntryHeadroom(xl, landscape))
    }

    @Test fun compensateShrinkingOutgoingRowFromTheActualXlOwnerOffset() {
        val initialTop = 670f
        val springTravel = 220f
        assertEquals(0f, xlEntryScrollCorrectionPx(450f, initialTop, springTravel), 0f)
        assertEquals(-92f, xlEntryScrollCorrectionPx(358f, initialTop, springTravel), 0f)
    }
}
