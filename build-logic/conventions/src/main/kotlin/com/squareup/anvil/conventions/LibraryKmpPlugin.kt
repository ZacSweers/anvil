package com.squareup.anvil.conventions

import com.rickbusarow.kgx.javaExtension
import com.rickbusarow.kgx.pluginId
import com.squareup.anvil.conventions.utils.libs
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl

class LibraryKmpPlugin : Plugin<Project> {

  @OptIn(ExperimentalWasmDsl::class)
  override fun apply(target: Project) = with(target) {
    pluginManager.apply("org.jetbrains.kotlin.multiplatform")

    kotlin {
      if (pluginManager.hasPlugin("com.android.library")) {
        androidTarget()
      }

      iosArm64()
      iosSimulatorArm64()
      iosX64()

      js {
        browser()
      }

      jvm()

      linuxArm64()
      linuxX64()

      macosArm64()
      macosX64()

      tvosArm64()
      tvosSimulatorArm64()
      tvosX64()

      wasmJs {
        browser()
      }

      watchosArm32()
      watchosArm64()
      watchosSimulatorArm64()
      watchosX64()

      applyDefaultHierarchyTemplate()
    }
    configureJavaCompile()
    configureBinaryCompatibilityValidator()
    configureExplicitApi()
  }

  private fun Project.configureExplicitApi() {
    kotlin.explicitApi()
  }

  private fun Project.kotlin(action: KotlinMultiplatformExtension.() -> Unit) {
    extensions.configure(KotlinMultiplatformExtension::class.java, action)
  }

  private val Project.kotlin: KotlinMultiplatformExtension
    get() = extensions.getByType(KotlinMultiplatformExtension::class.java)

  private fun Project.configureBinaryCompatibilityValidator() {
    plugins.apply(libs.plugins.kotlinx.binaryCompatibility.pluginId)
  }
}
