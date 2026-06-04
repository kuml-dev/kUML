package dev.kuml.profile.javaee

import dev.kuml.core.dsl.classDiagram
import dev.kuml.profile.ProfileRegistry
import dev.kuml.profile.UmlMetaclass
import dev.kuml.uml.UmlClass
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.attribute
import dev.kuml.uml.dsl.classOf
import dev.kuml.uml.dsl.stereotype
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * AP-5a.1 profile tests for the JavaEE profile.
 *
 * Covers:
 * 1. Whitelist — exactly 4 stereotypes
 * 2. Target metaclass checks for all 4 stereotypes (all UmlMetaclass.Class)
 * 3. Properties with correct definitions and defaults (D11)
 * 4. ServiceLoader discovery via ProfileRegistry.loadFromClasspath()
 * 5. Profile metadata
 */
class JavaEeProfileTest :
    FunSpec({

        beforeEach { ProfileRegistry.clear() }
        afterEach { ProfileRegistry.clear() }

        // ── Test 1: Whitelist — exactly 5 stereotypes (V1.1.2: +PersistenceContext) ─

        test("javaEeProfile has exactly 5 stereotypes") {
            val names = javaEeProfile.stereotypes.map { it.name }.toSet()
            names shouldContainExactlyInAnyOrder
                setOf("Entity", "Repository", "Service", "Controller", "PersistenceContext")
            javaEeProfile.stereotypes.size shouldBe 5
        }

        // ── Test 2: Class-level stereotypes target UmlMetaclass.Class ────────────

        test("Entity, Repository, Service, Controller target UmlMetaclass.Class") {
            for (name in listOf("Entity", "Repository", "Service", "Controller")) {
                val s = javaEeProfile.stereotype(name)
                s shouldNotBe null
                s!!.targetMetaclass shouldBe UmlMetaclass.Class
            }
        }

        // ── Test 3: Entity has 3 properties with correct defaults ─────────────────

        test("Entity has 3 properties with correct defaults") {
            val entity = javaEeProfile.stereotype("Entity")
            entity shouldNotBe null
            entity!!.properties.size shouldBe 3

            val tableName = entity.properties.first { it.name == "tableName" }
            tableName.required shouldBe true
            tableName.default shouldBe null

            val schema = entity.properties.first { it.name == "schema" }
            schema.required shouldBe false
            schema.default shouldBe "public"

            val cacheable = entity.properties.first { it.name == "cacheable" }
            cacheable.required shouldBe false
            cacheable.default shouldBe false
        }

        // ── Test 4: Repository has dataSource property with default ───────────────

        test("Repository has dataSource property with default 'default'") {
            val repo = javaEeProfile.stereotype("Repository")
            repo shouldNotBe null
            repo!!.properties.size shouldBe 1
            val ds = repo.properties.first()
            ds.name shouldBe "dataSource"
            ds.default shouldBe "default"
            ds.required shouldBe false
        }

        // ── Test 5: Service has transactional property with default true ──────────

        test("Service has transactional property with default true") {
            val svc = javaEeProfile.stereotype("Service")
            svc shouldNotBe null
            svc!!.properties.size shouldBe 1
            val tx = svc.properties.first()
            tx.name shouldBe "transactional"
            tx.default shouldBe true
            tx.required shouldBe false
        }

        // ── Test 6: Controller has requestMapping property with default "/" ────────

        test("Controller has requestMapping property with default '/'") {
            val ctrl = javaEeProfile.stereotype("Controller")
            ctrl shouldNotBe null
            ctrl!!.properties.size shouldBe 1
            val rm = ctrl.properties.first()
            rm.name shouldBe "requestMapping"
            rm.default shouldBe "/"
            rm.required shouldBe false
        }

        // ── Test 7: ServiceLoader discovery ──────────────────────────────────────

        test("javaEeProfile is discovered via ProfileRegistry.loadFromClasspath") {
            ProfileRegistry.loadFromClasspath()
            val found = ProfileRegistry.get("dev.kuml.profiles.javaee")
            found shouldNotBe null
            found!!.name shouldBe "JavaEE"
            found.stereotype("Entity") shouldNotBe null
            found.stereotype("Repository") shouldNotBe null
            found.stereotype("Service") shouldNotBe null
            found.stereotype("Controller") shouldNotBe null
            found.stereotype("PersistenceContext") shouldNotBe null
        }

        // ── Test 8: Profile metadata ──────────────────────────────────────────────

        test("javaEeProfile has correct namespace, version and no parent profiles") {
            javaEeProfile.namespace shouldBe "dev.kuml.profiles.javaee"
            javaEeProfile.version shouldBe "1.0.0"
            javaEeProfile.extendsProfiles shouldBe emptyList()
        }

        // ── Test 9 (V1.1.2): PersistenceContext targets UmlMetaclass.Property ─────

        test("PersistenceContext targets UmlMetaclass.Property") {
            val pc = javaEeProfile.stereotype("PersistenceContext")
            pc shouldNotBe null
            pc!!.targetMetaclass shouldBe UmlMetaclass.Property
        }

        // ── Test 10 (V1.1.2): PersistenceContext has unitName and type properties ──

        test("PersistenceContext has unitName (default 'default') and type (default 'TRANSACTION')") {
            val pc = javaEeProfile.stereotype("PersistenceContext")
            pc shouldNotBe null
            pc!!.properties.size shouldBe 2

            val unitName = pc.properties.first { it.name == "unitName" }
            unitName.required shouldBe false
            unitName.default shouldBe "default"

            val type = pc.properties.first { it.name == "type" }
            type.required shouldBe false
            type.default shouldBe "TRANSACTION"
        }

        // ── Test 11 (V1.1.2): PersistenceContext DSL stores appliedStereotype ──────

        test("PersistenceContext applied via DSL stores entry in appliedStereotypes") {
            val diagram =
                classDiagram("PC Test") {
                    applyProfile(javaEeProfile)
                    classOf("UserService") {
                        attribute("em", "EntityManager") {
                            stereotype("PersistenceContext") {
                                "unitName" to "myPU"
                            }
                        }
                    }
                }
            val cls = diagram.elements.filterIsInstance<UmlClass>().first()
            val attr = cls.attributes.first()
            attr.appliedStereotypes.size shouldBe 1
            attr.appliedStereotypes.first().stereotypeName shouldBe "PersistenceContext"
        }
    })
