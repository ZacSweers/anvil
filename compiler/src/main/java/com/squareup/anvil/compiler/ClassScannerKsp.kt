package com.squareup.anvil.compiler

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.anvil.compiler.ClassScannerKsp.GeneratedProperty.ReferenceProperty
import com.squareup.anvil.compiler.ClassScannerKsp.GeneratedProperty.ScopeProperty
import com.squareup.anvil.compiler.api.AnvilCompilationException
import com.squareup.anvil.compiler.codegen.ksp.KSCallable
import com.squareup.anvil.compiler.codegen.ksp.KspAnvilException
import com.squareup.anvil.compiler.codegen.ksp.KspTracer
import com.squareup.anvil.compiler.codegen.ksp.contextualToClassName
import com.squareup.anvil.compiler.codegen.ksp.fqName
import com.squareup.anvil.compiler.codegen.ksp.getAllCallables
import com.squareup.anvil.compiler.codegen.ksp.isAbstract
import com.squareup.anvil.compiler.codegen.ksp.isInterface
import com.squareup.anvil.compiler.codegen.ksp.resolvableAnnotations
import com.squareup.anvil.compiler.codegen.ksp.resolveKSClassDeclaration
import com.squareup.anvil.compiler.codegen.ksp.scope
import com.squareup.anvil.compiler.codegen.ksp.trace
import com.squareup.anvil.compiler.codegen.ksp.type
import org.jetbrains.kotlin.name.FqName

