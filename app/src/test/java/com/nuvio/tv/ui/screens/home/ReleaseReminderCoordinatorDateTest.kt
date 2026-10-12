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
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaBehaviorHints
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
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

class ReleaseReminderCoordinatorDateTest {

    private val addonBaseUrl = "https://release-authority.example"
    private val imdbId = "tt9876543"

    private lateinit var context: Context
    private lateinit var dataStore: ReleaseReminderDataStore
    private lateinit var metaRepository: MetaRepository
    private lateinit var tmdbService: TmdbService
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var profileManager: ProfileManager

    private val activeProfileId = MutableStateFlow(1)

    private var stored: ReleaseReminderRecord? = null
    private var freshMeta: Meta? = null
    private var libraryWrites = 0

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        dataStore = mockk()
        metaRepository = mockk()
        tmdbService = mockk(relaxed = true)
        libraryRepository = mockk(relaxed = true)
        profileManager = mockk()

        activeProfileId.value = 1
        stored = null
        freshMeta = null
        libraryWrites = 0

        every {
            profileManager.activeProfileId
        } returns activeProfileId

        every {
            dataStore.reminders
        } answers {
            flowOf(
                stored?.let(::listOf).orEmpty()
            )
        }

        coEvery {
            dataStore.upsert(any())
        } coAnswers {
            stored = firstArg()
        }

        coEvery {
            dataStore.markFulfilled(any(), any())
        } coAnswers {
            val key = firstArg<String>()
            val at = secondArg<Long>()
            stored = stored?.let { record ->
                if (record.key == key) {
                    record.copy(
                        status = ReleaseReminderStatus.FULFILLED,
                        fulfilledAtMillis = at,
                        membershipConfirmed = true
                    )
                } else {
                    record
                }
            }
        }

        coEvery {
            dataStore.markArmed(any())
        } coAnswers {
            val key = firstArg<String>()
            stored = stored?.let { record ->
                if (record.key == key) {
                    record.copy(
                        status = ReleaseReminderStatus.ARMED,
                        fulfilledAtMillis = null,
                        membershipConfirmed = false
                    )
                } else {
                    record
                }
            }
        }

        coEvery {
            dataStore.remove(any<String>())
        } coAnswers {
            val key = firstArg<String>()
            if (stored?.key == key) {
                stored = null
            }
        }

        every {
            metaRepository.getMetaFresh(
                any(),
                any(),
                any()
            )
        } answers {
            val value = freshMeta
            if (value == null) {
                flowOf<NetworkResult<Meta>>(
                    NetworkResult.Error("No authority result")
                )
            } else {
                flowOf<NetworkResult<Meta>>(
                    NetworkResult.Success(value)
                )
            }
        }

        coEvery {
            libraryRepository.ensureInDefault(any())
        } coAnswers {
            libraryWrites += 1
            true
        }

        mockkObject(ReleaseReminderWorker.Companion)

        every {
            ReleaseReminderWorker.scheduleDue(
                any(),
                any()
            )
        } just Runs

        mockkStatic(Log::class)

        every {
            Log.i(
                any<String>(),
                any<String>()
            )
        } returns 0

        every {
            Log.w(
                any<String>(),
                any<String>()
            )
        } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun dateMovesSoonerButRemainsFuture_updatesRecordAndScheduler() = runTest {
        val today = today()
        val originalDate = today.plusDays(30).toString()
        val correctedDate = today.plusDays(7).toString()

        stored = reminder(
            releaseDate = originalDate,
            lastAuthorityCheckAtMillis =
                System.currentTimeMillis()
        )

        freshMeta = authorityMeta(
            state = "coming_soon",
            releaseDate = correctedDate
        )

        val coordinator = coordinator()

        coordinator.updateHomeSnapshot(
            cwResolved = true,
            continueWatchingItems = emptyList(),
            catalogRows = listOf(
                comingSoonRow(correctedDate)
            )
        )

        coordinator.runPeriodicMaintenance()

        assertEquals(
            correctedDate,
            stored?.releaseDate
        )
        assertEquals(
            ReleaseReminderStatus.ARMED,
            stored?.status
        )
        assertEquals(
            0,
            libraryWrites
        )

        verify(exactly = 0) {
            metaRepository.getMetaFresh(
                any(),
                any(),
                any()
            )
        }

        verify(exactly = 1) {
            ReleaseReminderWorker.scheduleDue(
                context,
                match { reminders ->
                    reminders.single().releaseDate ==
                        correctedDate
                }
            )
        }
    }

