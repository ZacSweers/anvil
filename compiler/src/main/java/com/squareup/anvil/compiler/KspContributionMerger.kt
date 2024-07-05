package com.squareup.anvil.compiler

import com.google.auto.service.AutoService
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueArgument
import com.google.devtools.ksp.symbol.KSVisitor
import com.google.devtools.ksp.symbol.Visibility
import com.google.devtools.ksp.symbol.impl.hasAnnotation
import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.MergeComponent
import com.squareup.anvil.annotations.MergeSubcomponent
import com.squareup.anvil.annotations.compat.MergeInterfaces
import com.squareup.anvil.annotations.compat.MergeModules
import com.squareup.anvil.compiler.api.ComponentMergingBackend
import com.squareup.anvil.compiler.codegen.generatedAnvilSubcomponentClassId
import com.squareup.anvil.compiler.codegen.ksp.AnvilSymbolProcessor
import com.squareup.anvil.compiler.codegen.ksp.AnvilSymbolProcessorProvider
import com.squareup.anvil.compiler.codegen.ksp.KspAnvilException
import com.squareup.anvil.compiler.codegen.ksp.argumentAt
import com.squareup.anvil.compiler.codegen.ksp.atLeastOneAnnotation
import com.squareup.anvil.compiler.codegen.ksp.classId
import com.squareup.anvil.compiler.codegen.ksp.declaringClass
import com.squareup.anvil.compiler.codegen.ksp.exclude
import com.squareup.anvil.compiler.codegen.ksp.find
import com.squareup.anvil.compiler.codegen.ksp.findAll
import com.squareup.anvil.compiler.codegen.ksp.getKSAnnotationsByType
import com.squareup.anvil.compiler.codegen.ksp.getSymbolsWithAnnotations
import com.squareup.anvil.compiler.codegen.ksp.includes
import com.squareup.anvil.compiler.codegen.ksp.isAnnotationPresent
import com.squareup.anvil.compiler.codegen.ksp.isInterface
import com.squareup.anvil.compiler.codegen.ksp.modules
import com.squareup.anvil.compiler.codegen.ksp.parentScope
import com.squareup.anvil.compiler.codegen.ksp.replaces
import com.squareup.anvil.compiler.codegen.ksp.resolveKSClassDeclaration
import com.squareup.anvil.compiler.codegen.ksp.returnTypeOrNull
import com.squareup.anvil.compiler.codegen.ksp.scope
import com.squareup.anvil.compiler.codegen.ksp.superTypesExcludingAny
import com.squareup.anvil.compiler.codegen.ksp.toFunSpec
import com.squareup.anvil.compiler.internal.capitalize
import com.squareup.anvil.compiler.internal.createAnvilSpec
import com.squareup.anvil.compiler.internal.mergedClassName
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.ksp.toAnnotationSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo
import dagger.Component
import dagger.Module
import org.jetbrains.kotlin.name.Name

/**
 * A [com.google.devtools.ksp.processing.SymbolProcessor] that performs the two types of merging
 * Anvil supports.
 *
 * 1. **Module merging**: This step sources from `@MergeComponent`, `@MergeSubcomponent`, and
 * `@MergeModules` to merge all contributed modules on the classpath to the generated element.
 *
 * 2. **Interface merging**: This step finds all contributed component interfaces and adds them
 * as supertypes to generated Dagger components from interfaces annotated with `@MergeComponent`
 * or `@MergeSubcomponent`. This also supports arbitrary interface merging on interfaces annotated
 * with `@MergeInterfaces`.
 */
