package com.squareup.anvil.compiler

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.squareup.anvil.compiler.api.AnvilContext
import com.squareup.anvil.compiler.api.AnvilKspExtension
import com.squareup.anvil.compiler.api.ComponentMergingBackend
import com.squareup.anvil.compiler.codegen.KspContributesSubcomponentHandlerSymbolProcessor
import com.squareup.anvil.compiler.codegen.ksp.AnvilSymbolProcessor
import com.squareup.anvil.compiler.codegen.ksp.AnvilSymbolProcessorProvider
import com.squareup.anvil.compiler.codegen.toAnvilContext
import java.util.ServiceLoader

/**
 * An abstraction layer between [KspContributesSubcomponentHandlerSymbolProcessor] and [KspContributionMerger]
 * to handle running them contextually and with a shared caching [ClassScannerKsp].
 *
 * @see Provider for more details on the logic of when each are used.
 */
internal class ClassScanningKspProcessor(
  override val env: SymbolProcessorEnvironment,
  private val delegate: SymbolProcessor,
) : AnvilSymbolProcessor() {

  @AutoService(SymbolProcessorProvider::class)
  class Provider : AnvilSymbolProcessorProvider(
    { context ->
      // Both qualify to run if we're not only generating factories
      !context.generateFactoriesOnly
    },
    { env ->
      val context = env.toAnvilContext()
      // Shared caching class scanner for both processors
      val classScanner = ClassScannerKsp()
      // TODO where do we run extensions? Maybe make a composite runner and run both it and handler?
      val extensions = extensions(env, context)
      val contributesSubcomponentHandler =
        KspContributesSubcomponentHandlerSymbolProcessor(env, classScanner)
      val componentMergingEnabled =
        !context.disableComponentMerging && !context.generateFactories && context.componentMergingBackend == ComponentMergingBackend.KSP
      val delegate = if (componentMergingEnabled) {
        // We're running component merging, so we need to run both and let KspContributionMerger
        // handle running the contributesSubcomponentHandler when needed.
        KspContributionMerger(env, classScanner, contributesSubcomponentHandler, extensions)
      } else {
        // We're only generating factories/contributessubcomponents, so only run it.
        contributesSubcomponentHandler
      }
      ClassScanningKspProcessor(env, delegate)
    },
  )

  override fun processChecked(resolver: Resolver) = delegate.process(resolver)

  override fun finish() {
    delegate.finish()
  }

  override fun onError() {
    delegate.onError()
  }

  companion object {
    private fun extensions(
      env: SymbolProcessorEnvironment,
      context: AnvilContext,
    ): Set<AnvilKspExtension> {
      return try {
        ServiceLoader.load(
          AnvilKspExtension.Provider::class.java,
          AnvilKspExtension.Provider::class.java.classLoader,
        ).mapNotNullTo(mutableSetOf()) { provider ->
          if (provider.isApplicable(context)) {
            provider.create(env)
          } else {
            null
          }
        }
      } catch (e: Exception) {
        env.logger.exception(e)
        emptySet()
      }
    }
  }
}