    @Test
    fun dateMovesSoonerIntoPast_checksImmediatelyButComingSoonStaysArmed() = runTest {
        val today = today()
        val originalDate = today.plusDays(7).toString()
        val correctedDate = today.minusDays(1).toString()

        stored = reminder(
            releaseDate = originalDate
        )

        freshMeta = authorityMeta(
            state = "coming_soon",
            releaseDate = correctedDate
        )

        val coordinator = coordinator()

        coordinator.updateHomeSnapshot(
            cwResolved = true,
            continueWatchingItems = emptyList(),
            catalogRows = listOf(
                comingSoonRow(correctedDate)
            )
        )

        coordinator.runDueMaintenance()

        assertEquals(
            correctedDate,
            stored?.releaseDate
        )
        assertEquals(
            ReleaseReminderStatus.ARMED,
            stored?.status
        )
        assertNotNull(
            stored?.lastAuthorityCheckAtMillis
        )
        assertEquals(
            0,
            libraryWrites
        )

        verify(exactly = 1) {
            metaRepository.getMetaFresh(
                addonBaseUrl,
                "movie",
                imdbId
            )
        }
    }

    @Test
    fun dateMovesLater_updatesRecordAndScheduler() = runTest {
        val today = today()
        val originalDate = today.plusDays(4).toString()
        val correctedDate = today.plusDays(28).toString()

        stored = reminder(
            releaseDate = originalDate,
            lastAuthorityCheckAtMillis =
                System.currentTimeMillis()
        )

        freshMeta = authorityMeta(
            state = "coming_soon",
            releaseDate = correctedDate
        )

        val coordinator = coordinator()

        coordinator.updateHomeSnapshot(
            cwResolved = true,
            continueWatchingItems = emptyList(),
            catalogRows = listOf(
                comingSoonRow(correctedDate)
            )
        )

        coordinator.runPeriodicMaintenance()

        assertEquals(
            correctedDate,
            stored?.releaseDate
        )
        assertEquals(
            ReleaseReminderStatus.ARMED,
            stored?.status
        )
        assertEquals(
            0,
            libraryWrites
        )

        verify(exactly = 0) {
            metaRepository.getMetaFresh(
                any(),
                any(),
                any()
            )
        }

        verify(exactly = 1) {
            ReleaseReminderWorker.scheduleDue(
                context,
                match { reminders ->
                    reminders.single().releaseDate ==
                        correctedDate
                }
            )
        }
    }

    @Test
    fun dueDateReachedButAuthorityStillComingSoon_doesNotFulfill() = runTest {
        val dueDate = today().toString()

        stored = reminder(
            releaseDate = dueDate
        )

        freshMeta = authorityMeta(
            state = "coming_soon",
            releaseDate = dueDate
        )

        val coordinator = coordinator()

        coordinator.updateHomeSnapshot(
            cwResolved = true,
            continueWatchingItems = emptyList(),
            catalogRows = listOf(
                comingSoonRow(dueDate)
            )
        )

        coordinator.runDueMaintenance()

        assertEquals(
            ReleaseReminderStatus.ARMED,
            stored?.status
        )
        assertNotNull(
            stored?.lastAuthorityCheckAtMillis
        )
        assertEquals(
            0,
            libraryWrites
        )
    }

