package dev.kuml.profile.javaee

import dev.kuml.profile.ProfileRegistry
import dev.kuml.profile.UmlMetaclass
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

        // ── Test 1: Whitelist — exactly 4 stereotypes ────────────────────────────

        test("javaEeProfile has exactly 4 stereotypes") {
            val names = javaEeProfile.stereotypes.map { it.name }.toSet()
            names shouldContainExactlyInAnyOrder setOf("Entity", "Repository", "Service", "Controller")
            javaEeProfile.stereotypes.size shouldBe 4
        }

        // ── Test 2: All stereotypes target UmlMetaclass.Class ────────────────────

        test("all JavaEE stereotypes target UmlMetaclass.Class") {
            for (s in javaEeProfile.stereotypes) {
                s.targetMetaclass shouldBe UmlMetaclass.Class
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
        }

        // ── Test 8: Profile metadata ──────────────────────────────────────────────

        test("javaEeProfile has correct namespace, version and no parent profiles") {
            javaEeProfile.namespace shouldBe "dev.kuml.profiles.javaee"
            javaEeProfile.version shouldBe "1.0.0"
            javaEeProfile.extendsProfiles shouldBe emptyList()
        }
    })
