package dev.kuml.core.ocl

import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlParameter
import dev.kuml.uml.UmlProperty

internal object UmlPropertyAccessor {
    internal fun get(
        self: Any,
        prop: String,
    ): Any? =
        when {
            self is UmlClass && prop == "name" -> self.name
            self is UmlClass && prop == "isAbstract" -> self.isAbstract
            self is UmlClass && prop == "attributes" -> self.attributes
            self is UmlClass && prop == "operations" -> self.operations
            self is UmlClass && prop == "constraints" -> self.constraints
            self is UmlClass && prop == "stereotypes" -> self.stereotypes
            self is UmlInterface && prop == "name" -> self.name
            self is UmlInterface && prop == "attributes" -> self.attributes
            self is UmlInterface && prop == "operations" -> self.operations
            self is UmlInterface && prop == "constraints" -> self.constraints
            self is UmlInterface && prop == "stereotypes" -> self.stereotypes
            self is UmlEnumeration && prop == "name" -> self.name
            self is UmlEnumeration && prop == "literals" -> self.literals
            self is UmlProperty && prop == "name" -> self.name
            self is UmlProperty && prop == "isStatic" -> self.isStatic
            self is UmlProperty && prop == "isReadOnly" -> self.isReadOnly
            self is UmlOperation && prop == "name" -> self.name
            self is UmlOperation && prop == "parameters" -> self.parameters
            self is UmlEnumerationLiteral && prop == "name" -> self.name
            self is UmlParameter && prop == "name" -> self.name
            else -> throw OclEvaluationException(
                "Cannot navigate property '$prop' on ${self::class.simpleName}",
            )
        }
}
