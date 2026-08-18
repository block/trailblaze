package xyz.block.trailblaze.util

import java.util.concurrent.ConcurrentHashMap

internal actual fun <K, V> concurrentMutableMap(): MutableMap<K, V> = ConcurrentHashMap()

internal actual fun <E> concurrentMutableSet(): MutableSet<E> = ConcurrentHashMap.newKeySet()
