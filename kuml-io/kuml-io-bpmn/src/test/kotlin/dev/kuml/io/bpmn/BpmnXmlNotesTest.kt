package dev.kuml.io.bpmn

import dev.kuml.bpmn.dsl.bpmnModel
import dev.kuml.bpmn.model.BpmnAssociationDirection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class BpmnXmlNotesTest :
    FunSpec(body = {

        fun wrap(inner: String) = """<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" name="X">$inner</definitions>"""

        test("export and import round trip textAnnotation and association") {
            val model =
                bpmnModel(name = "M") {
                    process(id = "p") {
                        val t = task(name = "T")
                        textAnnotation(text = "a <b> & \"c\"\nline2", t)
                    }
                }
            val xml = BpmnXmlExporter().export(model)
            xml shouldContain "<textAnnotation id=\"p_annotation_1\">"
            xml shouldContain "a &lt;b&gt; &amp; \"c\""
            xml shouldContain "associationDirection=\"None\""
            val back = BpmnXmlImporter().import(xml).processes.single()
            back.textAnnotations
                .single()
                .note.body shouldBe "a <b> & \"c\"\nline2"
            back.associations
                .single()
                .link.noteId shouldBe "p_annotation_1"
            back.associations
                .single()
                .link.targetId shouldBe "p_task_1"
            back.associations.single().direction shouldBe BpmnAssociationDirection.NONE
        }

        test("import normalises an association whose targetRef is the annotation") {
            val xml =
                wrap(
                    """<process id="p"><task id="t"/><textAnnotation id="n"><text>x</text></textAnnotation>
                       <association id="a" sourceRef="t" targetRef="n" associationDirection="Both"/></process>""",
                )
            val a =
                BpmnXmlImporter()
                    .import(xml)
                    .processes
                    .single()
                    .associations
                    .single()
            a.link.noteId shouldBe "n"
            a.link.targetId shouldBe "t"
            a.direction shouldBe BpmnAssociationDirection.BOTH
        }

        test("association between two non-annotations is ignored") {
            val xml =
                wrap("""<process id="p"><task id="t"/><task id="u"/><association id="a" sourceRef="t" targetRef="u"/></process>""")
            BpmnXmlImporter()
                .import(xml)
                .processes
                .single()
                .associations shouldHaveSize 0
        }

        test("collaboration level annotations round trip") {
            val model =
                bpmnModel(name = "M") {
                    process(id = "p") { task(name = "T") }
                    collaboration(id = "c") {
                        pool(name = "A", id = "poolA") { process("p") }
                        textAnnotation(text = "hello", "p_task_1")
                    }
                }
            val back = BpmnXmlImporter().import(BpmnXmlExporter().export(model)).collaborations.single()
            back.textAnnotations shouldHaveSize 1
            back.associations shouldHaveSize 1
        }

        test("oversized annotation text fails with the id and without echoing the body") {
            val xml =
                wrap("""<process id="p"><textAnnotation id="big"><text>${"secret".repeat(20_000)}</text></textAnnotation></process>""")
            val ex = shouldThrow<IllegalArgumentException> { BpmnXmlImporter().import(xml) }
            ex.message!! shouldContain "big"
            ex.message!! shouldNotContain "secret"
        }

        test("DOCTYPE (XXE) is still rejected") {
            val xml = """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY e SYSTEM "file:///etc/passwd">]><definitions>&e;</definitions>"""
            shouldThrow<Exception> { BpmnXmlImporter().import(xml) }
        }
    })
