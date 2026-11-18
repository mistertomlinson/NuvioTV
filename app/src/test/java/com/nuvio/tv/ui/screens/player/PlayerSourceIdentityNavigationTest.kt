package com.nuvio.tv.ui.screens.player

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.ui.navigation.Screen
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerSourceIdentityNavigationTest {
    @Test
    fun `source key survives player route encoding and argument decoding once`() {
        val key = "Torrentio\u0000hash\u0000247\u0000FHD TB Instant\u0000Café 100% + & ? / %2F"
        val route = Screen.Player.createRoute(
            streamUrl = "https://cdn.example/current", title = "The Office", sourceStreamKey = key
        )
        // Navigation has already decoded each argument before SavedStateHandle receives it.
        val values = route.substringAfter('?').split('&').associate { pair ->
            pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
        }
        assertEquals(key, PlayerNavigationArgs.from(SavedStateHandle(values)).sourceStreamKey)
    }

    @Test
    fun `old routes without source key remain compatible`() {
        assertNull(PlayerNavigationArgs.from(SavedStateHandle()).sourceStreamKey)
    }
}
