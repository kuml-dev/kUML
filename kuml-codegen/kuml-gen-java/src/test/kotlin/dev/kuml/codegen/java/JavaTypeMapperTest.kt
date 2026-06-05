package dev.kuml.codegen.java

import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JavaTypeMapperTest :
    FunSpec({

        test("Int (1,1) maps to primitive int") {
            JavaTypeMapper.toJavaType(UmlTypeRef("Int"), Multiplicity(1, 1)) shouldBe "int"
        }

        test("Int (0,1) maps to boxed Integer") {
            JavaTypeMapper.toJavaType(UmlTypeRef("Int"), Multiplicity(0, 1)) shouldBe "Integer"
        }

        test("String maps to String regardless of multiplicity") {
            JavaTypeMapper.toJavaType(UmlTypeRef("String"), Multiplicity(1, 1)) shouldBe "String"
            JavaTypeMapper.toJavaType(UmlTypeRef("String"), Multiplicity(0, 1)) shouldBe "String"
        }

        test("UUID maps to fully qualified java.util.UUID") {
            JavaTypeMapper.toJavaType(UmlTypeRef("UUID"), Multiplicity(1, 1)) shouldBe "java.util.UUID"
        }

        test("upper > 1 wraps boxed type in java.util.List") {
            JavaTypeMapper.toJavaType(UmlTypeRef("Int"), Multiplicity(0, null)) shouldBe
                "java.util.List<Integer>"
            JavaTypeMapper.toJavaType(UmlTypeRef("String"), Multiplicity(1, null)) shouldBe
                "java.util.List<String>"
        }

        test("Boolean (1,1) maps to primitive boolean") {
            JavaTypeMapper.toJavaType(UmlTypeRef("Boolean"), Multiplicity(1, 1)) shouldBe "boolean"
        }

        test("unknown type passes through as-is") {
            JavaTypeMapper.toJavaType(UmlTypeRef("MyCustomType"), Multiplicity(1, 1)) shouldBe "MyCustomType"
        }
    })
