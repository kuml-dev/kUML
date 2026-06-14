package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import dev.kuml.uml.Visibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class JavaClassMapperTest :
    FunSpec({

        fun parseClass(src: String): ClassOrInterfaceDeclaration =
            StaticJavaParser.parse(src).findAll(ClassOrInterfaceDeclaration::class.java).first()

        test("plain public class becomes UmlClass with PUBLIC visibility") {
            val decl = parseClass("package com.example; public class Foo {}")
            val result = JavaClassMapper.map(decl, "com.example")
            result.umlClass.shouldNotBeNull()
            result.umlClass!!.visibility shouldBe Visibility.PUBLIC
            result.umlClass!!.name shouldBe "Foo"
            result.umlClass!!.id shouldBe "com.example.Foo"
        }

        test("abstract class becomes UmlClass with isAbstract true") {
            val decl = parseClass("public abstract class Base {}")
            val result = JavaClassMapper.map(decl, "")
            result.umlClass.shouldNotBeNull()
            result.umlClass!!.isAbstract shouldBe true
        }

        test("generic class Foo<T> emits «template» stereotype and template-params metadata") {
            val decl = parseClass("public class Foo<T> {}")
            val result = JavaClassMapper.map(decl, "")
            result.umlClass.shouldNotBeNull()
            result.umlClass!!.stereotypes shouldContain "template"
            val params = result.umlClass!!.metadata["kuml.template.params"]
            params.shouldNotBeNull()
        }

        test("nested class Outer Inner becomes separate UmlClass with enclosing metadata") {
            val cu = StaticJavaParser.parse("package pkg; public class Outer { public static class Inner {} }")
            val inner = cu.findAll(ClassOrInterfaceDeclaration::class.java).first { it.nameAsString == "Inner" }
            val result = JavaClassMapper.map(inner, "pkg", "Outer")
            result.umlClass.shouldNotBeNull()
            result.umlClass!!.id shouldBe "pkg.Outer.Inner"
            val enclosing = result.umlClass!!.metadata["kuml.java.enclosing"]
            enclosing.shouldNotBeNull()
        }

        test("package-info dot java does not produce a UmlClass") {
            val cu = StaticJavaParser.parse("/** package doc */ package edge;")
            val classes = cu.findAll(ClassOrInterfaceDeclaration::class.java)
            // package-info.java has no class declarations
            classes.size shouldBe 0
        }
    })
