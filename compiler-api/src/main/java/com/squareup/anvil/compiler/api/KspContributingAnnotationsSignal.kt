package com.squareup.anvil.compiler.api

/**
 * This interface helps custom KSP symbol processors that generate Anvil-relevant code to signal
 * their requirements to Anvil. [supportedAnnotationTypes] is used in contribution merging to know
 * when defer to a later round.
 *
 * For example, if you define a custom `SymbolProcessor` that generates code for `@MyAnnotation`,
 * you would implement [supportedAnnotationTypes] to return `setOf("com.example.MyAnnotation")`.
 * Then, during contribution merging, if Anvil KSP sees any symbols in that round annotated with
 * this annotation, it will defer to the next round to allow your processor to run first.
 *
 * You should only do this for classes that generate code that is also annotated with Anvil
 * annotations. It's not necessary for simple classes or dagger-only classes.
 *
 * This interface is loaded via [java.util.ServiceLoader] and you should package a service file for
 * your implementations accordingly. As such, order of execution is **not** guaranteed.
 */
public interface KspContributingAnnotationsSignal {
  /** Returns the set of annotation types that this code generator supports. */
  public val supportedAnnotationTypes: Set<String>
}
