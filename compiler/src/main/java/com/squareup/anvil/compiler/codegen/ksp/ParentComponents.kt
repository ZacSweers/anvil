package com.squareup.anvil.compiler.codegen.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.anvil.compiler.contributesToFqName

// TODO caching? Maybe in ClassScanner?
internal fun findParentComponentInterface(
  componentClass: KSClassDeclaration,
  factoryClass: KSClassDeclaration?,
  parentScopeType: KSType?,
): KSClassDeclaration? {
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

  val functions = componentInterface.overridableParentComponentFunctions(
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

  return componentInterface
}
