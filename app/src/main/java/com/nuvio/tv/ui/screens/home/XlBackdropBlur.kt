package com.nuvio.tv.ui.screens.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import coil.size.Size
import coil.transform.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val XL_BLUR_WIDTH = 160
internal const val XL_BLUR_HEIGHT = 90

internal fun isPreparedXlBlurBitmap(bitmap: Bitmap?): Boolean =
    bitmap != null && !bitmap.isRecycled && bitmap.width == XL_BLUR_WIDTH && bitmap.height == XL_BLUR_HEIGHT

// Share one CPU lane across rendering and lookahead. No blur runs on the main
// thread, in a draw callback, or once per animation frame.
@OptIn(ExperimentalCoroutinesApi::class)
private val xlBlurDispatcher = Dispatchers.Default.limitedParallelism(1)

internal fun xlBlurredBackdropRequest(context: Context, url: String?): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(XL_BLUR_WIDTH, XL_BLUR_HEIGHT)
        .scale(Scale.FILL)
        .precision(Precision.EXACT)
        .allowHardware(false)
        .crossfade(false)
        .transformations(XlBackdropBlur)
        .transformationDispatcher(xlBlurDispatcher)
        .build()

/** Bounded, cancellation-aware three-pass box blur, cached by Coil as its own variant. */
internal object XlBackdropBlur : Transformation {
    override val cacheKey = "nuvio-xl-blur-v2-160x90-r24-p3"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = withContext(xlBlurDispatcher) {
        // Exact crop bounds also protect against an original-size cache hit or
        // a very tall portrait fallback. Never mutate/recycle Coil's source.
        val output = Bitmap.createBitmap(XL_BLUR_WIDTH, XL_BLUR_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val scale = maxOf(XL_BLUR_WIDTH.toFloat() / input.width, XL_BLUR_HEIGHT.toFloat() / input.height)
            val left = (XL_BLUR_WIDTH - input.width * scale) / 2f
            val top = (XL_BLUR_HEIGHT - input.height * scale) / 2f
            Canvas(output).drawBitmap(input, null,
                RectF(left, top, left + input.width * scale, top + input.height * scale),
                Paint(Paint.FILTER_BITMAP_FLAG))
            val pixels = IntArray(XL_BLUR_WIDTH * XL_BLUR_HEIGHT)
            val scratch = IntArray(pixels.size)
            output.getPixels(pixels, 0, XL_BLUR_WIDTH, 0, 0, XL_BLUR_WIDTH, XL_BLUR_HEIGHT)
            repeat(3) {
                blurPass(pixels, scratch, XL_BLUR_WIDTH, XL_BLUR_HEIGHT, horizontal = true)
                blurPass(scratch, pixels, XL_BLUR_WIDTH, XL_BLUR_HEIGHT, horizontal = false)
            }
            currentCoroutineContext().ensureActive()
            output.setPixels(pixels, 0, XL_BLUR_WIDTH, 0, 0, XL_BLUR_WIDTH, XL_BLUR_HEIGHT)
            output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }

    private suspend fun blurPass(source: IntArray, target: IntArray, width: Int, height: Int, horizontal: Boolean) {
        val radius = 24
        val samples = radius * 2 + 1
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val step = if (horizontal) 1 else width
        for (line in 0 until lines) {
            if (line % 16 == 0) currentCoroutineContext().ensureActive()
            val start = if (horizontal) line * width else line
            var a = 0; var r = 0; var g = 0; var b = 0
            for (sample in -radius..radius) {
                val color = source[start + sample.coerceIn(0, length - 1) * step]
                a += color ushr 24; r += color ushr 16 and 255
                g += color ushr 8 and 255; b += color and 255
            }
            for (position in 0 until length) {
                target[start + position * step] = (a / samples shl 24) or (r / samples shl 16) or
                    (g / samples shl 8) or (b / samples)
                val remove = source[start + (position - radius).coerceAtLeast(0) * step]
                val add = source[start + (position + radius + 1).coerceAtMost(length - 1) * step]
                a += (add ushr 24) - (remove ushr 24)
                r += (add ushr 16 and 255) - (remove ushr 16 and 255)
                g += (add ushr 8 and 255) - (remove ushr 8 and 255)
                b += (add and 255) - (remove and 255)
            }
        }
    }
}