internal class ClassScannerKsp(
  tracer: KspTracer,
) : KspTracer by tracer {
  private val generatedPropertyCache =
    RecordingCache<FqName, Collection<List<GeneratedProperty>>>("Generated Property")

  private val parentComponentCache = RecordingCache<FqName, FqName?>("ParentComponent")

  private val overridableParentComponentCallableCache =
    RecordingCache<FqName, List<KSCallable.CacheEntry>>("Overridable ParentComponent Callable")

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

  private var hintCacheWarmer: (() -> Unit)? = null
  private val _hintCache = mutableMapOf<String, List<GeneratedProperty>>()
  private val hintCache: MutableMap<String, List<GeneratedProperty>>
    get() {
      hintCacheWarmer?.invoke()
      hintCacheWarmer = null
      return _hintCache
    }
  private var roundStarted = false

  @OptIn(KspExperimental::class)
  fun startRound(resolver: Resolver) {
    if (roundStarted) return
    roundStarted = true
    hintCacheWarmer = {
      _hintCache += trace("Warming hint cache") {
        resolver.getDeclarationsFromPackage(HINT_PACKAGE)
          .filterIsInstance<KSPropertyDeclaration>()
          .mapNotNull(GeneratedProperty::from)
          .groupBy(GeneratedProperty::baseName)
      }
    }
  }

  fun endRound() {
    _hintCache.clear()
    hintCacheWarmer = null
    roundStarted = false
    log(generatedPropertyCache.statsString())
    generatedPropertyCache.clear()
  }

  private fun getGeneratedProperties(
    annotation: FqName,
  ): Collection<List<GeneratedProperty>> {
    // Don't use getOrPut so we can skip the intermediate re-materialization step when we have a miss
    return if (annotation in generatedPropertyCache) {
      generatedPropertyCache.hit()
      trace("Materializing property groups cache hit for ${annotation.shortName().asString()}") {
        generatedPropertyCache.getValue(annotation)
      }
    } else {
      generatedPropertyCache.miss()
      trace("Computing property groups for ${annotation.shortName().asString()}") {
        // TODO can we optimize this further?
        hintCache.values
      }
    }
  }

  /**
   * Returns a sequence of contributed classes from the dependency graph. Note that the result
   * includes inner classes already.
   */
  fun findContributedClasses(
    annotation: FqName,
    scope: KSType?,
  ): Sequence<KSClassDeclaration> {
    val propertyGroups: Collection<List<GeneratedProperty>> = getGeneratedProperties(annotation)

    return trace("Processing contributed classes for ${annotation.shortName().asString()}") {
      propertyGroups
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
          // a safety net in case the generated properties are out of sync.
          clazz.resolvableAnnotations.any {
            it.annotationType
              .contextualToClassName().fqName == annotation && (scope == null || it.scope() == scope)
          }
        }
        .onEach { clazz ->
          if (clazz.origin == Origin.KOTLIN_LIB || clazz.origin == Origin.JAVA_LIB) {
            externalContributions.add(clazz.fqName)
          }
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

  /**
   * Finds the applicable parent component interface (if any) contributed to this component.
   */
  fun findParentComponentInterface(
    resolver: Resolver,
    componentClass: KSClassDeclaration,
    creatorClass: KSClassDeclaration?,
    parentScopeType: KSType?,
  ): KSClassDeclaration? = trace(
    "Finding parent component interface for ${componentClass.simpleName.asString()}",
  ) {
    val fqName = componentClass.fqName

    // Can't use getOrPut because it doesn't differentiate between absent and null
    if (fqName in parentComponentCache) {
      parentComponentCache.hit()
      return parentComponentCache[fqName]?.let { resolver.getClassDeclarationByName(it.asString()) }
    } else {
      parentComponentCache.miss()
    }

    val contributedInnerComponentInterfaces = componentClass
      .declarations
      .filterIsInstance<KSClassDeclaration>()
      .filter(KSClassDeclaration::isInterface)
      .filter { nestedClass ->
        nestedClass.resolvableAnnotations
          .any {
            it.fqName == contributesToFqName && (if (parentScopeType != null) it.scope() == parentScopeType else true)
          }
      }
      .toList()

    val componentInterface = when (contributedInnerComponentInterfaces.size) {
      0 -> {
        parentComponentCache[fqName] = null
        return null
      }
      1 -> contributedInnerComponentInterfaces[0]
      else -> throw KspAnvilException(
        node = componentClass,
        message = "Expected zero or one parent component interface within " +
          "${componentClass.fqName} being contributed to the parent scope.",
      )
    }

    val callables = trace("Finding overridable parent component callables") {
      overridableParentComponentCallables(
        resolver,
        componentInterface,
        componentClass.fqName,
        creatorClass?.fqName,
      )
    }

    when (callables.count()) {
      0 -> {
        parentComponentCache[fqName] = null
        return null
      }
      1 -> {
        // This is ok
      }
      else -> throw KspAnvilException(
        node = componentClass,
        message = "Expected zero or one function returning the " +
          "subcomponent ${componentClass.fqName}.",
      )
    }

    parentComponentCache[fqName] = componentInterface.fqName
    return componentInterface
  }

  /**
   * Returns a list of overridable parent component callables from a given [parentComponent]
   * for the given [targetReturnType]. This can include both functions and properties.
   */
  fun overridableParentComponentCallables(
    resolver: Resolver,
    parentComponent: KSClassDeclaration,
    targetReturnType: FqName,
    creatorClass: FqName?,
  ): List<KSCallable> {
    val fqName = parentComponent.fqName

    // Can't use getOrPut because it doesn't differentiate between absent and null
    if (fqName in overridableParentComponentCallableCache) {
      overridableParentComponentCallableCache.hit()
      return overridableParentComponentCallableCache.getValue(
        fqName,
      ).map { it.materialize(resolver) }
    } else {
      overridableParentComponentCallableCache.miss()
    }

    return parentComponent.getAllCallables()
      .filter { it.isAbstract && it.getVisibility() == Visibility.PUBLIC }
      .filter {
        val type = it.type?.resolveKSClassDeclaration()?.fqName ?: return@filter false
        type == targetReturnType || (creatorClass != null && type == creatorClass)
      }
      .toList()
      .also {
        overridableParentComponentCallableCache[fqName] = it.map(KSCallable::toCacheEntry)
      }
  }

  fun dumpStats() {
    log(parentComponentCache.statsString())
    log(overridableParentComponentCallableCache.statsString())
  }
}
