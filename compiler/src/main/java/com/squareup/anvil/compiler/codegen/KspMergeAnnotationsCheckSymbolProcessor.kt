package com.squareup.anvil.compiler.codegen

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.anvil.compiler.codegen.KspMergeAnnotationsCheckSymbolProcessor.Companion.checkNotAnnotatedWithDaggerAnnotation
import com.squareup.anvil.compiler.codegen.KspMergeAnnotationsCheckSymbolProcessor.Companion.checkSingleAnnotation
import com.squareup.anvil.compiler.codegen.ksp.AnvilSymbolProcessor
import com.squareup.anvil.compiler.codegen.ksp.AnvilSymbolProcessorProvider
import com.squareup.anvil.compiler.codegen.ksp.KspAnvilException
import com.squareup.anvil.compiler.codegen.ksp.checkNoDuplicateScope
import com.squareup.anvil.compiler.codegen.ksp.declaringClass
import com.squareup.anvil.compiler.codegen.ksp.getSymbolsWithAnnotations
import com.squareup.anvil.compiler.codegen.ksp.isAnnotationPresent
import com.squareup.anvil.compiler.codegen.ksp.mergeAnnotations
import com.squareup.anvil.compiler.codegen.ksp.resolveKSClassDeclaration
import com.squareup.anvil.compiler.daggerComponentFqName
import com.squareup.anvil.compiler.daggerModuleFqName
import com.squareup.anvil.compiler.daggerSubcomponentFqName
import com.squareup.anvil.compiler.mergeComponentClassName
import com.squareup.anvil.compiler.mergeComponentFqName
import com.squareup.anvil.compiler.mergeInterfacesFqName
import com.squareup.anvil.compiler.mergeModulesClassName
import com.squareup.anvil.compiler.mergeModulesFqName
import com.squareup.anvil.compiler.mergeSubcomponentClassName
import com.squareup.anvil.compiler.mergeSubcomponentFqName
import com.squareup.kotlinpoet.ksp.toClassName
import org.jetbrains.kotlin.name.FqName

internal class KspMergeAnnotationsCheckSymbolProcessor(
  override val env: SymbolProcessorEnvironment,
) : AnvilSymbolProcessor() {

  @AutoService(SymbolProcessorProvider::class)
  class Provider : AnvilSymbolProcessorProvider(
    applicabilityChecker = { context -> !context.disableComponentMerging },
    delegate = ::KspMergeAnnotationsCheckSymbolProcessor,
  )

  override fun processChecked(resolver: Resolver): List<KSAnnotated> {
    resolver.getSymbolsWithAnnotations(
      mergeComponentFqName,
      mergeSubcomponentFqName,
      mergeModulesFqName,
      mergeInterfacesFqName,
    )
      .filterIsInstance<KSClassDeclaration>()
      .forEach(::validate)

    return emptyList()
  }

  companion object {
    fun validate(
      clazz: KSClassDeclaration,
      mergeAnnotations: List<KSAnnotation> = clazz.mergeAnnotations(),
    ) {
      mergeAnnotations.checkSingleAnnotation()
      mergeAnnotations.checkNoDuplicateScope(
        annotatedType = clazz,
        isContributeAnnotation = false,
      )

      // Note that we only allow a single type of `@Merge*` annotation through the check above.
      // The same class can't merge a component and subcomponent at the same time. Therefore,
      // all annotations must have the same FqName and we can use the first annotation to check
      // for the Dagger annotation.
      mergeAnnotations
        .firstOrNull {
          it.annotationType.resolve().declaration.qualifiedName?.asString() != mergeInterfacesFqName.asString()
        }
        ?.checkNotAnnotatedWithDaggerAnnotation()
    }

    private fun List<KSAnnotation>.checkSingleAnnotation() {
      val distinctAnnotations = distinctBy { it.annotationType.resolve().declaration.qualifiedName }
      if (distinctAnnotations.size > 1) {
        throw KspAnvilException(
          node = this[0].declaringClass,
          message = "It's only allowed to have one single type of @Merge* annotation, " +
            "however multiple instances of the same annotation are allowed. You mix " +
            distinctAnnotations.joinToString(prefix = "[", postfix = "]") {
              it.shortName.asString()
            } +
            " and this is forbidden.",
        )
      }
    }

    private fun KSAnnotation.checkNotAnnotatedWithDaggerAnnotation() {
      if (declaringClass.isAnnotationPresent(daggerAnnotationFqName.asString())) {
        throw KspAnvilException(
          node = declaringClass,
          message = "When using @${shortName.asString()} it's not allowed to " +
            "annotate the same class with @${daggerAnnotationFqName.shortName().asString()}. " +
            "The Dagger annotation will be generated.",
        )
      }
    }

    private val KSAnnotation.daggerAnnotationFqName: FqName
      get() = when (annotationType.resolve().resolveKSClassDeclaration()?.toClassName()) {
        mergeComponentClassName -> daggerComponentFqName
        mergeSubcomponentClassName -> daggerSubcomponentFqName
        mergeModulesClassName -> daggerModuleFqName
        else -> throw NotImplementedError("Don't know how to handle $this.")
      }
  }
}