internal class KspContributionMerger(override val env: SymbolProcessorEnvironment) :
  AnvilSymbolProcessor() {

  @AutoService(SymbolProcessorProvider::class)
  class Provider : AnvilSymbolProcessorProvider(
    { context ->
      !context.disableComponentMerging && !context.generateFactories && !context.generateFactoriesOnly && context.componentMergingBackend == ComponentMergingBackend.KSP
    },
    ::KspContributionMerger,
  )

  private val classScanner = ClassScannerKsp()

  override fun processChecked(
    resolver: Resolver,
  ): List<KSAnnotated> {
    // If there's any remaining `@Contributes*`-annotated classes, defer to a later round
    val contributingAnnotations = resolver.getSymbolsWithAnnotations(
      contributesBindingFqName,
      contributesMultibindingFqName,
      contributesSubcomponentFqName,
    ).toList()

    val shouldDefer = contributingAnnotations.isNotEmpty()

    // Don't defer if it's both ContributesTo and MergeModules/MergeInterfaces. In this case,
    // we need to process now and just point at what will eventually be generated

    // Mapping of scopes to contributing interfaces in this round
    val contributedInterfacesInRound = mutableMapOf<KSType, MutableList<KSClassDeclaration>>()

    // Mapping of scopes to contributing modules in this round
    val contributedModulesInRound = mutableMapOf<KSType, MutableList<KSClassDeclaration>>()

    resolver.getSymbolsWithAnnotations(contributesToFqName)
      .filterIsInstance<KSClassDeclaration>()
      .forEach { contributedTypeInRound ->
        val contributesToScopes =
          contributedTypeInRound.getKSAnnotationsByType(ContributesTo::class)
            .map { it.scope() }
            .toSet()
        if (contributesToScopes.isNotEmpty()) {
          val isModule = contributedTypeInRound.isAnnotationPresent<Module>() ||
            contributedTypeInRound.isAnnotationPresent<MergeModules>()
          if (isModule) {
            for (scope in contributesToScopes) {
              contributedModulesInRound.getOrPut(scope, ::mutableListOf)
                .add(contributedTypeInRound)
            }
            return@forEach
          }

          if (contributedTypeInRound.isInterface()) {
            for (scope in contributesToScopes) {
              contributedInterfacesInRound.getOrPut(scope, ::mutableListOf)
                .add(contributedTypeInRound)
            }
          }
        }
      }

    val deferred = resolver.getSymbolsWithAnnotations(
      mergeComponentFqName,
      mergeSubcomponentFqName,
      mergeModulesFqName,
      mergeInterfacesFqName,
    ).validate { deferred -> return deferred }
      .also { mergeAnnotatedTypes ->
        if (shouldDefer) {
          return mergeAnnotatedTypes
        }
      }
      .mapNotNull { annotated ->
        processClass(
          resolver,
          annotated,
          contributedInterfacesInRound,
          contributedModulesInRound,
        )
      }

    return deferred
  }

  /**
   * Returns non-null if the given [mergeAnnotatedClass] could not be processed.
   */
  private fun processClass(
    resolver: Resolver,
    mergeAnnotatedClass: KSClassDeclaration,
    contributedInterfacesInRound: Map<KSType, List<KSClassDeclaration>>,
    contributedModulesInRound: Map<KSType, List<KSClassDeclaration>>,
  ): KSAnnotated? {
    val mergeComponentAnnotations = mergeAnnotatedClass
      .findAll(mergeComponentFqName.asString(), mergeSubcomponentFqName.asString())

    val mergeModulesAnnotations = mergeAnnotatedClass
      .findAll(mergeModulesFqName.asString())

    val moduleMergerAnnotations = mergeComponentAnnotations + mergeModulesAnnotations

    val isModule = mergeModulesAnnotations.isNotEmpty()

    val daggerAnnotation = if (moduleMergerAnnotations.isNotEmpty()) {
      generateDaggerAnnotation(
        annotations = moduleMergerAnnotations,
        resolver = resolver,
        declaration = mergeAnnotatedClass,
        isModule = isModule,
        contributedModulesInRound = contributedModulesInRound,
      )
    } else {
      null
    }

    val mergeInterfacesAnnotations = mergeAnnotatedClass
      .findAll(mergeInterfacesFqName.asString())

    val interfaceMergerAnnotations = mergeComponentAnnotations + mergeInterfacesAnnotations

    val contributedInterfaces = if (interfaceMergerAnnotations.isNotEmpty()) {
      if (!mergeAnnotatedClass.isInterface()) {
        throw KspAnvilException(
          node = mergeAnnotatedClass,
          message = "Dagger components (or classes annotated with @MergeInterfaces)" +
            " must be interfaces.",
        )
      }

      contributedInterfaces(
        mergeAnnotations = interfaceMergerAnnotations,
        resolver = resolver,
        mergeAnnotatedClass = mergeAnnotatedClass,
        contributedInterfacesInRound = contributedInterfacesInRound,
      )
    } else {
      null
    }

    generateMergedClass(
      mergeAnnotatedClass = mergeAnnotatedClass,
      daggerAnnotation = daggerAnnotation,
      contributedInterfaces = contributedInterfaces,
      isModule = isModule,
    )
    return null
  }

  private fun generateDaggerAnnotation(
    annotations: List<KSAnnotation>,
    resolver: Resolver,
    declaration: KSClassDeclaration,
    isModule: Boolean,
    contributedModulesInRound: Map<KSType, List<KSClassDeclaration>>,
  ): AnnotationSpec {
    val daggerAnnotationClassName = annotations[0].daggerAnnotationClassName

    val scopes = annotations.map { it.scope() }
    // Any modules that are @MergeModules, we need to include their generated modules instead
    val predefinedModules = if (isModule) {
      annotations.flatMap { it.includes() }
    } else {
      annotations.flatMap { it.modules() }
    }.map {
      if (it.isAnnotationPresent<MergeModules>()) {
        val expected = it.toClassName().mergedClassName().canonicalName
        resolver.getClassDeclarationByName(expected)!!
      } else {
        it
      }.toClassName()
    }

    val allContributesAnnotations: List<KSAnnotation> = annotations
      .asSequence()
      .flatMap { annotation ->
        val scope = annotation.scope()
        classScanner
          .findContributedClasses(
            resolver = resolver,
            annotation = contributesToFqName,
            scope = scope,
          )
          .plus(
            contributedModulesInRound[scope].orEmpty()
              .map { contributedModule ->
                val mergeModulesAnnotation = contributedModule
                  .getKSAnnotationsByType(MergeModules::class)
                  .singleOrNull()
                if (mergeModulesAnnotation != null) {
                  // Fake the eventually-generated one for simplicity
                  // This is a type in the current round, so we don't have full type information
                  // available yet.
                  val newName = contributedModule.toClassName().mergedClassName()

                  // Copy over the ContributesTo annotation to this new fake too, but we need
                  // to remap its parent node so that the declaringClass works appropriately
                  val contributesToAnnotation = contributedModule
                    .getKSAnnotationsByType(ContributesTo::class)
                    .single()

                  val newAnnotations =
                    contributedModule.annotations - mergeModulesAnnotation - contributesToAnnotation

                  // Fake a `@Module` annotation too
                  object : KSClassDeclaration by contributedModule {
                    private val newKSClass = this

                    // We also need to fake a `Module` annotation on to this.
                    val newModuleAnnotation = object : KSAnnotation by mergeModulesAnnotation {
                      override val annotationType: KSTypeReference
                        get() = resolver.createKSTypeReferenceFromKSType(
                          resolver.getClassDeclarationByName<Module>()!!.asType(emptyList()),
                        )
                      override val arguments: List<KSValueArgument> =
                        mergeModulesAnnotation.arguments.filterNot {
                          it.name?.asString() in setOf(
                            "scope",
                            "exclude",
                          )
                        }
                      override val defaultArguments: List<KSValueArgument> =
                        mergeModulesAnnotation.defaultArguments.filterNot {
                          it.name?.asString() in setOf(
                            "scope",
                            "exclude",
                          )
                        }
                      override val parent: KSNode = newKSClass
                      override val shortName: KSName = resolver.getKSNameFromString("Module")

                      override fun <D, R> accept(visitor: KSVisitor<D, R>, data: D): R {
                        throw NotImplementedError()
                      }
                    }

                    override val qualifiedName: KSName =
                      resolver.getKSNameFromString(newName.canonicalName)
                    override val simpleName: KSName =
                      resolver.getKSNameFromString(newName.simpleName)
                    override val annotations: Sequence<KSAnnotation> =
                      newAnnotations + newModuleAnnotation + object : KSAnnotation by contributesToAnnotation {
                        override val parent: KSNode = newKSClass
                      }
                  }
                } else {
                  // Standard contributed module, just use it as-is
                  contributedModule
                }
              },
          )
      }
      .flatMap { contributedClass ->
        contributedClass
          .find(annotationName = contributesToFqName.asString())
          .filter { it.scope() in scopes }
      }
      .filter { contributesAnnotation ->
        val contributedClass = contributesAnnotation.declaringClass
        val moduleAnnotation = contributedClass.find(daggerModuleFqName.asString()).singleOrNull()
        val mergeModulesAnnotation =
          contributedClass.find(mergeModulesFqName.asString()).singleOrNull()

        if (!contributedClass.isInterface() &&
          moduleAnnotation == null &&
          mergeModulesAnnotation == null
        ) {
          throw KspAnvilException(
            message = "${contributedClass.qualifiedName?.asString()} is annotated with " +
              "@${ContributesTo::class.simpleName}, but this class is neither an interface " +
              "nor a Dagger module. Did you forget to add @${Module::class.simpleName}?",
            node = contributedClass,
          )
        }

        moduleAnnotation != null || mergeModulesAnnotation != null
      }
      .onEach { contributesAnnotation ->
        val contributedClass = contributesAnnotation.declaringClass
        if (contributedClass.getVisibility() != Visibility.PUBLIC) {
          throw KspAnvilException(
            message = "${contributedClass.qualifiedName?.asString()} is contributed to the Dagger graph, but the " +
              "module is not public. Only public modules are supported.",
            node = contributedClass,
          )
        }
      }
      // Convert the sequence to a list to avoid iterating it twice. We use the result twice
      // for replaced classes and the final result.
      .toList()

    val (bindingModuleContributesAnnotations, contributesAnnotations) = allContributesAnnotations.partition {
      it.declaringClass.hasAnnotation(internalBindingMarkerFqName.asString())
    }

    val excludedModules = annotations
      .flatMap { it.exclude() }
      .asSequence()
      .resolveMergedTypes(resolver)
      .onEach { excludedClass ->

        // Verify that the replaced classes use the same scope.
        val contributesToOurScope = excludedClass
          .findAll(
            contributesToFqName.asString(),
            contributesBindingFqName.asString(),
            contributesMultibindingFqName.asString(),
          )
          .map { it.scope() }
          .plus(
            excludedClass
              .find(contributesSubcomponentFqName.asString())
              .map { it.parentScope() },
          )
          .any { scope -> scope in scopes }

        if (!contributesToOurScope) {
          val origin = declaration.originClass()
          throw KspAnvilException(
            message = "${origin.qualifiedName?.asString()} with scopes " +
              "${
                scopes.joinToString(
                  prefix = "[",
                  postfix = "]",
                ) { it.resolveKSClassDeclaration()?.qualifiedName?.asString()!! }
              } " +
              "wants to exclude ${excludedClass.qualifiedName?.asString()}, but the excluded class isn't " +
              "contributed to the same scope.",
            node = origin,
          )
        }
      }
      .map { it.toClassName() }
      .toSet()

    val replacedModules = allContributesAnnotations
      // Ignore replaced modules or bindings specified by excluded modules.
      .filter { contributesAnnotation ->
        contributesAnnotation.declaringClass.toClassName() !in excludedModules
      }
      .flatMap { contributesAnnotation ->
        val contributedClass = contributesAnnotation.declaringClass
        contributesAnnotation.replaces()
          .asSequence()
          .resolveMergedTypes(resolver)
          .onEach { classToReplace ->
            // Verify has @Module annotation. It doesn't make sense for a Dagger module to
            // replace a non-Dagger module.
            if (!classToReplace.hasAnnotation(daggerModuleFqName.asString()) &&
              !classToReplace.hasAnnotation(contributesBindingFqName.asString()) &&
              !classToReplace.hasAnnotation(contributesMultibindingFqName.asString())
            ) {
              val origin = contributedClass.originClass()
              throw KspAnvilException(
                message = "${origin.qualifiedName?.asString()} wants to replace " +
                  "${classToReplace.qualifiedName?.asString()}, but the class being " +
                  "replaced is not a Dagger module.",
                node = origin,
              )
            }

            checkSameScope(contributedClass, classToReplace, scopes)
          }
      }
      .map { it.toClassName() }
      .toSet()

    val bindings = bindingModuleContributesAnnotations
      .mapNotNull { contributedAnnotation ->
        val moduleClass = contributedAnnotation.declaringClass
        val internalBindingMarker =
          moduleClass.find(internalBindingMarkerFqName.asString()).single()

        val bindingFunction = moduleClass.getAllFunctions().single {
          val functionName = it.simpleName.asString()
          functionName.startsWith("bind") || functionName.startsWith("provide")
        }

        val originClass =
          internalBindingMarker.originClass()!!

        if (originClass.toClassName()
            .let { it in excludedModules || it in replacedModules }
        ) return@mapNotNull null
        if (moduleClass.toClassName()
            .let { it in excludedModules || it in replacedModules }
        ) return@mapNotNull null

        val boundType = bindingFunction.returnTypeOrNull()!!.resolveKSClassDeclaration()!!
        val isMultibinding =
          internalBindingMarker.argumentAt("isMultibinding")?.value == true
        val qualifierKey =
          (internalBindingMarker.argumentAt("qualifierKey")?.value as? String?).orEmpty()
        val rank = (
          internalBindingMarker.argumentAt("rank")
            ?.value as? Int?
          )
          ?: ContributesBinding.RANK_NORMAL
        val scope = contributedAnnotation.scope()
        ContributedBinding(
          scope = scope.toClassName(),
          isMultibinding = isMultibinding,
          bindingModule = moduleClass.toClassName(),
          originClass = originClass.toClassName(),
          boundType = boundType,
          qualifierKey = qualifierKey,
          rank = rank,
          replaces = moduleClass.find(contributesToFqName.asString()).single()
            .replaces()
            .map { it.toClassName() },
        )
      }
      .let { ContributedBindings.from(it) }

    if (predefinedModules.isNotEmpty()) {
      val intersect = predefinedModules.intersect(excludedModules.toSet())
      if (intersect.isNotEmpty()) {
        throw KspAnvilException(
          message = "${declaration.qualifiedName?.asString()} includes and excludes modules " +
            "at the same time: ${intersect.joinToString { it.canonicalName }}",
          node = declaration,
        )
      }
    }

    val contributedSubcomponentModules =
      findContributedSubcomponentModules(
        declaration,
        scopes,
        resolver,
      ).map { it.toClassName() }

    val contributedModules: List<ClassName> = contributesAnnotations
      .asSequence()
      .map { it.declaringClass.toClassName() }
      .plus(
        bindings.bindings.values.flatMap { it.values }
          .flatten()
          .map { it.bindingModule }
          .map {
            resolver.getClassDeclarationByName(
              resolver.getKSNameFromString(it.canonicalName),
            )!!.toClassName()
          },
      )
      .minus(replacedModules)
      .minus(excludedModules)
      .plus(predefinedModules)
      .plus(contributedSubcomponentModules)
      .distinct()
      .toList()

    val parameterName = if (isModule) {
      "includes"
    } else {
      "modules"
    }
    return AnnotationSpec.builder(daggerAnnotationClassName)
      .addMember(
        "$parameterName = [%L]",
        contributedModules.map { CodeBlock.of("%T::class", it) }.joinToCode(),
      )
      .apply {
        fun copyArrayValue(name: String) {
          val varargArguments = annotations
            .mapNotNull { annotation ->
              @Suppress("UNCHECKED_CAST")
              (annotation.argumentAt(name)?.value as? List<KSType>?)
                ?.map { it.toClassName() }
            }
            .flatten()
            .ifEmpty { return }

          addMember(
            "$name = [%L]",
            varargArguments.map { CodeBlock.of("%T::class", it) }.joinToCode(),
          )
        }

        if (annotations[0].annotationType.resolve().toClassName() == mergeComponentClassName) {
          copyArrayValue("dependencies")
        }

        if (annotations[0].annotationType.resolve().toClassName() == mergeModulesClassName) {
          copyArrayValue("subcomponents")
        }
      }
      .build()
  }

  private fun contributedInterfaces(
    mergeAnnotations: List<KSAnnotation>,
    resolver: Resolver,
    mergeAnnotatedClass: KSClassDeclaration,
    contributedInterfacesInRound: Map<KSType, List<KSClassDeclaration>>,
  ): List<ClassName> {
    val scopes = mergeAnnotations.map { it.scope() }
    val contributesAnnotations = mergeAnnotations
      .flatMap { annotation ->
        classScanner
          .findContributedClasses(
            resolver = resolver,
            annotation = contributesToFqName,
            scope = annotation.scope(),
          )
      }
      .asSequence()
      .filter { clazz ->
        clazz.isInterface() && clazz.findAll(daggerModuleFqName.asString()).singleOrNull() == null
      }
      .flatMap { clazz ->
        clazz
          .findAll(contributesToFqName.asString())
          .filter { it.scope() in scopes }
      }
      .onEach { contributeAnnotation ->
        val contributedClass = contributeAnnotation.declaringClass
        if (contributedClass.getVisibility() != Visibility.PUBLIC) {
          throw KspAnvilException(
            node = contributedClass,
            message = "${contributedClass.qualifiedName?.asString()} is contributed to the Dagger graph, but the " +
              "interface is not public. Only public interfaces are supported.",
          )
        }
      }
      // Convert the sequence to a list to avoid iterating it twice. We use the result twice
      // for replaced classes and the final result.
      .toList()

    val replacedClasses = contributesAnnotations
      .flatMap { contributeAnnotation ->
        val contributedClass = contributeAnnotation.declaringClass
        contributedClass
          .atLeastOneAnnotation(
            contributeAnnotation.annotationType.resolve()
              .resolveKSClassDeclaration()!!.qualifiedName!!.asString(),
          )
          .asSequence()
          .flatMap { it.replaces() }
          .onEach { classToReplace ->
            // Verify the other class is an interface. It doesn't make sense for a contributed
            // interface to replace a class that is not an interface.
            if (!classToReplace.isInterface()) {
              throw KspAnvilException(
                node = contributedClass,
                message = "${contributedClass.qualifiedName?.asString()} wants to replace " +
                  "${classToReplace.qualifiedName?.asString()}, but the class being " +
                  "replaced is not an interface.",
              )
            }

            val contributesToOurScope = classToReplace
              .findAll(
                contributesToFqName.asString(),
                contributesBindingFqName.asString(),
                contributesMultibindingFqName.asString(),
              )
              .map { it.scope() }
              .any { scope -> scope in scopes }

            if (!contributesToOurScope) {
              throw KspAnvilException(
                node = contributedClass,
                message = "${contributedClass.qualifiedName?.asString()} with scopes " +
                  "${
                    scopes.joinToString(
                      prefix = "[",
                      postfix = "]",
                    ) { it.resolveKSClassDeclaration()?.qualifiedName?.asString()!! }
                  } " +
                  "wants to replace ${classToReplace.qualifiedName?.asString()}, but the replaced class isn't " +
                  "contributed to the same scope.",
              )
            }
          }
      }
      .map { it.toClassName() }
      .toSet()

    val excludedClasses = mergeAnnotations
      .asSequence()
      .flatMap { it.exclude() }
      .filter { it.isInterface() }
      .onEach { excludedClass ->
        // Verify that the replaced classes use the same scope.
        val contributesToOurScope = excludedClass
          .findAll(
            contributesToFqName.asString(),
            contributesBindingFqName.asString(),
            contributesMultibindingFqName.asString(),
          )
          .map { it.scope() }
          .plus(
            excludedClass.findAll(contributesSubcomponentFqName.asString())
              .map { it.parentScope() },
          )
          .any { scope -> scope in scopes }

        if (!contributesToOurScope) {
          throw KspAnvilException(
            message = "${mergeAnnotatedClass.qualifiedName?.asString()} with scopes " +
              "${
                scopes.joinToString(
                  prefix = "[",
                  postfix = "]",
                ) { it.resolveKSClassDeclaration()?.qualifiedName?.asString()!! }
              } " +
              "wants to exclude ${excludedClass.qualifiedName?.asString()}, but the excluded class isn't " +
              "contributed to the same scope.",
            node = mergeAnnotatedClass,
          )
        }
      }
      .map { it.toClassName() }
      .toList()

    if (excludedClasses.isNotEmpty()) {
      val intersect: Set<ClassName> = mergeAnnotatedClass
        .superTypesExcludingAny(resolver)
        .mapNotNull { it.resolveKSClassDeclaration()?.toClassName() }
        .toSet()
        // Need to intersect with both merged and origin types to be sure
        .intersect(
          excludedClasses.toSet(),
        )

      if (intersect.isNotEmpty()) {
        throw KspAnvilException(
          node = mergeAnnotatedClass,
          message = "${mergeAnnotatedClass.simpleName.asString()} excludes types that it implements or " +
            "extends. These types cannot be excluded. Look at all the super types to find these " +
            "classes: ${intersect.joinToString { it.canonicalName }}.",
        )
      }
    }

    val supertypesToAdd: List<ClassName> = contributesAnnotations
      .asSequence()
      .map { it.declaringClass.toClassName() }
      .plus(
        scopes.flatMap { scope ->
          contributedInterfacesInRound[scope].orEmpty()
            .map {
              val originClassName = it.toClassName()
              val isMergedType =
                it.isAnnotationPresent<MergeInterfaces>() || it.isAnnotationPresent<MergeComponent>() || it.isAnnotationPresent<MergeSubcomponent>()
              if (isMergedType) {
                originClassName.mergedClassName()
              } else {
                originClassName
              }
            }
        },
      )
      .filter { clazz ->
        clazz !in replacedClasses && clazz !in excludedClasses
      }
      .plus(
        findContributedSubcomponentParentInterfaces(
          clazz = mergeAnnotatedClass,
          scopes = scopes,
          resolver = resolver,
        ),
      )
      // Avoids an error for repeated interfaces.
      .distinct()
      .toList()

    return supertypesToAdd
  }

  private fun generateMergedClass(
    mergeAnnotatedClass: KSClassDeclaration,
    daggerAnnotation: AnnotationSpec?,
    contributedInterfaces: List<ClassName>?,
    isModule: Boolean,
  ) {
    val generatedComponentClassName = mergeAnnotatedClass.toClassName()
      .mergedClassName()
    var factoryOrBuilderFunSpec: FunSpec? = null
    val generatedComponent = TypeSpec.interfaceBuilder(
      generatedComponentClassName.simpleName,
    )
      .apply {
        if (!isModule) {
          addSuperinterface(mergeAnnotatedClass.toClassName())
        }
        daggerAnnotation?.let { addAnnotation(it) }

        // Copy over any @ContributesTo annotations to the generated type
        mergeAnnotatedClass
          .getKSAnnotationsByType(ContributesTo::class)
          .forEach {
            addAnnotation(it.toAnnotationSpec())
          }

        contributedInterfaces?.forEach { contributedInterface ->
          addSuperinterface(contributedInterface)
        }

        val componentOrFactory = mergeAnnotatedClass.declarations
          .filterIsInstance<KSClassDeclaration>()
          .singleOrNull {
            // TODO does dagger also use these names? Or are they lowercase versions of the simple class name?
            if (it.isAnnotationPresent<Component.Factory>()) {
              factoryOrBuilderFunSpec = FunSpec.builder("factory")
                .returns(generatedComponentClassName.nestedClass(it.simpleName.asString()))
                .addStatement(
                  "return Dagger${
                    mergeAnnotatedClass.simpleName.asString()
                      .capitalize()
                  }.factory()",
                )
                .build()
              return@singleOrNull true
            }
            if (it.isAnnotationPresent<Component.Builder>()) {
              factoryOrBuilderFunSpec = FunSpec.builder("builder")
                .returns(generatedComponentClassName.nestedClass(it.simpleName.asString()))
                .addStatement(
                  "return Dagger${
                    mergeAnnotatedClass.simpleName.asString()
                      .capitalize()
                  }.builder()",
                )
                .build()
              return@singleOrNull true
            }
            false
          }

        componentOrFactory?.let {
          val factoryOrBuilder = it.extendFactoryOrBuilder(
            mergeAnnotatedClass.toClassName(),
            generatedComponentClassName,
          )
          addType(factoryOrBuilder)
        }
      }
      .build()

    val spec = FileSpec.createAnvilSpec(
      generatedComponentClassName.packageName,
      generatedComponent.name!!,
    ) {
      addType(generatedComponent)
      // Generate a shim of what the normal dagger entry point would be
      factoryOrBuilderFunSpec?.let {
        addType(
          TypeSpec.objectBuilder("Dagger${mergeAnnotatedClass.simpleName.asString().capitalize()}")
            .addFunction(it)
            .build(),
        )
      }
    }

    // Aggregating because we read symbols from the classpath
    spec.writeTo(
      env.codeGenerator,
      aggregating = true,
      originatingKSFiles = listOf(mergeAnnotatedClass.containingFile!!),
    )
  }

  private inline fun Sequence<KSAnnotated>.validate(
    escape: (List<KSAnnotated>) -> Nothing,
  ): List<KSClassDeclaration> {
    val (valid, deferred) = filterIsInstance<KSClassDeclaration>().partition { annotated ->
      // TODO check error types in annotations props
      !annotated.superTypes.any { it.resolve().isError }
    }
    return if (deferred.isNotEmpty()) {
      escape(deferred)
    } else {
      valid
    }
  }

  private fun findContributedSubcomponentModules(
    declaration: KSClassDeclaration,
    scopes: List<KSType>,
    resolver: Resolver,
  ): Sequence<KSClassDeclaration> {
    return classScanner
      .findContributedClasses(
        resolver = resolver,
        annotation = contributesSubcomponentFqName,
        scope = null,
      )
      .filter { clazz ->
        clazz.find(contributesSubcomponentFqName.asString())
          .any { it.parentScope().asType(emptyList()) in scopes }
      }
      .mapNotNull { contributedSubcomponent ->
        contributedSubcomponent.classId
          .generatedAnvilSubcomponentClassId(declaration.classId)
          .createNestedClassId(Name.identifier(SUBCOMPONENT_MODULE))
          .let { classId ->
            resolver.getClassDeclarationByName(
              resolver.getKSNameFromString(
                classId.asSingleFqName()
                  .toString(),
              ),
            )
          }
      }
  }

  private fun findContributedSubcomponentParentInterfaces(
    clazz: KSClassDeclaration,
    scopes: Collection<KSType>,
    resolver: Resolver,
  ): Sequence<ClassName> {
    return classScanner
      .findContributedClasses(
        resolver = resolver,
        annotation = contributesSubcomponentFqName,
        scope = null,
      )
      .filter {
        it.atLeastOneAnnotation(contributesSubcomponentFqName.asString()).single()
          .parentScope().asType(emptyList()) in scopes
      }
      .mapNotNull { contributedSubcomponent ->
        contributedSubcomponent.classId
          .generatedAnvilSubcomponentClassId(clazz.classId)
          .createNestedClassId(Name.identifier(PARENT_COMPONENT))
          .let { classId ->
            resolver.getClassDeclarationByName(
              resolver.getKSNameFromString(
                classId.asSingleFqName()
                  .toString(),
              ),
            )
          }
      }
      .map { it.toClassName() }
  }
}

