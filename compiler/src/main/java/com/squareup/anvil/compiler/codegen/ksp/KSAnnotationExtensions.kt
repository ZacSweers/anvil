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
import com.squareup.kotlinpoet.ksp.toClassName
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
      duplicates.keys.joinToString(prefix = "[", postfix = "]") { it.toClassName().simpleName }

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

internal fun KSAnnotation.scope(): KSType =
  scopeOrNull()
    ?: throw KspAnvilException(
      message = "Couldn't find scope for ${annotationType.resolve().declaration.qualifiedName?.asString()}.",
      this,
    )

internal fun KSAnnotation.scopeOrNull(): KSType? {
  return argumentAt("scope")?.value as? KSType?
}

internal fun KSAnnotation.boundTypeOrNull(): KSType? = argumentAt("boundType")?.value as? KSType?

internal fun KSAnnotation.resolveBoundType(
  resolver: Resolver,
  declaringClass: KSClassDeclaration,
): KSClassDeclaration {
  val declaredBoundType = boundTypeOrNull()?.resolveKSClassDeclaration()
  if (declaredBoundType != null) return declaredBoundType
  // Resolve from the first and only supertype
  return declaringClass.superTypesExcludingAny(resolver)
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

@Suppress("UNCHECKED_CAST")
private fun KSAnnotation.classArrayArgument(name: String): List<KSClassDeclaration> =
  (argumentAt(name)?.value as? List<KSType>).orEmpty().map {
    it.resolveKSClassDeclaration() ?: throw KspAnvilException("Could not resolve $name $it", this)
  }

internal fun KSAnnotation.parentScope(): KSClassDeclaration {
  return (
    argumentAt("parentScope")
      ?.value as? KSType
    )?.resolveKSClassDeclaration()
    ?: throw KspAnvilException(
      message = "Couldn't find parentScope for $shortName.",
      node = this,
    )
}

internal fun KSAnnotation.argumentAt(
  name: String,
): KSValueArgument? {
  arguments
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
  annotations.singleOrNull { it.isQualifier() }

internal fun KSAnnotation.ignoreQualifier(): Boolean =
  argumentAt("ignoreQualifier")?.value as? Boolean? == true

internal fun KSAnnotation.rank(): Int {
  return argumentAt("rank")?.value as Int?
    ?: priorityLegacy()
    ?: ContributesBinding.RANK_NORMAL
}

@Suppress("DEPRECATION")
internal fun KSAnnotation.priorityLegacy(): Int? {
  val priorityEntry = argumentAt("priority")?.value as KSType? ?: return null
  val name = priorityEntry.resolveKSClassDeclaration()?.simpleName?.asString() ?: return null
  val priority = ContributesBinding.Priority.valueOf(name)
  return priority.value
}
