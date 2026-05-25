package com.nuvio.tv.data.remote.supabase

import com.nuvio.tv.BuildConfig
import io.github.jan.supabase.postgrest.Postgrest
import javax.inject.Inject
import javax.inject.Singleton

data class AvatarCatalogItem(
    val id: String,
    val displayName: String,
    val imageUrl: String,
    val category: String,
    val sortOrder: Int,
    val bgColor: String? = null
)

@Singleton
class AvatarRepository @Inject constructor(
    private val postgrest: Postgrest
) {
    private var cachedCatalog: List<AvatarCatalogItem>? = null

    suspend fun getAvatarCatalog(): List<AvatarCatalogItem> {
        cachedCatalog?.let { return it }

        val response = postgrest.rpc("get_avatar_catalog")
        val remote = response.decodeList<SupabaseAvatarCatalogItem>()
        val catalog = remote.map { item ->
            AvatarCatalogItem(
                id = item.id,
                displayName = item.displayName,
                imageUrl = avatarImageUrl(item.storagePath),
                category = item.category,
                sortOrder = item.sortOrder,
                bgColor = item.bgColor
            )
        }
        val localAvatars = listOf(
            AvatarCatalogItem(id = "local_angela", displayName = "Angela", imageUrl = "res://avatar_angela", category = "personal", sortOrder = 0),
            AvatarCatalogItem(id = "local_brandon", displayName = "Brandon", imageUrl = "res://avatar_brandon", category = "personal", sortOrder = 1)
        )
        val finalCatalog = localAvatars + catalog
        cachedCatalog = finalCatalog
        return finalCatalog
    }

    fun getAvatarImageUrl(avatarId: String, catalog: List<AvatarCatalogItem>): String? {
        return catalog.find { it.id == avatarId }?.imageUrl
    }

    fun invalidateCache() {
        cachedCatalog = null
    }

    companion object {
        fun avatarImageUrl(storagePath: String): String {
            val baseUrl = BuildConfig.AVATAR_PUBLIC_BASE_URL.trimEnd('/')
            return if (baseUrl.isNotEmpty()) "$baseUrl/$storagePath" else storagePath
        }
    }
}
