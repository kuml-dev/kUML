package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JavaGeneralizationMapperTest :
    FunSpec({

        test("extends parent class emits UmlGeneralization(specific=this, general=parent)") {
            val cu = StaticJavaParser.parse("class Child extends Parent {}")
            val decl = cu.findAll(ClassOrInterfaceDeclaration::class.java).first { it.nameAsString == "Child" }
            val result = JavaGeneralizationMapper.map(decl, "Child", "Child.rel")
            result.generalizations.size shouldBe 1
            val gen = result.generalizations.first()
            gen.specificId shouldBe "Child"
            gen.generalId shouldBe "Parent"
            result.realizations.size shouldBe 0
        }

        test("implements interface emits UmlInterfaceRealization (not UmlGeneralization)") {
            val cu = StaticJavaParser.parse("class ServiceImpl implements Service {}")
            val decl = cu.findAll(ClassOrInterfaceDeclaration::class.java).first { it.nameAsString == "ServiceImpl" }
            val result = JavaGeneralizationMapper.map(decl, "ServiceImpl", "ServiceImpl.rel")
            result.realizations.size shouldBe 1
            val real = result.realizations.first()
            real.implementingId shouldBe "ServiceImpl"
            real.interfaceId shouldBe "Service"
            result.generalizations.size shouldBe 0
        }
    })
