package dev.kuml.io.bpmn

import dev.kuml.bpmn.dsl.bpmnModel
import dev.kuml.bpmn.model.BpmnAssociationDirection
import dev.kuml.core.model.note.NoteKind
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

        test("N3: text with markup characters round-trips through XML export and import unchanged") {
            val text = "**b** *i* `c` [l](https://kuml.dev) \\* C:\\Users\n- a\n1. x\n1. y \$x"
            val model =
                bpmnModel(name = "M") {
                    process(id = "p") {
                        val t = task(name = "T")
                        textAnnotation(text = text, t)
                    }
                }
            val back = BpmnXmlImporter().import(BpmnXmlExporter().export(model)).processes.single()
            back.textAnnotations
                .single()
                .note.body shouldBe text
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

        test("notes N2: every note kind survives export and import, in a process and in a collaboration") {
            val model =
                bpmnModel(name = "M") {
                    process(id = "p") {
                        val t = task(name = "T")
                        NoteKind.entries.forEach { textAnnotation(text = "kind ${it.name} <&>", t, id = "n_${it.name}", kind = it) }
                    }
                    collaboration(id = "c") {
                        pool(name = "A", id = "poolA") { process("p") }
                        textAnnotation(text = "in collaboration", "p_task_1", id = "cn", kind = NoteKind.RISK)
                    }
                }
            val xml = BpmnXmlExporter().export(model)
            xml shouldContain "xmlns:kuml=\"https://kuml.dev/schema/bpmn/1\""
            xml shouldContain "kuml:kind=\"WARNING\""
            xml shouldNotContain "kuml:kind=\"NOTE\""
            val back = BpmnXmlImporter().import(xml)
            val byId =
                back.processes
                    .single()
                    .textAnnotations
                    .associate { it.note.id to it.note.kind }
            NoteKind.entries.forEach { byId["n_${it.name}"] shouldBe it }
            back.collaborations
                .single()
                .textAnnotations
                .single()
                .note.kind shouldBe NoteKind.RISK
        }

        test("notes N2: an export without a note kind has no kuml namespace and no kind attribute") {
            val model =
                bpmnModel(name = "M") {
                    process(id = "p") {
                        val t = task(name = "T")
                        textAnnotation(text = "plain", t)
                    }
                }
            val xml = BpmnXmlExporter().export(model)
            xml shouldNotContain "kuml.dev/schema"
            xml shouldNotContain "kuml:"
            BpmnXmlImporter()
                .import(xml)
                .processes
                .single()
                .textAnnotations
                .single()
                .note.kind shouldBe NoteKind.NOTE
        }

        test("notes N2: a missing, empty or unknown kind imports as a plain note") {
            val xml =
                wrap(
                    """<process id="p"><task id="t"/>
                       <textAnnotation id="a" xmlns:kuml="https://kuml.dev/schema/bpmn/1" kuml:kind="NO_SUCH_KIND"><text>x</text></textAnnotation>
                       <textAnnotation id="b" xmlns:kuml="https://kuml.dev/schema/bpmn/1" kuml:kind=""><text>y</text></textAnnotation>
                       <textAnnotation id="c" xmlns:other="urn:other" other:kind="WARNING"><text>z</text></textAnnotation></process>""",
                )
            BpmnXmlImporter()
                .import(xml)
                .processes
                .single()
                .textAnnotations
                .forEach { it.note.kind shouldBe NoteKind.NOTE }
        }
    })