    @Test
    fun dueDateReachedButAuthorityUnknown_staysArmedForRetry() = runTest {
        stored = reminder(
            releaseDate =
                today().minusDays(1).toString()
        )

        freshMeta = authorityMeta(
            state = "unknown",
            releaseDate = null
        )

        val coordinator = coordinator()

        coordinator.updateHomeSnapshot(
            cwResolved = true,
            continueWatchingItems = emptyList(),
            catalogRows = emptyList()
        )

        coordinator.runDueMaintenance()

        assertEquals(
            ReleaseReminderStatus.ARMED,
            stored?.status
        )
        assertNotNull(
            stored?.lastAuthorityCheckAtMillis
        )
        assertEquals(
            0,
            libraryWrites
        )
    }

    @Test
    fun confirmedReleasedAuthority_fulfillsExactlyOnce() = runTest {
        stored = reminder(
            releaseDate =
                today().minusDays(1).toString()
        )

        freshMeta = authorityMeta(
            state = "released",
            releaseDate = null,
            platformId = "netflix"
        )

        val coordinator = coordinator()

        coordinator.updateHomeSnapshot(
            cwResolved = true,
            continueWatchingItems = emptyList(),
            catalogRows = emptyList()
        )

        coordinator.runDueMaintenance()

        assertEquals(
            ReleaseReminderStatus.FULFILLED,
            stored?.status
        )
        assertTrue(
            stored?.membershipConfirmed == true
        )
        assertNotNull(
            stored?.fulfilledAtMillis
        )
        assertEquals(
            1,
            libraryWrites
        )

        coordinator.runDueMaintenance()

        assertEquals(
            1,
            libraryWrites
        )
    }

    @Test
    fun matchingLatestEvidenceWithNeutralAuthority_fulfills() = runTest {
        stored = reminder(
            releaseDate =
                today().minusDays(1).toString()
        )

        freshMeta = authorityMeta(
            state = null,
            releaseDate = null
        )

        val coordinator = coordinator()

        coordinator.updateHomeSnapshot(
            cwResolved = true,
            continueWatchingItems = emptyList(),
            catalogRows = listOf(
                latestRow()
            )
        )

        coordinator.runDueMaintenance()

        assertEquals(
            ReleaseReminderStatus.FULFILLED,
            stored?.status
        )
        assertEquals(
            1,
            libraryWrites
        )
    }

    @Test
    fun releasedAuthorityFromWrongPlatform_doesNotFulfill() = runTest {
        stored = reminder(
            releaseDate =
                today().minusDays(1).toString(),
            platformId = "netflix"
        )

        freshMeta = authorityMeta(
            state = "released",
            releaseDate = null,
            platformId = "max"
        )

        val coordinator = coordinator()

        coordinator.runDueMaintenance()

        assertEquals(
            ReleaseReminderStatus.ARMED,
            stored?.status
        )
        assertEquals(
            0,
            libraryWrites
        )
    }

    @Test
    fun releasedAuthorityForOlderSeason_doesNotFulfillNewSeasonReminder() = runTest {
        stored = reminder(
            releaseDate =
                today().minusDays(1).toString(),
            type = ContentType.SERIES,
            seasonNumber = 3,
            platformId = "netflix"
        )

        freshMeta = authorityMeta(
            state = "released",
            releaseDate = null,
            platformId = "netflix",
            type = ContentType.SERIES,
            newSeasonNumber = 2
        )

        val coordinator = coordinator()

        coordinator.runDueMaintenance()

        assertEquals(
            ReleaseReminderStatus.ARMED,
            stored?.status
        )
        assertEquals(
            0,
            libraryWrites
        )
    }

