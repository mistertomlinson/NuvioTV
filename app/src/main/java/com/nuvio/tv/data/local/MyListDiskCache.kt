package com.nuvio.tv.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.domain.model.LibrarySourceMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class CachedMyListItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val imdbRating: Float?,
    val genres: List<String>,
    val status: String? = null,
    val ageRating: String? = null,
    val runtime: String? = null,
    val country: String? = null,
    val language: String? = null
)

@Singleton
class MyListDiskCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val mutex = Mutex()

    private fun cacheDirectory(): File =
        File(context.filesDir, "my_list_cache").also(File::mkdirs)

    private fun cacheFile(
        profileId: Int,
        sourceMode: LibrarySourceMode
    ): File = File(
        cacheDirectory(),
        "my_list_profile_${profileId}_${sourceMode.name.lowercase()}.json"
    )

    private fun legacyTraktCacheFile(profileId: Int): File =
        File(cacheDirectory(), "my_list_profile_$profileId.json")

    suspend fun load(
        profileId: Int,
        sourceMode: LibrarySourceMode
    ): List<CachedMyListItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val scopedFile = cacheFile(profileId, sourceMode)
                val sourceFile = when {
                    scopedFile.exists() -> scopedFile
                    sourceMode == LibrarySourceMode.TRAKT &&
                        legacyTraktCacheFile(profileId).exists() -> {
                        legacyTraktCacheFile(profileId)
                    }
                    else -> return@withLock emptyList()
                }

                val payload = sourceFile.readText()
                if (sourceFile != scopedFile) {
                    runCatching { scopedFile.writeText(payload) }
                }

                val type =
                    object : TypeToken<List<CachedMyListItem>>() {}.type
                gson.fromJson<List<CachedMyListItem>>(payload, type)
                    ?: emptyList()
            }.getOrDefault(emptyList())
        }
    }

    suspend fun save(
        profileId: Int,
        sourceMode: LibrarySourceMode,
        items: List<CachedMyListItem>
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val file = cacheFile(profileId, sourceMode)
                val tmp = File(file.parent, file.name + ".tmp")
                tmp.writeText(gson.toJson(items))
                tmp.renameTo(file)
            }
        }
    }

    suspend fun clear(
        profileId: Int,
        sourceMode: LibrarySourceMode
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { cacheFile(profileId, sourceMode).delete() }
        }
    }
}
