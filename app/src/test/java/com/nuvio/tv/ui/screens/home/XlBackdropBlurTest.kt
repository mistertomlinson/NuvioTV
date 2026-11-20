package com.nuvio.tv.ui.screens.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import coil.size.Dimension
import coil.size.Precision
import coil.size.Scale
import coil.size.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class XlBackdropBlurTest {
    @Test fun blurUsesABoundedDecodeAndADistinctCacheVariantWithoutASharpPlaceholder() = runBlocking {
        val request = xlBlurredBackdropRequest(RuntimeEnvironment.getApplication(), "artwork")
        assertEquals("artwork", request.data)
        assertEquals(Size(Dimension.Pixels(160), Dimension.Pixels(90)), request.sizeResolver.size())
        assertEquals(Precision.EXACT, request.precision)
        assertEquals(Scale.FILL, request.scale)
        assertFalse(request.allowHardware)
        assertEquals(listOf(XlBackdropBlur), request.transformations)
        assertNull("Never borrow a sharp image as the blurred placeholder", request.placeholderMemoryCacheKey)
    }

    @Test fun thePreparedBlurSmoothsEdgesWithoutMutatingOrRecyclingItsSource() = runBlocking {
        val input = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(input)
        canvas.drawColor(Color.BLACK)
        canvas.drawRect(640f, 0f, 1280f, 720f, android.graphics.Paint().apply { color = Color.WHITE })
        val output = XlBackdropBlur.transform(input, Size(160, 90))
        assertEquals(160, output.width); assertEquals(90, output.height)
        assertNotSame(input, output)
        assertFalse(input.isRecycled)
        assertEquals(Color.BLACK, input.getPixel(639, 360))
        assertEquals(Color.WHITE, input.getPixel(640, 360))
        assertEquals(Color.BLACK, output.getPixel(0, 45))
        assertEquals(Color.WHITE, output.getPixel(159, 45))
        val samples = (20..140).map { Color.red(output.getPixel(it, 45)) }
        assertTrue("The high-contrast edge must become a smooth spread", samples.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("The field must spread color far beyond a recognizable sharp edge", Color.red(output.getPixel(50, 45)) > 15)
        assertTrue(Color.red(output.getPixel(80, 45)) in 110..150)
        output.recycle(); input.recycle()
    }

    @Test fun aTallFallbackStillProducesOnlyOneSmallOpaqueBackdrop() = runBlocking {
        val input = Bitmap.createBitmap(100, 1600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val output = XlBackdropBlur.transform(input, Size(160, 90))
        assertEquals(160 * 90 * 4, output.byteCount)
        assertEquals(Color.GREEN, output.getPixel(0, 0))
        assertEquals(Color.GREEN, output.getPixel(159, 89))
        output.recycle(); input.recycle()
    }

    @Test fun cancelledLookaheadDoesNotContinueProcessingOrOwnItsSource() = runBlocking {
        val input = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val cancelled = Job().apply { cancel() }
        try {
            withContext(cancelled) { XlBackdropBlur.transform(input, Size(160, 90)) }
            fail("Cancelled blur must not return an image")
        } catch (_: CancellationException) { }
        assertFalse(input.isRecycled)
        input.recycle()
    }

    @Test fun broadRedArtworkKeepsItsColorWhileSmallBrightDetailsDisappear() = runBlocking {
        val input = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        android.graphics.Canvas(input).drawRect(300f, 160f, 340f, 200f,
            android.graphics.Paint().apply { color = Color.WHITE })
        val output = XlBackdropBlur.transform(input, Size(160, 90))
        val colors = IntArray(160 * 90)
        output.getPixels(colors, 0, 160, 0, 0, 160, 90)
        assertTrue("The dominant red channel must not be darkened by the blur", colors.all { Color.red(it) == 255 })
        assertTrue("A small bright detail must dissolve into the red field", colors.maxOf { Color.green(it) } < 20)
        assertEquals(Color.WHITE, input.getPixel(320, 180))
        input.recycle(); output.recycle()
    }
}
