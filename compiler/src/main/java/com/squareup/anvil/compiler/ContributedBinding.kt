package com.squareup.anvil.compiler

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.squareup.anvil.compiler.api.AnvilCompilationException
import com.squareup.anvil.compiler.codegen.ksp.KspAnvilException
import com.squareup.anvil.compiler.codegen.ksp.resolveKSClassDeclaration
import com.squareup.anvil.compiler.codegen.reference.AnvilCompilationExceptionClassReferenceIr
import com.squareup.anvil.compiler.codegen.reference.ClassReferenceIr
import com.squareup.anvil.compiler.internal.reference.AnnotationReference
import com.squareup.anvil.compiler.internal.reference.ClassReference
import com.squareup.anvil.compiler.internal.reference.ClassReference.Descriptor
import com.squareup.anvil.compiler.internal.reference.ClassReference.Psi
import com.squareup.anvil.compiler.internal.requireFqName
import com.squareup.kotlinpoet.ClassName
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.KotlinType

private typealias Scope = ClassName
private typealias BindingModule = ClassName
private typealias OriginClass = ClassName

internal data class BindingKey<T>(
  val scope: Scope,
  val boundType: T,
  val qualifierKey: String,
)

/**
 * A data structure for organizing all contributed bindings with layering down through scopes and
 * binding keys.
 *
 * ```
 *
 *   Contributed Bindings
 *
 *     ┌──────────────────────────────────────────┐
 *     │                                          │
 *     │  Scope                                   │
 *     │                                          │
 *     │   ┌──────────────────────────────────┐   │
 *     │   │                                  │   │
 *     │   │  BindingKey (type + qualifier)   │   │
 *     │   │                                  │   │
 *     │   │    ┌────────────────────────┐    │   │
 *     │   │    │                        │    │   │
 *     │   │    │  Prioritized bindings  │    │   │
 *     │   │    │                        │    │   │
 *     │   │    └────────────────────────┘    │   │
 *     │   │                                  │   │
 *     │   │                                  │   │
 *     │   └──────────────────────────────────┘   │
 *     │                                          │
 *     │                                          │
 *     └──────────────────────────────────────────┘
 *
 *     ┌──────────────────────────────────────────┐
 *     │                                          │
 *     │  Scope2                                  │
 *     │                                          │
 *     │   ┌──────────────────────────────────┐   │
 *     │   │                                  │   │
 *     │   │  BindingKey (type + qualifier)   │   │
 *     │   │                                  │   │
 *     │   │    ┌────────────────────────┐    │   │
 *     │   │    │                        │    │   │
 *     │   │    │  Prioritized bindings  │    │   │
 *     │   │    │                        │    │   │
 *     │   │    └────────────────────────┘    │   │
 *     │   │                                  │   │
 *     │   │                                  │   │
 *     │   └──────────────────────────────────┘   │
 *     │                                          │
 *     │                                          │
 *     └──────────────────────────────────────────┘
 * ```
 */
internal data class ContributedBindings(
  val bindings: Map<Scope, Map<BindingKey<*>, List<ContributedBinding<*>>>>,
) {
  companion object {
    fun from(
      bindings: List<ContributedBinding<*>>,
    ): ContributedBindings {
      val groupedByScope = bindings.groupBy { it.scope }
      val groupedByScopeAndKey = groupedByScope.mapValues { (_, bindings) ->
        bindings.groupBy { it.bindingKey }
          .mapValues innerMapValues@{ (_, bindings) ->
            if (bindings.size < 2) return@innerMapValues bindings
            val (multiBindings, bindingsOnly) = bindings.partition { it.isMultibinding }
            if (bindingsOnly.size < 2) return@innerMapValues bindings
            val highestPriorityBinding = bindingsOnly.findHighestPriorityBinding()
            multiBindings + listOf(highestPriorityBinding)
          }
      }

      return ContributedBindings(groupedByScopeAndKey)
    }
  }
}

internal data class ContributedBinding<T>(
  val scope: Scope,
  val isMultibinding: Boolean,
  val bindingModule: BindingModule,
  val originClass: OriginClass,
  val boundType: T,
  val qualifierKey: String,
  val rank: Int,
  val replaces: List<ClassName>,
) {
  val bindingKey = BindingKey(scope, boundType, qualifierKey)
}

