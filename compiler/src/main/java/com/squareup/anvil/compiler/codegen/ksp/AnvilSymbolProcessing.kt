package com.squareup.anvil.compiler.codegen.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.squareup.anvil.compiler.OPTION_VERBOSE
import com.squareup.anvil.compiler.api.AnvilApplicabilityChecker
import com.squareup.anvil.compiler.codegen.toAnvilContext
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.time.measureTimedValue

private object NoOpProcessor : SymbolProcessor {
  override fun process(resolver: Resolver): List<KSAnnotated> = emptyList()
}

internal open class AnvilSymbolProcessorProvider(
  private val applicabilityChecker: AnvilApplicabilityChecker,
  private val delegate: (SymbolProcessorEnvironment) -> AnvilSymbolProcessor,
) : SymbolProcessorProvider {
  final override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
    val context = environment.toAnvilContext()
    if (!applicabilityChecker.isApplicable(context)) return NoOpProcessor
    return delegate(environment)
  }
}

internal abstract class AnvilSymbolProcessor : SymbolProcessor {
  abstract val env: SymbolProcessorEnvironment
  protected val verbose by lazy(LazyThreadSafetyMode.NONE) {
    env.options[OPTION_VERBOSE]?.toBoolean() ?: false
  }
  private var round = 0
  private var totalTime = 0L

  final override fun process(resolver: Resolver): List<KSAnnotated> {
    round++
    val (result, duration) = measureTimedValue {
      runInternal(resolver)
    }
    val durationMs = duration.inWholeMilliseconds
    totalTime += durationMs
    log("Round $round took ${durationMs}ms")
    return result
  }

  private fun runInternal(resolver: Resolver): List<KSAnnotated> {
    return try {
      processChecked(resolver)
    } catch (e: KspAnvilException) {
      env.logger.error(e.message, e.node)
      e.cause?.let(env.logger::exception)
      emptyList()
    }
  }

  @OptIn(ExperimentalContracts::class)
  protected inline fun <T> logTimed(message: String, block: () -> T): T {
    contract { callsInPlace(block, kotlin.contracts.InvocationKind.EXACTLY_ONCE) }
    val (result, duration) = measureTimedValue(block)
    log("$message took ${duration.inWholeMilliseconds}ms")
    return result
  }

  protected fun log(message: String) {
    val messageWithTag = "[Anvil] ${javaClass.simpleName}: $message"
    if (verbose) {
      env.logger.warn(messageWithTag)
    } else {
      env.logger.info(messageWithTag)
    }
  }

  override fun finish() {
    log("${javaClass.simpleName}: Total processing time after $round round(s) took ${totalTime}ms")
  }

  protected abstract fun processChecked(resolver: Resolver): List<KSAnnotated>
}
