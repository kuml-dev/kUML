package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.FieldDeclaration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JavaMultiplicityInferrerTest :
    FunSpec({

        fun fieldType(typeDecl: String): com.github.javaparser.ast.type.Type {
            val cu = StaticJavaParser.parse("import java.util.*; class T { $typeDecl x; }")
            return cu.findAll(FieldDeclaration::class.java).first().elementType
        }

        test("List X infers 0 to star") {
            val result = JavaMultiplicityInferrer.infer(fieldType("List<String>"))
            result.multiplicity.lower shouldBe 0
            result.multiplicity.upper shouldBe null
            result.isContainer shouldBe true
        }

        test("Optional X infers 0 to 1") {
            val result = JavaMultiplicityInferrer.infer(fieldType("Optional<String>"))
            result.multiplicity.lower shouldBe 0
            result.multiplicity.upper shouldBe 1
            result.isContainer shouldBe true
        }

        test("X array infers 0 to star") {
            val cu = StaticJavaParser.parse("class T { String[] arr; }")
            // Use variable.type to get the array type (field.elementType strips array brackets)
            val varType =
                cu
                    .findAll(FieldDeclaration::class.java)
                    .first()
                    .variables
                    .first()
                    .type
            val result = JavaMultiplicityInferrer.infer(varType)
            result.multiplicity.lower shouldBe 0
            result.multiplicity.upper shouldBe null
            result.isContainer shouldBe true
        }

        test("plain type infers 1 to 1") {
            val result = JavaMultiplicityInferrer.infer(fieldType("String"))
            result.multiplicity.lower shouldBe 1
            result.multiplicity.upper shouldBe 1
            result.isContainer shouldBe false
        }
    })
