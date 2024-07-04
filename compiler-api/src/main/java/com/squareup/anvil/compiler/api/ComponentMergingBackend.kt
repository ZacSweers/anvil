package com.squareup.anvil.compiler.api

import java.util.Locale

/** Possible modes of component merging. */
public enum class ComponentMergingBackend {
  /** Component merging runs as an IR plugin during kapt stub generation. */
  IR,

  /** Component merging runs as a Kotlin Symbol Processor (KSP) with dagger KSP. */
  KSP,

  ;

  public companion object {
    public fun fromString(value: String): ComponentMergingBackend? {
      // We call it kapt processing in testing, but the actual name here is IR.
      // TODO consolidate?
      val uppercase = value.uppercase(Locale.US)
        .let { if (it == "KAPT") IR.name else it }
      return ComponentMergingBackend.entries.find { it.name == uppercase }
    }
  }
}
