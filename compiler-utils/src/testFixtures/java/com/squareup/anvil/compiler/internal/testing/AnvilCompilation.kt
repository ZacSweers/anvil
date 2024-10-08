package com.squareup.anvil.compiler.internal.testing

import com.google.auto.value.processor.AutoAnnotationProcessor
import com.google.common.truth.Truth.assertWithMessage
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.squareup.anvil.annotations.ExperimentalAnvilApi
import com.squareup.anvil.compiler.AnvilCommandLineProcessor
import com.squareup.anvil.compiler.AnvilComponentRegistrar
import com.squareup.anvil.compiler.OPTION_GENERATE_SHIMS
import com.squareup.anvil.compiler.api.ComponentMergingBackend
import com.squareup.anvil.compiler.internal.testing.AnvilCompilationMode.Embedded
import com.squareup.anvil.compiler.internal.testing.AnvilCompilationMode.Ksp
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.PluginOption
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.addPreviousResultToClasspath
import com.tschuchort.compiletesting.kspArgs
import com.tschuchort.compiletesting.kspWithCompilation
import com.tschuchort.compiletesting.symbolProcessorProviders
import dagger.internal.codegen.ComponentProcessor
import dagger.internal.codegen.KspComponentProcessor
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.config.JvmTarget
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.Locale
import java.util.ServiceLoader

/**
 * A simple API over a [KotlinCompilation] with extra configuration support for Anvil.
 */
