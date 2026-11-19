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
    @Test fun `ordinary to ordinary metadata stays stationary when XL exists elsewhere`() {
        assertFalse(xlUpperMetadataFollowsRow(false,false,true,false))
        assertFalse(xlUpperMetadataFollowsRow(false,false,false,false))
    }
    @Test fun `entering XL and leaving a summary XL retain the synchronized handoff`() {
        assertTrue(xlUpperMetadataFollowsRow(true,false,false,false))
        assertTrue(xlUpperMetadataFollowsRow(false,true,true,false))
        assertTrue(xlUpperMetadataFollowsRow(false,false,true,true))
    }
    @Test fun `compact XL exit returns to ordinary viewport even if an older motion is pending`() {
        assertFalse(xlUpperMetadataFollowsRow(false,true,false,true))
    }
    @Test fun `only a summary XL predecessor reserves ordinary metadata headroom`() {
        assertTrue(xlNeedsMetadataHeadroom(true,true,false))
        assertFalse(xlNeedsMetadataHeadroom(true,false,false))
        assertFalse(xlNeedsMetadataHeadroom(true,true,true))
        assertFalse(xlNeedsMetadataHeadroom(false,true,false))
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
        assertEquals(104f,t.featuredLeft,0.001f)
        for (i in 1..9) {
            val x=t.portraitLeft(i,0,430f)
            val priorRight=if (i==1) t.featuredLeft+430f else t.portraitLeft(i-1,0,430f)+160f
            assertEquals(12f,t.numberLeft(i,x)-priorRight,0.001f)
            assertEquals(x+XL_RANK_OVERLAP_DP,t.numberLeft(i,x)+widths[i],0.001f)
        }
    }
    @Test fun `featured slot does not shift when rank changes from one to two digits`() {
        val t=XlRankTrack(160f,floatArrayOf(24f,28f,30f,30f,30f,30f,30f,30f,30f,60f))
        assertEquals(104f,t.featuredLeft,0f)
        assertTrue(t.numberLeft(9,t.featuredLeft)>=52f)
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
}
