package com.squareup.anvil.compiler.codegen

import com.google.common.truth.Truth.assertThat
import com.rickbusarow.kase.asClueCatching
import com.squareup.anvil.annotations.MergeSubcomponent
import com.squareup.anvil.compiler.OPTION_ENABLE_CONTRIBUTES_SUBCOMPONENT_MERGING
import com.squareup.anvil.compiler.PARENT_COMPONENT
import com.squareup.anvil.compiler.SUBCOMPONENT_FACTORY
import com.squareup.anvil.compiler.SUBCOMPONENT_MODULE
import com.squareup.anvil.compiler.checkFullTestRun
import com.squareup.anvil.compiler.compile
import com.squareup.anvil.compiler.componentInterface
import com.squareup.anvil.compiler.contributingInterface
import com.squareup.anvil.compiler.daggerModule1
import com.squareup.anvil.compiler.includeKspTests
import com.squareup.anvil.compiler.internal.testing.AnvilCompilationMode
import com.squareup.anvil.compiler.internal.testing.ComponentProcessingMode
import com.squareup.anvil.compiler.internal.testing.extends
import com.squareup.anvil.compiler.internal.testing.originIfMerged
import com.squareup.anvil.compiler.internal.testing.resolveIfMerged
import com.squareup.anvil.compiler.internal.testing.shouldNotExtend
import com.squareup.anvil.compiler.internal.testing.simpleCodeGenerator
import com.squareup.anvil.compiler.internal.testing.use
import com.squareup.anvil.compiler.mergeComponentFqName
import com.squareup.anvil.compiler.secondContributingInterface
import com.squareup.anvil.compiler.subcomponentInterface
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode.OK
import dagger.Component
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.jetbrains.kotlin.descriptors.runtime.structure.classId
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import java.lang.reflect.Method
import javax.inject.Singleton
import kotlin.test.assertFailsWith

