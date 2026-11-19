package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.CatalogXlMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CatalogXlPreferencesTest {
    private class MemoryStore:DataStore<Preferences> {
        val state=MutableStateFlow(emptyPreferences())
        var writes=0
        override val data:Flow<Preferences> = state
        override suspend fun updateData(transform:suspend(Preferences)->Preferences):Preferences =
            transform(state.value).also { state.value=it; writes++ }
    }
    private class Fixture {
        val profile=MutableStateFlow(1)
        val first=MemoryStore()
        val second=MemoryStore()
        val factory=mockk<ProfileDataStoreFactory>()
        val manager=mockk<ProfileManager>()
        val store:LayoutPreferenceDataStore
        init {
            every { manager.activeProfileId } returns profile
            every { factory.get(1,"layout_settings") } returns first
            every { factory.get(2,"layout_settings") } returns second
            store=LayoutPreferenceDataStore(factory,manager,mockk<Context>(relaxed=true))
        }
    }
    @Test fun `existing XL preference defaults to summary mode`()=runTest {
        val f=Fixture()
        f.store.setXlHomeCatalogKeys(listOf("row"))
        assertEquals(CatalogXlMode.WITH_SUMMARY,f.store.xlHomeCatalogModes.first()["row"])
    }
    @Test fun `mode changes update enabled and summary preferences atomically`()=runTest {
        val f=Fixture()
        f.store.setXlHomeCatalogMode("row",CatalogXlMode.WITH_SUMMARY)
        assertEquals(1,f.first.writes)
        f.store.setXlHomeCatalogMode("row",CatalogXlMode.WITHOUT_SUMMARY)
        assertEquals(2,f.first.writes)
        assertEquals(listOf("row"),f.store.xlHomeCatalogKeys.first())
        assertEquals(CatalogXlMode.WITHOUT_SUMMARY,f.store.xlHomeCatalogModes.first()["row"])
        f.store.setXlHomeCatalogMode("row",CatalogXlMode.OFF)
        assertEquals(3,f.first.writes)
        assertTrue(f.store.xlHomeCatalogModes.first().isEmpty())
        assertTrue(f.store.xlHomeCatalogKeys.first().isEmpty())
    }
    @Test fun `summary mode and numbering remain scoped to their profile`()=runTest {
        val f=Fixture()
        f.store.setNumberedHomeCatalogKeys(listOf("row"))
        f.store.setXlHomeCatalogMode("row",CatalogXlMode.WITH_SUMMARY)
        f.profile.value=2
        assertTrue(f.store.xlHomeCatalogModes.first().isEmpty())
        f.store.setXlHomeCatalogMode("row",CatalogXlMode.WITHOUT_SUMMARY)
        assertTrue(f.store.numberedHomeCatalogKeys.first().isEmpty())
        f.profile.value=1
        assertEquals(CatalogXlMode.WITH_SUMMARY,f.store.xlHomeCatalogModes.first()["row"])
        assertEquals(listOf("row"),f.store.numberedHomeCatalogKeys.first())
    }
}