@ExperimentalAnvilApi
public class AnvilCompilation internal constructor(
  public val kotlinCompilation: KotlinCompilation,
) {

  private var isCompiled = false
  private var anvilConfigured = false

  /** Configures this the Anvil behavior of this compilation. */
  @ExperimentalAnvilApi
  public fun configureAnvil(
    componentProcessingMode: ComponentProcessingMode = ComponentProcessingMode.NONE,
    componentMergingBackend: ComponentMergingBackend = if (componentProcessingMode == ComponentProcessingMode.KSP) {
      ComponentMergingBackend.KSP
    } else {
      ComponentMergingBackend.IR
    },
    generateDaggerFactories: Boolean = false,
    generateDaggerFactoriesOnly: Boolean = false,
    disableComponentMerging: Boolean = false,
    enableExperimentalAnvilApis: Boolean = true,
    trackSourceFiles: Boolean = true,
    mode: AnvilCompilationMode = if (componentProcessingMode == ComponentProcessingMode.KSP || componentMergingBackend == ComponentMergingBackend.KSP) Ksp() else Embedded(),
    enableAnvil: Boolean = true,
  ): AnvilCompilation = apply {
    checkNotCompiled()
    check(!anvilConfigured) { "Anvil should not be configured twice." }

    anvilConfigured = true

    if (!enableAnvil) return@apply

    val forceKspEverywhere = componentProcessingMode == ComponentProcessingMode.KSP ||
      componentMergingBackend == ComponentMergingBackend.KSP

    if (forceKspEverywhere) {
      check(mode is Ksp) {
        "AnvilCompilationMode.Ksp mode must be used when component processing mode or merging backend are KSP."
      }
    }

    kotlinCompilation.apply {
      val anvilComponentRegistrar = AnvilComponentRegistrar()
      // Deprecation tracked in https://github.com/square/anvil/issues/672
      @Suppress("DEPRECATION")
      componentRegistrars = listOf(anvilComponentRegistrar)
      when (componentProcessingMode) {
        ComponentProcessingMode.KAPT -> {
          annotationProcessors = listOf(ComponentProcessor(), AutoAnnotationProcessor())
        }
        ComponentProcessingMode.KSP -> {
          // Other symbol processors are added later down
          symbolProcessorProviders += KspComponentProcessor.Provider()
        }
        ComponentProcessingMode.NONE -> {
          // Do nothing, but disable shim generation to avoid unresolved references in compilations
          kspArgs[OPTION_GENERATE_SHIMS] = "false"
        }
      }

      val anvilCommandLineProcessor = AnvilCommandLineProcessor()
      commandLineProcessors = listOf(anvilCommandLineProcessor)

      pluginOptions += listOf(
        PluginOption(
          pluginId = anvilCommandLineProcessor.pluginId,
          optionName = "disable-component-merging",
          optionValue = disableComponentMerging.toString(),
        ),
        PluginOption(
          pluginId = anvilCommandLineProcessor.pluginId,
          optionName = "analysis-backend",
          optionValue = mode.analysisBackend.name.lowercase(Locale.US),
        ),
        PluginOption(
          pluginId = anvilCommandLineProcessor.pluginId,
          optionName = "merging-backend",
          optionValue = componentMergingBackend.name.lowercase(Locale.US),
        ),
      )

      when (mode) {
        is Embedded -> {
          anvilComponentRegistrar.addCodeGenerators(mode.codeGenerators)
          val buildDir = workingDir.resolve("build")
          pluginOptions +=
            listOf(
              PluginOption(
                pluginId = anvilCommandLineProcessor.pluginId,
                optionName = "gradle-project-dir",
                optionValue = workingDir.absolutePath,
              ),
              PluginOption(
                pluginId = anvilCommandLineProcessor.pluginId,
                optionName = "gradle-build-dir",
                optionValue = buildDir.absolutePath,
              ),
              PluginOption(
                pluginId = anvilCommandLineProcessor.pluginId,
                optionName = "src-gen-dir",
                optionValue = buildDir.resolve("anvil").absolutePath,
              ),
              PluginOption(
                pluginId = anvilCommandLineProcessor.pluginId,
                optionName = "anvil-cache-dir",
                optionValue = buildDir.resolve("anvil-cache").absolutePath,
              ),
              PluginOption(
                pluginId = anvilCommandLineProcessor.pluginId,
                optionName = "generate-dagger-factories",
                optionValue = generateDaggerFactories.toString(),
              ),
              PluginOption(
                pluginId = anvilCommandLineProcessor.pluginId,
                optionName = "generate-dagger-factories-only",
                optionValue = generateDaggerFactoriesOnly.toString(),
              ),
              PluginOption(
                pluginId = anvilCommandLineProcessor.pluginId,
                optionName = "will-have-dagger-factories",
                optionValue = (generateDaggerFactories || (componentProcessingMode != ComponentProcessingMode.NONE)).toString(),
              ),
              PluginOption(
                pluginId = anvilCommandLineProcessor.pluginId,
                optionName = "track-source-files",
                optionValue = trackSourceFiles.toString(),
              ),
            )
        }

        is Ksp -> {
          symbolProcessorProviders += buildList {
            addAll(
              ServiceLoader.load(
                SymbolProcessorProvider::class.java,
                SymbolProcessorProvider::class.java.classLoader,
              ).filterNot {
                // We either explicitly add this above or not at all
                it is KspComponentProcessor.Provider
              },
            )
            addAll(mode.symbolProcessorProviders)
          }
          // Run KSP in a single-pass. Does nothing in KSP2, but in KSP1 will run embedded within kotlinc
          // https://kotlinlang.slack.com/archives/C013BA8EQSE/p1639462548225400?thread_ts=1639433474.224900&cid=C013BA8EQSE
          kspWithCompilation = true
          kspArgs["will-have-dagger-factories"] = generateDaggerFactories.toString()
          kspArgs["generate-dagger-factories"] = generateDaggerFactories.toString()
          kspArgs["generate-dagger-factories-only"] = generateDaggerFactoriesOnly.toString()
          kspArgs["disable-component-merging"] = disableComponentMerging.toString()
          kspArgs["merging-backend"] = componentMergingBackend.name.lowercase(Locale.US)
          if (mode.options.isNotEmpty()) {
            for ((key, value) in mode.options) {
              kspArgs[key] = value
            }
          }
        }
      }

      if (enableExperimentalAnvilApis) {
        kotlincArguments += listOf(
          "-opt-in=kotlin.RequiresOptIn",
          "-opt-in=com.squareup.anvil.annotations.ExperimentalAnvilApi",
        )
      }
    }
  }

  /** Adds the given sources to this compilation with their packages and names inferred. */
  public fun addSources(@Language("kotlin") vararg sources: String): AnvilCompilation = apply {
    checkNotCompiled()
    kotlinCompilation.sources += sources.mapIndexed { index, content ->
      val packageDir = content.lines()
        .firstOrNull { it.trim().startsWith("package ") }
        ?.substringAfter("package ")
        ?.replace('.', '/')
        ?.let { "$it/" }
        ?: ""

      val name = "${kotlinCompilation.workingDir.absolutePath}/sources/src/main/java/" +
        "$packageDir/Source$index.kt"

      Files.createDirectories(File(name).parentFile.toPath())

      SourceFile.kotlin(name, contents = content, trimIndent = true)
    }
  }

  public fun addPreviousCompilationResult(result: JvmCompilationResult): AnvilCompilation = apply {
    checkNotCompiled()
    kotlinCompilation.addPreviousResultToClasspath(result)
  }

  public fun jvmTarget(jvmTarget: JvmTarget) {
    checkNotCompiled()
    kotlinCompilation.jvmTarget = jvmTarget.description
  }

  /**
   * Returns an Anvil-generated file with the given [packageName] and [fileName] from its expected
   * path.
   */
  public fun generatedAnvilFile(
    packageName: String,
    fileName: String,
  ): File {
    check(isCompiled) {
      "No compilation run yet! Call compile() first."
    }
    return File(
      kotlinCompilation.workingDir,
      "build/anvil/${packageName.replace('.', File.separatorChar)}/$fileName.kt",
    )
      .apply {
        check(exists()) {
          "Generated file not found!"
        }
      }
  }

  private fun checkNotCompiled() {
    check(!isCompiled) {
      "Already compiled! Create a new compilation if you want to compile again."
    }
  }

  /**
   * Compiles the underlying [KotlinCompilation]. Note that if [configureAnvil] has not been called
   * prior to this, it will be configured with default behavior.
   */
  public fun compile(
    @Language("kotlin") vararg sources: String,
    expectExitCode: KotlinCompilation.ExitCode? = null,
    block: JvmCompilationResult.() -> Unit = {},
  ): JvmCompilationResult {
    checkNotCompiled()
    if (!anvilConfigured) {
      // Configure with default behaviors
      configureAnvil()
    }
    addSources(*sources)
    isCompiled = true

    return kotlinCompilation.compile().apply {

      if (exitCode != expectExitCode) {
        when {
          expectExitCode == null -> {
            // No expected code, so no assertion to be made
          }
          expectExitCode == KotlinCompilation.ExitCode.OK -> {
            assertWithMessage("Compilation failed unexpectedly\n\n$messages")
              .that(exitCode)
              .isEqualTo(KotlinCompilation.ExitCode.OK)
          }
          exitCode == KotlinCompilation.ExitCode.OK -> {
            assertWithMessage("Compilation succeeded unexpectedly\n\n$messages")
              .that(exitCode)
              .isEqualTo(expectExitCode)
          }
          else -> {
            assertWithMessage("Error code mismatch\n\n$messages")
              .that(exitCode)
              .isEqualTo(expectExitCode)
          }
        }
      }
      block()
    }
  }

  public companion object {
    public operator fun invoke(): AnvilCompilation {
      return AnvilCompilation(
        KotlinCompilation().apply {
          // Sensible default behaviors
          inheritClassPath = true
          jvmTarget = JvmTarget.JVM_1_8.description
          verbose = false
        },
      )
    }
  }
}

