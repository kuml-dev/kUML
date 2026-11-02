@file:Suppress("RequireNamedArguments")

package dev.kuml.sysml2

import dev.kuml.sysml2.constraint.PackageSeverity
import dev.kuml.sysml2.constraint.Sysml2LayoutHintChecker
import dev.kuml.sysml2.constraint.Sysml2NoteAnchors
import dev.kuml.sysml2.constraint.Sysml2PackageChecker
import dev.kuml.sysml2.dsl.print.Sysml2DslPrinter
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.edge.PackageEdgeAdapter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json

/** Wave 2I-a - packages, imports, views, viewpoints: DSL, ids, qualified names, limits, JSON, printer, checker, edge adapter. */
class Sysml2Wave2IaPackagesTest :
    FunSpec({
        fun sample(): Sysml2Model =
            sysml2Model(name = "S") {
                pkg("Common") {
                    attributeDef("Mass", id = "Common::Mass")
                    portDef("Port", id = "Common::Port")
                }
                pkg("Vehicle") {
                    imports("Common::*")
                    imports("Common::Port", public = true)
                    partDef("Vehicle", id = "Vehicle::Vehicle")
                    pkg("Drive") {
                        imports("Common::**")
                        partDef("Motor", id = "Vehicle::Drive::Motor")
                    }
                }
                val vp =
                    viewpoint("Safety") {
                        stakeholder("Engineer")
                        concern("Hazards")
                    }
                val v =
                    view("Overview") {
                        satisfies(vp)
                        expose("Vehicle::*")
                        filter("@Safety")
                    }
                packageDiagram("P") {
                    includeById("pkg:Vehicle")
                    include(v)
                    include(vp)
                }
            }

        test("pkg builds the tree: ids, parents and the member delta without the nested package's members") {
            val m = sample()
            m.packages.map { it.id } shouldContainExactly listOf("pkg:Common", "pkg:Vehicle", "pkg:Vehicle::Drive")
            m.packages.map { it.parentId } shouldContainExactly listOf(null, null, "pkg:Vehicle")
            m.packages[0].memberIds shouldContainExactly listOf("Common::Mass", "Common::Port")
            m.packages[1].memberIds shouldContainExactly listOf("Vehicle::Vehicle")
            m.packages[2].memberIds shouldContainExactly listOf("Vehicle::Drive::Motor")
        }

        test("import kinds follow the suffix, the target is stored without it, public sets the visibility") {
            val imports = sample().imports
            imports.map { it.kind } shouldContainExactly
                listOf(Sysml2ImportKind.NAMESPACE, Sysml2ImportKind.MEMBERSHIP, Sysml2ImportKind.NAMESPACE_RECURSIVE)
            imports.map { it.targetQualifiedName } shouldContainExactly listOf("Common", "Common::Port", "Common")
            imports.map { it.visibility } shouldContainExactly
                listOf(Sysml2ImportVisibility.PRIVATE, Sysml2ImportVisibility.PUBLIC, Sysml2ImportVisibility.PRIVATE)
            imports.map { it.rawTarget } shouldContainExactly listOf("Common::*", "Common::Port", "Common::**")
            imports[0].id shouldBe "import:pkg:Vehicle::Common::*"
        }

        test("qualified names, owner lookup, depth and sub-tree") {
            val m = sample()
            val idx = m.packageIndex()
            idx.qualifiedNameOf(idx.packageById("pkg:Vehicle::Drive")!!) shouldBe "Vehicle::Drive"
            idx.memberQualifiedName(m.definitions.first { it.id == "Vehicle::Drive::Motor" }) shouldBe "Vehicle::Drive::Motor"
            idx.ownerOf("Common::Mass")!!.id shouldBe "pkg:Common"
            idx.depthOf(idx.packageById("pkg:Vehicle::Drive")!!) shouldBe 2
            idx.subtreeOf(idx.packageById("pkg:Vehicle")!!).map { it.id } shouldContainExactly listOf("pkg:Vehicle", "pkg:Vehicle::Drive")
            idx.resolveImportTarget(m.imports[0]).let { (it as Sysml2ImportTarget.PackageTarget).pkg.id } shouldBe "pkg:Common"
            idx.resolveImportTarget(m.imports[1]).let { (it as Sysml2ImportTarget.MemberTarget).definition.id } shouldBe "Common::Port"
        }

        test("views and viewpoints get their ids; satisfies by name and by handle agree") {
            val m = sample()
            m.viewpoints.single().id shouldBe "viewpoint:Safety"
            m.views.single().id shouldBe "view:Overview"
            m.views.single().viewpointIds shouldContainExactly listOf("viewpoint:Safety")
            m.views.single().exposes shouldContainExactly listOf("Vehicle::*")
            val byName =
                sysml2Model("N") {
                    viewpoint("A")
                    view("V") { satisfies("A") }
                }
            byName.views.single().viewpointIds shouldContainExactly listOf("viewpoint:A")
        }

        test("the package diagram lists what was included, in order") {
            val d = sample().diagrams.single() as Sysml2PackageDiagram
            d.elementIds shouldContainExactly listOf("pkg:Vehicle", "view:Overview", "viewpoint:Safety")
        }

        test("builder rejects blank and '::' names, duplicate packages, imports outside a package, blank import targets") {
            shouldThrow<IllegalArgumentException> { sysml2Model("M") { pkg("") } }
            shouldThrow<IllegalArgumentException> { sysml2Model("M") { pkg("A::B") } }
            shouldThrow<IllegalArgumentException> {
                sysml2Model("M") {
                    pkg("A")
                    pkg("A")
                }
            }
            shouldThrow<IllegalArgumentException> { sysml2Model("M") { imports("X::*") } }
            shouldThrow<IllegalArgumentException> { sysml2Model("M") { pkg("A") { imports("  ") } } }
            shouldThrow<IllegalArgumentException> { sysml2Model("M") { pkg("A") { imports("::*") } } }
            shouldThrow<IllegalArgumentException> {
                sysml2Model("M") {
                    pkg("A") {
                        imports("X::*")
                        imports("X::*")
                    }
                }
            }
            shouldThrow<IllegalArgumentException> {
                sysml2Model("M") {
                    viewpoint("A")
                    viewpoint("A")
                }
            }
            shouldThrow<IllegalArgumentException> { sysml2Model("M") { view("V") { expose("") } } }
        }

        test("same-named definitions in two packages need explicit ids; the message says so") {
            val ex =
                shouldThrow<IllegalArgumentException> {
                    sysml2Model("M") {
                        pkg("A") { partDef("X") }
                        pkg("B") { partDef("X") }
                    }
                }
            ex.message!! shouldContain "explicit id"
            sysml2Model("M") {
                pkg("A") { partDef("X", id = "A::X") }
                pkg("B") { partDef("X", id = "B::X") }
            }.packages.size shouldBe 2
        }

        test("limits: depth, package count, import count, entries per view") {
            shouldThrow<IllegalArgumentException> {
                sysml2Model("M") {
                    fun nest(level: Int) {
                        if (level <= Sysml2Limits.PACKAGE_DEPTH + 1) pkg("L$level") { nest(level + 1) }
                    }
                    nest(1)
                }
            }
            shouldThrow<IllegalArgumentException> { sysml2Model("M") { repeat(Sysml2Limits.PACKAGES + 1) { pkg("P$it") } } }
            shouldThrow<IllegalArgumentException> {
                sysml2Model("M") { pkg("A") { repeat(Sysml2Limits.IMPORTS + 1) { imports("T$it::*") } } }
            }
            shouldThrow<IllegalArgumentException> {
                sysml2Model("M") { view("V") { repeat(Sysml2Limits.VIEW_ENTRIES + 1) { expose("e$it") } } }
            }
        }

        test("an old model JSON without the new fields still decodes; the new fields default to empty") {
            val json = Json { ignoreUnknownKeys = true }
            val m = json.decodeFromString(Sysml2Model.serializer(), """{"name":"Old"}""")
            m.packages.shouldBeEmpty()
            m.imports.shouldBeEmpty()
            m.viewpoints.shouldBeEmpty()
            m.views.shouldBeEmpty()
        }

        test("JSON round trip keeps packages, imports, views, viewpoints and the diagram") {
            val json = Json { encodeDefaults = true }
            val m = sample()
            val back = json.decodeFromString(Sysml2Model.serializer(), json.encodeToString(Sysml2Model.serializer(), m))
            back shouldBe m
            back.diagrams.single().shouldBe(m.diagrams.single())
        }

        test("printer emits the id form and the printed DSL names every package, import, viewpoint and view") {
            val dsl = Sysml2DslPrinter.print(sample())
            dsl shouldContain
                """packageById(name = "Drive", id = "pkg:Vehicle::Drive", parentId = "pkg:Vehicle", memberIds = listOf("Vehicle::Drive::Motor"))"""
            dsl shouldContain """importById(ownerPackageId = "pkg:Vehicle", target = "Common::Port", public = true)"""
            dsl shouldContain """importById(ownerPackageId = "pkg:Vehicle::Drive", target = "Common::**")"""
            dsl shouldContain """viewpoint("Safety", id = "viewpoint:Safety") {"""
            dsl shouldContain """satisfiesById("viewpoint:Safety")"""
            dsl shouldContain """packageDiagram("P") {"""
            dsl shouldContain """includeById("pkg:Vehicle")"""
        }

        test("the id form builds the same model: printer output replayed through the builder equals the original") {
            val original = sample()
            // Replay by hand what the printed DSL does (the module has no script engine): packageById / importById / viewpoint / view.
            val replay =
                sysml2Model("S") {
                    original.definitions.filterIsInstance<AttributeDefinition>().forEach { attributeDef(it.name, id = it.id) }
                    portDef("Port", id = "Common::Port")
                    partDef("Vehicle", id = "Vehicle::Vehicle")
                    partDef("Motor", id = "Vehicle::Drive::Motor")
                    original.packages.forEach { packageById(name = it.name, id = it.id, parentId = it.parentId, memberIds = it.memberIds) }
                    original.imports.forEach {
                        importById(
                            ownerPackageId = it.ownerPackageId,
                            target = it.rawTarget,
                            public = it.visibility == Sysml2ImportVisibility.PUBLIC,
                        )
                    }
                    original.viewpoints.forEach { vp ->
                        viewpoint(vp.name, id = vp.id) {
                            vp.stakeholders.forEach { stakeholder(it) }
                            vp.concerns.forEach { concern(it) }
                        }
                    }
                    original.views.forEach { v ->
                        view(v.name, id = v.id) {
                            v.viewpointIds.forEach { satisfiesById(it) }
                            v.exposes.forEach { expose(it) }
                            v.filters.forEach { filter(it) }
                        }
                    }
                }
            replay.packages shouldBe original.packages
            replay.imports shouldBe original.imports
            replay.viewpoints shouldBe original.viewpoints
            replay.views shouldBe original.views
        }

        test("checker: a clean model has no findings") {
            Sysml2PackageChecker.check(sample(), sample().diagrams.single()).shouldBeEmpty()
        }

        test("checker: unknown import target is KUML-PKG-W001, unknown viewpoint KUML-PKG-E002, unknown diagram id KUML-PKG-W003") {
            val m =
                sysml2Model("M") {
                    pkg("A") { imports("Nowhere::*") }
                    view("V") { satisfies("Missing") }
                    packageDiagram("D") {
                        includeById("pkg:A")
                        includeById("bogus")
                    }
                }
            val issues = Sysml2PackageChecker.check(m, m.diagrams.single())
            issues.map { it.code } shouldContainExactly listOf("KUML-PKG-W001", "KUML-PKG-E002", "KUML-PKG-W003")
            issues.single { it.code == "KUML-PKG-E002" }.severity shouldBe PackageSeverity.Error
        }

        test("checker: import of an ancestor or descendant is KUML-PKG-W004; deeper than three levels is KUML-PKG-W005") {
            val m =
                sysml2Model("M") {
                    pkg("A") {
                        imports("A::B::*")
                        pkg("B") { pkg("C") { pkg("D") { partDef("X", id = "X") } } }
                    }
                    packageDiagram("D") { includeById("pkg:A") }
                }
            val codes = Sysml2PackageChecker.check(m, m.diagrams.single()).map { it.code }
            codes shouldContainExactly listOf("KUML-PKG-W004", "KUML-PKG-W005")
        }

        test("checker: package cycle and unknown parent are KUML-PKG-E001 and do not hang") {
            val cyc =
                Sysml2Model(
                    name = "C",
                    packages =
                        listOf(
                            Sysml2Package(id = "a", name = "A", parentId = "b"),
                            Sysml2Package(id = "b", name = "B", parentId = "a"),
                            Sysml2Package(id = "c", name = "C", parentId = "nope"),
                        ),
                )
            Sysml2PackageChecker.check(cyc).map { it.code } shouldContainExactly listOf("KUML-PKG-E001", "KUML-PKG-E001", "KUML-PKG-E001")
            cyc.packageIndex().qualifiedNameOf(cyc.packages[0]) // must terminate
        }

        test("checker: limits are KUML-PKG-E003") {
            val many = Sysml2Model(name = "M", packages = (0..Sysml2Limits.PACKAGES).map { Sysml2Package(id = "p$it", name = "P$it") })
            Sysml2PackageChecker.check(many).map { it.code } shouldContainExactly listOf("KUML-PKG-E003")
        }

        test("edge adapter: stereotypes, public prefix, dash 6 4, satisfy") {
            val m = sample()
            val a = PackageEdgeAdapter(m)
            a.metadataFor(m.imports[0].id)!!.stereotype shouldBe "«import» *"
            a.metadataFor(m.imports[1].id)!!.stereotype shouldBe "public «import»"
            a.metadataFor(m.imports[2].id)!!.stereotype shouldBe "«import» **"
            a.metadataFor(m.imports[0].id)!!.dashArray shouldBe "6 4"
            val sat = a.metadataFor(PackageEdgeAdapter.satisfyEdgeId("view:Overview", "viewpoint:Safety"))!!
            sat.stereotype shouldBe "«satisfy»"
            sat.dashArray shouldBe "6 4"
            a.metadataFor("other") shouldBe null
        }

        test("layout hint checker knows the ids of a drawn package's sub-tree and members") {
            val m =
                sysml2Model("M") {
                    pkg("A") {
                        partDef("X", id = "A::X")
                        pkg("B") { partDef("Y", id = "A::B::Y") }
                    }
                    packageDiagram("D") {
                        includeById("pkg:A")
                        layout { sameLayerById("A::X", "A::B::Y") }
                    }
                }
            Sysml2LayoutHintChecker
                .check(m, m.diagrams.single())
                .map { it.code }
                .filter { it == 431 }
                .shouldBeEmpty()
        }

        test("notes anchored to a definition are supported by the package diagram") {
            val d = Sysml2PackageDiagram(name = "D")
            Sysml2NoteAnchors.supported(d) shouldBe setOf(dev.kuml.sysml2.constraint.NoteAnchorKind.DEFINITION)
        }
    })
