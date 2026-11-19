package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.CatalogPosterMode
import com.nuvio.tv.domain.model.CatalogXlMode
import com.nuvio.tv.domain.model.usesLandscape
import com.nuvio.tv.ui.catalog.SEASONAL_SPOTLIGHT_ORDER_ANCHOR
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CatalogPosterPreferencesTest {
    private class MemoryStore : DataStore<Preferences> {
        val state = MutableStateFlow(emptyPreferences())
        var writes = 0
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it; writes++ }
        }
    }
    private class Fixture {
        val profile = MutableStateFlow(1)
        val first = MemoryStore()
        val second = MemoryStore()
        val manager = mockk<ProfileManager>()
        val factory = mockk<ProfileDataStoreFactory>()
        val store: LayoutPreferenceDataStore
        init {
            every { manager.activeProfileId } returns profile
            every { factory.get(1, "layout_settings") } returns first
            every { factory.get(2, "layout_settings") } returns second
            store = LayoutPreferenceDataStore(factory, manager, mockk<Context>(relaxed = true))
        }
    }

    @Test fun `one control cycles all four choices without leaving conflicting overrides`() = runTest {
        val f = Fixture()
        val observed = mutableListOf<CatalogPosterMode>()
        repeat(4) {
            f.store.cycleCatalogPosterMode("row")
            observed += checkNotNull(f.store.catalogPosterModes.first()["row"])
        }
        assertEquals(listOf(CatalogPosterMode.LANDSCAPE, CatalogPosterMode.XL_WITH_SUMMARY,
            CatalogPosterMode.XL_WITHOUT_SUMMARY, CatalogPosterMode.PORTRAIT), observed)
        assertEquals(4, f.first.writes)
        assertTrue(f.store.landscapeHomeCatalogKeys.first().isEmpty())
        assertTrue(f.store.xlHomeCatalogModes.first().isEmpty())
    }

    @Test fun `legacy XL wins an old landscape conflict and the next cycle removes both`() = runTest {
        val f = Fixture()
        f.store.setLandscapeHomeCatalogKeys(listOf("row", "other"))
        f.store.setXlHomeCatalogMode("row", CatalogXlMode.WITHOUT_SUMMARY)
        assertEquals(CatalogPosterMode.XL_WITHOUT_SUMMARY, f.store.catalogPosterModes.first()["row"])
        f.store.cycleCatalogPosterMode("row")
        assertEquals(CatalogPosterMode.PORTRAIT, f.store.catalogPosterModes.first()["row"])
        assertEquals(listOf("other"), f.store.landscapeHomeCatalogKeys.first())
        assertTrue(f.store.xlHomeCatalogKeys.first().isEmpty())
    }

    @Test fun `explicit portrait overrides global landscape without changing other rows or the default`() = runTest {
        val f = Fixture()
        f.store.setModernLandscapePostersEnabled(true)
        f.store.setCatalogPosterMode("row", CatalogPosterMode.PORTRAIT)
        val modes = f.store.catalogPosterModes.first()
        assertFalse(modes["row"].usesLandscape(true))
        assertTrue(modes["other"].usesLandscape(true))
        assertTrue(f.store.modernLandscapePostersEnabled.first())
    }

    @Test fun `unconfigured global landscape starts at landscape and cycles directly into XL`() = runTest {
        val f = Fixture()
        f.store.cycleCatalogPosterMode("row", CatalogPosterMode.LANDSCAPE)
        assertEquals(CatalogPosterMode.XL_WITH_SUMMARY, f.store.catalogPosterModes.first()["row"])
        assertTrue(f.store.landscapeHomeCatalogKeys.first().isEmpty())
    }

    @Test fun `rapid queued presses cycle the persisted value rather than a stale UI snapshot`() = runTest {
        val f = Fixture()
        val jobs = List(12) { launch { f.store.cycleCatalogPosterMode("row") } }
        jobs.forEach { it.join() }
        assertEquals(12, f.first.writes)
        assertEquals(CatalogPosterMode.PORTRAIT, f.store.catalogPosterModes.first()["row"])
    }

    @Test fun `dynamic group migration replaces all old member overrides with one stable choice`() = runTest {
        val f = Fixture()
        val a = "community.seasonalspotlight_movie_event_a"
        val b = "community.seasonalspotlight_series_event_b"
        f.store.setLandscapeHomeCatalogKeys(listOf(a, b, "unrelated"))
        assertEquals(CatalogPosterMode.LANDSCAPE, f.store.catalogPosterModes.first()[SEASONAL_SPOTLIGHT_ORDER_ANCHOR])
        f.store.setCatalogPosterMode(a, CatalogPosterMode.XL_WITH_SUMMARY)
        assertEquals(listOf("unrelated"), f.store.landscapeHomeCatalogKeys.first())
        assertEquals(listOf(SEASONAL_SPOTLIGHT_ORDER_ANCHOR), f.store.xlHomeCatalogKeys.first())
        assertEquals(CatalogPosterMode.XL_WITH_SUMMARY, f.store.catalogPosterModes.first()[SEASONAL_SPOTLIGHT_ORDER_ANCHOR])
    }

    @Test fun `poster modes remain independent between profiles and preserve numbering`() = runTest {
        val f = Fixture()
        f.store.setNumberedHomeCatalogKeys(listOf("row"))
        f.store.setCatalogPosterMode("row", CatalogPosterMode.PORTRAIT)
        f.profile.value = 2
        assertTrue(f.store.catalogPosterModes.first().isEmpty())
        f.store.setCatalogPosterMode("row", CatalogPosterMode.XL_WITHOUT_SUMMARY)
        f.profile.value = 1
        assertEquals(CatalogPosterMode.PORTRAIT, f.store.catalogPosterModes.first()["row"])
        assertEquals(listOf("row"), f.store.numberedHomeCatalogKeys.first())
    }
}
