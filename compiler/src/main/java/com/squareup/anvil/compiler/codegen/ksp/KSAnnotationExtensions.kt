@file:Suppress("invisible_reference", "invisible_member")

package com.squareup.anvil.compiler.codegen.ksp

import com.google.devtools.ksp.isDefault
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueArgument
import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.compiler.internal.daggerScopeFqName
import com.squareup.anvil.compiler.internal.mapKeyFqName
import com.squareup.anvil.compiler.qualifierFqName
import com.squareup.kotlinpoet.ClassName
import org.jetbrains.kotlin.name.FqName

internal fun <T : KSAnnotation> List<T>.checkNoDuplicateScope(
  annotatedType: KSClassDeclaration,
  isContributeAnnotation: Boolean,
) {
  // Exit early to avoid allocating additional collections.
  if (size < 2) return
  if (size == 2 && this[0].scope() != this[1].scope()) return

  // Check for duplicate scopes. Multiple contributions to the same scope are forbidden.
  val duplicates = groupBy { it.scope() }.filterValues { it.size > 1 }

  if (duplicates.isNotEmpty()) {
    val annotatedClass = annotatedType.qualifiedName!!.asString()
    val duplicateScopesMessage =
      duplicates.keys.joinToString(prefix = "[", postfix = "]") {
        it.contextualToClassName(annotatedType).simpleName
      }

    throw KspAnvilException(
      message = if (isContributeAnnotation) {
        "$annotatedClass contributes multiple times to the same scope: $duplicateScopesMessage. " +
          "Contributing multiple times to the same scope is forbidden and all scopes must " +
          "be distinct."
      } else {
        "$annotatedClass merges multiple times to the same scope: $duplicateScopesMessage. " +
          "Merging multiple times to the same scope is forbidden and all scopes must " +
          "be distinct."
      },
      node = annotatedType,
    )
  }
}

internal fun <T : KSAnnotation> List<T>.checkNoDuplicateScopeAndBoundType(
  annotatedType: KSClassDeclaration,
) {
  // Exit early to avoid allocating additional collections.
  if (size < 2) return
  if (size == 2 && this[0].scope() != this[1].scope()) return

  val duplicateScopes = groupBy { it.scope() }
    .filterValues { it.size > 1 }
    .ifEmpty { return }

  duplicateScopes.values.forEach { duplicateScopeAnnotations ->
    val duplicateBoundTypes = duplicateScopeAnnotations
      .groupBy { it.boundTypeOrNull() }
      .filterValues { it.size > 1 }
      .ifEmpty { return }
      .keys

    throw KspAnvilException(
      message = "${annotatedType.qualifiedName?.asString()} contributes multiple times to " +
        "the same scope using the same bound type: " +
        duplicateBoundTypes.joinToString(prefix = "[", postfix = "]") {
          it?.declaration?.simpleName?.getShortName() ?: annotatedType.superTypes.single()
            .resolve().declaration.simpleName.getShortName()
        } +
        ". Contributing multiple times to the same scope with the same bound type is forbidden " +
        "and all scope - bound type combinations must be distinct.",
      annotatedType,
    )
  }
}

internal fun KSAnnotation.scopeClassName(): ClassName =
  classNameArgumentAt("scope")
    ?: throw KspAnvilException(
      message = "Couldn't find scope for ${annotationType.resolve().declaration.qualifiedName?.asString()}.",
      this,
    )

internal fun KSAnnotation.scope(): KSType =
  scopeOrNull()
    ?: throw KspAnvilException(
      message = "Couldn't find scope for ${annotationType.resolve().declaration.qualifiedName?.asString()}.",
      this,
    )

internal fun KSAnnotation.scopeOrNull(): KSType? {
  return argumentOfTypeAt<KSType>("scope")
}

internal fun KSAnnotation.boundTypeOrNull(): KSType? = argumentOfTypeAt<KSType>("boundType")

internal fun KSAnnotation.resolveBoundType(
  resolver: Resolver,
  declaringClass: KSClassDeclaration,
): KSClassDeclaration {
  val declaredBoundType = boundTypeOrNull()?.resolveKSClassDeclaration()
  if (declaredBoundType != null) return declaredBoundType
  // Resolve from the first and only supertype
  return declaringClass.superTypesExcludingAny(resolver, shallow = true)
    .single()
    .resolveKSClassDeclaration() ?: throw KspAnvilException(
    message = "Couldn't resolve bound type for ${declaringClass.qualifiedName}",
    node = declaringClass,
  )
}

