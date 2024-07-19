package com.squareup.anvil.compiler

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.anvil.compiler.ClassScannerKsp.GeneratedProperty.ReferenceProperty
import com.squareup.anvil.compiler.ClassScannerKsp.GeneratedProperty.ScopeProperty
import com.squareup.anvil.compiler.api.AnvilCompilationException
import com.squareup.anvil.compiler.codegen.ksp.KspAnvilException
import com.squareup.anvil.compiler.codegen.ksp.fqName
import com.squareup.anvil.compiler.codegen.ksp.isInterface
import com.squareup.anvil.compiler.codegen.ksp.resolveKSClassDeclaration
import com.squareup.anvil.compiler.codegen.ksp.returnTypeOrNull
import com.squareup.anvil.compiler.codegen.ksp.scope
import com.squareup.kotlinpoet.ksp.toClassName
import org.jetbrains.kotlin.name.FqName

internal class ClassScannerKsp {

  private val generatedPropertyCache = mutableMapOf<CacheKey, Collection<List<GeneratedProperty>>>()
  private val parentComponentCache = mutableMapOf<FqName, KSClassDeclaration?>()
  private val overridableParentComponentFunctionCache =
    mutableMapOf<FqName, List<KSFunctionDeclaration>>()

  /**
   * Externally-contributed contributions, which are important to track so that we don't try to
   * add originating files for them when generating code.
   */
  private val externalContributions = mutableSetOf<FqName>()

  fun isExternallyContributed(declaration: KSClassDeclaration): Boolean {
    return declaration.fqName in externalContributions
  }

  private fun KSTypeReference.resolveKClassType(): KSType {
    return resolve()
      .arguments.single().type!!.resolve()
  }

  /**
   * Returns a sequence of contributed classes from the dependency graph. Note that the result
   * includes inner classes already.
   */
  @OptIn(KspExperimental::class)
  fun findContributedClasses(
    resolver: Resolver,
    annotation: FqName,
    scope: KSType?,
  ): Sequence<KSClassDeclaration> {
    val propertyGroups: Collection<List<GeneratedProperty>> =
      generatedPropertyCache.getOrPut(CacheKey(annotation, resolver.hashCode())) {
        resolver.getDeclarationsFromPackage(HINT_PACKAGE)
          .filterIsInstance<KSPropertyDeclaration>()
          .mapNotNull { GeneratedProperty.from(it) }
          .groupBy { property -> property.baseName }
          .values
      }

    return propertyGroups
      .asSequence()
      .mapNotNull { properties ->
        val reference = properties.filterIsInstance<ReferenceProperty>()
          // In some rare cases we can see a generated property for the same identifier.
          // Filter them just in case, see https://github.com/square/anvil/issues/460 and
          // https://github.com/square/anvil/issues/565
          .distinctBy { it.baseName }
          .singleOrEmpty()
          ?: throw AnvilCompilationException(
            message = "Couldn't find the reference for a generated hint: ${properties[0].baseName}.",
          )

        val scopes = properties.filterIsInstance<ScopeProperty>()
          .ifEmpty {
            throw AnvilCompilationException(
              message = "Couldn't find any scope for a generated hint: ${properties[0].baseName}.",
            )
          }
          .map {
            it.declaration.type.resolveKClassType()
          }

        // Look for the right scope even before resolving the class and resolving all its super
        // types.
        if (scope != null && scope !in scopes) return@mapNotNull null

        reference.declaration.type
          .resolveKClassType()
          .resolveKSClassDeclaration()
      }
      .filter { clazz ->
        // Check that the annotation really is present. It should always be the case, but it's
        // a safetynet in case the generated properties are out of sync.
        clazz.annotations.any {
          it.annotationType.resolve()
            .toClassName().fqName == annotation && (scope == null || it.scope() == scope)
        }
      }
      .onEach { clazz ->
        if (clazz.origin == Origin.KOTLIN_LIB || clazz.origin == Origin.JAVA_LIB) {
          externalContributions.add(clazz.fqName)
        }
      }
  }

