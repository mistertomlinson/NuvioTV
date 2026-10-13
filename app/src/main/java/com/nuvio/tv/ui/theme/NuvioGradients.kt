package com.nuvio.tv.ui.theme

import android.graphics.ComposeShader
import android.graphics.LinearGradient as AndroidLinearGradient
import android.graphics.PorterDuff
import android.graphics.RadialGradient as AndroidRadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader as AndroidShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb

object NuvioGradients {
    private val HomePurple = Color(0xFF9B5FE0)
    private val HomePurpleMid = Color(0xFF6A3FD4)
    private val HomeCyan = Color(0xFF00C8C8)
    private val HomeHotPink = Color(0xFFFF408C)

    /*
     * Home-only AGSL field.
     *
     * There is deliberately NO time uniform. The pattern is static while
     * Home is focused. A new seed only changes the shape on the next Home
     * focus.
     *
     * The base flow is diagonal rather than radial. Two low-amplitude wave
     * fields bend its coordinates, then independent cyan and pink fields
     * disturb the ends. This prevents the obvious circular arcs produced by
     * a normal radial gradient.
     */
    private const val HOME_SHADER_SOURCE = """
        uniform float2 resolution;
        uniform float seed;

        float hash11(float p) {
            return fract(sin(p * 127.1) * 43758.5453);
        }

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / resolution;

            float r1 = hash11(seed + 1.37);
            float r2 = hash11(seed + 7.91);
            float r3 = hash11(seed + 17.53);
            float r4 = hash11(seed + 31.17);
            float r5 = hash11(seed + 47.81);

            float phase1 = r1 * 6.2831853;
            float phase2 = r2 * 6.2831853;
            float phase3 = r3 * 6.2831853;

            /*
             * Very low-frequency coordinate warp.
             *
             * Enough to bend color contours but intentionally nowhere near
             * high-frequency "noise".
             */
            float warpX =
                sin(uv.y * 5.2 + phase1) * (0.035 + r3 * 0.025) +
                sin((uv.x + uv.y) * 8.0 + phase2) * 0.020;

            float warpY =
                sin(uv.x * 4.4 + phase2) * (0.022 + r4 * 0.020) +
                sin((uv.x * 0.7 - uv.y) * 7.0 + phase3) * 0.014;

            float2 warped = uv + float2(warpX, warpY);

            float flow =
                clamp(
                    warped.x * (0.72 + r4 * 0.08) +
                    warped.y * (0.20 + r5 * 0.08),
                    0.0,
                    1.0
                );

            float split =
                0.47 + (r2 - 0.5) * 0.10;

            float lightEnd =
                0.90 + (r3 - 0.5) * 0.06;

            float3 cyan =
                float3(
                    0.0000000,
                    0.7843137,
                    0.7843137
                );

            float3 deepPurple =
                float3(
                    0.4156863,
                    0.2470588,
                    0.8313725
                );

            float3 lightPurple =
                float3(
                    0.6078431,
                    0.3725490,
                    0.8784314
                );

            float3 hotPink =
                float3(
                    1.0000000,
                    0.2509804,
                    0.5490196
                );

            float3 color;

            if (flow < split) {
                color =
                    mix(
                        cyan,
                        deepPurple,
                        smoothstep(
                            0.0,
                            split,
                            flow
                        )
                    );
            } else {
                color =
                    mix(
                        deepPurple,
                        lightPurple,
                        smoothstep(
                            split,
                            lightEnd,
                            flow
                        )
                    );
            }

            /*
             * Asymmetric cyan bloom.
             *
             * Its center is partially outside the selector and its contour
             * receives two different waves, so the upper-left cannot present
             * as a clean radial circle.
             */
            float cyanCenterX =
                -0.04 +
                (r4 - 0.5) * 0.10;

            float cyanCenterY =
                0.04 +
                (r5 - 0.5) * 0.16;

            float2 cyanDelta =
                uv -
                float2(
                    cyanCenterX,
                    cyanCenterY
                );

            cyanDelta.x *=
                0.82 + r2 * 0.14;

            float cyanDistance =
                length(cyanDelta) +
                sin(
                    uv.y * 9.0 +
                    phase2
                ) * 0.030 +
                sin(
                    (uv.x + uv.y) * 15.0 +
                    phase1
                ) * 0.016;

            float cyanField =
                1.0 -
                smoothstep(
                    0.14,
                    0.62,
                    cyanDistance
                );

            color =
                mix(
                    color,
                    cyan,
                    clamp(
                        cyanField *
                            (0.18 + r1 * 0.16),
                        0.0,
                        0.34
                    )
                );

            /*
             * Main pink intrusion.
             *
             * Center remains outside the right edge. Its vertical position,
             * width and contour vary by seed, but the field is hard-gated to
             * the far side so an unlucky seed cannot turn the selector pink.
             */
            float pinkCenterX =
                1.055 +
                (r1 - 0.5) * 0.075;

            float pinkCenterY =
                0.77 +
                (r2 - 0.5) * 0.30;

            float2 pinkDelta =
                uv -
                float2(
                    pinkCenterX,
                    pinkCenterY
                );

            pinkDelta.x *=
                1.45 + r5 * 0.35;

            float pinkDistance =
                length(pinkDelta) +
                sin(
                    uv.y * 10.5 +
                    phase1
                ) * 0.037 +
                sin(
                    (uv.x + uv.y) * 17.0 +
                    phase3
                ) * 0.020;

            /*
             * Broad translucent pink wash, closer to the release-badge
             * treatment. The wider falloff and earlier edge gate prevent
             * a concentrated hot-pink spot from forming.
             */
            float pinkField =
                1.0 -
                smoothstep(
                    0.04,
                    0.39,
                    pinkDistance
                );

            pinkField *=
                smoothstep(
                    0.66,
                    1.00,
                    uv.x
                );

            /*
             * Secondary lower-right lobe breaks the remaining single-arc
             * silhouette without substantially increasing the amount of pink.
             */
            float secondPinkX =
                0.96 +
                (r3 - 0.5) * 0.08;

            float secondPinkY =
                1.08 +
                (r5 - 0.5) * 0.10;

            float2 pinkDelta2 =
                uv -
                float2(
                    secondPinkX,
                    secondPinkY
                );

            pinkDelta2.x *= 1.35;

            float pinkDistance2 =
                length(pinkDelta2) +
                sin(
                    uv.x * 13.0 +
                    phase2
                ) * 0.022;

            /*
             * Secondary pink lobe stays broad and soft so it reads as a
             * blended color intrusion instead of a small circular pink spot.
             */
            float pinkField2 =
                1.0 -
                smoothstep(
                    0.03,
                    0.36,
                    pinkDistance2
                );

            pinkField2 *=
                smoothstep(
                    0.72,
                    1.00,
                    uv.x
                );

            /*
             * Reduce peak saturation slightly while spreading the color
             * across a larger area. This should read as purple warming into
             * pink rather than a discrete pink spot.
             */
            float finalPink =
                clamp(
                    pinkField *
                        (0.50 + r4 * 0.14) +
                    pinkField2 *
                        0.14,
                    0.0,
                    0.72
                );

            color =
                mix(
                    color,
                    hotPink,
                    finalPink
                );

            return half4(
                color.r,
                color.g,
                color.b,
                1.0
            );
        }
    """