    @Test
    fun profileChangeDuringAuthorityFetch_mustNotFulfillIntoNewProfile() = runTest {
        stored = reminder(
            releaseDate =
                today().minusDays(1).toString()
        )

        val released = authorityMeta(
            state = "released",
            releaseDate = null,
            platformId = "netflix"
        )

        every {
            metaRepository.getMetaFresh(
                any(),
                any(),
                any()
            )
        } answers {
            activeProfileId.value = 2

            flowOf<NetworkResult<Meta>>(
                NetworkResult.Success(released)
            )
        }

        val coordinator = coordinator()

        coordinator.runDueMaintenance()

        assertEquals(
            0,
            libraryWrites
        )
        assertEquals(
            ReleaseReminderStatus.ARMED,
            stored?.status
        )
    }

    private fun coordinator(): ReleaseReminderCoordinator =
        ReleaseReminderCoordinator(
            appContext = context,
            dataStore = dataStore,
            metaRepository = metaRepository,
            tmdbService = tmdbService,
            libraryRepository = libraryRepository,
            profileManager = profileManager
        )

    private fun reminder(
        releaseDate: String,
        type: ContentType = ContentType.MOVIE,
        seasonNumber: Int? = null,
        platformId: String = "netflix",
        lastAuthorityCheckAtMillis: Long? = null
    ): ReleaseReminderRecord =
        ReleaseReminderRecord(
            itemId = imdbId,
            itemType = type.toApiString(),
            title = "Reminder Test",
            imdbId = imdbId,
            addonBaseUrl = addonBaseUrl,
            releaseDate = releaseDate,
            seasonNumber = seasonNumber,
            platformId = platformId,
            lastAuthorityCheckAtMillis =
                lastAuthorityCheckAtMillis
        )

    private fun comingSoonRow(
        releaseDate: String,
        type: ContentType = ContentType.MOVIE,
        seasonNumber: Int? = null,
        platformId: String = "netflix"
    ): CatalogRow =
        CatalogRow(
            addonId = "authority",
            addonName = "Authority",
            addonBaseUrl = addonBaseUrl,
            catalogId =
                if (type == ContentType.SERIES) {
                    "coming_soon_series"
                } else {
                    "coming_soon_movies"
                },
            catalogName = "Coming Soon",
            type = type,
            items = listOf(
                preview(
                    type = type,
                    hints = MetaBehaviorHints(
                        comingSoon = true,
                        releaseDate = releaseDate,
                        upcomingSeason = seasonNumber,
                        platformId = platformId,
                        releaseReminderState =
                            "coming_soon"
                    )
                )
            )
        )

    private fun latestRow(
        type: ContentType = ContentType.MOVIE,
        newSeasonNumber: Int? = null
    ): CatalogRow =
        CatalogRow(
            addonId = "authority",
            addonName = "Authority",
            addonBaseUrl = addonBaseUrl,
            catalogId =
                if (type == ContentType.SERIES) {
                    "latest_series"
                } else {
                    "latest_movies"
                },
            catalogName = "Latest",
            type = type,
            items = listOf(
                preview(
                    type = type,
                    hints = MetaBehaviorHints(
                        comingSoon = false,
                        platformId = "netflix",
                        newSeasonNumber =
                            newSeasonNumber
                    )
                )
            )
        )

    private fun preview(
        type: ContentType,
        hints: MetaBehaviorHints
    ): MetaPreview =
        MetaPreview(
            id = imdbId,
            type = type,
            name = "Reminder Test",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            imdbId = imdbId,
            behaviorHints = hints
        )

    private fun authorityMeta(
        state: String?,
        releaseDate: String?,
        platformId: String = "netflix",
        type: ContentType = ContentType.MOVIE,
        newSeasonNumber: Int? = null
    ): Meta =
        Meta(
            id = imdbId,
            type = type,
            name = "Reminder Test",
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
            imdbId = imdbId,
            behaviorHints = MetaBehaviorHints(
                releaseDate = releaseDate,
                platformId = platformId,
                newSeasonNumber = newSeasonNumber,
                releaseReminderState = state
            )
        )

    private fun today(): LocalDate =
        LocalDate.now(
            ZoneId.systemDefault()
        )
}
