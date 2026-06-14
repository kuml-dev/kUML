package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import dev.kuml.codegen.reverse.ReverseDiagnostic
import dev.kuml.uml.UmlProperty
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class JavaAssociationDetectorTest :
    FunSpec({
        // Detector backed by a solver that knows about "UserClass" as a user-defined type
        fun makeDetector(userTypeNames: Set<String>): JavaAssociationDetector {
            val solver = CombinedTypeSolver().apply { add(ReflectionTypeSolver(false)) }
            return JavaAssociationDetector(JavaTypeResolver(solver, userTypeNames))
        }

        fun firstField(src: String): FieldDeclaration = StaticJavaParser.parse(src).findAll(FieldDeclaration::class.java).first()

        test("field of resolvable user class becomes UmlAssociation 1..1") {
            val field = firstField("class Owner { private UserClass user; }")
            val variable = field.variables.first()
            val detector = makeDetector(setOf("UserClass"))
            val result = detector.classify(field, variable, "Owner", "Owner.java")
            result.shouldBeInstanceOf<JavaAssociationDetector.FieldClassification.AsAssociation>()
            val assoc = (result as JavaAssociationDetector.FieldClassification.AsAssociation).association
            assoc.ends[1].typeId shouldBe "UserClass"
            assoc.ends[1].multiplicity.lower shouldBe 1
            assoc.ends[1].multiplicity.upper shouldBe 1
        }

        test("field List of user class becomes UmlAssociation 0..*") {
            val field = firstField("import java.util.List; class Owner { private List<UserClass> users; }")
            val variable = field.variables.first()
            val detector = makeDetector(setOf("UserClass"))
            val result = detector.classify(field, variable, "Owner", "Owner.java")
            result.shouldBeInstanceOf<JavaAssociationDetector.FieldClassification.AsAssociation>()
            val assoc = (result as JavaAssociationDetector.FieldClassification.AsAssociation).association
            assoc.ends[1].multiplicity.lower shouldBe 0
            assoc.ends[1].multiplicity.upper shouldBe null
        }

        test("field Optional of user class becomes UmlAssociation 0..1") {
            val field = firstField("import java.util.Optional; class Owner { private Optional<UserClass> maybeUser; }")
            val variable = field.variables.first()
            val detector = makeDetector(setOf("UserClass"))
            val result = detector.classify(field, variable, "Owner", "Owner.java")
            result.shouldBeInstanceOf<JavaAssociationDetector.FieldClassification.AsAssociation>()
            val assoc = (result as JavaAssociationDetector.FieldClassification.AsAssociation).association
            assoc.ends[1].multiplicity.lower shouldBe 0
            assoc.ends[1].multiplicity.upper shouldBe 1
        }

        test("field Map K V is skipped with WARN diagnostic REV-J-003") {
            val field = firstField("import java.util.Map; class Owner { private Map<String, UserClass> mapField; }")
            val variable = field.variables.first()
            val detector = makeDetector(setOf("UserClass"))
            val result = detector.classify(field, variable, "Owner", "Owner.java")
            result.shouldBeInstanceOf<JavaAssociationDetector.FieldClassification.Skipped>()
            val skipped = (result as JavaAssociationDetector.FieldClassification.Skipped)
            skipped.diagnostic.code shouldBe "REV-J-003"
            skipped.diagnostic.severity shouldBe ReverseDiagnostic.Severity.WARN
        }

        test("field of primitive type becomes UmlProperty (not association)") {
            val field = firstField("class Foo { private int count; }")
            val variable = field.variables.first()
            val detector = makeDetector(emptySet())
            val result = detector.classify(field, variable, "Foo", "Foo.java")
            result.shouldBeInstanceOf<JavaAssociationDetector.FieldClassification.AsProperty>()
            val prop = (result as JavaAssociationDetector.FieldClassification.AsProperty).property
            prop.shouldBeInstanceOf<UmlProperty>()
        }
    })
