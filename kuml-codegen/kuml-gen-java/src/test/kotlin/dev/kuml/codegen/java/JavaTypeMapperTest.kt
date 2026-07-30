package dev.kuml.codegen.java

import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JavaTypeMapperTest :
    FunSpec({

        test("Int (1,1) maps to primitive int") {
            JavaTypeMapper.toJavaType(typeRef = UmlTypeRef(name = "Int"), multiplicity = Multiplicity(lower = 1, upper = 1)) shouldBe "int"
        }

        test("Int (0,1) maps to boxed Integer") {
            JavaTypeMapper.toJavaType(typeRef = UmlTypeRef(name = "Int"), multiplicity = Multiplicity(lower = 0, upper = 1)) shouldBe
                "Integer"
        }

        test("String maps to String regardless of multiplicity") {
            JavaTypeMapper.toJavaType(typeRef = UmlTypeRef(name = "String"), multiplicity = Multiplicity(lower = 1, upper = 1)) shouldBe
                "String"
            JavaTypeMapper.toJavaType(typeRef = UmlTypeRef(name = "String"), multiplicity = Multiplicity(lower = 0, upper = 1)) shouldBe
                "String"
        }

        test("UUID maps to fully qualified java.util.UUID") {
            JavaTypeMapper.toJavaType(typeRef = UmlTypeRef(name = "UUID"), multiplicity = Multiplicity(lower = 1, upper = 1)) shouldBe
                "java.util.UUID"
        }

        test("upper > 1 wraps boxed type in java.util.List") {
            JavaTypeMapper.toJavaType(typeRef = UmlTypeRef(name = "Int"), multiplicity = Multiplicity(lower = 0, upper = null)) shouldBe
                "java.util.List<Integer>"
            JavaTypeMapper.toJavaType(typeRef = UmlTypeRef(name = "String"), multiplicity = Multiplicity(lower = 1, upper = null)) shouldBe
                "java.util.List<String>"
        }

        test("Boolean (1,1) maps to primitive boolean") {
            JavaTypeMapper.toJavaType(typeRef = UmlTypeRef(name = "Boolean"), multiplicity = Multiplicity(lower = 1, upper = 1)) shouldBe
                "boolean"
        }

        test("unknown type passes through as-is") {
            JavaTypeMapper.toJavaType(
                typeRef = UmlTypeRef(name = "MyCustomType"),
                multiplicity = Multiplicity(lower = 1, upper = 1),
            ) shouldBe
                "MyCustomType"
        }
    })
