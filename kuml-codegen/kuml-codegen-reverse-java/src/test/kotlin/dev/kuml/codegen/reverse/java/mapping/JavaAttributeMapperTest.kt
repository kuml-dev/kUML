package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.FieldDeclaration
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JavaAttributeMapperTest :
    FunSpec({

        fun firstField(src: String): FieldDeclaration {
            val cu = StaticJavaParser.parse(src)
            return cu.findAll(FieldDeclaration::class.java).first()
        }

        test("private int age becomes UmlProperty with PRIVATE Int 1..1") {
            val field = firstField("class Foo { private int age; }")
            val variable = field.variables.first()
            val prop = JavaAttributeMapper.map(field, variable, "Foo")
            prop.name shouldBe "age"
            prop.visibility shouldBe Visibility.PRIVATE
            prop.type.name shouldBe "Int"
            prop.multiplicity.lower shouldBe 1
            prop.multiplicity.upper shouldBe 1
        }

        test("public static final String CONST becomes UmlProperty isStatic true isReadOnly true") {
            val field = firstField("class Foo { public static final String CONST = \"val\"; }")
            val variable = field.variables.first()
            val prop = JavaAttributeMapper.map(field, variable, "Foo")
            prop.isStatic shouldBe true
            prop.isReadOnly shouldBe true
            prop.visibility shouldBe Visibility.PUBLIC
        }

        test("package-private field becomes UmlProperty with PACKAGE visibility") {
            val field = firstField("class Foo { String data; }")
            val variable = field.variables.first()
            val prop = JavaAttributeMapper.map(field, variable, "Foo")
            prop.visibility shouldBe Visibility.PACKAGE
        }
    })
