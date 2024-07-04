package com.squareup.anvil.compiler.internal.testing

import com.squareup.anvil.annotations.ExperimentalAnvilApi
import com.squareup.anvil.annotations.MergeComponent
import com.squareup.anvil.annotations.MergeSubcomponent
import com.squareup.anvil.annotations.compat.MergeModules
import com.squareup.anvil.compiler.internal.capitalize
import kotlin.reflect.KClass

@ExperimentalAnvilApi
public interface AnyDaggerComponent {
  public val modules: List<KClass<*>>
  public val dependencies: List<KClass<*>>
}

@ExperimentalAnvilApi
public fun Class<*>.anyDaggerComponent(annotationClass: KClass<*>): AnyDaggerComponent {
  val classToCheck = generatedMergedComponent() ?: this
  return when (annotationClass) {
    MergeComponent::class -> object : AnyDaggerComponent {
      override val modules: List<KClass<*>> = classToCheck.daggerComponent.modules.toList()
      override val dependencies: List<KClass<*>> =
        classToCheck.daggerComponent.dependencies.toList()
    }
    MergeSubcomponent::class -> object : AnyDaggerComponent {
      override val modules: List<KClass<*>> = classToCheck.daggerSubcomponent.modules.toList()
      override val dependencies: List<KClass<*>> get() = throw IllegalAccessException()
    }
    MergeModules::class -> object : AnyDaggerComponent {
      override val modules: List<KClass<*>> = classToCheck.daggerModule.includes.toList()
      override val dependencies: List<KClass<*>> get() = throw IllegalAccessException()
    }
    else -> throw IllegalArgumentException("Cannot handle $annotationClass")
  }
}

/**
 * If there's a generated merged component, returns that [Class]. This would imply that this was
 * generated under KSP.
 */
@ExperimentalAnvilApi
public fun Class<*>.generatedMergedComponent(): Class<*>? {
  return try {
    classLoader.loadClass(packageName() + "Anvil" + simpleName.capitalize())
  } catch (e: ClassNotFoundException) {
    null
  }
}
