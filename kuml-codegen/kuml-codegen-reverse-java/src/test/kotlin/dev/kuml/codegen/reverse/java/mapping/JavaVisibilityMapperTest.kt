package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.StaticJavaParser
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JavaVisibilityMapperTest :
    FunSpec({

        test("public protected private default each map to PUBLIC PROTECTED PRIVATE PACKAGE") {
            fun visibilityFor(modifier: String): Visibility {
                val code = if (modifier.isNotBlank()) "$modifier int x;" else "int x;"
                val cu = StaticJavaParser.parse("class T { $code }")
                val field = cu.findAll(com.github.javaparser.ast.body.FieldDeclaration::class.java).first()
                return JavaVisibilityMapper.map(field)
            }

            visibilityFor("public") shouldBe Visibility.PUBLIC
            visibilityFor("protected") shouldBe Visibility.PROTECTED
            visibilityFor("private") shouldBe Visibility.PRIVATE
            visibilityFor("") shouldBe Visibility.PACKAGE
        }
    })
