package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.reminders.ReleaseReminderWorker
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.ReleaseReminderDataStore
import com.nuvio.tv.data.local.ReleaseReminderRecord
import com.nuvio.tv.data.local.ReleaseReminderStatus
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaBehaviorHints
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReleaseReminderHomeFreezeTest {

    private val addonBaseUrl = "https://release-authority.example"

    private lateinit var context: Context
    private lateinit var dataStore: ReleaseReminderDataStore
    private lateinit var metaRepository: MetaRepository
    private lateinit var tmdbService: TmdbService
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var watchProgressRepository: WatchProgressRepository
    private lateinit var watchedSeriesStateHolder: WatchedSeriesStateHolder
    private lateinit var profileManager: ProfileManager

    private val activeProfileId = MutableStateFlow(1)
    private val watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    private val fullyWatchedSeriesIds = MutableStateFlow<Set<String>>(emptySet())

    private val stored = mutableListOf<ReleaseReminderRecord>()
    private val freshMeta = mutableMapOf<String, Meta>()
    private var libraryWrites = 0

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        dataStore = mockk()
        metaRepository = mockk()
        tmdbService = mockk(relaxed = true)
        libraryRepository = mockk(relaxed = true)
        watchProgressRepository = mockk()
        watchedSeriesStateHolder = mockk()
        profileManager = mockk()

        activeProfileId.value = 1
        watchedMovieIds.value = emptySet()
        fullyWatchedSeriesIds.value = emptySet()
        stored.clear()
        freshMeta.clear()
        libraryWrites = 0

        every { profileManager.activeProfileId } returns activeProfileId
        coEvery {
            profileManager.withActiveProfileMutation(any(), any())
        } coAnswers {
            val expectedProfile = firstArg<Int>()
            val block = secondArg<suspend () -> Boolean>()
            if (activeProfileId.value == expectedProfile) block() else false
        }

        every { watchProgressRepository.observeWatchedMovieIds() } returns watchedMovieIds
        every {
            watchedSeriesStateHolder.observeFullyWatchedSeriesIds(any())
        } returns fullyWatchedSeriesIds

        every { dataStore.remindersForProfile(any()) } answers {
            flowOf(stored.toList())
        }
        every { dataStore.reminders } answers {
            flowOf(stored.toList())
        }

        coEvery { dataStore.upsert(any(), any()) } coAnswers {
            replace(firstArg())
        }

        coEvery { dataStore.markFulfilled(any(), any(), any()) } coAnswers {
            val key = firstArg<String>()
            val fulfilledAt = secondArg<Long>()
            mutate(key) { record ->
                record.copy(
                    status = ReleaseReminderStatus.FULFILLED,
                    fulfilledAtMillis = fulfilledAt,
                    membershipConfirmed = true
                )
            }
        }

        coEvery { dataStore.markArmed(any(), any()) } coAnswers {
            val key = firstArg<String>()
            mutate(key) { record ->
                record.copy(
                    status = ReleaseReminderStatus.ARMED,
                    fulfilledAtMillis = null,
                    membershipConfirmed = false
                )
            }
        }

        coEvery { dataStore.remove(any<String>(), any()) } coAnswers {
            val key = firstArg<String>()
            stored.removeAll { it.key == key }
        }

        every {
            metaRepository.getMetaFresh(any(), any(), any())
        } answers {
            val id = thirdArg<String>()
            val meta = freshMeta[id]
            if (meta == null) {
                flowOf<NetworkResult<Meta>>(
                    NetworkResult.Error("No authority result")
                )
            } else {
                flowOf<NetworkResult<Meta>>(
                    NetworkResult.Success(meta)
                )
            }
        }

        coEvery { libraryRepository.ensureInDefault(any()) } coAnswers {
            libraryWrites += 1
            true
        }

        mockkObject(ReleaseReminderWorker.Companion)
        every {
            ReleaseReminderWorker.scheduleDue(any(), any())
        } just Runs
        every {
            ReleaseReminderWorker.scheduleDueAfterRunningWorker(any(), any())
        } just Runs

        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun confirmedReleaseWhileHomeVisible_waitsToFulfillUntilHomeExit() = runTest {
        addReleasedReminder("tt9876543")

        val coordinator = coordinator()
        coordinator.setHomeVisible(true)
        coordinator.runDueMaintenance()

        assertEquals(ReleaseReminderStatus.ARMED, stored.single().status)
        assertNotNull(stored.single().lastAuthorityCheckAtMillis)
        assertEquals(0, libraryWrites)

        coordinator.setHomeVisible(false)
        coordinator.runDueMaintenance()

        assertEquals(ReleaseReminderStatus.FULFILLED, stored.single().status)
        assertTrue(stored.single().membershipConfirmed)
        assertEquals(1, libraryWrites)
    }

    @Test
    fun twoReleasedReminders_fulfillInSameOffHomeBatchEvenIfHomeReturnsAfterFirst() = runTest {
        addReleasedReminder("tt1000001")
        addReleasedReminder("tt1000002")

        val coordinator = coordinator()
        coEvery { libraryRepository.ensureInDefault(any()) } coAnswers {
            libraryWrites += 1
            if (libraryWrites == 1) {
                coordinator.setHomeVisible(true)
            }
            true
        }

        coordinator.setHomeVisible(false)
        coordinator.runDueMaintenance()

        assertEquals(2, libraryWrites)
        assertEquals(
            setOf(ReleaseReminderStatus.FULFILLED),
            stored.map { it.status }.toSet()
        )
        assertTrue(stored.all { it.membershipConfirmed })
    }

    @Test
    fun movieAndReturningSeason_fulfillTogetherInOneOffHomeBatch() = runTest {
        addReleasedReminder("tt2000001")
        addReleasedReminder(
            id = "tt2000002",
            type = ContentType.SERIES,
            seasonNumber = 4
        )

        val coordinator = coordinator()
        coordinator.runDueMaintenance()

        assertEquals(2, libraryWrites)
        assertTrue(stored.all { it.status == ReleaseReminderStatus.FULFILLED })
    }

    @Test
    fun failedMembershipCommit_staysArmedAndBecomesPromptlyRetryable() = runTest {
        addReleasedReminder("tt3000001")
        coEvery { libraryRepository.ensureInDefault(any()) } returns false

        val before = System.currentTimeMillis()
        coordinator().runDueMaintenance()

        val record = stored.single()
        assertEquals(ReleaseReminderStatus.ARMED, record.status)
        assertNotNull(record.lastAuthorityCheckAtMillis)
        /* Retry timestamp is deliberately backdated so the next due run is ~1 minute away. */
        assertTrue(record.lastAuthorityCheckAtMillis!! < before)
    }

    @Test
    fun watchedReminderWhileHomeVisible_waitsToRemoveUntilHomeExit() = runTest {
        val contentId = "tt4000001"
        stored += reminder(
            id = contentId,
            releaseDate = today().plusDays(30).toString()
        )
        watchedMovieIds.value = setOf(contentId)

        val coordinator = coordinator()
        coordinator.setHomeVisible(true)
        coordinator.runWatchedReminderMaintenance()

        assertEquals(1, stored.size)
        assertEquals(ReleaseReminderStatus.ARMED, stored.single().status)

        coordinator.setHomeVisible(false)
        coordinator.runDueMaintenance()

        assertNull(stored.firstOrNull())
        assertEquals(0, libraryWrites)
    }

    private fun addReleasedReminder(
        id: String,
        type: ContentType = ContentType.MOVIE,
        seasonNumber: Int? = null
    ) {
        stored += reminder(
            id = id,
            releaseDate = today().minusDays(1).toString(),
            type = type,
            seasonNumber = seasonNumber
        )
        freshMeta[id] = releasedAuthority(
            id = id,
            type = type,
            newSeasonNumber = seasonNumber
        )
    }

    private fun replace(record: ReleaseReminderRecord) {
        val index = stored.indexOfFirst { it.key == record.key }
        if (index >= 0) stored[index] = record else stored += record
    }

    private fun mutate(
        key: String,
        transform: (ReleaseReminderRecord) -> ReleaseReminderRecord
    ) {
        val index = stored.indexOfFirst { it.key == key }
        if (index >= 0) stored[index] = transform(stored[index])
    }

    private fun coordinator(): ReleaseReminderCoordinator =
        ReleaseReminderCoordinator(
            appContext = context,
            dataStore = dataStore,
            metaRepository = metaRepository,
            tmdbService = tmdbService,
            libraryRepository = libraryRepository,
            watchProgressRepository = watchProgressRepository,
            watchedSeriesStateHolder = watchedSeriesStateHolder,
            profileManager = profileManager
        )

    private fun reminder(
        id: String,
        releaseDate: String,
        type: ContentType = ContentType.MOVIE,
        seasonNumber: Int? = null
    ): ReleaseReminderRecord =
        ReleaseReminderRecord(
            itemId = id,
            itemType = type.toApiString(),
            title = "Freeze Test $id",
            imdbId = id,
            addonBaseUrl = addonBaseUrl,
            releaseDate = releaseDate,
            seasonNumber = seasonNumber,
            platformId = "netflix"
        )

    private fun releasedAuthority(
        id: String,
        type: ContentType = ContentType.MOVIE,
        newSeasonNumber: Int? = null
    ): Meta =
        Meta(
            id = id,
            type = type,
            name = "Freeze Test $id",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            videos = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            imdbId = id,
            behaviorHints = MetaBehaviorHints(
                platformId = "netflix",
                newSeasonNumber = newSeasonNumber,
                releaseReminderState = "released"
            )
        )

    private fun today(): LocalDate =
        LocalDate.now(ZoneId.systemDefault())
}