@RunWith(Parameterized::class)
class ContributesSubcomponentHandlerGeneratorTest(
  private val componentProcessingMode: ComponentProcessingMode,
  private val mode: AnvilCompilationMode,
) {

  companion object {
    @JvmStatic
    @Parameters(name = "{0} {1}")
    fun parameters(): Collection<Any> {
      return buildList {
        add(arrayOf(ComponentProcessingMode.NONE, AnvilCompilationMode.Embedded()))
        add(arrayOf(ComponentProcessingMode.KAPT, AnvilCompilationMode.Embedded()))
        if (includeKspTests()) {
          add(arrayOf(ComponentProcessingMode.NONE, AnvilCompilationMode.Ksp()))
          add(arrayOf(ComponentProcessingMode.KSP, AnvilCompilationMode.Ksp()))
        }
      }
    }
  }

  @Test fun `there is a subcomponent generated for a @MergeComponent`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent
  
        @ContributesSubcomponent(Any::class, Unit::class)
        interface SubcomponentInterface
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      val anvilComponent = subcomponentInterface.anvilComponent(componentInterface)
      assertThat(anvilComponent).isNotNull()

      val annotation = anvilComponent.getAnnotation(MergeSubcomponent::class.java)
      assertThat(annotation).isNotNull()
      assertThat(annotation.scope).isEqualTo(Any::class)
    }
  }

  @Test fun `can be disabled in KSP`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    assumeTrue(mode is AnvilCompilationMode.Ksp)

    val modeWithOption = (mode as AnvilCompilationMode.Ksp).copy(options = mapOf(
      OPTION_ENABLE_CONTRIBUTES_SUBCOMPONENT_MERGING to "false"
    ))
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent
  
        @ContributesSubcomponent(Any::class, Unit::class)
        interface SubcomponentInterface
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = modeWithOption,
    ) {
      // val anvilComponent = subcomponentInterface.anvilComponent(componentInterface)
      // assertThat(anvilComponent).isNotNull()
      //
      // val annotation = anvilComponent.getAnnotation(MergeSubcomponent::class.java)
      // assertThat(annotation).isNotNull()
      // assertThat(annotation.scope).isEqualTo(Any::class)
    }
  }

  @Test fun `generation works for a nested contributed subcomponent with a very long name`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    // This test tests legacy behavior that we no longer need to do in KSP processing
    assumeFalse(
      mode is AnvilCompilationMode.Ksp && componentProcessingMode != ComponentProcessingMode.KSP,
    )

    // These name lengths are arbitrary. They're long enough to be somewhat realistic,
    // but short enough that most file names won't become obnoxiously long for no reason.
    val a = "A".repeat(24)
    val b = "B".repeat(37)
    val c = "C".repeat(33)

    // The maximum file name length is 255 characters, including a dot and extension.
    // The maximum canonical class name length is 249 characters, since ".class" uses the last 6.
    // Canonical names concatenate parent names with a '$' separator, like:
    //   com.example.Outer$Middle$Inner
    //
    // So, a class's simple name's length cannot exceed:
    //   `249 - parents.sumOf { it.simpleName.length + 1 }`
    // where the + 1 is for the '$' separator.
    //
    val d = "D".repeat(249 - a.length - b.length - c.length - 3) // 152

    compile(
      """
        package com.squareup.test

        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent

        @MergeComponent(Unit::class)
        interface ComponentInterface

        @ContributesSubcomponent(Any::class, Unit::class)
        interface $a {
          @ContributesSubcomponent(Int::class, Any::class)
          interface $b {
            @ContributesSubcomponent(String::class, Int::class)
            interface $c {
              @ContributesSubcomponent(Number::class, String::class)
              interface $d
            }
          }
        }
        
      """.trimIndent(),
      componentProcessingMode = componentProcessingMode,
      mode = mode,
    ) {

      val subA = classLoader.loadClass("com.squareup.test.$a")
      val subB = classLoader.loadClass("com.squareup.test.$a\$$b")
      val subC = classLoader.loadClass("com.squareup.test.$a\$$b\$$c")
      val subD = classLoader.loadClass("com.squareup.test.$a\$$b\$$c\$$d")

      val anvilSubA = subA.anvilComponent(componentInterface)
      val anvilSubB = subB.anvilComponent(anvilSubA)
      val anvilSubC = subC.anvilComponent(anvilSubB)
      val anvilSubD = subD.anvilComponent(anvilSubC)

      // The 'D' name is truncated in the generated Anvil subcomponent, to leave space for:
      // - the `ParentComponent` inner type
      // - the additional component outer class (ComponentInterface in this test)
      // - the `.kapt_metadata` extension (the longest extension we contend with)
      "The generated subcomponent class name is shortened for the file system".asClueCatching {
        anvilSubD.simpleName.length shouldBe 217
      }

      "The unique hash is not truncated".asClueCatching {
        anvilSubD.simpleName shouldEndWith "c7aeb310"
      }

      "The `ParentComponent` inner type is present, meaning its file name is short enough".asClueCatching {

        anvilSubD.parentComponentInterface
          .declaredMethods[0].returnType shouldBe anvilSubD
      }
    }
  }

  @Test fun `there is a subcomponent generated for a @MergeSubcomponent`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeSubcomponent
  
        @ContributesSubcomponent(Any::class, Unit::class)
        interface SubcomponentInterface
        
        @MergeSubcomponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      val anvilComponent = subcomponentInterface.anvilComponent(componentInterface)
      assertThat(anvilComponent).isNotNull()

      val annotation = anvilComponent.getAnnotation(MergeSubcomponent::class.java)
      assertThat(annotation).isNotNull()
      assertThat(annotation.scope).isEqualTo(Any::class)
    }
  }

  @Test
  fun `there is a subcomponent generated for a @MergeInterfaces and the parent component is added to the interface`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.compat.MergeInterfaces
  
        @ContributesSubcomponent(Any::class, Unit::class)
        interface SubcomponentInterface
        
        @MergeInterfaces(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      val anvilComponent = subcomponentInterface.anvilComponent(componentInterface)
      assertThat(anvilComponent).isNotNull()

      val annotation = anvilComponent.getAnnotation(MergeSubcomponent::class.java)
      assertThat(annotation).isNotNull()
      assertThat(annotation.scope).isEqualTo(Any::class)

      assertThat(
        componentInterface extends subcomponentInterface
          .anvilComponent(componentInterface)
          .parentComponentInterface,
      ).isTrue()
    }
  }

  @Test fun `there is no subcomponent generated for a @MergeModules`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.compat.MergeModules
  
        @ContributesSubcomponent(Any::class, Unit::class)
        interface SubcomponentInterface
        
        @MergeModules(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      assertThat(exitCode).isEqualTo(OK)

      assertFailsWith<ClassNotFoundException> {
        subcomponentInterface.anvilComponent(componentInterface)
      }
    }
  }

  @Test fun `there is no subcomponent generated for a mismatching scopes`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent
  
        @ContributesSubcomponent(Any::class, parentScope = Int::class)
        interface SubcomponentInterface
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      assertThat(exitCode).isEqualTo(OK)

      assertFailsWith<ClassNotFoundException> {
        subcomponentInterface.anvilComponent(componentInterface)
      }
    }
  }

  @Test fun `there is a subcomponent generated for an inner class`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent
  
        class Outer {
          @ContributesSubcomponent(Any::class, Unit::class)
          interface SubcomponentInterface
        }
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      val subcomponentInterface = classLoader
        .loadClass("com.squareup.test.Outer\$SubcomponentInterface")

      val anvilComponent = subcomponentInterface.anvilComponent(componentInterface)
      assertThat(anvilComponent).isNotNull()

      val annotation = anvilComponent.getAnnotation(MergeSubcomponent::class.java)
      assertThat(annotation).isNotNull()
      assertThat(annotation.scope).isEqualTo(Any::class)
    }
  }

  @Test fun `there is a subcomponent generated for an inner parent class`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent
  
        @ContributesSubcomponent(Any::class, Unit::class)
        interface SubcomponentInterface
        
        class Outer {
          @MergeComponent(Unit::class)
          interface ComponentInterface
        }
        
      """.trimIndent(),
      mode = mode,
    ) {
      val anvilComponent = subcomponentInterface.anvilComponent(
        classLoader.loadClass("com.squareup.test.Outer\$ComponentInterface"),
      )
      assertThat(anvilComponent).isNotNull()

      val annotation = anvilComponent.getAnnotation(MergeSubcomponent::class.java)
      assertThat(annotation).isNotNull()
      assertThat(annotation.scope).isEqualTo(Any::class)
    }
  }

  @Test fun `there is a subcomponent generated in a chain of contributed subcomponents`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    // This test will exercise multiple rounds of code generation.
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
  
        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface1
  
        @ContributesSubcomponent(Int::class, parentScope = Any::class)
        interface SubcomponentInterface2
  
        @ContributesSubcomponent(Long::class, parentScope = Int::class)
        interface SubcomponentInterface3
      """.trimIndent(),
      mode = mode,
    ) {
      var parentComponentInterface = componentInterface

      for (index in 1..3) {
        val subcomponentInterface = classLoader
          .loadClass("com.squareup.test.SubcomponentInterface$index")

        val anvilComponent = subcomponentInterface.anvilComponent(parentComponentInterface)
        assertThat(anvilComponent).isNotNull()

        val annotation = anvilComponent.getAnnotation(MergeSubcomponent::class.java)
        assertThat(annotation).isNotNull()

        parentComponentInterface = anvilComponent
      }
    }
  }

  @Test fun `there is a subcomponent generated with separate compilations`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    val firstCompilationResult = compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        
        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface1
      """.trimIndent(),
      mode = mode,
    ) {
      assertThat(exitCode).isEqualTo(OK)
    }

    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.MergeComponent
        import com.squareup.anvil.annotations.ContributesSubcomponent
        
        @ContributesSubcomponent(Unit::class, parentScope = Int::class)
        interface SubcomponentInterface2
        
        @MergeComponent(Int::class)
        interface ComponentInterface
      """.trimIndent(),
      previousCompilationResult = firstCompilationResult,
    ) {
      val anvilComponent = classLoader
        .loadClass("com.squareup.test.SubcomponentInterface2")
        .anvilComponent(componentInterface)
      assertThat(anvilComponent).isNotNull()

      val annotation = anvilComponent.getAnnotation(MergeSubcomponent::class.java)
      assertThat(annotation).isNotNull()
      assertThat(annotation.scope).isEqualTo(Unit::class)
    }
  }

  @Test fun `Dagger modules can be added manually`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent
        import dagger.Module

        @Module
        object DaggerModule1
  
        @ContributesSubcomponent(
          scope = Any::class, 
          parentScope = Unit::class,
          modules = [DaggerModule1::class]
        )
        interface SubcomponentInterface
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      val annotation = subcomponentInterface.anvilComponent(componentInterface)
        .getAnnotation(MergeSubcomponent::class.java)

      assertThat(annotation.modules.toList()).containsExactly(daggerModule1.kotlin)
    }
  }

  @Test fun `Dagger modules can be added manually with multiple compilations`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    val firstCompilationResult = compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import dagger.Module

        @Module
        object DaggerModule1
  
        @ContributesSubcomponent(
          scope = Any::class, 
          parentScope = Unit::class,
          modules = [DaggerModule1::class]
        )
        interface SubcomponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      assertThat(exitCode).isEqualTo(OK)
    }

    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.MergeComponent
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
      previousCompilationResult = firstCompilationResult,
    ) {
      val annotation = subcomponentInterface.anvilComponent(componentInterface)
        .getAnnotation(MergeSubcomponent::class.java)

      assertThat(annotation.modules.toList()).containsExactly(daggerModule1.kotlin)
    }
  }

  @Test fun `Dagger modules, component interfaces and bindings can be excluded`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesBinding
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesTo
        import com.squareup.anvil.annotations.MergeComponent
        import dagger.Module

        @Module
        @ContributesTo(Any::class)
        object DaggerModule1

        @ContributesTo(Any::class)
        interface ContributingInterface
  
        @ContributesBinding(Any::class)
        interface SecondContributingInterface : CharSequence
  
        @ContributesSubcomponent(
          scope = Any::class, 
          parentScope = Unit::class,
          exclude = [
            DaggerModule1::class, 
            ContributingInterface::class, 
            SecondContributingInterface::class
          ]
        )
        interface SubcomponentInterface
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      val annotation = subcomponentInterface.anvilComponent(componentInterface)
        .getAnnotation(MergeSubcomponent::class.java)

      assertThat(annotation.exclude.toList()).containsExactly(
        daggerModule1.kotlin,
        contributingInterface.kotlin,
        secondContributingInterface.kotlin,
      )
    }
  }

  @Test
  fun `Dagger modules, component interfaces and bindings can be excluded with multiple compilations`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    val firstCompilationResult = compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesBinding
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesTo
        import dagger.Module

        @Module
        @ContributesTo(Any::class)
        object DaggerModule1

        @ContributesTo(Any::class)
        interface ContributingInterface
  
        @ContributesBinding(Any::class)
        interface SecondContributingInterface : CharSequence
  
        @ContributesSubcomponent(
          scope = Any::class, 
          parentScope = Unit::class,
          exclude = [
            DaggerModule1::class, 
            ContributingInterface::class, 
            SecondContributingInterface::class
          ]
        )
        interface SubcomponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      assertThat(exitCode).isEqualTo(OK)
    }

    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.MergeComponent

        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      previousCompilationResult = firstCompilationResult,
    ) {
      val annotation = subcomponentInterface.anvilComponent(componentInterface)
        .getAnnotation(MergeSubcomponent::class.java)

      assertThat(annotation.exclude.toList()).containsExactly(
        daggerModule1.kotlin,
        contributingInterface.kotlin,
        secondContributingInterface.kotlin,
      )
    }
  }

  @Test
  fun `there is a parent component interface automatically generated without declaring one explicitly`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    // This test tests legacy behavior that we no longer need to do in KSP processing
    assumeFalse(
      mode is AnvilCompilationMode.Ksp && componentProcessingMode != ComponentProcessingMode.KSP,
    )
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent
  
        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      val parentComponent =
        subcomponentInterface.anvilComponent(componentInterface).parentComponentInterface
      assertThat(parentComponent).isNotNull()

      assertThat(parentComponent.declaredMethods.single().returnType)
        .isEqualTo(subcomponentInterface.anvilComponent(componentInterface))

      assertThat(componentInterface extends parentComponent).isTrue()
    }
  }

  @Test
  fun `the parent component interface extends a manually declared component interface with the same scope`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    // This test tests legacy behavior that we no longer need to do in KSP processing
    assumeFalse(
      mode is AnvilCompilationMode.Ksp && componentProcessingMode != ComponentProcessingMode.KSP,
    )
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesTo
        import com.squareup.anvil.annotations.MergeComponent
  
        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface {
          @ContributesTo(Unit::class)
          interface AnyParentComponent {
            fun createComponent(): SubcomponentInterface
            fun integer(): Int
          }
        }
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      val parentComponent =
        subcomponentInterface.anvilComponent(componentInterface).parentComponentInterface
      assertThat(parentComponent).isNotNull()

      val createComponentFunction = parentComponent.declaredMethods.single()
      assertThat(createComponentFunction.returnType)
        .isEqualTo(subcomponentInterface.anvilComponent(componentInterface))
      assertThat(createComponentFunction.name)
        .isEqualTo("createComponent")

      assertThat(
        parentComponent extends subcomponentInterface.anyParentComponentInterface,
      ).isTrue()

      assertThat(componentInterface extends parentComponent).isTrue()
    }
  }

  @Test
  fun `the parent component interface extends a manually declared component interface with the same scope with multiple compilations`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    // This test tests legacy behavior that we no longer need to do in KSP processing
    assumeFalse(
      mode is AnvilCompilationMode.Ksp && componentProcessingMode != ComponentProcessingMode.KSP,
    )
    val firstCompilationResult = compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesTo
  
        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface {
          @ContributesTo(Unit::class)
          interface AnyParentComponent {
            fun createComponent(): SubcomponentInterface
            fun integer(): Int
          }
        }
      """,
      mode = mode,
    ) {
      assertThat(exitCode).isEqualTo(OK)
    }

    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.MergeComponent
  
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """,
      mode = mode,
      previousCompilationResult = firstCompilationResult,
    ) {
      val parentComponent =
        subcomponentInterface.anvilComponent(componentInterface).parentComponentInterface
      assertThat(parentComponent).isNotNull()

      val createComponentFunction = parentComponent.declaredMethods.single()
      assertThat(createComponentFunction.returnType)
        .isEqualTo(subcomponentInterface.anvilComponent(componentInterface))
      assertThat(createComponentFunction.name)
        .isEqualTo("createComponent")

      assertThat(
        parentComponent extends subcomponentInterface.anyParentComponentInterface,
      ).isTrue()

      assertThat(componentInterface extends parentComponent).isTrue()
    }
  }

  @Test
  fun `the parent component interface does not extend a manually declared component interface with a different scope`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesTo
        import com.squareup.anvil.annotations.MergeComponent
  
        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface {
          @ContributesTo(Int::class)
          interface AnyParentComponent {
            fun createComponent(): SubcomponentInterface
          }
        }
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      val parentComponent =
        subcomponentInterface.anvilComponent(componentInterface).parentComponentInterface
      assertThat(
        parentComponent extends subcomponentInterface.anyParentComponentInterface,
      ).isFalse()
    }
  }

  @Test
  fun `Dagger generates the real component and subcomponent and they can be instantiated through the component interfaces`() {
    assumeTrue(componentProcessingMode != ComponentProcessingMode.NONE)
    checkFullTestRun()

    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesTo
        import com.squareup.anvil.annotations.MergeComponent
        import dagger.Module
        import dagger.Provides

        @ContributesTo(Any::class)
        @Module
        object DaggerModule {
          @Provides fun provideInteger(): Int = 5
        }
  
        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface {
          @ContributesTo(Unit::class)
          interface AnyParentComponent {
            fun createComponent(): SubcomponentInterface
          }
          
          fun integer(): Int
        }
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """,
      mode = mode,
      componentProcessingMode = componentProcessingMode,
    ) {
      val daggerComponent = componentInterface.daggerComponent.declaredMethods
        .single { it.name == "create" }
        .invoke(null)

      // Note that there are no declared methods, only inherited methods.
      assertThat(componentInterface.declaredMethods.toList()).isEmpty()

      // There are two methods: one from AnyParentComponent and one from the generated component
      // interface. Both show up in the reflection APIs.
      val subcomponent = componentInterface.methods
        .first { it.name == "createComponent" }
        .invoke(daggerComponent)

      val int = subcomponent::class.java.declaredMethods
        .single { it.name == "integer" }
        .use { it.invoke(subcomponent) as Int }

      assertThat(int).isEqualTo(5)
    }
  }

  @Test
  fun `the parent interface of a contributed subcomponent is picked up by components and other contributed subcomponents`() {
    // This test tests legacy behavior that we no longer need to do in KSP processing
    assumeFalse(
      mode is AnvilCompilationMode.Ksp && componentProcessingMode != ComponentProcessingMode.KSP,
    )
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesTo
        import com.squareup.anvil.annotations.MergeComponent
        import dagger.Module
        import dagger.Provides

        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface1 {
          @ContributesTo(Unit::class)
          interface AnyParentComponent {
            fun createComponent(): SubcomponentInterface1
          }
        }

        @ContributesSubcomponent(Unit::class, parentScope = Int::class)
        interface SubcomponentInterface2 {
          @ContributesTo(Int::class)
          interface AnyParentComponent {
            fun createComponent(): SubcomponentInterface2
          }
        }
        
        @MergeComponent(Unit::class)
        interface ComponentInterface1
        
        @MergeComponent(Int::class)
        interface ComponentInterface2
      """,
      mode = mode,
      // Keep Dagger enabled, because it complained initially.
      componentProcessingMode = componentProcessingMode,
    ) {
      val anvilComponent1 = subcomponentInterface1.anvilComponent(componentInterface1)

      assertThat(componentInterface1)
        .isAssignableTo(subcomponentInterface1.anyParentComponentInterface)
      assertThat(componentInterface1)
        .isAssignableTo(anvilComponent1.parentComponentInterface)

      val anvilComponent2 = subcomponentInterface2.anvilComponent(componentInterface2)

      assertThat(componentInterface2)
        .isAssignableTo(subcomponentInterface2.anyParentComponentInterface)
      assertThat(componentInterface2)
        .isAssignableTo(anvilComponent2.parentComponentInterface)

      // Note that NOT subcomponentInterface2 extends these parent component interface, but its
      // generated @MergeSubcomponent extends them.
      subcomponentInterface2 shouldNotExtend subcomponentInterface1.anyParentComponentInterface
      assertThat(anvilComponent2)
        .isAssignableTo(
          subcomponentInterface1.anvilComponent(anvilComponent2).parentComponentInterface,
        )
    }
  }

  @Test fun `contributed subcomponents can be excluded with @MergeComponent`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesSubcomponent
      import com.squareup.anvil.annotations.MergeComponent

      @ContributesSubcomponent(Unit::class, parentScope = Any::class)
      interface SubcomponentInterface

      @MergeComponent(
          scope = Any::class,
          exclude = [SubcomponentInterface::class]
      )
      interface ComponentInterface
      """,
      mode = mode,
    ) {
      assertThat(exitCode).isEqualTo(OK)

      // Fails because the component is never generated.
      assertFailsWith<ClassNotFoundException> {
        subcomponentInterface.anvilComponent(componentInterface)
      }
      assertThat(componentInterface.interfaces).isEmpty()
    }
  }

  @Test fun `contributed subcomponents can be excluded with @MergeSubcomponent`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesSubcomponent
      import com.squareup.anvil.annotations.MergeSubcomponent

      @ContributesSubcomponent(Unit::class, parentScope = Any::class)
      interface SubcomponentInterface

      @MergeSubcomponent(
          scope = Any::class,
          exclude = [SubcomponentInterface::class]
      )
      interface ComponentInterface
      """,
      mode = mode,
    ) {
      assertThat(exitCode).isEqualTo(OK)

      // Fails because the component is never generated.
      assertFailsWith<ClassNotFoundException> {
        subcomponentInterface.anvilComponent(componentInterface)
      }
      assertThat(componentInterface.interfaces).isEmpty()
    }
  }

  @Test fun `contributed subcomponents can be excluded with @MergeModules`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.compat.MergeModules
      import com.squareup.anvil.annotations.ContributesSubcomponent

      @ContributesSubcomponent(Unit::class, parentScope = Any::class)
      interface SubcomponentInterface

      @MergeModules(
          scope = Any::class,
          exclude = [SubcomponentInterface::class]
      )
      interface ComponentInterface
      """,
      mode = mode,
    ) {
      assertThat(exitCode).isEqualTo(OK)

      // Fails because the component is never generated.
      assertFailsWith<ClassNotFoundException> {
        subcomponentInterface.anvilComponent(componentInterface)
      }
    }
  }

  @Test fun `contributed subcomponents can be excluded in one component but not the other`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesSubcomponent
      import com.squareup.anvil.annotations.MergeComponent

      @ContributesSubcomponent(Unit::class, parentScope = Any::class)
      interface SubcomponentInterface

      @MergeComponent(
        scope = Any::class,
        exclude = [SubcomponentInterface::class]
      )
      interface ComponentInterface

      @MergeComponent(
        scope = Any::class,
      )
      interface ContributingInterface
      """,
      mode = mode,
    ) {
      // Fails because the component is never generated.
      assertFailsWith<ClassNotFoundException> {
        subcomponentInterface.anvilComponent(componentInterface)
      }

      assertThat(
        contributingInterface extends
          subcomponentInterface
            .anvilComponent(contributingInterface)
            .parentComponentInterface,
      ).isTrue()
    }
  }

  @Test fun `contributed subcomponents can be excluded only with a matching scope`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesSubcomponent
      import com.squareup.anvil.annotations.MergeComponent

      @ContributesSubcomponent(Unit::class, parentScope = Int::class)
      interface SubcomponentInterface

      @MergeComponent(
          scope = Any::class,
          exclude = [SubcomponentInterface::class]
      )
      interface ComponentInterface
      """,
      mode = mode,
      expectExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertThat(messages).contains(
        "com.squareup.test.ComponentInterface with scopes [kotlin.Any] wants to exclude " +
          "com.squareup.test.SubcomponentInterface, but the excluded class isn't contributed " +
          "to the same scope.",
      )
    }
  }

  @Test
  fun `the parent component interface can return a factory`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    // This test tests legacy behavior that we no longer need to do in KSP processing
    assumeFalse(
      mode is AnvilCompilationMode.Ksp && componentProcessingMode != ComponentProcessingMode.KSP,
    )
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesSubcomponent.Factory
        import com.squareup.anvil.annotations.ContributesTo
        import com.squareup.anvil.annotations.MergeComponent
  
        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface {
          @ContributesTo(Unit::class)
          interface AnyParentComponent {
            fun createFactory(): ComponentFactory
          }

          @Factory
          interface ComponentFactory {
            fun createComponent(): SubcomponentInterface
          }
        }
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      val createFactoryFunction = subcomponentInterface.anvilComponent(componentInterface)
        .parentComponentInterface
        .declaredMethods
        .single()

      assertThat(createFactoryFunction.returnType)
        .isEqualTo(subcomponentInterface.anvilComponent(componentInterface).generatedFactory)
      assertThat(createFactoryFunction.name).isEqualTo("createFactory")

      assertThat(
        subcomponentInterface.anvilComponent(componentInterface).generatedFactory extends
          subcomponentInterface.componentFactory,
      ).isTrue()
    }
  }

  @Test
  fun `a factory can be an abstract class`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    // This test tests legacy behavior that we no longer need to do in KSP processing
    assumeFalse(
      mode is AnvilCompilationMode.Ksp && componentProcessingMode != ComponentProcessingMode.KSP,
    )
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesSubcomponent.Factory
        import com.squareup.anvil.annotations.ContributesTo
        import com.squareup.anvil.annotations.MergeComponent
  
        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface {
          @ContributesTo(Unit::class)
          interface AnyParentComponent {
            fun createFactory(): ComponentFactory
          }

          @Factory
          abstract class ComponentFactory {
            abstract fun createComponent(): SubcomponentInterface
          }
        }
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      val createFactoryFunction = subcomponentInterface.anvilComponent(componentInterface)
        .parentComponentInterface
        .declaredMethods
        .single()

      assertThat(createFactoryFunction.returnType)
        .isEqualTo(subcomponentInterface.anvilComponent(componentInterface).generatedFactory)
      assertThat(createFactoryFunction.name).isEqualTo("createFactory")

      assertThat(
        subcomponentInterface.anvilComponent(componentInterface).generatedFactory extends
          subcomponentInterface.componentFactory,
      ).isTrue()
    }
  }

  @Test fun `the generated parent component interface returns the factory if one is present`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    // This test tests legacy behavior that we no longer need to do in KSP processing
    assumeFalse(
      mode is AnvilCompilationMode.Ksp && componentProcessingMode != ComponentProcessingMode.KSP,
    )
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesSubcomponent.Factory
        import com.squareup.anvil.annotations.ContributesTo
        import com.squareup.anvil.annotations.MergeComponent
  
        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface {
          @Factory
          interface ComponentFactory {
            fun createComponent(): SubcomponentInterface
          }
        }
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      val parentComponent =
        subcomponentInterface.anvilComponent(componentInterface).parentComponentInterface
      assertThat(parentComponent).isNotNull()

      val createFactoryFunction = parentComponent.declaredMethods.single()
      assertThat(createFactoryFunction.returnType)
        .isEqualTo(subcomponentInterface.anvilComponent(componentInterface).generatedFactory)
      assertThat(createFactoryFunction.name).isEqualTo("createComponentFactory")

      assertThat(
        subcomponentInterface.anvilComponent(componentInterface).generatedFactory extends
          subcomponentInterface.componentFactory,
      ).isTrue()
    }
  }

  @Test fun `Dagger generates the real component and subcomponent with a factory`() {
    assumeTrue(componentProcessingMode != ComponentProcessingMode.NONE)
    checkFullTestRun()

    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesSubcomponent.Factory
        import com.squareup.anvil.annotations.ContributesTo
        import com.squareup.anvil.annotations.MergeComponent
        import dagger.BindsInstance

        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface {
          @ContributesTo(Unit::class)
          interface AnyParentComponent {
            fun createFactory(): ComponentFactory
          }

          @Factory
          interface ComponentFactory {
            fun createComponent(
              @BindsInstance integer: Int
            ): SubcomponentInterface
          }
          
          fun integer(): Int
        }
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """,
      mode = mode,
      componentProcessingMode = componentProcessingMode,
    ) {
      val daggerComponent = componentInterface.daggerComponent.declaredMethods
        .single { it.name == "create" }
        .invoke(null)

      // Note that there are no declared methods, only inherited methods.
      assertThat(componentInterface.declaredMethods.toList()).isEmpty()

      // There are two methods: one from AnyParentComponent and one from the generated component
      // interface. Both show up in the reflection APIs.
      val factory = componentInterface.methods
        .first { it.name == "createFactory" }
        .invoke(daggerComponent)

      val subcomponent = factory::class.java.declaredMethods
        .getMethodReturningA(subcomponentInterface)
        .use { it.invoke(factory, 5) }

      val int = subcomponent::class.java.declaredMethods
        .single { it.name == "integer" }
        .use { it.invoke(subcomponent) as Int }

      assertThat(int).isEqualTo(5)
    }
  }

  @Test
  fun `the generated factory can be injected`() {
    assumeTrue(componentProcessingMode != ComponentProcessingMode.NONE)
    checkFullTestRun()

    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesSubcomponent.Factory
        import com.squareup.anvil.annotations.MergeComponent
        import dagger.BindsInstance
        import javax.inject.Inject 

        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface {
          @Factory
          interface ComponentFactory {
            fun createComponent(
              @BindsInstance integer: Int
            ): SubcomponentInterface
          }
          
          fun integer(): Int
        }
        
        @MergeComponent(Unit::class)
        interface ComponentInterface {
          fun testClass(): TestClass
        }

        class TestClass @Inject constructor(val factory: SubcomponentInterface.ComponentFactory)
      """,
      mode = mode,
      componentProcessingMode = componentProcessingMode,
    ) {
      val daggerComponent = componentInterface.daggerComponent.declaredMethods
        .single { it.name == "create" }
        .invoke(null)

      val testClassInstance = componentInterface.methods
        .single { it.name == "testClass" }
        .invoke(daggerComponent)

      val factory = testClassInstance::class.java.declaredMethods
        .single { it.name == "getFactory" }
        .invoke(testClassInstance)

      val subcomponent = factory::class.java.declaredMethods
        .getMethodReturningA(subcomponentInterface)
        .use { it.invoke(factory, 5) }

      val int = subcomponent::class.java.declaredMethods
        .single { it.name == "integer" }
        .use { it.invoke(subcomponent) as Int }

      assertThat(int).isEqualTo(5)
    }
  }

  @Test
  fun `the generated factory can be injected in a nested subcomponent`() {
    assumeTrue(componentProcessingMode != ComponentProcessingMode.NONE)
    checkFullTestRun()

    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesSubcomponent.Factory
        import com.squareup.anvil.annotations.MergeComponent
        import dagger.BindsInstance
        import javax.inject.Inject 

        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface {
          @Factory
          interface ComponentFactory {
            fun createComponent(
              @BindsInstance integer: Int
            ): SubcomponentInterface
          }
          
          fun integer(): Int
        } 

        @ContributesSubcomponent(Unit::class, parentScope = Int::class)
        interface ComponentInterface1 {
          fun testClass(): TestClass
        }
        
        @MergeComponent(Int::class)
        interface ComponentInterface2

        class TestClass @Inject constructor(val factory: SubcomponentInterface.ComponentFactory)
      """,
      mode = mode,
      componentProcessingMode = componentProcessingMode,
    ) {
      val daggerComponent = componentInterface2.daggerComponent.declaredMethods
        .single { it.name == "create" }
        .invoke(null)

      val subcomponent1 = componentInterface2.methods
        .getMethodReturningA(componentInterface1)
        .invoke(daggerComponent)

      val testClassInstance = componentInterface1.declaredMethods
        .single { it.name == "testClass" }
        .invoke(subcomponent1)

      val factory = testClassInstance::class.java.declaredMethods
        .single { it.name == "getFactory" }
        .invoke(testClassInstance)

      val subcomponent2 = factory::class.java.declaredMethods
        .getMethodReturningA(subcomponentInterface)
        .use { it.invoke(factory, 5) }

      val int = subcomponent2::class.java.declaredMethods
        .single { it.name == "integer" }
        .use { it.invoke(subcomponent2) as Int }

      assertThat(int).isEqualTo(5)
    }
  }

  @Test
  fun `the generated factory can be injected with multiple compilations`() {
    assumeTrue(componentProcessingMode != ComponentProcessingMode.NONE)
    checkFullTestRun()

    println("Compiling project 1")
    val firstCompilationResult = compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesSubcomponent.Factory
        import com.squareup.anvil.annotations.ContributesTo
        import com.squareup.anvil.annotations.MergeComponent
        import dagger.BindsInstance

        @ContributesSubcomponent(
          scope = Any::class, 
          parentScope = Unit::class
        )
        interface SubcomponentInterface {
          fun integer(): Int

          @Factory
          interface ComponentFactory {
            fun createComponent(@BindsInstance integer: Int): SubcomponentInterface
          }

          @ContributesTo(Unit::class)
          interface AnyParentComponent {
            fun createFactory(): ComponentFactory
          }
        }

        @MergeComponent(Unit::class)
        interface ComponentInterface1
      """.trimIndent(),
      mode = mode,
      componentProcessingMode = componentProcessingMode,
    ) {
      assertThat(exitCode).isEqualTo(OK)

      assertThat(
        componentInterface1 extends subcomponentInterface.anyParentComponentInterface,
      ).isTrue()
    }

    println("Compiling project 2")
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.MergeComponent

        @MergeComponent(Unit::class)
        interface ComponentInterface2
      """.trimIndent(),
      mode = mode,
      previousCompilationResult = firstCompilationResult,
      componentProcessingMode = componentProcessingMode,
    ) {
      assertThat(exitCode).isEqualTo(OK)

      val daggerComponent = componentInterface2.daggerComponent.declaredMethods
        .single { it.name == "create" }
        .invoke(null)

      val factory = componentInterface2.methods
        .first { it.name == "createFactory" }
        .invoke(daggerComponent)

      val subcomponent = factory::class.java.declaredMethods
        .getMethodReturningA(subcomponentInterface)
        .use { it.invoke(factory, 5) }

      val int = subcomponent::class.java.declaredMethods
        .single { it.name == "integer" }
        .use { it.invoke(subcomponent) as Int }

      assertThat(int).isEqualTo(5)
    }
  }

  @Test
  fun `the correct generated factory is bound`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    // This test tests legacy behavior that we no longer need to do in KSP processing
    assumeFalse(
      mode is AnvilCompilationMode.Ksp && componentProcessingMode != ComponentProcessingMode.KSP,
    )
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesSubcomponent.Factory
        import com.squareup.anvil.annotations.MergeComponent
        import dagger.BindsInstance
        import javax.inject.Inject 

        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface {
          @Factory
          interface ComponentFactory {
            fun createComponent(
              @BindsInstance integer: Int
            ): SubcomponentInterface
          }
        }
        
        @MergeComponent(Unit::class)
        interface ComponentInterface1
        
        @MergeComponent(Unit::class)
        interface ComponentInterface2
      """,
      mode = mode,
    ) {
      val modules1 = componentInterface1.getAnnotation(Component::class.java).modules.toList()
      val modules2 = componentInterface2.getAnnotation(Component::class.java).modules.toList()

      val subcomponentModule1 = subcomponentInterface
        .anvilComponent(componentInterface1)
        .declaredClasses
        .single { it.simpleName == SUBCOMPONENT_MODULE }
        .kotlin

      val subcomponentModule2 = subcomponentInterface
        .anvilComponent(componentInterface2)
        .declaredClasses
        .single { it.simpleName == SUBCOMPONENT_MODULE }
        .kotlin

      assertThat(modules1).contains(subcomponentModule1)
      assertThat(modules1).doesNotContain(subcomponentModule2)

      assertThat(modules2).contains(subcomponentModule2)
      assertThat(modules2).doesNotContain(subcomponentModule1)
    }
  }

  @Test
  fun `the correct generated factory is bound - with Dagger`() {
    assumeTrue(componentProcessingMode != ComponentProcessingMode.NONE)
    checkFullTestRun()

    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesSubcomponent.Factory
        import com.squareup.anvil.annotations.MergeComponent
        import dagger.BindsInstance
        import javax.inject.Inject 

        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface {
          @Factory
          interface ComponentFactory {
            fun createComponent(
              @BindsInstance integer: Int
            ): SubcomponentInterface
          }
          
          fun integer(): Int
        }
        
        @MergeComponent(Unit::class)
        interface ComponentInterface1 {
          fun testClass(): TestClass
        }
        
        @MergeComponent(Unit::class)
        interface ComponentInterface2 {
          fun testClass(): TestClass
        }

        class TestClass @Inject constructor(val factory: SubcomponentInterface.ComponentFactory)
      """,
      mode = mode,
      componentProcessingMode = componentProcessingMode,
    ) {
      val daggerComponent1 = componentInterface1.daggerComponent.declaredMethods
        .single { it.name == "create" }
        .invoke(null)

      val testClassInstance1 = componentInterface1.methods
        .single { it.name == "testClass" }
        .invoke(daggerComponent1)

      val factory1 = testClassInstance1::class.java.declaredMethods
        .single { it.name == "getFactory" }
        .invoke(testClassInstance1)

      val mergedPrefix = if (componentProcessingMode == ComponentProcessingMode.KSP) {
        "Merged"
      } else {
        ""
      }
      assertThat(factory1::class.java.name)
        .isEqualTo(
          "${componentInterface1.daggerComponent.canonicalName}\$${mergedPrefix}SubcomponentInterface_30237c4fFactory",
        )

      val daggerComponent2 = componentInterface2.daggerComponent.declaredMethods
        .single { it.name == "create" }
        .invoke(null)

      val testClassInstance2 = componentInterface2.methods
        .single { it.name == "testClass" }
        .invoke(daggerComponent2)

      val factory2 = testClassInstance2::class.java.declaredMethods
        .single { it.name == "getFactory" }
        .invoke(testClassInstance2)

      assertThat(factory2::class.java.name)
        .isEqualTo(
          "${componentInterface2.daggerComponent.canonicalName}\$${mergedPrefix}SubcomponentInterface_42b84d1bFactory",
        )
    }
  }

  @Test fun `the generated subcomponent contains the same scope annotation`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent
        import javax.inject.Singleton
  
        @ContributesSubcomponent(Any::class, Unit::class)
        @Singleton
        interface SubcomponentInterface
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      val anvilComponent = subcomponentInterface.anvilComponent(componentInterface)
      assertThat(anvilComponent).isNotNull()

      val annotation = anvilComponent.getAnnotation(Singleton::class.java)
      assertThat(annotation).isNotNull()
    }
  }

  @Test fun `the generated subcomponent contains the same scope annotation - custom scope`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent
        import javax.inject.Scope
        import javax.inject.Singleton
        import kotlin.reflect.KClass
        
        @Scope
        @Retention(AnnotationRetention.RUNTIME)
        annotation class SingleIn(val clazz: KClass<*>)
  
        @ContributesSubcomponent(Any::class, Unit::class)
        @SingleIn(Any::class)
        @Singleton
        interface SubcomponentInterface
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """.trimIndent(),
      mode = mode,
    ) {
      val anvilComponent = subcomponentInterface.anvilComponent(componentInterface)
      assertThat(anvilComponent).isNotNull()

      val singleIn = classLoader.loadClass("com.squareup.test.SingleIn")
        .asSubclass(Annotation::class.java)

      val annotation = anvilComponent.getAnnotation(singleIn)
      assertThat(annotation).isNotNull()
      assertThat(anvilComponent.getAnnotation(Singleton::class.java)).isNotNull()

      val singleInClass = singleIn.declaredMethods.single().invoke(annotation)
      assertThat(singleInClass).isEqualTo(Any::class.java)
    }
  }

  @Test fun `subcomponent can be contributed and bindings replaced in a 2nd compilation`() {
    assumeTrue(componentProcessingMode != ComponentProcessingMode.NONE)
    checkFullTestRun()

    // This test simulates a compilation in the main source set and test/androidTest source set,
    // where contributed subcomponents are generated a second time for the test components. This
    // test ensures that there are no duplicate generated classes.
    val firstResult = compile(
      """
        package com.squareup.test

        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesTo
        import com.squareup.anvil.annotations.MergeComponent
        import dagger.Module
        import dagger.Provides

        @ContributesSubcomponent(Any::class, parentScope = Unit::class)
        interface SubcomponentInterface1

        @ContributesSubcomponent(Int::class, parentScope = Any::class)
        interface SubcomponentInterface2 {
          fun integer(): Int
        }

        @ContributesTo(Int::class)
        @Module
        object DaggerModule1 {
          @Provides fun provideIntegerFive(): Int = 5
        }

        @MergeComponent(Unit::class)
        interface ComponentInterface1
      """,
      mode = mode,
      componentProcessingMode = componentProcessingMode,
    ) {
      val daggerComponent = componentInterface1.daggerComponent.declaredMethods
        .single { it.name == "create" }
        .invoke(null)

      val subcomponent1 = componentInterface1.methods
        .getMethodReturningA(subcomponentInterface1)
        .invoke(daggerComponent)

      val subcomponent2 = subcomponent1::class.java.declaredMethods
        .getMethodReturningA(subcomponentInterface2)
        .use { it.invoke(subcomponent1) }

      val int = subcomponent2::class.java.declaredMethods
        .single { it.name == "integer" }
        .use { it.invoke(subcomponent2) as Int }

      assertThat(int).isEqualTo(5)
    }

    compile(
      """
        package com.squareup.test

        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesTo
        import com.squareup.anvil.annotations.MergeComponent
        import dagger.Module
        import dagger.Provides

        @ContributesTo(Int::class, replaces = [DaggerModule1::class])
        @Module
        object DaggerModule2 {
          @Provides fun provideIntegerSix(): Int = 6
          @Provides fun provideLongSeven(): Long = 7L
        }

        @ContributesTo(Int::class)
        interface ContributedComponentInterface {
          fun getLong(): Long
        }

        @MergeComponent(Unit::class)
        interface ComponentInterface2
      """,
      mode = mode,
      componentProcessingMode = componentProcessingMode,
      previousCompilationResult = firstResult,
    ) {
      val daggerComponent = componentInterface2.daggerComponent.declaredMethods
        .single { it.name == "create" }
        .invoke(null)

      val subcomponent1 = componentInterface2.methods
        .getMethodReturningA(subcomponentInterface1)
        .invoke(daggerComponent)

      val subcomponent2 = subcomponent1::class.java.declaredMethods
        .getMethodReturningA(subcomponentInterface2)
        .use { it.invoke(subcomponent1) }

      val int = subcomponent2::class.java.declaredMethods
        .single { it.name == "integer" }
        .use { it.invoke(subcomponent2) as Int }

      assertThat(int).isEqualTo(6)

      val long = subcomponent2::class.java.methods
        .single { it.name == "getLong" }
        .use { it.invoke(subcomponent2) as Long }

      assertThat(long).isEqualTo(7L)
    }
  }

  @Test fun `contributed subcomponent parent interfaces are merged with the right component`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesSubcomponent
      import com.squareup.anvil.annotations.MergeComponent

      @ContributesSubcomponent(Unit::class, parentScope = Any::class)
      interface SubcomponentInterface

      @MergeComponent(Any::class)
      interface ComponentInterface1

      @MergeComponent(Any::class)
      interface ComponentInterface2
      """,
      mode = mode,
    ) {
      val parentComponentInterface1 = subcomponentInterface
        .anvilComponent(componentInterface1)
        .parentComponentInterface
      val parentComponentInterface2 = subcomponentInterface
        .anvilComponent(componentInterface2)
        .parentComponentInterface

      assertThat(componentInterface1 extends parentComponentInterface1).isTrue()
      assertThat(componentInterface1 extends parentComponentInterface2).isFalse()

      assertThat(componentInterface2 extends parentComponentInterface2).isTrue()
      assertThat(componentInterface2 extends parentComponentInterface1).isFalse()
    }
  }

  @Test fun `contributed subcomponent class names are compacted`() {
    checkFullTestRun()

    // This test would fail when javac runs during annotation processing with the class names we
    // originally generated. We now encode the parent class name in the package rather than the
    // class name. Then Dagger won't create too long nested class names for subcomponents. See
    // https://github.com/google/dagger/issues/421
    compile(
      """
        package com.squareup.test

        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent

        @ContributesSubcomponent(Int::class, parentScope = Any::class)
        interface SubcomponentInterface

        @MergeComponent(Unit::class)
        interface ComponentInterface
      """,
      """
        package com.squareup.test.superlongpackagename.superlongpackagename.superlongpackagename.superlongpackagename

        import com.squareup.anvil.annotations.ContributesSubcomponent

        @ContributesSubcomponent(scope = Any::class, parentScope = Unit::class)
        interface SubcomponentInterfacewithVeryVeryVeryVeryVeryVeryVeryLongName
      """,
      mode = mode,
      componentProcessingMode = componentProcessingMode,
    ) {
      assertThat(exitCode).isEqualTo(OK)
    }
  }

  @Test fun `an @ContributeSubcomponent class can be generated`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    // TODO support KSP in this too
    assumeTrue(mode is AnvilCompilationMode.Embedded)
    val codeGenerator = simpleCodeGenerator { clazz ->
      clazz
        .takeIf { it.isAnnotatedWith(mergeComponentFqName) }
        ?.let {
          //language=kotlin
          """
            package com.squareup.test
                
            import com.squareup.anvil.annotations.ContributesSubcomponent
            import com.squareup.test.SubcomponentInterface1
      
            @ContributesSubcomponent(
              scope = Any::class, 
              parentScope = Unit::class,
            )
            interface SubcomponentInterface2
          """.trimIndent()
        }
    }

    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent
  
        @ContributesSubcomponent(
          scope = Any::class, 
          parentScope = Unit::class
        )
        interface SubcomponentInterface1
  
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """,
      mode = AnvilCompilationMode.Embedded(listOf(codeGenerator)),
    ) {
      val parentComponentInterface1 = subcomponentInterface1
        .anvilComponent(componentInterface)
        .parentComponentInterface
      val parentComponentInterface2 = subcomponentInterface2
        .anvilComponent(componentInterface)
        .parentComponentInterface

      assertThat(componentInterface extends parentComponentInterface1).isTrue()
      assertThat(componentInterface extends parentComponentInterface2).isTrue()
    }
  }

  @Test fun `an @ContributeSubcomponent class can be generated with a custom factory`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    // TODO support KSP in this too
    assumeTrue(mode is AnvilCompilationMode.Embedded)
    val codeGenerator = simpleCodeGenerator { clazz ->
      clazz
        .takeIf { it.isAnnotatedWith(mergeComponentFqName) }
        ?.let {
          //language=kotlin
          """
            package com.squareup.test
                  
            import com.squareup.anvil.annotations.ContributesSubcomponent
            import com.squareup.anvil.annotations.ContributesTo
            import com.squareup.test.SubcomponentInterface1
        
            @ContributesSubcomponent(
              scope = Any::class, 
              parentScope = Unit::class,
            )
            interface SubcomponentInterface2 {
              @ContributesSubcomponent.Factory
              interface Factory {
                fun create(): SubcomponentInterface2
              }
    
              @ContributesTo(Unit::class)
              interface ParentComponent {
                fun createFactory(): Factory
              }
            }
          """.trimIndent()
        }
    }

    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent
  
        @ContributesSubcomponent(
          scope = Any::class, 
          parentScope = Unit::class
        )
        interface SubcomponentInterface1
  
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """,
      mode = AnvilCompilationMode.Embedded(listOf(codeGenerator)),
    ) {
      val parentComponentInterface1 = subcomponentInterface1
        .anvilComponent(componentInterface)
        .parentComponentInterface

      val parentComponentInterface2 = subcomponentInterface2
        .parentComponentInterface

      assertThat(componentInterface extends parentComponentInterface1).isTrue()
      assertThat(componentInterface extends parentComponentInterface2).isTrue()
    }
  }

  @Test fun `a contributed subcomponent can be replaced`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent
  
        @ContributesSubcomponent(
          scope = Any::class, 
          parentScope = Unit::class
        )
        interface SubcomponentInterface1
  
        @ContributesSubcomponent(
          scope = Any::class, 
          parentScope = Unit::class,
          replaces = [SubcomponentInterface1::class]
        )
        interface SubcomponentInterface2
  
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """,
      mode = mode,
    ) {
      val parentComponentInterface2 = subcomponentInterface2
        .anvilComponent(componentInterface)
        .parentComponentInterface

      assertThat(componentInterface extends parentComponentInterface2).isTrue()

      assertFailsWith<ClassNotFoundException> {
        subcomponentInterface1.anvilComponent(componentInterface)
      }
    }
  }

  @Test
  fun `a replaced subcomponent's exclusions for Dagger modules, component interfaces and bindings are ignored`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesBinding
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.ContributesTo
        import com.squareup.anvil.annotations.MergeComponent
        import dagger.Module

        @Module
        @ContributesTo(Any::class)
        object DaggerModule1

        @ContributesTo(Any::class)
        interface ContributingInterface
  
        @ContributesBinding(Any::class)
        interface SecondContributingInterface : CharSequence
  
        @ContributesSubcomponent(
          scope = Any::class, 
          parentScope = Unit::class,
          exclude = [
            DaggerModule1::class, 
            ContributingInterface::class, 
            SecondContributingInterface::class
          ]
        )
        interface SubcomponentInterface1
        
        @ContributesSubcomponent(
          scope = Any::class, 
          parentScope = Unit::class,
          replaces = [SubcomponentInterface1::class]
        )
        interface SubcomponentInterface2
        
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """,
      mode = mode,
    ) {
      val annotation = subcomponentInterface2.anvilComponent(componentInterface)
        .getAnnotation(MergeSubcomponent::class.java)

      assertThat(annotation.exclude).isEmpty()
    }
  }

  @Test
  fun `a subcomponent can replace another subcomponent with a different parent scope`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent
  
        @ContributesSubcomponent(
          scope = Any::class, 
          parentScope = Long::class
        )
        interface SubcomponentInterface1
  
        @ContributesSubcomponent(
          scope = Long::class, 
          parentScope = Unit::class
        )
        interface SubcomponentInterface2
  
        @ContributesSubcomponent(
          scope = Any::class, 
          parentScope = Unit::class,
          replaces = [SubcomponentInterface1::class]
        )
        interface SubcomponentInterface3
  
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """,
      mode = mode,
    ) {
      val parentComponentInterface2 = subcomponentInterface2
        .anvilComponent(componentInterface)
        .parentComponentInterface

      val parentComponentInterface3 = subcomponentInterface3
        .anvilComponent(componentInterface)
        .parentComponentInterface

      assertThat(componentInterface extends parentComponentInterface2).isTrue()
      assertThat(componentInterface extends parentComponentInterface3).isTrue()

      assertFailsWith<ClassNotFoundException> {
        subcomponentInterface1.anvilComponent(componentInterface)
      }

      // It does not contain the parent interface for subcomponentInterface1.
      assertThat(
        subcomponentInterface2
          .anvilComponent(componentInterface)
          .interfaces
          .toList(),
      ).containsExactly(subcomponentInterface2)
    }
  }

  @Test
  fun `a previously generated contributed subcomponent can be replaced in a later round of generations`() {
    assumeTrue(componentProcessingMode == ComponentProcessingMode.NONE)
    // TODO support KSP in this too
    assumeTrue(mode is AnvilCompilationMode.Embedded)
    val codeGenerator = simpleCodeGenerator { clazz ->
      clazz
        .takeIf { it.isAnnotatedWith(mergeComponentFqName) }
        ?.let {
          //language=kotlin
          """
            package com.squareup.test
                
            import com.squareup.anvil.annotations.ContributesSubcomponent
      
            @ContributesSubcomponent(
              scope = Any::class, 
              parentScope = Unit::class,
              replaces = [SubcomponentInterface1::class]
            )
            interface SubcomponentInterface2
          """.trimIndent()
        }
    }

    compile(
      """
        package com.squareup.test
  
        import com.squareup.anvil.annotations.ContributesSubcomponent
        import com.squareup.anvil.annotations.MergeComponent
  
        @ContributesSubcomponent(
          scope = Any::class, 
          parentScope = Unit::class,
        )
        interface SubcomponentInterface1
  
        @MergeComponent(Unit::class)
        interface ComponentInterface
      """,
      mode = AnvilCompilationMode.Embedded(listOf(codeGenerator)),
    ) {
      val parentComponentInterface2 = subcomponentInterface2
        .anvilComponent(componentInterface)
        .parentComponentInterface

      assertThat(componentInterface extends parentComponentInterface2).isTrue()

      assertFailsWith<ClassNotFoundException> {
        subcomponentInterface1.anvilComponent(componentInterface)
      }
    }
  }

  // E.g. anvil.component.com.squareup.test.componentinterface.SubcomponentInterfaceA
  private fun Class<*>.anvilComponent(parent: Class<*>): Class<*> {
    return classLoader.loadClass(
      classId.generatedAnvilSubcomponentClassId(parent.originIfMerged().classId)
        .asFqNameString(),
    ).resolveIfMerged()
  }

  private val Class<*>.anyParentComponentInterface: Class<*>
    get() = classLoader.loadClass("$canonicalName\$AnyParentComponent")

  private val Class<*>.parentComponentInterface: Class<*>
    get() = classLoader.loadClass("$canonicalName\$$PARENT_COMPONENT")

  private val Class<*>.generatedFactory: Class<*>
    get() = classLoader.loadClass("$canonicalName\$$SUBCOMPONENT_FACTORY")

  private val Class<*>.componentFactory: Class<*>
    get() = classLoader.loadClass("$canonicalName\$ComponentFactory")

  @Suppress("Since15")
  private val Class<*>.daggerComponent: Class<*>
    get() = classLoader.loadClass("$packageName.Dagger$simpleName")

  private val JvmCompilationResult.componentInterface1: Class<*>
    get() = classLoader.loadClass("com.squareup.test.ComponentInterface1")
      .resolveIfMerged()

  private val JvmCompilationResult.componentInterface2: Class<*>
    get() = classLoader.loadClass("com.squareup.test.ComponentInterface2")
      .resolveIfMerged()

  private val JvmCompilationResult.subcomponentInterface1: Class<*>
    get() = classLoader.loadClass("com.squareup.test.SubcomponentInterface1")
      .resolveIfMerged()

  private val JvmCompilationResult.subcomponentInterface2: Class<*>
    get() = classLoader.loadClass("com.squareup.test.SubcomponentInterface2")
      .resolveIfMerged()

  private val JvmCompilationResult.subcomponentInterface3: Class<*>
    get() = classLoader.loadClass("com.squareup.test.SubcomponentInterface3")
      .resolveIfMerged()

  private fun Array<Method>.getMethodReturningA(clazz: Class<*>): Method {
    // We may have multiple methods that return the direct class or subtype. In this
    // scenario, we prefer the direct equals method.
    return firstOrNull { it.returnType == clazz }
      ?: firstOrNull { it.returnType.extends(clazz) }
      ?: throw AssertionError(
        "Method returning $clazz not found. Available methods are ${
          joinToString(
            "\n",
          )
        }",
      )
  }
}
