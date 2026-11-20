package com.nuvio.tv.ui.screens.home

import org.junit.Assert.*
import org.junit.Test

class ModernXlRowPolicyTest {
    @Test fun `featured and portrait images retain matching heights and aspect ratios`() {
        for ((w,h) in listOf(960f to 540f,1280f to 720f,1920f to 1080f)) for (large in listOf(false,true)) {
            val g=xlRowGeometry(w,h,large)
            assertEquals(16f/9f,g.featuredWidth/g.cardHeight,0.001f)
            assertEquals(2f/3f,g.portraitWidth/g.cardHeight,0.001f)
            assertTrue(g.featuredWidth < w-52f)
        }
    }
    @Test fun `ordinary rows keep their anchor while XL clears platform controls`() {
        assertEquals(259.2f,xlRowTopInset(false,259.2f),0f)
        assertEquals(54f,xlRowTopInset(true,259.2f),0f)
    }
    @Test fun `unnumbered portrait runway retains its original twelve dp gaps`() {
        val t=XlRankTrack(160f,FloatArray(10))
        assertEquals(52f,t.featuredLeft,0f)
        assertEquals(52f+430f+12f,t.portraitLeft(4,3,430f),0.001f)
        assertEquals(172f,t.portraitLeft(5,3,430f)-t.portraitLeft(4,3,430f),0.001f)
    }
    @Test fun `each number has room before its own poster with an eight dp overlap`() {
        val widths=floatArrayOf(24f,31f,30f,32f,32f,33f,31f,34f,32f,60f)
        val t=XlRankTrack(160f,widths)
        assertEquals(52f,t.featuredLeft,0.001f)
        for (i in 1..9) {
            val x=t.portraitLeft(i,0,430f)
            val priorRight=if (i==1) t.featuredLeft+430f else t.portraitLeft(i-1,0,430f)+160f
            assertEquals(12f,t.numberLeft(i,x)-priorRight,0.001f)
            assertEquals(x+XL_RANK_OVERLAP_DP,t.numberLeft(i,x)+widths[i],0.001f)
        }
    }
    @Test fun `featured slot does not shift when rank changes from one to two digits`() {
        val t=XlRankTrack(160f,floatArrayOf(24f,28f,30f,30f,30f,30f,30f,30f,30f,60f))
        assertEquals(52f,t.featuredLeft,0f)
        assertTrue(t.numberLeft(9,t.featuredLeft)>=0f)
    }
    @Test fun `XL ranks are smaller than ordinary portrait ranks and remain bounded`() {
        for(h in listOf(180f,244f,320f,500f)) {
            assertTrue(xlRankFontSize(h)<h*0.55f)
            assertTrue(xlRankFontSize(h) in 40f..56f)
        }
    }
    @Test fun `held horizontal events are throttled without generic focus traversal`() {
        val gate=XlHorizontalRepeatGate()
        assertTrue(gate.accept(0,100L,false))
        assertFalse(gate.accept(1,130L,false))
        assertTrue(gate.accept(2,190L,false))
        assertTrue(gate.accept(0,200L,false))
        assertFalse(gate.accept(1,230L,false))
        assertTrue(gate.accept(2,250L,true))
    }
    @Test fun `balanced full width placement leaves equal margins and preserves compact anchor`() {
        for ((screen, row) in listOf(540f to 430f, 720f to 530f, 1080f to 740f)) {
            val top = xlBalancedTop(screen, row, true)
            assertEquals(top - HOME_PLATFORM_SELECTOR_BOTTOM_DP, screen - top - row, 0.001f)
            assertEquals(54f, xlBalancedTop(screen, row, false), 0f)
        }
    }

    @Test fun `following hero starts just off screen while the XL tail can clear completely`() {
        val gap = xlFollowingHeadroom(540f, 470f, 260f, 30f, 6f)
        val heroTop = 470f + 24f + gap - 260f + 30f + 6f
        assertEquals(548f, heroTop, 0.001f)
        assertTrue(260f - 24f - gap < 0f)
    }

    @Test fun `a centered ordinary hero starts just off screen and only gains clearance during exit`() {
        val previewGap = xlFollowingHeadroom(540f, 505f, 260f, 96f, -2f, clearXl = false)
        assertEquals(548f, 505f + 24f + previewGap - 260f + 96f - 2f, 0.001f)
        val exitGap = xlFollowingHeadroom(540f, 505f, 260f, 96f, -2f, clearXl = true)
        assertTrue(exitGap > previewGap)
        assertTrue(260f - 24f - exitGap < 0f)
    }

    @Test fun `long jump runway only needs a visible window at each scroll position`() {
        val track = XlRankTrack(160f, FloatArray(10_000) { 40f })
        for (fraction in listOf(1f, 0.75f, 0.5f, 0.25f, 0f)) {
            val origin = track.origin(9999) * fraction + track.origin(0) * (1f - fraction)
            val first = track.firstPortrait(origin)
            val x = track.portraitLeftAtOrigin(first, origin, 430f)
            assertTrue(x in 200f..500f)
            assertTrue(first in 0..9999)
        }
    }

    @Test fun `short headings cannot strand the XL tail above hidden metadata`() {
        for (header in listOf(28f, 32f, 40f, 46f)) {
            val correction = header - 40f
            val gap = xlFollowingHeadroom(540f, 495f, 259f, 106f, correction)
            assertTrue(259f - correction - 24f - gap <= 0f)
        }
    }

}
