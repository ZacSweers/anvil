import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
  id("conventions.kmp-library")
  id("conventions.publish")
}

publish {
  configurePom(
    artifactId = "annotations-optional",
    pomName = "Anvil Optional Annotations",
    pomDescription = "Optional annotations that we\"ve found to be helpful with managing larger dependency graphs",
  )
}

kotlin {
  @OptIn(ExperimentalKotlinGradlePluginApi::class)
  compilerOptions {
    freeCompilerArgs.add("-Xexpect-actual-classes")
  }
}

dependencies {
  jvmMainApi(libs.inject)
}
