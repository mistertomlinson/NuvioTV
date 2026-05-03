package com.nuvio.tv.ui.shared

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SharedBackdropViewModel @Inject constructor() : ViewModel() {

    private val _backdropUrl = MutableStateFlow<String?>(null)
    init { android.util.Log.e("SharedBackdrop", "VM CREATED hash=\${System.identityHashCode(this)}") }
    val backdropUrl: StateFlow<String?> = _backdropUrl.asStateFlow()

    // Parallax offset driven by home screen, ignored by detail
    private val _parallaxOffsetX = MutableStateFlow(0f)
    val parallaxOffsetX: StateFlow<Float> = _parallaxOffsetX.asStateFlow()

    // Whether to apply the 1.1x overscale (home=true, detail=true but no parallax)
    private val _overscale = MutableStateFlow(true)
    val overscale: StateFlow<Boolean> = _overscale.asStateFlow()

    fun setBackdrop(url: String?) {
        android.util.Log.e("SharedBackdrop", "setBackdrop vm=\${System.identityHashCode(this)} url=\$url")
        _backdropUrl.value = url
    }
    fun setParallaxOffsetX(offset: Float) { _parallaxOffsetX.value = offset }
    fun setOverscale(enabled: Boolean) { _overscale.value = enabled }
}
