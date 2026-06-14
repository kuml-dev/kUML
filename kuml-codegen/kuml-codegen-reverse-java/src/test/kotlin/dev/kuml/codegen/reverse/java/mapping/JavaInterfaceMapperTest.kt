package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class JavaInterfaceMapperTest :
    FunSpec({

        test("interface becomes UmlInterface with public visibility") {
            val cu = StaticJavaParser.parse("package svc; public interface Runnable {}")
            val decl = cu.findAll(ClassOrInterfaceDeclaration::class.java).first()
            val result = JavaClassMapper.map(decl, "svc")
            result.umlInterface.shouldNotBeNull()
            result.umlInterface!!.visibility shouldBe Visibility.PUBLIC
            result.umlInterface!!.name shouldBe "Runnable"
        }

        test("interface extends another interface emits UmlGeneralization") {
            val cu = StaticJavaParser.parse("interface Child extends Parent {}")
            val decl = cu.findAll(ClassOrInterfaceDeclaration::class.java).first { it.nameAsString == "Child" }
            val genResult = JavaGeneralizationMapper.map(decl, "Child", "Child.rel")
            genResult.generalizations.size shouldBe 1
            genResult.generalizations.first().specificId shouldBe "Child"
            genResult.generalizations.first().generalId shouldBe "Parent"
            // interfaces use extends, not implements → no realizations
            genResult.realizations.size shouldBe 0
        }
    })