internal fun KSAnnotation.replaces(): List<KSClassDeclaration> = classArrayArgument("replaces")

internal fun KSAnnotation.subcomponents(): List<KSClassDeclaration> = classArrayArgument(
  "subcomponents",
)

internal fun KSAnnotation.exclude(): List<KSClassDeclaration> = classArrayArgument("exclude")

internal fun KSAnnotation.modules(): List<KSClassDeclaration> = classArrayArgument("modules")

internal fun KSAnnotation.includes(): List<KSClassDeclaration> = classArrayArgument("includes")

private fun KSAnnotation.classArrayArgument(name: String): List<KSClassDeclaration> =
  argumentOfTypeWithMapperAt<List<KSType>, List<KSClassDeclaration>>(
    name,
  ) { arg, value ->
    value.map {
      it.resolveKSClassDeclaration()
        ?: throw KspAnvilException("Could not resolve $name $it", arg)
    }
  }.orEmpty()

internal fun KSAnnotation.parentScope(): KSClassDeclaration {
  return argumentOfTypeAt<KSType>("parentScope")
    ?.resolveKSClassDeclaration()
    ?: throw KspAnvilException(
      message = "Couldn't find parentScope for $shortName.",
      node = this,
    )
}

internal fun KSAnnotation.classNameArrayArgumentAt(
  name: String,
): List<ClassName>? {
  return argumentOfTypeWithMapperAt<List<KSType>, List<ClassName>>(name) { arg, value ->
    value.map { it.contextualToClassName(arg) }
  }
}

internal fun KSAnnotation.classNameArgumentAt(
  name: String,
): ClassName? {
  return argumentOfTypeWithMapperAt<KSType, ClassName>(name) { arg, value ->
    value.contextualToClassName(arg)
  }
}

internal inline fun <reified T> KSAnnotation.argumentOfTypeAt(
  name: String,
): T? {
  return argumentOfTypeWithMapperAt<T, T>(name) { _, value ->
    value
  }
}

private inline fun <reified T, R> KSAnnotation.argumentOfTypeWithMapperAt(
  name: String,
  mapper: (arg: KSValueArgument, value: T) -> R,
): R? {
  return argumentAt(name)
    ?.let { arg ->
      val value = arg.value
      if (value !is T) {
        throw KspAnvilException(
          message = "Expected argument '$name' of type '${T::class.qualifiedName} but was '${arg.javaClass.name}'.",
          node = arg,
        )
      } else {
        value?.let { mapper(arg, it) }
      }
    }
}

internal fun KSAnnotation.argumentAt(
  name: String,
): KSValueArgument? {
  return arguments.find { it.name?.asString() == name }
    ?.takeUnless { it.isDefault() }
}

private fun KSAnnotation.isTypeAnnotatedWith(
  annotationFqName: FqName,
): Boolean = annotationType.resolve()
  .declaration
  .isAnnotationPresent(annotationFqName.asString())

internal fun KSAnnotation.isQualifier(): Boolean = isTypeAnnotatedWith(qualifierFqName)
internal fun KSAnnotation.isMapKey(): Boolean = isTypeAnnotatedWith(mapKeyFqName)
internal fun KSAnnotation.isDaggerScope(): Boolean = isTypeAnnotatedWith(daggerScopeFqName)

internal fun KSAnnotated.qualifierAnnotation(): KSAnnotation? =
  resolvableAnnotations.singleOrNull { it.isQualifier() }

internal fun KSAnnotation.ignoreQualifier(): Boolean =
  argumentOfTypeAt<Boolean>("ignoreQualifier") == true

internal fun KSAnnotation.rank(): Int {
  return argumentOfTypeAt<Int>("rank")
    ?: priorityLegacy()
    ?: ContributesBinding.RANK_NORMAL
}

@Suppress("DEPRECATION")
internal fun KSAnnotation.priorityLegacy(): Int? {
  val priorityEntry = argumentOfTypeAt<KSType>("priority") ?: return null
  val name = priorityEntry.resolveKSClassDeclaration()?.simpleName?.asString() ?: return null
  val priority = ContributesBinding.Priority.valueOf(name)
  return priority.value
}
