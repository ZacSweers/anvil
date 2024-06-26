package com.squareup.anvil.compiler.codegen

import com.google.common.truth.Truth.assertThat
import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.internal.InternalBindingMarker
import com.squareup.anvil.compiler.assertFileGenerated
import com.squareup.anvil.compiler.bindingModuleScope
import com.squareup.anvil.compiler.bindingModuleScopes
import com.squareup.anvil.compiler.bindingOriginKClass
import com.squareup.anvil.compiler.compile
import com.squareup.anvil.compiler.contributingInterface
import com.squareup.anvil.compiler.generatedBindingModule
import com.squareup.anvil.compiler.generatedBindingModules
import com.squareup.anvil.compiler.generatedFileOrNull
import com.squareup.anvil.compiler.injectClass
import com.squareup.anvil.compiler.internal.testing.AnvilCompilationMode
import com.squareup.anvil.compiler.isError
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode.OK
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import javax.inject.Named

@Suppress("RemoveRedundantQualifierName")
@RunWith(Parameterized::class)
class ContributesBindingGeneratorTest(
  private val mode: AnvilCompilationMode,
) {

  companion object {
    @Parameterized.Parameters(name = "{0}")
    @JvmStatic
    fun modes(): Collection<Any> {
      return buildList {
        add(AnvilCompilationMode.Embedded())
        add(AnvilCompilationMode.Ksp())
      }
    }
  }

  @Test fun `there is a binding module for a contributed binding for interfaces`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding

      interface ParentInterface

      @ContributesBinding(Any::class, ParentInterface::class)
      interface ContributingInterface : ParentInterface
      """,
      mode = mode,
    ) {
      assertThat(contributingInterface.bindingOriginKClass?.java).isEqualTo(contributingInterface)
      assertThat(contributingInterface.bindingModuleScope).isEqualTo(Any::class)

      assertFileGenerated(
        mode,
        "ContributingInterfaceAsComSquareupTestParentInterfaceToKotlinAnyBindingModule.kt",
      )
    }
  }

  @Test fun `there is a binding module for a contributed binding for classes`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding

      interface ParentInterface

      @ContributesBinding(Any::class, ParentInterface::class)
      class ContributingInterface : ParentInterface
      """,
      mode = mode,
    ) {
      assertThat(contributingInterface.bindingOriginKClass?.java).isEqualTo(contributingInterface)
      assertThat(contributingInterface.bindingModuleScope).isEqualTo(Any::class)
    }
  }

  @Test
  fun `a Named annotation using a private top-level const property is inlined in the generated module`() {

    // https://github.com/square/anvil/issues/938

    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding
      import com.squareup.test.other.OTHER_CONSTANT
      import javax.inject.Inject
      import javax.inject.Named

      interface ParentInterface

      private const val CONSTANT = "${'$'}OTHER_CONSTANT.foo"

      @Named(CONSTANT)
      @ContributesBinding(Any::class)
      class InjectClass @Inject constructor() : ParentInterface
      """,
      """
      package com.squareup.test.other
      
      const val OTHER_CONSTANT = "abc"
      """.trimIndent(),
      mode = mode,
    ) {

      assertThat(exitCode).isEqualTo(OK)

      val stringKey = injectClass.generatedBindingModule.methods.single()
        .getDeclaredAnnotation(Named::class.java)

      assertThat(stringKey.value).isEqualTo("abc.foo")
    }
  }

  @Test
  fun `a Named annotation using a private object's const property is inlined in the generated module`() {

    // https://github.com/square/anvil/issues/938

    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding
      import com.squareup.test.other.OTHER_CONSTANT
      import javax.inject.Inject
      import javax.inject.Named

      private object Constants {
        const val CONSTANT = "${'$'}OTHER_CONSTANT.foo"
      }

      interface ParentInterface

      @Named(Constants.CONSTANT)
      @ContributesBinding(Any::class)
      class InjectClass @Inject constructor() : ParentInterface
      """,
      """
      package com.squareup.test.other
      
      const val OTHER_CONSTANT = "abc"
      """.trimIndent(),
      mode = mode,
    ) {

      assertThat(exitCode).isEqualTo(OK)

      val Named = injectClass.generatedBindingModule.methods.single()
        .getDeclaredAnnotation(Named::class.java)

      assertThat(Named.value).isEqualTo("abc.foo")
    }
  }

  @Test
  fun `a Named annotation using a private companion object's const property is inlined in the generated module`() {

    // https://github.com/square/anvil/issues/938

    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding 
      import com.squareup.test.other.OTHER_CONSTANT
      import javax.inject.Inject
      import javax.inject.Named

      private interface Settings {
        companion object {
          const val CONSTANT = "${'$'}OTHER_CONSTANT.foo"
        }
      }

      interface ParentInterface

      @Named(Settings.CONSTANT)
      @ContributesBinding(Any::class)
      class InjectClass @Inject constructor() : ParentInterface
      """,
      """
      package com.squareup.test.other
      
      const val OTHER_CONSTANT = "abc"
      """.trimIndent(),
      mode = mode,
    ) {

      assertThat(exitCode).isEqualTo(OK)

      val Named = injectClass.generatedBindingModule.methods.single()
        .getDeclaredAnnotation(Named::class.java)

      assertThat(Named.value).isEqualTo("abc.foo")
    }
  }

  @Test fun `there is a binding module for a contributed binding for an object`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding

      interface ParentInterface

      @ContributesBinding(Any::class, ParentInterface::class)
      object ContributingInterface : ParentInterface
      """,
      mode = mode,
    ) {
      assertThat(contributingInterface.bindingOriginKClass?.java).isEqualTo(contributingInterface)
      assertThat(contributingInterface.bindingModuleScope).isEqualTo(Any::class)
    }
  }

  @Test fun `the order of the scope can be changed with named parameters`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding

      interface ParentInterface

      @ContributesBinding(boundType = ParentInterface::class, scope = Int::class)
      class ContributingInterface : ParentInterface
      """,
      mode = mode,
    ) {
      assertThat(contributingInterface.bindingModuleScope).isEqualTo(Int::class)
    }
  }

  @Test fun `there is a binding module for a contributed binding for inner interfaces`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding

      interface ParentInterface

      class Abc {
        @ContributesBinding(Any::class, ParentInterface::class)
        interface ContributingInterface : ParentInterface
      }
      """,
      mode = mode,
    ) {
      val contributingInterface =
        classLoader.loadClass("com.squareup.test.Abc\$ContributingInterface")
      assertThat(contributingInterface.bindingOriginKClass?.java).isEqualTo(contributingInterface)
      assertThat(contributingInterface.bindingModuleScope).isEqualTo(Any::class)
    }
  }

  @Test fun `there is a binding module for a contributed binding for inner classes`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding

      interface ParentInterface
      
      class Abc {
        @ContributesBinding(Any::class, ParentInterface::class)
        class ContributingClass : ParentInterface
      }
      """,
      mode = mode,
    ) {
      val contributingClass =
        classLoader.loadClass("com.squareup.test.Abc\$ContributingClass")
      assertThat(contributingClass.bindingOriginKClass?.java).isEqualTo(contributingClass)
      assertThat(contributingClass.bindingModuleScope).isEqualTo(Any::class)

      assertFileGenerated(
        mode,
        "Abc_ContributingClassAsComSquareupTestParentInterfaceToKotlinAnyBindingModule.kt",
      )
    }
  }

  @Test fun `contributed binding class must be public`() {
    val visibilities = setOf(
      "internal",
      "private",
      "protected",
    )

    visibilities.forEach { visibility ->
      compile(
        """
        package com.squareup.test

        import com.squareup.anvil.annotations.ContributesBinding

        interface ParentInterface

        @ContributesBinding(Any::class, ParentInterface::class)
        $visibility class ContributingInterface : ParentInterface
        """,
        mode = mode,
      ) {
        assertThat(exitCode).isError()
        // Position to the class.
        assertThat(messages).contains("Source0.kt:8")
        assertThat(messages).contains(
          "com.squareup.test.ContributingInterface is binding a type, but the class is not " +
            "public. Only public types are supported.",
        )
      }
    }
  }

  @Test fun `contributed bindings aren't allowed to have more than one qualifier`() {
    compile(
      """
      package com.squareup.test

      import javax.inject.Qualifier
      
      @Qualifier
      annotation class AnyQualifier1
      
      @Qualifier
      annotation class AnyQualifier2

      interface ParentInterface

      @com.squareup.anvil.annotations.ContributesBinding(Any::class)
      @AnyQualifier1 
      @AnyQualifier2
      interface ContributingInterface : ParentInterface
      """,
      mode = mode,
    ) {
      assertThat(exitCode).isError()
      assertThat(messages).contains(
        "Classes annotated with @ContributesBinding may not use more than one @Qualifier.",
      )
    }
  }

  @Test fun `the bound type can only be implied with one super type (2 interfaces)`() {
    compile(
      """
      package com.squareup.test
      
      import com.squareup.anvil.annotations.ContributesBinding

      interface ParentInterface
      
      @ContributesBinding(Any::class)
      interface ContributingInterface : ParentInterface, CharSequence
      """,
      mode = mode,
    ) {
      assertThat(exitCode).isError()
      assertThat(messages).contains(
        "com.squareup.test.ContributingInterface contributes a binding, but does not specify " +
          "the bound type. This is only allowed with exactly one direct super type. If there " +
          "are multiple or none, then the bound type must be explicitly defined in the " +
          "@ContributesBinding annotation.",
      )
    }
  }

  @Test fun `the bound type can only be implied with one super type (class and interface)`() {
    compile(
      """
      package com.squareup.test
      
      import com.squareup.anvil.annotations.ContributesBinding

      interface ParentInterface
      
      open class Abc
      
      @ContributesBinding(Any::class)
      interface ContributingInterface : Abc(), ParentInterface
      """,
      mode = mode,
    ) {
      assertThat(exitCode).isError()
      assertThat(messages).contains(
        "com.squareup.test.ContributingInterface contributes a binding, but does not specify " +
          "the bound type. This is only allowed with exactly one direct super type. If there " +
          "are multiple or none, then the bound type must be explicitly defined in the " +
          "@ContributesBinding annotation.",
      )
    }
  }

  @Test fun `the bound type can only be implied with one super type (no super type)`() {
    compile(
      """
      package com.squareup.test
      
      import com.squareup.anvil.annotations.ContributesBinding

      @ContributesBinding(Any::class)
      object ContributingInterface
      """,
      mode = mode,
    ) {
      assertThat(exitCode).isError()
      assertThat(messages).contains(
        "com.squareup.test.ContributingInterface contributes a binding, but does not specify " +
          "the bound type. This is only allowed with exactly one direct super type. If there " +
          "are multiple or none, then the bound type must be explicitly defined in the " +
          "@ContributesBinding annotation.",
      )
    }
  }

  @Test fun `the bound type is not implied when explicitly defined`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding

      interface ParentInterface

      @ContributesBinding(Int::class, ParentInterface::class)
      interface ContributingInterface : ParentInterface, CharSequence
      """,
      mode = mode,
    ) {
      assertThat(contributingInterface.bindingOriginKClass?.java).isEqualTo(contributingInterface)
      assertThat(contributingInterface.bindingModuleScope).isEqualTo(Int::class)
    }
  }

  @Test fun `the contributed binding class must extend the bound type`() {
    compile(
      """
      package com.squareup.test
      
      import com.squareup.anvil.annotations.ContributesBinding

      interface ParentInterface

      @ContributesBinding(Any::class, ParentInterface::class)
      interface ContributingInterface : CharSequence
      """,
      mode = mode,
    ) {
      assertThat(exitCode).isError()
      assertThat(messages).contains(
        "com.squareup.test.ContributingInterface contributes a binding for " +
          "com.squareup.test.ParentInterface, but doesn't extend this type.",
      )
    }
  }

  @Test fun `the contributed binding class can extend Any explicitly`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding
      import com.squareup.anvil.annotations.MergeComponent

      @ContributesBinding(Int::class, boundType = Any::class)
      interface ContributingInterface

      @MergeComponent(
        scope = Int::class,
      )
      interface ComponentInterface
      """,
      mode = mode,
    ) {
      assertThat(contributingInterface.bindingOriginKClass?.java).isEqualTo(contributingInterface)
      assertThat(contributingInterface.bindingModuleScope).isEqualTo(Int::class)
    }
  }

  @Test fun `there are multiple hints for multiple contributed bindings`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding

      interface ParentInterface

      @ContributesBinding(Any::class)
      @ContributesBinding(Unit::class)
      class ContributingInterface : ParentInterface
      """,
      mode = mode,
    ) {
      assertThat(contributingInterface.bindingOriginKClass?.java).isEqualTo(contributingInterface)
      assertThat(contributingInterface.bindingModuleScopes).containsExactly(Any::class, Unit::class)
    }
  }

  @Test fun `the scopes for multiple contributions have a stable sort`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding

      interface ParentInterface

      @ContributesBinding(Any::class)
      @ContributesBinding(Unit::class)
      class ContributingInterface : ParentInterface
      
      @ContributesBinding(Unit::class)
      @ContributesBinding(Any::class)
      class SecondContributingInterface : ParentInterface
      """,
      mode = mode,
    ) {
      generatedFileOrNull(
        mode,
        "ContributingInterfaceAsComSquareupTestParentInterfaceToKotlinAnyBindingModule.kt",
      )!!
      generatedFileOrNull(
        mode,
        "SecondContributingInterfaceAsComSquareupTestParentInterfaceToKotlinUnitBindingModule.kt",
      )!!
    }
  }

  @Test fun `there are multiple hints for contributed bindings with fully qualified names`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding
      
      interface ParentInterface

      @ContributesBinding(Any::class)
      @com.squareup.anvil.annotations.ContributesBinding(Unit::class)
      class ContributingInterface : ParentInterface
      """,
      mode = mode,
    ) {
      assertThat(contributingInterface.bindingOriginKClass?.java).isEqualTo(contributingInterface)
      assertThat(contributingInterface.bindingModuleScopes).containsExactly(Any::class, Unit::class)
    }
  }

  @Test fun `multiple annotations with the same scope and bound type aren't allowed`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding

      interface ParentInterface

      @ContributesBinding(Any::class)
      @ContributesBinding(Any::class, replaces = [Int::class])
      @ContributesBinding(Unit::class)
      @ContributesBinding(Unit::class, replaces = [Int::class])
      class ContributingInterface : ParentInterface
      """,
      mode = mode,
    ) {
      assertThat(exitCode).isError()
      assertThat(messages).contains(
        "com.squareup.test.ContributingInterface contributes multiple times to the same scope " +
          "using the same bound type: [ParentInterface]. Contributing multiple times to the " +
          "same scope with the same bound type is forbidden and all scope - bound type " +
          "combinations must be distinct.",
      )
    }
  }

  @Test fun `multiple annotations with the same scope and different bound type are allowed`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding

      interface ParentInterface1
      interface ParentInterface2

      @ContributesBinding(Any::class, boundType = ParentInterface1::class)
      @ContributesBinding(Any::class, boundType = ParentInterface2::class)
      @ContributesBinding(Unit::class, boundType = ParentInterface1::class)
      @ContributesBinding(Unit::class, boundType = ParentInterface2::class)
      class ContributingInterface : ParentInterface1, ParentInterface2
      """,
      mode = mode,
    ) {
      assertThat(contributingInterface.bindingOriginKClass?.java).isEqualTo(contributingInterface)
      assertThat(
        contributingInterface.bindingModuleScopes.toSet(),
      ).containsExactly(Any::class, Unit::class)
    }
  }

  @Test fun `priority is correctly propagated`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding

      interface ParentInterface1
      interface ParentInterface2

      @ContributesBinding(Any::class, boundType = ParentInterface1::class) // Default case is NORMAL
      @ContributesBinding(Any::class, boundType = ParentInterface2::class, priority = ContributesBinding.PRIORITY_NORMAL)
      @ContributesBinding(Unit::class, boundType = ParentInterface1::class, priority = ContributesBinding.PRIORITY_HIGH)
      @ContributesBinding(Unit::class, boundType = ParentInterface2::class, priority = ContributesBinding.PRIORITY_HIGHEST)
      class ContributingInterface : ParentInterface1, ParentInterface2
      """,
      mode = mode,
    ) {
      val bindingModules = contributingInterface.generatedBindingModules()
        .associate { clazz ->
          val bindingMarker = clazz.getAnnotation(InternalBindingMarker::class.java)
          clazz.simpleName to bindingMarker.priority
        }
      assertThat(
        bindingModules["ContributingInterfaceAsComSquareupTestParentInterface1ToKotlinAnyBindingModule"],
      ).isEqualTo(ContributesBinding.PRIORITY_NORMAL)
      assertThat(
        bindingModules["ContributingInterfaceAsComSquareupTestParentInterface2ToKotlinAnyBindingModule"],
      ).isEqualTo(ContributesBinding.PRIORITY_NORMAL)
      assertThat(
        bindingModules["ContributingInterfaceAsComSquareupTestParentInterface1ToKotlinUnitBindingModule"],
      ).isEqualTo(ContributesBinding.PRIORITY_HIGH)
      assertThat(
        bindingModules["ContributingInterfaceAsComSquareupTestParentInterface2ToKotlinUnitBindingModule"],
      ).isEqualTo(ContributesBinding.PRIORITY_HIGHEST)
    }
  }

  @Test fun `legacy priority is correctly propagated`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding
      import com.squareup.anvil.annotations.ContributesBinding.Priority

      interface ParentInterface1
      interface ParentInterface2

      @ContributesBinding(Any::class, boundType = ParentInterface1::class) // Default case is NORMAL
      @ContributesBinding(Any::class, boundType = ParentInterface2::class, priorityDeprecated = Priority.NORMAL)
      @ContributesBinding(Unit::class, boundType = ParentInterface1::class, priorityDeprecated = Priority.HIGH)
      @ContributesBinding(Unit::class, boundType = ParentInterface2::class, priorityDeprecated = Priority.HIGHEST)
      class ContributingInterface : ParentInterface1, ParentInterface2
      """,
      mode = mode,
      allWarningsAsErrors = false,
    ) {
      val bindingModules = contributingInterface.generatedBindingModules()
        .associate { clazz ->
          val bindingMarker = clazz.getAnnotation(InternalBindingMarker::class.java)
          clazz.simpleName to bindingMarker.priority
        }
      assertThat(
        bindingModules["ContributingInterfaceAsComSquareupTestParentInterface1ToKotlinAnyBindingModule"],
      ).isEqualTo(ContributesBinding.PRIORITY_NORMAL)
      assertThat(
        bindingModules["ContributingInterfaceAsComSquareupTestParentInterface2ToKotlinAnyBindingModule"],
      ).isEqualTo(ContributesBinding.PRIORITY_NORMAL)
      assertThat(
        bindingModules["ContributingInterfaceAsComSquareupTestParentInterface1ToKotlinUnitBindingModule"],
      ).isEqualTo(ContributesBinding.PRIORITY_HIGH)
      assertThat(
        bindingModules["ContributingInterfaceAsComSquareupTestParentInterface2ToKotlinUnitBindingModule"],
      ).isEqualTo(ContributesBinding.PRIORITY_HIGHEST)
    }
  }
}
