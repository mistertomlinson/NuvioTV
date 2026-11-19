package com.nuvio.tv.ui.screens.home

import org.junit.Assert.*
import org.junit.Test

class ModernXlRowPolicyTest {
    @Test fun `featured card and portrait neighbors always have the same height and correct aspect ratios`() {
        for ((width, height) in listOf(960f to 540f, 1280f to 720f, 1920f to 1080f)) {
            for (large in listOf(false, true)) {
                val geometry = xlRowGeometry(width, height, large)
                assertEquals(16f / 9f, geometry.featuredWidth / geometry.cardHeight, 0.001f)
                assertEquals(2f / 3f, geometry.portraitWidth / geometry.cardHeight, 0.001f)
                assertTrue(geometry.cardHeight > 0f)
                assertTrue(geometry.featuredWidth < width - 52f)
                assertTrue(geometry.visiblePortraitCount in 1..8)
            }
        }
    }

    @Test fun `large metadata retains room for both metadata lines and full synopsis on 540p TV canvas`() {
        val geometry = xlRowGeometry(960f, 540f, true)
        assertTrue(54f + 40f + geometry.cardHeight + 178f + 24f <= 540f)
    }

    @Test fun `focused slot stays fixed through every right and left step`() {
        val geometry = xlRowGeometry(960f, 540f, true)
        for (selected in 0..100) {
            assertEquals(0f, xlCardOffset(selected, selected, geometry.featuredWidth, geometry.portraitWidth), 0f)
            assertEquals(geometry.featuredWidth + 12f,
                xlCardOffset(selected + 1, selected, geometry.featuredWidth, geometry.portraitWidth), 0.001f)
        }
    }

    @Test fun `upcoming portraits advance exactly one portrait pitch when pressing right`() {
        val geometry = xlRowGeometry(960f, 540f, false)
        val before = xlCardOffset(4, 1, geometry.featuredWidth, geometry.portraitWidth)
        val after = xlCardOffset(4, 2, geometry.featuredWidth, geometry.portraitWidth)
        assertEquals(geometry.portraitWidth + 12f, before - after, 0.001f)
    }

    @Test fun `first row includes no negative indices and composes one clipped safety portrait`() {
        assertEquals(0..4, xlVisibleIndices(0, 200, 4))
    }

    @Test fun `middle window retains a predecessor for left focus without composing whole catalog`() {
        assertEquals(99..104, xlVisibleIndices(100, 200, 4))
    }

    @Test fun `last title remains the featured slot without invalid neighbors`() {
        assertEquals(198..199, xlVisibleIndices(199, 200, 4))
    }

    @Test fun `single title and empty catalog do not create phantom focus targets`() {
        assertEquals(0..0, xlVisibleIndices(0, 1, 4))
        assertTrue(xlVisibleIndices(0, 0, 4).isEmpty())
    }

    @Test fun `ordinary rows retain their original vertical anchor and XL rows clear platform icons`() {
        assertEquals(259.2f, xlRowTopInset(false, 259.2f), 0f)
        assertEquals(54f, xlRowTopInset(true, 259.2f), 0f)
    }
}
