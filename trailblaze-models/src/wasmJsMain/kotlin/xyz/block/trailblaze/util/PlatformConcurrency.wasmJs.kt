package xyz.block.trailblaze.util

internal actual fun <K, V> concurrentMutableMap(): MutableMap<K, V> = LinkedHashMap()

internal actual fun <E> concurrentMutableSet(): MutableSet<E> = LinkedHashSet()
