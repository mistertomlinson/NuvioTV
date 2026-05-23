package com.nuvio.tv.core.homechannel

import android.content.Intent
import android.net.Uri
import androidx.navigation.NavHostController
import com.nuvio.tv.ui.navigation.Screen

/**
 * Handles deep links from the Android TV home screen channel.
 * Expected URI formats:
 *   nuvio://detail/{contentId}/{contentType}?addonBaseUrl={addonBaseUrl}
 *   nuvio://home
 */
object DeepLinkHandler {

    /**
     * Extracts the profileId query parameter from a home screen channel deep link.
     * Returns null if not present or not a valid integer.
     */
    fun extractProfileId(intent: Intent?): Int? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "nuvio") return null
        return uri.getQueryParameter("profileId")?.toIntOrNull()
    }

    fun handle(intent: Intent?, navController: NavHostController): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "nuvio") return false

        val host = uri.host ?: return false
        val path = uri.path?.trimStart('/') ?: ""
        val fullPath = if (path.isBlank()) host else "$host/$path"

        return when {
            fullPath.startsWith("detail/") -> {
                val parts = fullPath.removePrefix("detail/").split("/")
                if (parts.size < 2) return false
                val contentId = parts[0]
                val contentType = parts[1]
                val addonBaseUrl = uri.getQueryParameter("addonBaseUrl") ?: ""
                val route = Screen.Detail.createRoute(
                    itemId = contentId,
                    itemType = contentType,
                    addonBaseUrl = addonBaseUrl,
                    returnToHomeOnBack = true
                )
                try {
                    navController.navigate(route) {
                        launchSingleTop = true
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            fullPath == "home" -> {
                navController.navigate(Screen.Home.route) {
                    launchSingleTop = true
                    popUpTo(Screen.Home.route) { inclusive = false }
                }
                true
            }
            else -> false
        }
    }
}
