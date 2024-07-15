package com.squareup.anvil.annotations.internal

import com.squareup.anvil.annotations.ContributesSubcomponent
import kotlin.reflect.KClass

/**
 * When using [ContributesSubcomponent], we generate some extra information based on the source
 * class.
 *
 * There are two types of contribution supported.
 *
 * ## Factory
 *
 * ```kotlin
 * @ContributesSubcomponent(UserScope::class, parentScope = AppScope::class)
 * interface UserComponent {
 *   @ContributesSubcomponent.Factory
 *   interface ComponentFactory {
 *     fun createComponent(
 *       @BindsInstance integer: Int
 *     ): UserComponent
 *   }
 *
 *   fun integer(): Int
 * }
 * ```
 *
 * In this case, [componentFactory] will be set to the `ComponentFactory` interface. This in turn
 * will generate code like so.
 *
 * ```kotlin
 * // Intermediate merge class
 * @InternalContributedSubcomponentMarker(
 *   originClass = UserComponent::class,
 *   componentFactory = UserComponent.ComponentFactory::class
 * )
 * @MergeSubcomponent(scope = Any::class)
 * interface UserComponent_0536E4Be : UserComponent
 *
 * // Final merged classes
 * @Subcomponent(includes = [MergedUserComponent_0536E4Be.BindingModule::class])
 * interface MergedUserComponent_0536E4Be : UserComponent_0536E4Be {
 *   @Module
 *   interface BindingModule {
 *     @Binds
 *     fun bindComponent(impl: MergedUserComponent_0536E4Be): UserComponent
 *   }
 *
 *   @Module
 *   interface SubcomponentModule {
 *     @Binds
 *     fun bindComponentFactory(impl: ComponentFactory): UserComponent.ComponentFactory
 *   }
 *
 *   @Subcomponent.Factory
 *   interface ComponentFactory : UserComponent.ComponentFactory {
 *     override fun createComponent(@BindsInstance integer: Int): MergedUserComponent_0536E4Be
 *   }
 *
 *   interface ParentComponent {
 *     fun createComponentFactory(): ComponentFactory
 *   }
 * }
 *
 * @Component(modules = [MergedUserComponent_0536E4Be.SubcomponentModule::class])
 * interface MergedAppComponent : AppComponent, MergedUserComponent_0536E4Be.ParentComponent {
 *   @Component.Factory
 *   fun interface AppComponentFactory : AppComponent.Factory {
 *     override fun create(): MergedAppComponent
 *   }
 * }
 * ```
 *
 * ## Contributed Parent Component
 *
 * ```kotlin
 * @ContributesSubcomponent(UserScope::class, parentScope = AppScope::class)
 * interface UserComponent {
 *   @ContributesTo(AppScope::class)
 *   interface UserScopeParentComponent {
 *     fun createComponent(): UserComponent
 *   }
 * }
 * ```
 * In this case, [contributor] will be set to the `UserScopeParentComponent` interface. This in turn
 * will generate code like so.
 *
 * ```kotlin
 * // Intermediate merge class
 * @MergeSubcomponent(scope = Any::class)
 * interface UserComponent_0536E4Be : UserComponent
 *
 * // Final merged classes
 * @InternalContributedSubcomponentMarker(
 *   originClass = UserComponent::class,
 *   contributor = UserComponent.UserScopeParentComponent::class
 * )
 * @Subcomponent(modules = [MergedUserComponent_0536E4Be.BindingModule::class])
 * interface MergedUserComponent_0536E4Be : UserComponent_0536E4Be {
 *   @Module
 *   interface BindingModule {
 *     @Binds
 *     fun bindComponent(impl: MergedUserComponent_0536E4Be): UserComponent
 *   }
 *
 *   interface ParentComponent : UserComponent.UserScopeParentComponent {
 *     override fun createComponent(): MergedUserComponent_0536E4Be
 *   }
 * }
 *
 * @Component
 * interface MergedAppComponent : AppComponent,
 *   UserComponent.UserScopeParentComponent,
 *   MergedUserComponent_0536E4Be.ParentComponent
 * ```
 */
@Target(AnnotationTarget.CLASS)
public annotation class InternalContributedSubcomponentMarker(
  val originClass: KClass<*>,
  val contributor: KClass<*> = Nothing::class,
  val componentFactory: KClass<*> = Nothing::class,
)
