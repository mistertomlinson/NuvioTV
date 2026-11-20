package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.*

/** The outgoing bitmap is captured before XL's separate dim layer is applied. */
@Stable
internal class XlPlatformBackdropHandoff {
    var outgoingDim by mutableFloatStateOf(0f)
        private set
    var outgoingXl by mutableStateOf(false)
        private set

    fun capture(dim: Float, startsWithXl: Boolean) {
        outgoingDim = dim
        outgoingXl = startsWithXl
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
