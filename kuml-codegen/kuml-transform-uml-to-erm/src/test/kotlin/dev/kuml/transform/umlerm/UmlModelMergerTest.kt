package dev.kuml.transform.umlerm

import dev.kuml.core.model.DiagramType
import dev.kuml.core.model.KumlDiagram
import dev.kuml.profile.KumlStereotypeApplication
import dev.kuml.profile.erm.ErmProfileNames
import dev.kuml.uml.TagValue
import dev.kuml.uml.UmlAssociation
import dev.kuml.uml.UmlAssociationEnd
import dev.kuml.uml.UmlClass
import dev.kuml.uml.UmlDependency
import dev.kuml.uml.UmlEnumeration
import dev.kuml.uml.UmlEnumerationLiteral
import dev.kuml.uml.UmlGeneralization
import dev.kuml.uml.UmlInterface
import dev.kuml.uml.UmlInterfaceRealization
import dev.kuml.uml.UmlOperation
import dev.kuml.uml.UmlProperty
import dev.kuml.uml.UmlTypeRef
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs

private fun entityClass(
    id: String,
    name: String,
    tableName: String?,
    attributeNames: List<String>,
): UmlClass =
    UmlClass(
        id = id,
        name = name,
        attributes =
            attributeNames.map { attrName ->
                UmlProperty(id = "$id-$attrName", name = attrName, type = UmlTypeRef(name = "String"))
            },
        appliedStereotypes =
            if (tableName == null) {
                emptyList()
            } else {
                listOf(
                    KumlStereotypeApplication(
                        profileNamespace = ErmProfileNames.NAMESPACE,
                        stereotypeName = ErmProfileNames.ENTITY,
                        tags = mapOf(ErmProfileNames.TAG_TABLE_NAME to TagValue.StringVal(tableName)),
                    ),
                )
            },
    )

private fun diagram(
    name: String,
    elements: List<dev.kuml.core.model.KumlElement>,
) = KumlDiagram(name = name, type = DiagramType.CLASS, elements = elements)

private fun src(
    origin: String,
    d: KumlDiagram,
) = UmlModelSource(origin = origin, diagram = d)

private fun permutations(items: List<UmlModelSource>): List<List<UmlModelSource>> =
    if (items.size <= 1) {
        listOf(items)
    } else {
        items.flatMap { head -> permutations(items - head).map { listOf(head) + it } }
    }