  private sealed class GeneratedProperty(
    val declaration: KSPropertyDeclaration,
    val baseName: String,
  ) {
    class ReferenceProperty(
      declaration: KSPropertyDeclaration,
      baseName: String,
    ) : GeneratedProperty(declaration, baseName)

    class ScopeProperty(
      declaration: KSPropertyDeclaration,
      baseName: String,
    ) : GeneratedProperty(declaration, baseName)

    companion object {
      fun from(declaration: KSPropertyDeclaration): GeneratedProperty? {
        // For each contributed hint there are several properties, e.g. the reference itself
        // and the scopes. Group them by their common name without the suffix.
        val name = declaration.simpleName.asString()

        return when {
          name.endsWith(REFERENCE_SUFFIX) ->
            ReferenceProperty(declaration, name.substringBeforeLast(REFERENCE_SUFFIX))
          name.contains(SCOPE_SUFFIX) -> {
            // The old scope hint didn't have a number. Now that there can be multiple scopes
            // we append a number for all scopes, but we still need to support the old format.
            val indexString = name.substringAfterLast(SCOPE_SUFFIX)
            if (indexString.toIntOrNull() != null || indexString.isEmpty()) {
              ScopeProperty(declaration, name.substringBeforeLast(SCOPE_SUFFIX))
            } else {
              null
            }
          }
          else -> null
        }
      }
    }
  }

  private data class CacheKey(
    val fqName: FqName,
    val resolverHash: Int,
  )

  /**
   * Finds the applicable parent component interface (if any) contributed to this component.
   */
  fun findParentComponentInterface(
    componentClass: KSClassDeclaration,
    factoryClass: KSClassDeclaration?,
    parentScopeType: KSType?,
  ): KSClassDeclaration? {
    val fqName = componentClass.fqName

    // Can't use getOrPut because it doesn't differentiate between absent and null
    if (fqName in parentComponentCache) {
      return parentComponentCache[fqName]
    }

    val contributedInnerComponentInterfaces = componentClass
      .declarations
      .filterIsInstance<KSClassDeclaration>()
      .filter(KSClassDeclaration::isInterface)
      .filter { nestedClass ->
        nestedClass.annotations
          .any {
            it.fqName == contributesToFqName && (if (parentScopeType != null) it.scope() == parentScopeType else true)
          }
      }
      .toList()

    val componentInterface = when (contributedInnerComponentInterfaces.size) {
      0 -> return null
      1 -> contributedInnerComponentInterfaces[0]
      else -> throw KspAnvilException(
        node = componentClass,
        message = "Expected zero or one parent component interface within " +
          "${componentClass.fqName} being contributed to the parent scope.",
      )
    }

    val functions = overridableParentComponentFunctions(
      componentInterface,
      componentClass.fqName,
      factoryClass?.fqName,
    )

    when (functions.count()) {
      0 -> return null
      1 -> {
        // This is ok
      }
      else -> throw KspAnvilException(
        node = componentClass,
        message = "Expected zero or one function returning the " +
          "subcomponent ${componentClass.fqName}.",
      )
    }

    parentComponentCache[fqName] = componentInterface
    return componentInterface
  }

  /**
   * Returns a list of overridable parent component functions from a given [parentComponent]
   * for the given [targetReturnType].
   */
  fun overridableParentComponentFunctions(
    parentComponent: KSClassDeclaration,
    targetReturnType: FqName,
    factoryClass: FqName?,
  ): List<KSFunctionDeclaration> {
    val fqName = parentComponent.fqName

    // Can't use getOrPut because it doesn't differentiate between absent and null
    if (fqName in overridableParentComponentFunctionCache) {
      return overridableParentComponentFunctionCache.getValue(fqName)
    }

    return parentComponent.getAllFunctions()
      .filter { it.isAbstract && it.getVisibility() == Visibility.PUBLIC }
      .filter {
        val returnType = it.returnTypeOrNull()?.resolveKSClassDeclaration() ?: return@filter false
        returnType.fqName == targetReturnType || (factoryClass != null && returnType.fqName == factoryClass)
      }
      .toList()
      .also {
        overridableParentComponentFunctionCache[fqName] = it
      }
  }
}
