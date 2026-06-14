package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.EnumDeclaration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JavaEnumerationMapperTest :
    FunSpec({

        test("enum Color RED GREEN BLUE produces UmlEnumeration with three literals") {
            val cu = StaticJavaParser.parse("package colors; public enum Color { RED, GREEN, BLUE }")
            val decl = cu.findAll(EnumDeclaration::class.java).first()
            val result = JavaEnumerationMapper.map(decl, "colors")
            result.name shouldBe "Color"
            result.id shouldBe "colors.Color"
            result.literals.size shouldBe 3
            result.literals.map { it.name } shouldBe listOf("RED", "GREEN", "BLUE")
        }

        test("enum with constructor and method still produces UmlEnumeration (constructor methods skipped)") {
            val src =
                """
                public enum Planet {
                    MERCURY(3.303e+23, 2.4397e6),
                    VENUS(4.869e+24, 6.0518e6);

                    private final double mass;
                    private final double radius;

                    Planet(double mass, double radius) {
                        this.mass = mass;
                        this.radius = radius;
                    }

                    double surfaceGravity() {
                        final double G = 6.67300E-11;
                        return G * mass / (radius * radius);
                    }
                }
                """.trimIndent()
            val cu = StaticJavaParser.parse(src)
            val decl = cu.findAll(EnumDeclaration::class.java).first()
            val result = JavaEnumerationMapper.map(decl, "")
            result.name shouldBe "Planet"
            result.literals.size shouldBe 2
            result.literals.map { it.name } shouldBe listOf("MERCURY", "VENUS")
        }
    })
