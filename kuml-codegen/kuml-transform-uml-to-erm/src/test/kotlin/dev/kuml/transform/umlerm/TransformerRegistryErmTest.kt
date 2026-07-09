package dev.kuml.transform.umlerm

import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.KumlTransformer
import dev.kuml.codegen.m2m.TransformerRegistry
import dev.kuml.core.model.KumlDiagram
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class TransformerRegistryErmTest :
    FunSpec({

        beforeEach { TransformerRegistry.clear() }
        afterEach { TransformerRegistry.clear() }

        test("both uml-to-erm and uml-to-erm-script are discovered via loadFromClasspath") {
            TransformerRegistry.loadFromClasspath()
            val ids = TransformerRegistry.ids()
            ("uml-to-erm" in ids) shouldBe true
            ("uml-to-erm-script" in ids) shouldBe true
        }

        test("uml-to-erm resolves to a KumlTransformer<KumlDiagram, ErmModel>") {
            TransformerRegistry.loadFromClasspath()
            val transformer = TransformerRegistry.get<KumlDiagram, ErmModel>("uml-to-erm")
            transformer.shouldBeInstanceOf<KumlTransformer<KumlDiagram, ErmModel>>()
        }

        test("uml-to-erm-script resolves to a KumlTransformer<KumlDiagram, List<GeneratedFile>>") {
            TransformerRegistry.loadFromClasspath()
            val transformer = TransformerRegistry.get<KumlDiagram, List<GeneratedFile>>("uml-to-erm-script")
            transformer.shouldBeInstanceOf<KumlTransformer<KumlDiagram, List<GeneratedFile>>>()
        }
    })