internal fun List<ContributedBinding<*>>.findHighestPriorityBinding(): ContributedBinding<*> {
  if (size == 1) return this[0]

  val bindings = groupBy { it.rank }
    .toSortedMap()
    .let { it.getValue(it.lastKey()) }
    .distinctBy { it.originClass }

  if (bindings.size > 1) {
    val rankName = bindings[0].rank.toString()
    val boundType = bindings[0].boundType
    fun message(boundTypeName: String?): String {
      return "There are multiple contributed bindings with the same bound type and rank. The bound type is " +
        "$boundTypeName. The rank is $rankName. The contributed binding classes are: " +
        bindings.joinToString(
          prefix = "[",
          postfix = "]",
        ) { it.originClass.canonicalName }
    }

    when (boundType) {
      is ClassReferenceIr -> {
        throw AnvilCompilationExceptionClassReferenceIr(
          boundType,
          message(boundType.fqName.asString()),
        )
      }
      is KSTypeReference -> {
        throw KspAnvilException(
          node = boundType,
          message = message(
            boundType.resolve().resolveKSClassDeclaration()?.qualifiedName?.asString(),
          ),
        )
      }
      else -> error("Not possible")
    }
  }

  return bindings[0]
}

private fun genericExceptionText(
  origin: String,
  boundType: String,
  typeString: String,
): String {
  return "Class $origin binds $boundType," +
    " but the bound type contains type parameter(s) $typeString." +
    " Type parameters in bindings are not supported. This binding needs" +
    " to be contributed in a Dagger module manually."
}

internal fun ClassReference.checkNotGeneric(
  contributedClass: ClassReference,
) {
  fun KotlinType.describeTypeParameters(): String = arguments
    .ifEmpty { return "" }
    .joinToString(prefix = "<", postfix = ">") { typeArgument ->
      typeArgument.type.toString() + typeArgument.type.describeTypeParameters()
    }

  when (this) {
    is Descriptor -> {
      if (clazz.declaredTypeParameters.isNotEmpty()) {

        throw AnvilCompilationException(
          classDescriptor = clazz,
          message = genericExceptionText(
            contributedClass.fqName.asString(),
            clazz.fqNameSafe.asString(),
            clazz.defaultType.describeTypeParameters(),
          ),
        )
      }
    }
    is Psi -> {
      if (clazz.typeParameters.isNotEmpty()) {
        val typeString = clazz.typeParameters
          .joinToString(prefix = "<", postfix = ">") { it.name!! }

        throw AnvilCompilationException(
          message = genericExceptionText(
            contributedClass.fqName.asString(),
            clazz.requireFqName().asString(),
            typeString,
          ),
          element = clazz.nameIdentifier,
        )
      }
    }
  }
}

internal fun AnnotationReference.qualifierKey(): String {
  return fqName.asString() +
    arguments.joinToString(separator = "") { argument ->
      val valueString = when (val value = argument.value<Any>()) {
        is ClassReference -> value.fqName.asString()
        // TODO what if it's another annotation?
        else -> value.toString()
      }

      argument.resolvedName + valueString
    }
}

internal fun KSClassDeclaration.checkNotGeneric(
  contributedClass: KSClassDeclaration,
) {
  if (typeParameters.isNotEmpty()) {
    val typeString = typeParameters
      .joinToString(prefix = "<", postfix = ">") { it.name.asString() }

    throw KspAnvilException(
      message = genericExceptionText(
        contributedClass.qualifiedName!!.asString(),
        qualifiedName!!.asString(),
        typeString,
      ),
      node = contributedClass,
    )
  }
}

internal fun KSAnnotation.qualifierKey(): String {
  return shortName.asString() +
    arguments.joinToString(separator = "") { argument ->
      val valueString = when (val value = argument.value) {
        is KSType -> value.resolveKSClassDeclaration()!!.qualifiedName!!.asString()
        // TODO what if it's another annotation?
        else -> value.toString()
      }

      argument.name!!.asString() + valueString
    }
}
