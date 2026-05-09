package com.nuvio.tv.ui.util

import androidx.compose.runtime.Immutable

@Immutable
data class StableMap<K, V>(val map: Map<K, V> = emptyMap()) : Map<K, V> by map

@Immutable
data class StableList<T>(val list: List<T> = emptyList()) : List<T> by list

@Immutable
data class StableSet<T>(val set: Set<T> = emptySet()) : Set<T> by set

fun <K, V> Map<K, V>.asStable(): StableMap<K, V> = StableMap(this)
fun <T> List<T>.asStable(): StableList<T> = StableList(this)
fun <T> Set<T>.asStable(): StableSet<T> = StableSet(this)

@Suppress("unused")
@androidx.compose.runtime.Stable
class StableRef<T>(val value: T)