private fun checkSameScope(
  contributedClass: KSClassDeclaration,
  classToReplace: KSClassDeclaration,
  scopes: List<KSType>,
) {
  val contributesToOurScope = classToReplace
    .findAll(
      contributesToFqName.asString(),
      contributesBindingFqName.asString(),
      contributesMultibindingFqName.asString(),
    )
    .map { it.scope() }
    .any { scope -> scope in scopes }

  if (!contributesToOurScope) {
    val origin = contributedClass.originClass()
    throw KspAnvilException(
      node = origin,
      message = "${origin.qualifiedName?.asString()} with scopes " +
        "${
          scopes.joinToString(
            prefix = "[",
            postfix = "]",
          ) { it.resolveKSClassDeclaration()?.qualifiedName?.asString()!! }
        } " +
        "wants to replace ${classToReplace.qualifiedName?.asString()}, but the replaced class isn't " +
        "contributed to the same scope.",
    )
  }
}

private fun KSClassDeclaration.originClass(): KSClassDeclaration {
  val originClassValue = find(internalBindingMarkerFqName.asString())
    .singleOrNull()
    ?.argumentAt("originClass")
    ?.value

  val originClass = (originClassValue as? KSType?)?.resolveKSClassDeclaration()
  return originClass ?: this
}

private fun KSAnnotation.originClass(): KSClassDeclaration? {
  val originClassValue = argumentAt("originClass")
    ?.value

  val originClass = (originClassValue as? KSType?)?.resolveKSClassDeclaration()
  return originClass
}