/** Available component processing modes. */
public enum class ComponentProcessingMode {
  KAPT,
  KSP,
  NONE,
}

/**
 * Helpful for testing code generators in unit tests end to end.
 *
 * This covers common cases, but is built upon reusable logic in [AnvilCompilation] and
 * [AnvilCompilation.configureAnvil]. Consider using those APIs if more advanced configuration
 * is needed.
 */
@ExperimentalAnvilApi
public fun compileAnvil(
  @Language("kotlin") vararg sources: String,
  componentProcessingMode: ComponentProcessingMode = ComponentProcessingMode.NONE,
  componentMergingBackend: ComponentMergingBackend = if (componentProcessingMode == ComponentProcessingMode.KSP) {
    ComponentMergingBackend.KSP
  } else {
    ComponentMergingBackend.IR
  },
  generateDaggerFactories: Boolean = false,
  generateDaggerFactoriesOnly: Boolean = false,
  disableComponentMerging: Boolean = false,
  allWarningsAsErrors: Boolean = true,
  messageOutputStream: OutputStream = System.out,
  workingDir: File? = null,
  enableExperimentalAnvilApis: Boolean = true,
  trackSourceFiles: Boolean = true,
  previousCompilationResult: JvmCompilationResult? = null,
  mode: AnvilCompilationMode = if (componentProcessingMode == ComponentProcessingMode.KSP || componentMergingBackend == ComponentMergingBackend.KSP) Ksp() else Embedded(),
  moduleName: String? = null,
  jvmTarget: JvmTarget? = null,
  expectExitCode: KotlinCompilation.ExitCode? = null,
  onCompilation: AnvilCompilation.() -> Unit = {},
  block: JvmCompilationResult.() -> Unit = { },
): JvmCompilationResult {
  return AnvilCompilation()
    .apply {
      kotlinCompilation.apply {
        this.allWarningsAsErrors = allWarningsAsErrors
        this.messageOutputStream = messageOutputStream
        if (workingDir != null) {
          this.workingDir = workingDir
        }
        if (moduleName != null) {
          this.moduleName = moduleName
        }
      }

      if (jvmTarget != null) {
        jvmTarget(jvmTarget)
      }

      if (previousCompilationResult != null) {
        addPreviousCompilationResult(previousCompilationResult)
      }
    }
    .configureAnvil(
      componentProcessingMode = componentProcessingMode,
      componentMergingBackend = componentMergingBackend,
      generateDaggerFactories = generateDaggerFactories,
      generateDaggerFactoriesOnly = generateDaggerFactoriesOnly,
      disableComponentMerging = disableComponentMerging,
      enableExperimentalAnvilApis = enableExperimentalAnvilApis,
      trackSourceFiles = trackSourceFiles,
      mode = mode,
    )
    .apply(onCompilation)
    .compile(
      *sources,
      expectExitCode = expectExitCode,
    )
    .also(block)
}