class UmlModelMergerTest :
    FunSpec({

        test("a full entity and a same-table stub merge to one class and the stub's association end is remapped") {
            val fooFull = entityClass(id = "a1", name = "Foo", tableName = "foo", attributeNames = listOf("id", "name", "email"))
            val fooStub = entityClass(id = "b1", name = "Foo", tableName = "foo", attributeNames = listOf("id"))
            val bar = entityClass(id = "b2", name = "Bar", tableName = "bar", attributeNames = listOf("id"))
            val assoc =
                UmlAssociation(
                    id = "assoc-bar-foo",
                    ends = listOf(UmlAssociationEnd(typeId = "b2", role = "bar"), UmlAssociationEnd(typeId = "b1", role = "fooId")),
                )

            val merged =
                UmlModelMerger.merge(
                    sources =
                        listOf(
                            src(origin = "a", d = diagram(name = "A", elements = listOf(fooFull))),
                            src(origin = "b", d = diagram(name = "B", elements = listOf(fooStub, bar, assoc))),
                        ),
                )

            val foos = merged.elements.filterIsInstance<UmlClass>().filter { it.name == "Foo" }
            foos shouldHaveSize 1
            foos.single().id shouldBe "a1"
            val mergedAssoc = merged.elements.filterIsInstance<UmlAssociation>().single()
            mergedAssoc.ends.single { it.role == "fooId" }.typeId shouldBe "a1"
            merged.name shouldBe UmlModelMerger.DEFAULT_MERGED_NAME
        }

        test("a non-subset attribute conflict throws with table, classes, origins and attribute") {
            val fooA = entityClass(id = "a1", name = "Foo", tableName = "foo", attributeNames = listOf("id", "name"))
            val fooB = entityClass(id = "b1", name = "FooB", tableName = "foo", attributeNames = listOf("id", "differentAttribute"))

            val ex =
                shouldThrow<UmlModelMergeException> {
                    UmlModelMerger.merge(
                        sources =
                            listOf(
                                src(origin = "b.kuml.kts", d = diagram(name = "B", elements = listOf(fooB))),
                                src(origin = "a.kuml.kts", d = diagram(name = "A", elements = listOf(fooA))),
                            ),
                    )
                }
            ex.tableName shouldBe "foo"
            ex.canonicalClassName shouldBe "Foo"
            ex.canonicalOrigin shouldBe "a.kuml.kts"
            ex.conflictingClassName shouldBe "FooB"
            ex.conflictingOrigin shouldBe "b.kuml.kts"
            ex.missingAttributes shouldBe listOf("differentAttribute")
            listOf("foo", "Foo", "FooB", "a.kuml.kts", "b.kuml.kts", "differentAttribute").forEach { ex.message!! shouldContain it }
            shouldThrow<IllegalStateException> {
                UmlModelMerger.merge(
                    sources =
                        listOf(
                            src(origin = "a", d = diagram(name = "A", elements = listOf(fooA))),
                            src(origin = "b", d = diagram(name = "B", elements = listOf(fooB))),
                        ),
                )
            }
        }

        test("distinct entities merge to the plain union") {
            val foo = entityClass(id = "a1", name = "Foo", tableName = "foo", attributeNames = listOf("id"))
            val baz = entityClass(id = "b1", name = "Baz", tableName = "baz", attributeNames = listOf("id"))
            val merged =
                UmlModelMerger.merge(
                    sources =
                        listOf(
                            src(origin = "a", d = diagram(name = "A", elements = listOf(foo))),
                            src(origin = "b", d = diagram(name = "B", elements = listOf(baz))),
                        ),
                )
            merged.elements.filterIsInstance<UmlClass>().map { it.id } shouldBe listOf("a1", "b1")
        }

        test("identical associations after remapping are deduplicated, role-distinct ones and reversed ends are handled") {
            val foo = entityClass(id = "a1", name = "Foo", tableName = "foo", attributeNames = listOf("id"))
            val bar = entityClass(id = "a2", name = "Bar", tableName = "bar", attributeNames = listOf("id"))
            val a =
                UmlAssociation(
                    id = "assoc-a",
                    ends = listOf(UmlAssociationEnd(typeId = "a2", role = "bar"), UmlAssociationEnd(typeId = "a1", role = "fooId")),
                )
            val fooStub = entityClass(id = "b1", name = "Foo", tableName = "foo", attributeNames = listOf("id"))
            val duplicate =
                UmlAssociation(
                    id = "assoc-b-dup",
                    ends = listOf(UmlAssociationEnd(typeId = "b1", role = "fooId"), UmlAssociationEnd(typeId = "a2", role = "bar")),
                )
            val distinct =
                UmlAssociation(
                    id = "assoc-b-distinct",
                    ends = listOf(UmlAssociationEnd(typeId = "b1", role = "other"), UmlAssociationEnd(typeId = "a2", role = "bar")),
                )
            val nullRoleA = UmlAssociation(id = "n1", ends = listOf(UmlAssociationEnd(typeId = "a1"), UmlAssociationEnd(typeId = "a2")))
            val nullRoleB =
                UmlAssociation(id = "n2", ends = listOf(UmlAssociationEnd(typeId = "b1", role = ""), UmlAssociationEnd(typeId = "a2")))

            val merged =
                UmlModelMerger.merge(
                    sources =
                        listOf(
                            src(origin = "a", d = diagram(name = "A", elements = listOf(foo, bar, a, nullRoleA))),
                            src(origin = "b", d = diagram(name = "B", elements = listOf(fooStub, duplicate, distinct, nullRoleB))),
                        ),
                )
            merged.elements.filterIsInstance<UmlAssociation>().map { it.id } shouldContainExactlyInAnyOrder
                listOf("assoc-a", "assoc-b-distinct", "n1")
            // associations are placed after all non-association elements
            merged.elements.takeLast(3).all { it is UmlAssociation } shouldBe true
        }

        test("a single source is returned as the same instance") {
            val d =
                diagram(
                    name = "A",
                    elements = listOf(entityClass(id = "a1", name = "Foo", tableName = "foo", attributeNames = listOf("id"))),
                )
            UmlModelMerger.merge(sources = listOf(src(origin = "a", d = d))) shouldBeSameInstanceAs d
        }

        test("the result does not depend on the input order") {
            val full = entityClass(id = "Member", name = "Member", tableName = "member", attributeNames = listOf("id", "name"))
            val stub1 = entityClass(id = "Member", name = "Member", tableName = "member", attributeNames = listOf("id"))
            val stub2 = entityClass(id = "Member", name = "Member", tableName = "member", attributeNames = listOf("id"))
            val order = entityClass(id = "Order", name = "Order", tableName = "order", attributeNames = listOf("id"))
            val tag = entityClass(id = "Tag", name = "Tag", tableName = "tag", attributeNames = listOf("id"))
            val assoc1 =
                UmlAssociation(
                    id = "o-m",
                    ends = listOf(UmlAssociationEnd(typeId = "Order", role = "o"), UmlAssociationEnd(typeId = "Member", role = "m")),
                )
            val assoc2 =
                UmlAssociation(
                    id = "t-m",
                    ends = listOf(UmlAssociationEnd(typeId = "Tag", role = "t"), UmlAssociationEnd(typeId = "Member", role = "m")),
                )
            val sources =
                listOf(
                    src(origin = "00", d = diagram(name = "A", elements = listOf(full))),
                    src(origin = "10", d = diagram(name = "B", elements = listOf(stub1, order, assoc1))),
                    src(origin = "20", d = diagram(name = "C", elements = listOf(stub2, tag, assoc2))),
                )
            val expected = UmlModelMerger.merge(sources = sources)
            permutations(sources).forEach { UmlModelMerger.merge(sources = it) shouldBe expected }
        }

        test("a tie on attribute count picks the declaration with the smallest origin") {
            val first = entityClass(id = "x", name = "First", tableName = "t", attributeNames = listOf("id"))
            val second = entityClass(id = "y", name = "Second", tableName = "t", attributeNames = listOf("id"))
            val sources =
                listOf(
                    src(origin = "a", d = diagram(name = "A", elements = listOf(first))),
                    src(origin = "b", d = diagram(name = "B", elements = listOf(second))),
                )
            listOf(sources, sources.reversed()).forEach { input ->
                UmlModelMerger
                    .merge(sources = input)
                    .elements
                    .filterIsInstance<UmlClass>()
                    .single()
                    .name shouldBe "First"
            }
        }

        test("identical enums from two sources are de-duplicated and generalizations are remapped") {
            val enumA = UmlEnumeration(id = "E", name = "E")
            val enumB = UmlEnumeration(id = "E", name = "E")
            val parent = entityClass(id = "p1", name = "P", tableName = "p", attributeNames = listOf("id", "x"))
            val stub = entityClass(id = "p2", name = "P", tableName = "p", attributeNames = listOf("id"))
            val gen = UmlGeneralization(id = "g", specificId = "c", generalId = "p2")
            val merged =
                UmlModelMerger.merge(
                    sources =
                        listOf(
                            src(origin = "a", d = diagram(name = "A", elements = listOf(enumA, parent))),
                            src(origin = "b", d = diagram(name = "B", elements = listOf(enumB, stub, gen))),
                        ),
                )
            merged.elements.filterIsInstance<UmlEnumeration>() shouldHaveSize 1
            merged.elements
                .filterIsInstance<UmlGeneralization>()
                .single()
                .generalId shouldBe "p1"
        }

        test("an enum stub with a literal subset merges into the full enum, independent of source order") {
            fun enumOf(vararg names: String) =
                UmlEnumeration(
                    id = "MemberStatus",
                    name = "MemberStatus",
                    literals = names.map { UmlEnumerationLiteral(id = "l-$it", name = it) },
                )
            val full = enumOf("ACTIVE", "INACTIVE")
            val stub = enumOf("ACTIVE")
            for (order in permutations(
                listOf(
                    src(origin = "00", d = diagram(name = "A", elements = listOf(full))),
                    src(origin = "20", d = diagram(name = "B", elements = listOf(stub))),
                ),
            )) {
                val merged = UmlModelMerger.merge(sources = order)
                val enums = merged.elements.filterIsInstance<UmlEnumeration>()
                enums shouldHaveSize 1
                enums.single().literals.map { it.name } shouldBe listOf("ACTIVE", "INACTIVE")
            }
        }

        test("enums with the same name but diverging literals fail the merge") {
            fun enumOf(vararg names: String) =
                UmlEnumeration(id = "S", name = "S", literals = names.map { UmlEnumerationLiteral(id = "l-$it", name = it) })
            val ex =
                shouldThrow<UmlModelMergeException> {
                    UmlModelMerger.merge(
                        sources =
                            listOf(
                                src(origin = "a", d = diagram(name = "A", elements = listOf(enumOf("X", "Y")))),
                                src(origin = "b", d = diagram(name = "B", elements = listOf(enumOf("X", "Z")))),
                            ),
                    )
                }
            ex.missingAttributes shouldBe listOf("Z")
        }

        test("associations with the same types and roles but different multiplicity from two sources conflict") {
            val a = entityClass(id = "o", name = "Order", tableName = "o", attributeNames = listOf("id"))
            val b = entityClass(id = "m", name = "Member", tableName = "m", attributeNames = listOf("id"))

            fun assoc(
                id: String,
                targetLower: Int,
            ) = UmlAssociation(
                id = id,
                ends =
                    listOf(
                        UmlAssociationEnd(typeId = "o"),
                        UmlAssociationEnd(
                            typeId = "m",
                            role = "memberId",
                            multiplicity = dev.kuml.uml.Multiplicity(lower = targetLower, upper = 1),
                        ),
                    ),
            )
            shouldThrow<UmlModelMergeException> {
                UmlModelMerger.merge(
                    sources =
                        listOf(
                            src(origin = "a", d = diagram(name = "A", elements = listOf(a, b, assoc("x", 1)))),
                            src(origin = "b", d = diagram(name = "B", elements = listOf(a, b, assoc("y", 0)))),
                        ),
                )
            }
        }

        test("role pairing is part of the association signature") {
            val a = entityClass(id = "o", name = "Order", tableName = "o", attributeNames = listOf("id"))
            val b = entityClass(id = "m", name = "Member", tableName = "m", attributeNames = listOf("id"))
            val first =
                UmlAssociation(
                    id = "x",
                    ends = listOf(UmlAssociationEnd(typeId = "o", role = "r1"), UmlAssociationEnd(typeId = "m", role = "r2")),
                )
            val second =
                UmlAssociation(
                    id = "y",
                    ends = listOf(UmlAssociationEnd(typeId = "o", role = "r2"), UmlAssociationEnd(typeId = "m", role = "r1")),
                )
            val merged =
                UmlModelMerger.merge(
                    sources =
                        listOf(
                            src(origin = "a", d = diagram(name = "A", elements = listOf(a, b, first))),
                            src(origin = "b", d = diagram(name = "B", elements = listOf(a, b, second))),
                        ),
                )
            merged.elements.filterIsInstance<UmlAssociation>() shouldHaveSize 2
        }

        test("same-named plain classes in one source are not de-duplicated") {
            val plain1 = entityClass(id = "a", name = "Plain", tableName = null, attributeNames = listOf("id"))
            val plain2 = entityClass(id = "b", name = "Plain", tableName = null, attributeNames = listOf("id"))
            val other = entityClass(id = "o", name = "Other", tableName = null, attributeNames = listOf("id"))
            val merged =
                UmlModelMerger.merge(
                    sources =
                        listOf(
                            src(origin = "a", d = diagram(name = "A", elements = listOf(plain1, plain2))),
                            src(origin = "b", d = diagram(name = "B", elements = listOf(other))),
                        ),
                )
            merged.elements.filterIsInstance<UmlClass>() shouldHaveSize 3
        }

        test("cross-source stubs of plain classes and interfaces are merged into the fullest declaration") {
            val full = entityClass(id = "addr", name = "Address", tableName = null, attributeNames = listOf("street", "city"))
            val stub = entityClass(id = "addr", name = "Address", tableName = null, attributeNames = listOf("street"))
            val itfFull =
                UmlInterface(id = "i", name = "Named", operations = listOf(UmlOperation(id = "i-n", name = "name")))
            val itfStub = UmlInterface(id = "i", name = "Named")
            val merged =
                UmlModelMerger.merge(
                    sources =
                        listOf(
                            src(origin = "a", d = diagram(name = "A", elements = listOf(full, itfFull))),
                            src(origin = "b", d = diagram(name = "B", elements = listOf(stub, itfStub))),
                        ),
                )
            merged.elements.filterIsInstance<UmlClass>().single() shouldBeSameInstanceAs full
            merged.elements.filterIsInstance<UmlInterface>().single() shouldBeSameInstanceAs itfFull
        }

        test("cross-source plain class declarations with unknown members are a merge conflict") {
            val a = entityClass(id = "addr", name = "Address", tableName = null, attributeNames = listOf("street"))
            val b = entityClass(id = "addr", name = "Address", tableName = null, attributeNames = listOf("zip"))
            val ex =
                shouldThrow<UmlModelMergeException> {
                    UmlModelMerger.merge(
                        sources =
                            listOf(
                                src(origin = "a", d = diagram(name = "A", elements = listOf(a))),
                                src(origin = "b", d = diagram(name = "B", elements = listOf(b))),
                            ),
                    )
                }
            ex.missingAttributes shouldBe listOf("zip")
            ex.message!! shouldContain "Address"
        }

        test("associations differing only in name or applied stereotypes are a merge conflict") {
            val x = entityClass(id = "x", name = "X", tableName = "x", attributeNames = listOf("id"))
            val y = entityClass(id = "y", name = "Y", tableName = "y", attributeNames = listOf("id"))

            fun assoc(
                name: String?,
                stereotypes: List<KumlStereotypeApplication>,
            ) = UmlAssociation(
                id = "assoc",
                name = name,
                ends = listOf(UmlAssociationEnd(typeId = "x"), UmlAssociationEnd(typeId = "y", role = "y")),
                appliedStereotypes = stereotypes,
            )
            val cascade =
                KumlStereotypeApplication(
                    profileNamespace = ErmProfileNames.NAMESPACE,
                    stereotypeName = "ForeignKey",
                    tags = mapOf("onDelete" to TagValue.StringVal("CASCADE")),
                )

            fun merge(
                first: UmlAssociation,
                second: UmlAssociation,
            ) = UmlModelMerger.merge(
                sources =
                    listOf(
                        src(origin = "a", d = diagram(name = "A", elements = listOf(x, y, first))),
                        src(origin = "b", d = diagram(name = "B", elements = listOf(x, y, second))),
                    ),
            )
            shouldThrow<UmlModelMergeException> { merge(assoc(null, listOf(cascade)), assoc(null, emptyList())) }
            shouldThrow<UmlModelMergeException> { merge(assoc("owns", emptyList()), assoc(null, emptyList())) }
            val same = merge(assoc("owns", listOf(cascade)), assoc("owns", listOf(cascade)))
            same.elements.filterIsInstance<UmlAssociation>() shouldHaveSize 1
        }

        test("a stereotype-less stub of an entity in another source is merged into the entity") {
            val full = entityClass(id = "a1", name = "Address", tableName = "addresses", attributeNames = listOf("id", "street", "city"))
            val stub = entityClass(id = "b1", name = "Address", tableName = null, attributeNames = listOf("street"))
            val customer = entityClass(id = "b2", name = "Customer", tableName = "customers", attributeNames = listOf("id"))
            val assoc =
                UmlAssociation(
                    id = "as",
                    ends = listOf(UmlAssociationEnd(typeId = "b2", role = "customer"), UmlAssociationEnd(typeId = "b1", role = "address")),
                )
            val merged =
                UmlModelMerger.merge(
                    sources =
                        listOf(
                            src(origin = "a", d = diagram(name = "A", elements = listOf(full))),
                            src(origin = "b", d = diagram(name = "B", elements = listOf(stub, customer, assoc))),
                        ),
                )
            merged.elements
                .filterIsInstance<UmlClass>()
                .filter { it.name == "Address" }
                .single() shouldBeSameInstanceAs full
            merged.elements
                .filterIsInstance<UmlAssociation>()
                .single()
                .ends
                .map { it.typeId } shouldContainExactlyInAnyOrder
                listOf("b2", "a1")
        }

        test("a stereotype-less class with unknown members next to an entity is a merge conflict") {
            val full = entityClass(id = "a1", name = "Address", tableName = "addresses", attributeNames = listOf("id"))
            val stub = entityClass(id = "b1", name = "Address", tableName = null, attributeNames = listOf("zip"))
            shouldThrow<UmlModelMergeException> {
                UmlModelMerger.merge(
                    sources =
                        listOf(
                            src(origin = "a", d = diagram(name = "A", elements = listOf(full))),
                            src(origin = "b", d = diagram(name = "B", elements = listOf(stub))),
                        ),
                )
            }.message!! shouldContain "zip"
        }

        test("equal generalizations, realizations and dependencies from different sources are de-duplicated") {
            fun classes() =
                listOf(
                    entityClass(id = "p", name = "Person", tableName = null, attributeNames = listOf("name")),
                    entityClass(id = "c", name = "Customer", tableName = null, attributeNames = listOf("no")),
                    UmlInterface(id = "i", name = "Named"),
                )

            fun rels() =
                listOf(
                    UmlGeneralization(id = "g", specificId = "c", generalId = "p"),
                    UmlInterfaceRealization(id = "r", implementingId = "c", interfaceId = "i"),
                    UmlDependency(id = "d", clientId = "c", supplierId = "p"),
                )
            val merged =
                UmlModelMerger.merge(
                    sources =
                        listOf(
                            src(origin = "a", d = diagram(name = "A", elements = classes() + rels())),
                            src(origin = "b", d = diagram(name = "B", elements = classes() + rels())),
                        ),
                )
            merged.elements.filterIsInstance<UmlGeneralization>() shouldHaveSize 1
            merged.elements.filterIsInstance<UmlInterfaceRealization>() shouldHaveSize 1
            merged.elements.filterIsInstance<UmlDependency>() shouldHaveSize 1
        }

        test("invalid input is rejected") {
            val d =
                diagram(
                    name = "A",
                    elements = listOf(entityClass(id = "a1", name = "Foo", tableName = "foo", attributeNames = listOf("id"))),
                )
            shouldThrow<IllegalArgumentException> { UmlModelMerger.merge(sources = emptyList()) }
            shouldThrow<IllegalArgumentException> {
                UmlModelMerger.merge(sources = listOf(src(origin = "a", d = d), src(origin = "a", d = d)))
            }.message!! shouldContain "'a'"
            shouldThrow<IllegalArgumentException> {
                UmlModelMerger.merge(
                    sources = listOf(src(origin = "a", d = d), src(origin = "c4", d = KumlDiagram(name = "X", type = DiagramType.STATE))),
                )
            }.message!! shouldContain "c4"
        }
    })
