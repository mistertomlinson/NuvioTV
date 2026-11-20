package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.*

/** The outgoing bitmap is captured before XL's separate dim layer is applied. */
@Stable
internal class XlPlatformBackdropHandoff {
    var outgoingDim by mutableFloatStateOf(0f)
        private set
    var outgoingXl by mutableStateOf(false)
        private set
    var outgoingBlurred by mutableStateOf(false)
        private set

    fun capture(dim: Float, startsWithXl: Boolean, blurred: Boolean = startsWithXl) {
        outgoingDim = dim
        outgoingXl = startsWithXl
        outgoingBlurred = blurred
    }

    fun involvesXl(incomingXl: Boolean): Boolean = outgoingXl || incomingXl
}

@Composable
internal fun rememberXlPlatformBackdropDim(
    backdrop: XlBackdropPresentation,
    handoff: XlPlatformBackdropHandoff,
    ghostVisible: Boolean
): State<Float> = remember(backdrop, handoff, ghostVisible) {
    derivedStateOf { if (ghostVisible) handoff.outgoingDim else backdrop.dim.value }
}

/** Keep the outgoing color field free of ordinary gradients until its ghost reaches black. */
@Composable
internal fun rememberXlPlatformBackdropBlurred(backdrop: XlBackdropPresentation,
    handoff: XlPlatformBackdropHandoff, ghostVisible: Boolean): State<Boolean> = remember(backdrop, handoff, ghostVisible) {
    derivedStateOf { if (ghostVisible) handoff.outgoingBlurred else backdrop.blurred }
}
