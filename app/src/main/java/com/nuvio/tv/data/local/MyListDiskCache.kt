package com.nuvio.tv.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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

    private fun cacheFile(profileId: Int): File {
        val dir = File(context.filesDir, "my_list_cache")
        dir.mkdirs()
        return File(dir, "my_list_profile_$profileId.json")
    }

    suspend fun load(profileId: Int): List<CachedMyListItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val file = cacheFile(profileId)
                if (!file.exists()) return@withLock emptyList()
                val type = object : TypeToken<List<CachedMyListItem>>() {}.type
                gson.fromJson<List<CachedMyListItem>>(file.readText(), type) ?: emptyList()
            }.getOrDefault(emptyList())
        }
    }

    suspend fun save(profileId: Int, items: List<CachedMyListItem>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val file = cacheFile(profileId)
                val tmp = File(file.parent, file.name + ".tmp")
                tmp.writeText(gson.toJson(items))
                tmp.renameTo(file)
            }
        }
    }

    suspend fun clear(profileId: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { cacheFile(profileId).delete() }
        }
    }
}