private val KSAnnotation.daggerAnnotationClassName: ClassName
  get() = when (annotationType.resolve().toClassName()) {
    mergeComponentClassName -> daggerComponentClassName
    mergeSubcomponentClassName -> daggerSubcomponentClassName
    mergeModulesClassName -> daggerModuleClassName
    else -> throw NotImplementedError("Don't know how to handle $this.")
  }

private fun KSClassDeclaration.extendFactoryOrBuilder(
  originalComponentClassName: ClassName,
  generatedComponentClassName: ClassName,
): TypeSpec {
  val name = simpleName.asString()
  val newClassName = generatedComponentClassName.nestedClass(name)
  val thisType = asType(emptyList())
  val builder = when (classKind) {
    ClassKind.INTERFACE -> TypeSpec.interfaceBuilder(name)
      .superclass(toClassName())
    ClassKind.CLASS -> TypeSpec.classBuilder(name)
      .addSuperinterface(toClassName())
    ClassKind.ENUM_CLASS,
    ClassKind.ENUM_ENTRY,
    ClassKind.OBJECT,
    ClassKind.ANNOTATION_CLASS,
    -> throw KspAnvilException(
      node = this,
      message = "Unsupported class kind: $classKind",
    )
  }
  return builder
    .addAnnotations(annotations.map { it.toAnnotationSpec() }.asIterable())
    .apply {
      for (function in getDeclaredFunctions()) {
        // Only add the function we need to override and update the type
        if (function.isAbstract) {
          val returnType = function.returnTypeOrNull()
          if (returnType == thisType) {
            // Handles fluent builder functions
            addFunction(
              function.toFunSpec().toBuilder()
                .addModifiers(KModifier.OVERRIDE)
                .returns(newClassName)
                .build(),
            )
          } else if (returnType?.toClassName() == originalComponentClassName) {
            // Handles functions that return the Component class, such as
            // factory create() or builder build()
            addFunction(
              function.toFunSpec().toBuilder()
                .addModifiers(KModifier.OVERRIDE)
                .returns(generatedComponentClassName)
                .build(),
            )
          }
        }
      }
    }
    .build()
}

private fun Sequence<KSClassDeclaration>.resolveMergedTypes(
  resolver: Resolver,
): Sequence<KSClassDeclaration> {
  return map { it.resolveMergedType(resolver) }
}

private fun KSClassDeclaration.resolveMergedType(resolver: Resolver): KSClassDeclaration {
  val isMergedType = hasAnnotation(mergeModulesFqName.asString()) ||
    hasAnnotation(mergeInterfacesFqName.asString()) ||
    hasAnnotation(mergeComponentFqName.asString()) ||
    hasAnnotation(mergeSubcomponentFqName.asString())
  return if (isMergedType) {
    resolver.getClassDeclarationByName(
      toClassName().mergedClassName().canonicalName,
    ) ?: throw KspAnvilException(
      message = "Could not find merged module/interface for ${qualifiedName?.asString()}",
      node = this,
    )
  } else {
    this
  }
}
