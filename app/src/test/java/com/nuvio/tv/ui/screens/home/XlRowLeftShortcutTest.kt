package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XlRowLeftShortcutTest {
    @Test fun `two fresh left presses jump to first item`() {
        val gesture = XlRowLeftShortcut()
        assertFalse(gesture.onDown(5, 0, true, 100L))
        gesture.onUp(120L)
        assertTrue(gesture.onDown(4, 0, true, 220L))
    }
    @Test fun `second press from item one is consumed at item zero instead of opening sidebar`() {
        val gesture = XlRowLeftShortcut()
        assertFalse(gesture.onDown(1, 0, true, 100L))
        gesture.onUp(120L)
        assertTrue(gesture.onDown(0, 0, true, 200L))
        gesture.onUp(220L)
        assertFalse(gesture.onDown(0, 0, true, 260L))
    }
    @Test fun `starting at first item does not claim a double Left shortcut`() {
        val gesture = XlRowLeftShortcut()
        assertFalse(gesture.onDown(0, 0, true, 100L))
        gesture.onUp(120L)
        assertFalse(gesture.onDown(0, 0, true, 200L))
    }
    @Test fun `held key repeats do not become a double press`() {
        val gesture = XlRowLeftShortcut()
        assertFalse(gesture.onDown(5, 0, true, 100L))
        assertFalse(gesture.onDown(4, 1, true, 200L))
        assertFalse(gesture.onDown(3, 2, true, 280L))
    }
    @Test fun `disabled shortcut and slow presses preserve ordinary left movement`() {
        val gesture = XlRowLeftShortcut()
        assertFalse(gesture.onDown(5, 0, true, 100L))
        gesture.onUp(120L)
        assertFalse(gesture.onDown(4, 0, false, 220L))
        gesture.onUp(240L)
        assertFalse(gesture.onDown(3, 0, true, 600L))
    }
    @Test fun `right movement or row exit cancels the previous left gesture`() {
        val gesture = XlRowLeftShortcut()
        gesture.onDown(5, 0, true, 100L)
        gesture.onUp(120L)
        gesture.reset()
        assertFalse(gesture.onDown(4, 0, true, 200L))
    }
}
