package com.nuvio.tv.ui.screens.home

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import coil.ImageLoader
import coil.decode.DataSource
import coil.request.SuccessResult
import coil.size.Size
import coil.transform.Transformation
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class XlBackdropCacheTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var loader: ImageLoader
    @After fun cleanup() {
        if (::activity.isInitialized) compose.runOnUiThread { activity.pause().stop().destroy() }
        if (::loader.isInitialized) loader.shutdown()
    }
    @Test fun revisitingPreparedArtworkUsesTheBlurCacheAndNeverBlursOnTheMainThread() {
        val context = RuntimeEnvironment.getApplication()
        val source = java.io.File(context.cacheDir, "blur-cache-reuse.png")
        Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
            source.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val transformations = AtomicInteger()
        var second: SuccessResult? = null
        var failure: Throwable? = null
        val counting = object : Transformation {
            override val cacheKey = XlBackdropBlur.cacheKey
            override suspend fun transform(input: Bitmap, size: Size): Bitmap {
                assertNotEquals("No blur computation on the UI thread", Looper.getMainLooper(), Looper.myLooper())
                transformations.incrementAndGet()
                return XlBackdropBlur.transform(input, size)
            }
        }
        compose.runOnUiThread {
            loader = ImageLoader.Builder(context).build()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                LaunchedEffect(Unit) {
                    try {
                        val preload = checkNotNull(homeBackdropPreloadRequest(context,
                            HomeBackdropArtwork(source.toURI().toString(), true), 1920, 1080))
                            .newBuilder().transformations(counting).build()
                        assertTrue(loader.execute(preload) is SuccessResult)
                        second = loader.execute(xlBlurredBackdropRequest(context, source.toURI().toString())) as SuccessResult
                    } catch (error: Throwable) { failure = error }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(16); compose.waitForIdle()
            second != null || failure != null
        }
        failure?.let { throw it }
        assertEquals("The same artwork must not be blurred twice", 1, transformations.get())
        assertEquals(DataSource.MEMORY_CACHE, checkNotNull(second).dataSource)
    }
}
