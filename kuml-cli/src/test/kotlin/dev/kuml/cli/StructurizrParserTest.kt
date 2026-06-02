package dev.kuml.cli

import dev.kuml.cli.structurizr.StructurizrDslParser
import dev.kuml.cli.structurizr.StructurizrElement
import dev.kuml.cli.structurizr.StructurizrView
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class StructurizrParserTest :
    FunSpec({

        // 1. Parse minimal workspace (no elements)
        test("parse minimal workspace with no elements") {
            val dsl =
                """
                workspace "Empty" "An empty workspace" {
                    model {
                    }
                    views {
                    }
                }
                """.trimIndent()

            val workspace = StructurizrDslParser.parse(dsl)

            workspace.name shouldBe "Empty"
            workspace.description shouldBe "An empty workspace"
            workspace.model.elements.shouldBeEmpty()
            workspace.model.relationships.shouldBeEmpty()
            workspace.views.views.shouldBeEmpty()
        }

        // 2. Parse workspace with one person
        test("parse workspace with one person") {
            val dsl =
                """
                workspace "Test" {
                    model {
                        customer = person "Personal Banking Customer" "A customer of the bank."
                    }
                }
                """.trimIndent()

            val workspace = StructurizrDslParser.parse(dsl)
            val elements = workspace.model.elements

            elements shouldHaveSize 1
            val person = elements[0].shouldBeInstanceOf<StructurizrElement.Person>()
            person.identifier shouldBe "customer"
            person.name shouldBe "Personal Banking Customer"
            person.description shouldBe "A customer of the bank."
            person.external shouldBe false
        }

        // 3. Parse workspace with softwareSystem + containers
        test("parse workspace with softwareSystem containing containers") {
            val dsl =
                """
                workspace "BigBank" {
                    model {
                        internetBanking = softwareSystem "Internet Banking System" "Allows customers to check balances." {
                            webApp = container "Web Application" "Web front-end" "Spring MVC" {
                                component "Sign In Controller" "Handles sign-in" "Spring MVC"
                            }
                            mobileApp = container "Mobile App" "iOS/Android app" "React Native"
                        }
                    }
                }
                """.trimIndent()

            val workspace = StructurizrDslParser.parse(dsl)
            val elements = workspace.model.elements

            elements shouldHaveSize 1
            val system = elements[0].shouldBeInstanceOf<StructurizrElement.SoftwareSystem>()
            system.identifier shouldBe "internetBanking"
            system.name shouldBe "Internet Banking System"
            system.description shouldBe "Allows customers to check balances."
            system.containers shouldHaveSize 2

            val webApp = system.containers[0]
            webApp.identifier shouldBe "webApp"
            webApp.name shouldBe "Web Application"
            webApp.technology shouldBe "Spring MVC"
            webApp.components shouldHaveSize 1
            webApp.components[0].name shouldBe "Sign In Controller"

            val mobile = system.containers[1]
            mobile.identifier shouldBe "mobileApp"
            mobile.technology shouldBe "React Native"
        }

        // 4. Parse workspace with relationships
        test("parse workspace with relationships") {
            val dsl =
                """
                workspace "Bank" {
                    model {
                        customer = person "Customer" "Bank customer"
                        banking = softwareSystem "Banking System" "Core banking"
                        customer -> banking "Uses" "HTTPS"
                    }
                }
                """.trimIndent()

            val workspace = StructurizrDslParser.parse(dsl)

            workspace.model.elements shouldHaveSize 2
            workspace.model.relationships shouldHaveSize 1

            val rel = workspace.model.relationships[0]
            rel.sourceIdentifier shouldBe "customer"
            rel.targetIdentifier shouldBe "banking"
            rel.description shouldBe "Uses"
            rel.technology shouldBe "HTTPS"
        }

        // 5. Parse workspace with views
        test("parse workspace with views section") {
            val dsl =
                """
                workspace "Views" {
                    model {
                        sys = softwareSystem "My System" "Description"
                    }
                    views {
                        systemContext sys "Context" "System Context Diagram" {
                            include *
                            autoLayout lr
                        }
                        container sys "Containers" "Container Diagram" {
                            include *
                        }
                        systemLandscape "Landscape" "All systems" {
                            include *
                        }
                        deployment * "Production" "Deploy" "Production Deployment" {
                            include *
                        }
                    }
                }
                """.trimIndent()

            val workspace = StructurizrDslParser.parse(dsl)
            val views = workspace.views.views

            views shouldHaveSize 4

            views[0].shouldBeInstanceOf<StructurizrView.SystemContext>()
            views[1].shouldBeInstanceOf<StructurizrView.Container>()
            views[2].shouldBeInstanceOf<StructurizrView.SystemLandscape>()
            views[3].shouldBeInstanceOf<StructurizrView.Deployment>()
        }

        // 6. Parse workspace with comments
        test("parse workspace ignores line comments") {
            val dsl =
                """
                // This is a comment
                workspace "Commented" { // inline comment
                    # hash comment
                    model {
                        // another comment
                        user = person "User" "A user" # end of line comment
                    }
                }
                """.trimIndent()

            val workspace = StructurizrDslParser.parse(dsl)
            workspace.model.elements shouldHaveSize 1
            val person = workspace.model.elements[0].shouldBeInstanceOf<StructurizrElement.Person>()
            person.name shouldBe "User"
        }

        // 7. Parse group wrapper — elements inside group should be extracted
        test("parse group wrapper treats contents as regular elements") {
            val dsl =
                """
                workspace "Grouped" {
                    model {
                        group "Internal" {
                            system1 = softwareSystem "System One" "First"
                            system2 = softwareSystem "System Two" "Second"
                        }
                        customer = person "Customer" "External user"
                    }
                }
                """.trimIndent()

            val workspace = StructurizrDslParser.parse(dsl)
            val elements = workspace.model.elements

            // All 3 elements should be at the top level after unwrapping the group
            elements shouldHaveSize 3
            elements.filterIsInstance<StructurizrElement.SoftwareSystem>() shouldHaveSize 2
            elements.filterIsInstance<StructurizrElement.Person>() shouldHaveSize 1
        }

        // 8. Full BigBank-like workspace
        test("parse full BigBank-like workspace with all element types") {
            val dsl =
                """
                workspace "Big Bank plc" "Banking workspace" {
                    model {
                        !identifiers hierarchical

                        customer = person "Personal Banking Customer" "A customer of the bank." {
                            tags "Customer"
                        }

                        enterprise "Big Bank plc" {
                            bankStaff = person "Bank Staff" "Internal bank employee."
                        }

                        internetBanking = softwareSystem "Internet Banking System" "Allows customers to view their accounts." {
                            webApp = container "Web Application" "The web front-end" "Java, Spring MVC" {
                                signinController = component "Sign In Controller" "Allows users to sign in." "Spring MVC Rest Controller"
                                accountsController = component "Accounts Summary Controller" "Provides account summary." "Spring MVC Rest Controller"
                            }
                            apiApp = container "API Application" "Backend JSON API" "Java, Spring MVC"
                            database = container "Database" "Stores user accounts." "Relational Database Schema"
                        }

                        mainframe = softwareSystem "Mainframe Banking System" "Stores all banking info." {
                            tags "External System"
                        }

                        aws = deploymentNode "Amazon Web Services" "" "Amazon Web Services" {
                            region = deploymentNode "US-East-1" "" "Amazon Web Services"
                        }

                        customer -> internetBanking "Views account balances" "HTTPS"
                        bankStaff -> internetBanking "Uses" "HTTPS"
                        webApp -> mainframe "Gets account info from" "XML/HTTPS"
                    }

                    views {
                        systemContext internetBanking "SystemContext" "System Context diagram" {
                            include *
                            autoLayout lr
                        }

                        container internetBanking "Containers" "Container diagram" {
                            include *
                        }

                        component webApp "Components" "Component diagram" {
                            include *
                        }

                        deployment * "Live" "LiveDeployment" "Live deployment diagram" {
                            include *
                        }

                        theme default
                    }
                }
                """.trimIndent()

            val workspace = StructurizrDslParser.parse(dsl)

            workspace.name shouldBe "Big Bank plc"
            workspace.description shouldBe "Banking workspace"

            val elements = workspace.model.elements
            // customer, bankStaff (from enterprise), internetBanking, mainframe, aws
            val persons = elements.filterIsInstance<StructurizrElement.Person>()
            val systems = elements.filterIsInstance<StructurizrElement.SoftwareSystem>()
            val deploymentNodes = elements.filterIsInstance<StructurizrElement.DeploymentNode>()

            persons shouldHaveSize 2
            systems shouldHaveSize 2
            deploymentNodes shouldHaveSize 1

            // Check internetBanking containers
            val internetBanking = systems.first { it.identifier == "internetBanking" }
            internetBanking.containers shouldHaveSize 3

            // Check webApp components
            val webApp = internetBanking.containers.first { it.identifier == "webApp" }
            webApp.components shouldHaveSize 2

            // Check mainframe is external
            val mainframe = systems.first { it.identifier == "mainframe" }
            mainframe.external shouldBe true

            // Check relationships
            workspace.model.relationships shouldHaveSize 3

            // Check views
            workspace.views.views shouldHaveSize 4
            workspace.views.views[0].shouldBeInstanceOf<StructurizrView.SystemContext>()
            workspace.views.views[1].shouldBeInstanceOf<StructurizrView.Container>()
            workspace.views.views[2].shouldBeInstanceOf<StructurizrView.Component>()
            workspace.views.views[3].shouldBeInstanceOf<StructurizrView.Deployment>()
        }
    })
