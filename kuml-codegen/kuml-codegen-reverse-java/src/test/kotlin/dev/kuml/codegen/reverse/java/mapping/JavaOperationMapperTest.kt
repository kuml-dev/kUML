package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JavaOperationMapperTest :
    FunSpec({

        fun firstMethod(src: String): MethodDeclaration {
            val cu = StaticJavaParser.parse(src)
            return cu.findAll(MethodDeclaration::class.java).first()
        }

        test("void method without params becomes UmlOperation with returnType void") {
            val method = firstMethod("class Foo { public void doNothing() {} }")
            val op = JavaOperationMapper.map(method, "Foo")
            op.name shouldBe "doNothing"
            op.parameters.size shouldBe 0
            op.returnType?.name shouldBe "void"
        }

        test("method with three IN parameters has three UmlParameters in order") {
            val method = firstMethod("class Foo { public String concat(String a, String b, int c) { return a; } }")
            val op = JavaOperationMapper.map(method, "Foo")
            op.parameters.size shouldBe 3
            op.parameters[0].name shouldBe "a"
            op.parameters[1].name shouldBe "b"
            op.parameters[2].name shouldBe "c"
            op.parameters.all { it.direction == dev.kuml.uml.ParameterDirection.IN } shouldBe true
        }

        test("abstract method becomes UmlOperation with isAbstract true") {
            val method = firstMethod("abstract class Foo { public abstract void run(); }")
            val op = JavaOperationMapper.map(method, "Foo")
            op.isAbstract shouldBe true
        }
    })