    /*
     * One RuntimeShader instance for Home.
     *
     * Parsing the AGSL program is NOT repeated on every focus. Only the small
     * seed uniform changes between brushes.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object HomeRuntimeShaderHolder {
        val shader =
            RuntimeShader(
                HOME_SHADER_SOURCE
            )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class SeededHomeRuntimeBrush(
        private val shader: RuntimeShader,
        private val seed: Float
    ) : ShaderBrush() {
        override fun createShader(
            size: Size
        ): Shader {
            shader.setFloatUniform(
                "resolution",
                size.width.coerceAtLeast(1f),
                size.height.coerceAtLeast(1f)
            )

            shader.setFloatUniform(
                "seed",
                seed
            )

            return shader
        }
    }

    private fun seedToUniform(
        seed: Int
    ): Float {
        val bits =
            (seed ushr 8) and
                0x00FFFFFF

        return 1f +
            bits.toFloat() /
                16_777_215f *
                997f
    }

    /*
     * Portable organic field.
     *
     * This is used for every release-status badge because native gradients
     * are considerably cheaper than compiling a RuntimeShader per card.
     *
     * It is also the Home fallback below Android 13.
     */
    private class OrganicCompositeBrush(
        private val seed: Int,
        private val alpha: Float
    ) : ShaderBrush() {
        override fun createShader(
            size: Size
        ): Shader {
            val width =
                size.width.coerceAtLeast(1f)

            val height =
                size.height.coerceAtLeast(1f)

            val maxDimension =
                maxOf(
                    width,
                    height
                )

            val r1 = seedUnit(seed, 1)
            val r2 = seedUnit(seed, 2)
            val r3 = seedUnit(seed, 3)
            val r4 = seedUnit(seed, 4)
            val r5 = seedUnit(seed, 5)
            val r6 = seedUnit(seed, 6)

            /*
             * Main direction is diagonal rather than circular.
             */
            val base =
                AndroidLinearGradient(
                    -width * 0.08f,
                    height *
                        (
                            0.02f +
                                (r1 - 0.5f) *
                                0.10f
                            ),
                    width * 1.08f,
                    height *
                        (
                            0.92f +
                                (r2 - 0.5f) *
                                0.12f
                            ),
                    intArrayOf(
                        HomeCyan
                            .copy(alpha = alpha)
                            .toArgb(),
                        HomePurpleMid
                            .copy(alpha = alpha)
                            .toArgb(),
                        HomePurple
                            .copy(alpha = alpha)
                            .toArgb()
                    ),
                    floatArrayOf(
                        0f,
                        0.48f +
                            (r3 - 0.5f) *
                            0.08f,
                        1f
                    ),
                    AndroidShader.TileMode.CLAMP
                )

            /*
             * Broad cyan lobe starts outside the upper-left.
             */
            val cyanBloom =
                AndroidRadialGradient(
                    width *
                        (
                            -0.07f +
                                (r2 - 0.5f) *
                                0.08f
                            ),
                    height *
                        (
                            0.08f +
                                (r4 - 0.5f) *
                                0.22f
                            ),
                    maxDimension *
                        (
                            0.48f +
                                r1 *
                                0.10f
                            ),
                    intArrayOf(
                        HomeCyan
                            .copy(
                                alpha =
                                    alpha *
                                        0.28f
                            )
                            .toArgb(),
                        HomeCyan
                            .copy(alpha = 0f)
                            .toArgb()
                    ),
                    floatArrayOf(
                        0f,
                        1f
                    ),
                    AndroidShader.TileMode.CLAMP
                )

            /*
             * An off-center purple lobe bends the middle transition so the
             * cyan boundary cannot simply reveal the shape of cyanBloom.
             */
            val purpleBloom =
                AndroidRadialGradient(
                    width *
                        (
                            0.48f +
                                (r5 - 0.5f) *
                                0.16f
                            ),
                    height *
                        (
                            0.66f +
                                (r6 - 0.5f) *
                                0.30f
                            ),
                    maxDimension *
                        (
                            0.34f +
                                r3 *
                                0.10f
                            ),
                    intArrayOf(
                        HomePurpleMid
                            .copy(
                                alpha =
                                    alpha *
                                        0.16f
                            )
                            .toArgb(),
                        HomePurpleMid
                            .copy(alpha = 0f)
                            .toArgb()
                    ),
                    floatArrayOf(
                        0f,
                        1f
                    ),
                    AndroidShader.TileMode.CLAMP
                )

            /*
             * Two small pink lobes instead of one large radial edge.
             *
             * Their overlap creates an asymmetric pink flare rather than the
             * previous vertical/circular arch.
             */
            val pinkBloomA =
                AndroidRadialGradient(
                    width *
                        (
                            1.055f +
                                (r1 - 0.5f) *
                                0.055f
                            ),
                    height *
                        (
                            0.76f +
                                (r2 - 0.5f) *
                                0.26f
                            ),
                    maxDimension *
                        (
                            0.18f +
                                r4 *
                                0.035f
                            ),
                    intArrayOf(
                        HomeHotPink
                            .copy(
                                alpha =
                                    alpha *
                                        0.72f
                            )
                            .toArgb(),
                        HomeHotPink
                            .copy(alpha = 0f)
                            .toArgb()
                    ),
                    floatArrayOf(
                        0f,
                        1f
                    ),
                    AndroidShader.TileMode.CLAMP
                )

            val pinkBloomB =
                AndroidRadialGradient(
                    width *
                        (
                            0.97f +
                                (r5 - 0.5f) *
                                0.07f
                            ),
                    height *
                        (
                            1.09f +
                                (r6 - 0.5f) *
                                0.08f
                            ),
                    maxDimension *
                        (
                            0.13f +
                                r2 *
                                0.035f
                            ),
                    intArrayOf(
                        HomeHotPink
                            .copy(
                                alpha =
                                    alpha *
                                        0.42f
                            )
                            .toArgb(),
                        HomeHotPink
                            .copy(alpha = 0f)
                            .toArgb()
                    ),
                    floatArrayOf(
                        0f,
                        1f
                    ),
                    AndroidShader.TileMode.CLAMP
                )

            /*
             * SRC_ATOP keeps the base field's alpha. The overlays change hue
             * and contour without making an 80%-opacity badge progressively
             * more opaque.
             */
            var result: AndroidShader =
                ComposeShader(
                    base,
                    cyanBloom,
                    PorterDuff.Mode.SRC_ATOP
                )

            result =
                ComposeShader(
                    result,
                    purpleBloom,
                    PorterDuff.Mode.SRC_ATOP
                )

            result =
                ComposeShader(
                    result,
                    pinkBloomA,
                    PorterDuff.Mode.SRC_ATOP
                )

            result =
                ComposeShader(
                    result,
                    pinkBloomB,
                    PorterDuff.Mode.SRC_ATOP
                )

            return result
        }
    }

