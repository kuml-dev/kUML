package dev.kuml.profile.spring

import dev.kuml.profile.ProfileRegistry
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.javaee.javaEeProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * AP-5a.2 profile tests for the Spring profile.
 *
 * Covers:
 * 1. Whitelist — exactly 2 stereotypes
 * 2. Profile extends JavaEE by namespace (D12)
 * 3. Stereotyp-Spezialisierung: RestController specializes Controller, SpringData specializes Repository
 * 4. ServiceLoader discovery
 * 5. Vererbungs-Test: springProfile alone does not contain Entity/Controller
 * 6. Properties on Spring stereotypes
 */
class SpringProfileTest :
    FunSpec({

        beforeEach { ProfileRegistry.clear() }
        afterEach { ProfileRegistry.clear() }

        // ── Test 1: Whitelist — exactly 2 stereotypes ────────────────────────────

        test("springProfile has exactly 2 stereotypes") {
            val names = springProfile.stereotypes.map { it.name }.toSet()
            names shouldContainExactlyInAnyOrder setOf("RestController", "SpringData")
            springProfile.stereotypes.size shouldBe 2
        }

        // ── Test 2: springProfile extends javaEeProfile by namespace ─────────────

        test("springProfile extends javaEeProfile by namespace") {
            springProfile.extendsProfiles shouldBe listOf("dev.kuml.profiles.javaee")
        }

        // ── Test 3: RestController specializes Controller ─────────────────────────

        test("RestController specializes Controller") {
            val rc = springProfile.stereotype("RestController")
            rc shouldNotBe null
            rc!!.specializes shouldBe "Controller"
        }

        // ── Test 4: SpringData specializes Repository ─────────────────────────────

        test("SpringData specializes Repository") {
            val sd = springProfile.stereotype("SpringData")
            sd shouldNotBe null
            sd!!.specializes shouldBe "Repository"
        }

        // ── Test 5: ServiceLoader discovery (both profiles on classpath) ──────────

        test("springProfile is discovered via ProfileRegistry.loadFromClasspath") {
            ProfileRegistry.loadFromClasspath()
            val found = ProfileRegistry.get("dev.kuml.profiles.spring")
            found shouldNotBe null
            found!!.name shouldBe "Spring"
            found.stereotype("RestController") shouldNotBe null
            found.stereotype("SpringData") shouldNotBe null
        }

        // ── Test 6: springProfile alone does not contain JavaEE stereotypes ───────

        test("springProfile does not directly contain Entity or Controller stereotypes") {
            // Entity and Controller live in JavaEE, not Spring's own stereotype list
            springProfile.stereotype("Entity") shouldBe null
            springProfile.stereotype("Controller") shouldBe null
        }

        // ── Test 7: RestController has produces and consumes properties ───────────

        test("RestController has produces and consumes properties with json defaults") {
            val rc = springProfile.stereotype("RestController")
            rc shouldNotBe null
            rc!!.properties.size shouldBe 2

            val produces = rc.properties.first { it.name == "produces" }
            produces.default shouldBe "application/json"

            val consumes = rc.properties.first { it.name == "consumes" }
            consumes.default shouldBe "application/json"
        }

        // ── Test 8: validateClosure passes when both profiles are registered ──────

        test("validateClosure passes when both JavaEE and Spring are registered") {
            ProfileRegistry.register(javaEeProfile)
            ProfileRegistry.register(springProfile)
            // Should not throw — Controller is in JavaEE, accessible via extends-closure
            ProfileRegistry.validateClosure()
        }

        // ── Test 9: All Spring stereotypes target UmlMetaclass.Class ─────────────

        test("all Spring stereotypes target UmlMetaclass.Class") {
            for (s in springProfile.stereotypes) {
                s.targetMetaclass shouldBe UmlMetaclass.Class
            }
        }
    })
