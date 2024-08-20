package com.squareup.anvil.compiler

internal class RecordingCache<K, V>(private val name: String) {
  val cache: MutableMap<K, V> = mutableMapOf()
  private var hits = 0
  private var misses = 0

  operator fun contains(key: K): Boolean {
    return key in cache
  }

  operator fun get(key: K): V? {
    return cache[key]
  }

  operator fun set(key: K, value: V) {
    cache[key] = value
  }

  fun getValue(key: K): V {
    return cache.getValue(key)
  }

  fun hit() {
    hits++
  }

  fun miss() {
    misses++
  }

  fun statsString(): String {
    return """
      $name Cache
        Size:     ${cache.size}
        Hits:     $hits
        Misses:   $misses
        Fidelity: ${(hits.toDouble() / (hits + misses)) * 100}%
    """.trimIndent()
  }
}