    private fun seedUnit(
        seed: Int,
        salt: Int
    ): Float {
        var mixed =
            seed xor
                (
                    salt *
                        0x045D9F3B
                    )

        mixed =
            (
                mixed xor
                    (mixed ushr 16)
                ) *
                0x045D9F3B

        mixed =
            mixed xor
                (mixed ushr 16)

        return (
            (mixed ushr 8) and
                0x00FFFFFF
            ).toFloat() /
            16_777_215f
    }

    /*
     * Only Home calls this changing-seed path.
     *
     * Every real streaming service continues to use its existing solid
     * brandColor in StreamingPlatformCarousel.
     */
    fun homeFocus(
        seed: Int
    ): Brush {
        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            runtimeHomeFocus(seed)
        } else {
            OrganicCompositeBrush(
                seed = seed,
                alpha = 1f
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun runtimeHomeFocus(
        seed: Int
    ): Brush {
        return SeededHomeRuntimeBrush(
            shader =
                HomeRuntimeShaderHolder.shader,
            seed =
                seedToUniform(seed)
        )
    }

    /*
     * Compatibility alias. StreamingPlatformCarousel no longer uses this
     * fixed brush, but retaining it prevents unrelated callers from changing.
     */
    val HomeFocus: Brush by lazy {
        homeFocus(
            seed = 0x13579BDF
        )
    }

    /*
     * One fixed organic pattern shared by every Nuvio release-status badge.
     * There is deliberately no title/profile/focus seed here.
     */
    val ReleaseStatusBadge: Brush =
        OrganicCompositeBrush(
            seed = 0x51A7BEEF,
            alpha = 0.8f
        )

    /*
     * Intentionally unchanged.
     */
    val ComingSoonHeroPill: Brush =
        Brush.radialGradient(
            colors =
                listOf(
                    HomePurple,
                    HomePurpleMid,
                    HomeCyan
                ),
            center = Offset.Zero,
            radius = 120f
        )
}
