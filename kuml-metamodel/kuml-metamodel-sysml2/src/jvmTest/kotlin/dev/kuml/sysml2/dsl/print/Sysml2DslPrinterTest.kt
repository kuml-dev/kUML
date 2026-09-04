package dev.kuml.sysml2.dsl.print

import dev.kuml.kerml.KermlFeature
import dev.kuml.kerml.KermlMultiplicity
import dev.kuml.kerml.KermlSpecialization
import dev.kuml.sysml2.CombinedFragmentOperator
import dev.kuml.sysml2.ConstraintParameterDirection
import dev.kuml.sysml2.ExecutionSpecificationUsage
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.PartDefinition
import dev.kuml.sysml2.dsl.sysml2Model
import dev.kuml.sysml2.units.kg
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

class Sysml2DslPrinterTest :
    FunSpec(body = {

        test("empty model prints a valid empty sysml2Model block") {
            val model = sysml2Model(name = "Empty")
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "sysml2Model(\"Empty\")"
        }

        test("partDef with nested attribute/part/port/connect preserves feature order and ids") {
            val model =
                sysml2Model(name = "HybridVehicle") {
                    attributeDef(name = "Mass")
                    portDef(name = "Inlet")
                    portDef(name = "Outlet")
                    connectionDef(name = "PowerLine")
                    partDef(name = "Engine") {
                        port(name = "inlet", typeId = "Inlet")
                        port(name = "outlet", typeId = "Outlet")
                    }
                    partDef(name = "Vehicle") {
                        attribute(name = "mass", typeId = "Mass", default = 1500.kg)
                        part(name = "engine", typeId = "Engine")
                        connect(
                            name = "wiring",
                            typeId = "PowerLine",
                            sourceEndId = "Vehicle::engine::inlet",
                            targetEndId = "Vehicle::engine::outlet",
                        )
                    }
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "partDef(name = \"Vehicle\", id = \"Vehicle\")"
            dsl shouldContain "attribute(name = \"mass\", typeId = \"Mass\")"
            dsl shouldContain "part(name = \"engine\", typeId = \"Engine\")"
            dsl shouldContain "connect(name = \"wiring\""
            dsl shouldContain "port(name = \"inlet\", typeId = \"Inlet\")"
            // default value cannot be reconstructed — documented as a TODO, not silently dropped.
            dsl shouldContain "TODO: AttributeUsage 'mass'"
        }

        test("non-default multiplicity triggers the KermlMultiplicity import") {
            val model =
                sysml2Model(name = "Test") {
                    portDef(name = "Port")
                    partDef(name = "P") {
                        port(name = "ports", typeId = "Port", multiplicity = KermlMultiplicity.ZERO_OR_MORE)
                    }
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "import dev.kuml.kerml.KermlMultiplicity"
            dsl shouldContain "multiplicity = KermlMultiplicity.ZERO_OR_MORE"
        }

        test("stateDef + transition + stmDiagram round-trips via includeById and transitionById") {
            val model =
                sysml2Model(name = "TrafficLight") {
                    val red = stateDef(name = "Red", isInitial = true)
                    val green = stateDef(name = "Green")
                    transition(name = "go", source = red, target = green, trigger = "timer")
                    stmDiagram(name = "Cycle") {
                        include(red)
                        include(green)
                    }
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "stateDef(name = \"Red\", id = \"Red\", isInitial = true)"
            dsl shouldContain "transitionById(name = \"go\", sourceStateId = \"Red\", targetStateId = \"Green\""
            dsl shouldContain "trigger = \"timer\""
            dsl shouldContain "stmDiagram(\"Cycle\")"
            dsl shouldContain "includeById(\"Red\")"
            dsl shouldContain "includeById(\"Green\")"
        }

        test("actionDef with partition binds the partition to a val declared before the action") {
            val model =
                sysml2Model(name = "Order") {
                    val customer = activityPartition(name = "Customer")
                    val validate = actionDef(name = "Validate", partition = customer)
                    val ship = actionDef(name = "Ship")
                    controlFlow(name = "next", source = validate, target = ship)
                    actDiagram(name = "Flow") {
                        include(validate)
                        include(ship)
                    }
                }
            val dsl = Sysml2DslPrinter.print(model)
            val partitionValLine = dsl.lines().indexOfFirst { it.contains("activityPartition(name = \"Customer\"") }
            val actionLine = dsl.lines().indexOfFirst { it.contains("actionDef(name = \"Validate\"") }
            (partitionValLine >= 0 && actionLine >= 0 && partitionValLine < actionLine).shouldBeTrue()
            dsl shouldContain "partition = s2v"
            dsl shouldContain "controlFlowById(name = \"next\", sourceNodeId = \"Validate\", targetNodeId = \"Ship\""
            dsl shouldContain "actDiagram(\"Flow\")"
        }

        test("lifelineDef + message + executionSpec + seqDiagram") {
            val model =
                sysml2Model(name = "Login") {
                    val browser = lifelineDef(name = "Browser")
                    val auth = lifelineDef(name = "AuthService")
                    message(label = "login(user, pwd)", source = browser, target = auth, seqNo = 1)
                    executionSpec(name = "validate", lifeline = auth, startSeqNo = 1, endSeqNo = 1)
                    seqDiagram(name = "Login flow") {
                        include(browser)
                        include(auth)
                    }
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "lifelineDef(name = \"Browser\", id = \"Browser\")"
            dsl shouldContain
                "messageById(label = \"login(user, pwd)\", sourceLifelineId = \"Browser\", targetLifelineId = \"AuthService\", seqNo = 1"
            dsl shouldContain "executionSpec(name = \"validate\", lifeline = s2v"
            dsl shouldContain "seqDiagram(\"Login flow\")"
        }

        test("ibd owner is a PartDefinition bound to a val") {
            val model =
                sysml2Model(name = "Test") {
                    val vehicle =
                        partDef(name = "Vehicle") {
                            part(name = "engine", typeId = "Engine")
                        }
                    ibd(name = "Vehicle wiring", owner = vehicle)
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "val s2v0 = partDef(name = \"Vehicle\", id = \"Vehicle\")"
            dsl shouldContain "ibd(\"Vehicle wiring\", owner = s2v0)"
        }

        test("ucDiagram round-trips associations, includes and extends via ById forms") {
            val model =
                sysml2Model(name = "Library") {
                    val reader = actorDef(name = "Reader")
                    val borrow = useCaseDef(name = "BorrowBook")
                    val authenticate = useCaseDef(name = "Authenticate")
                    ucDiagram(name = "Top-level") {
                        include(reader)
                        include(borrow)
                        include(authenticate)
                        association(actor = reader, useCase = borrow)
                        include(source = borrow, target = authenticate)
                    }
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "ucDiagram(\"Top-level\")"
            dsl shouldContain "associationById(actorId = \"Reader\", useCaseId = \"BorrowBook\")"
            dsl shouldContain "includeById(sourceId = \"BorrowBook\", targetId = \"Authenticate\")"
        }

        test("reqDiagram round-trips satisfy/verify/derive/contains via ById forms") {
            val model =
                sysml2Model(name = "Vehicle") {
                    val topSpeed = requirementDef(name = "TopSpeed", reqId = "R-001", text = "shall reach 180 km/h")
                    val sub = requirementDef(name = "SubReq")
                    val part = partDef(name = "Vehicle")
                    reqDiagram(name = "Traceability") {
                        include(topSpeed)
                        include(part)
                        satisfy(source = part, requirement = topSpeed)
                        derive(source = sub, target = topSpeed)
                        contains(parent = topSpeed, child = sub)
                    }
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "requirementDef(name = \"TopSpeed\", id = \"TopSpeed\", reqId = \"R-001\", text = \"shall reach 180 km/h\")"
            dsl shouldContain "satisfyById(sourceId = \"Vehicle\", requirementId = \"TopSpeed\")"
            dsl shouldContain "deriveById(sourceRequirementId = \"SubReq\", targetRequirementId = \"TopSpeed\")"
            dsl shouldContain "containsById(parentRequirementId = \"TopSpeed\", childRequirementId = \"SubReq\")"
        }

        test("constraintDef + bind + parDiagram") {
            val model =
                sysml2Model(name = "Newton") {
                    val vehicle = partDef(name = "Vehicle") { attribute(name = "mass", typeId = "Mass") }
                    val newton =
                        constraintDef(
                            name = "NewtonsLaw",
                            expression = "F = m * a",
                            parameters =
                                listOf(
                                    dev.kuml.sysml2.ConstraintParameter(name = "m", direction = ConstraintParameterDirection.In),
                                ),
                        )
                    bind(name = "massBinding", source = "NewtonsLaw::m", target = "Vehicle::mass")
                    parDiagram(name = "F = m*a") {
                        include(newton)
                        include(vehicle)
                    }
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "constraintDef(name = \"NewtonsLaw\", id = \"NewtonsLaw\", expression = \"F = m * a\""
            dsl shouldContain "ConstraintParameter(name = \"m\", direction = ConstraintParameterDirection.In)"
            dsl shouldContain "bind(name = \"massBinding\", source = \"NewtonsLaw::m\", target = \"Vehicle::mass\""
            dsl shouldContain "parDiagram(\"F = m*a\")"
        }

        test("combinedFragment + executionSpec together in a seqDiagram") {
            val model =
                sysml2Model(name = "Login") {
                    val browser = lifelineDef(name = "Browser")
                    val auth = lifelineDef(name = "AuthService")
                    message(label = "login", source = browser, target = auth, seqNo = 1)
                    combinedFragment(name = "alt1", operator = CombinedFragmentOperator.Alt, startSeqNo = 1, endSeqNo = 1, guard = "valid")
                    executionSpec(name = "validate", lifeline = auth, startSeqNo = 1, endSeqNo = 1)
                    seqDiagram(name = "Flow") {
                        include(browser)
                        include(auth)
                    }
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "combinedFragment(name = \"alt1\", operator = CombinedFragmentOperator.Alt"
            dsl shouldContain "CombinedFragmentOperand(guard = \"valid\", startSeqNo = 1, endSeqNo = 1)"
            dsl shouldContain "executionSpec(name = \"validate\""
        }

        test("PartDefinition.constraints round-trip as constraint(...) calls") {
            val model =
                sysml2Model(name = "Test") {
                    partDef(name = "Vehicle") {
                        attribute(name = "mass", typeId = "Mass")
                        constraint(name = "hasMass", body = "self.mass->notEmpty()")
                    }
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "constraint(name = \"hasMass\", body = \"self.mass->notEmpty()\")"
        }

        test("isAbstract on AttributeDefinition/PortDefinition/ConnectionDefinition round-trips (ADR-0017 Wave B)") {
            val model =
                sysml2Model(name = "Test") {
                    attributeDef(name = "Mass", isAbstract = true)
                    portDef(name = "Inlet", isAbstract = true)
                    connectionDef(name = "PowerLine", isAbstract = true)
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "attributeDef(name = \"Mass\", id = \"Mass\", isAbstract = true)"
            dsl shouldContain "portDef(name = \"Inlet\", id = \"Inlet\", isAbstract = true)"
            dsl shouldContain "connectionDef(name = \"PowerLine\", id = \"PowerLine\", isAbstract = true)"
            dsl shouldNotContain "// TODO"
        }

        test("a Sysml2Usage type without a DSL constructor becomes a TODO instead of a crash") {
            // IncludeUsage/ExtendUsage remain intentionally unsupported by any top-level usage
            // constructor (the diagram-level UcDiagram edges are the DSL-supported path for that
            // relationship) — unlike the 8 Wave B usage types (ActorUsage etc.), which now have
            // DSL constructors and so no longer exercise this fallback.
            val base =
                sysml2Model(name = "Test") {
                    useCaseDef(name = "BorrowBook")
                    useCaseDef(name = "Authenticate")
                }
            val includeUsage =
                dev.kuml.sysml2.IncludeUsage(
                    id = "orphan-include-usage",
                    name = "borrowIncludesAuth",
                    definitionId = "Authenticate",
                    sourceUseCaseId = "BorrowBook",
                    targetUseCaseId = "Authenticate",
                )
            val withOrphanUsage = base.copy(usages = base.usages + includeUsage)
            val dsl = Sysml2DslPrinter.print(withOrphanUsage)
            dsl shouldContain "TODO: Sysml2Usage of type 'IncludeUsage'"
        }

        test("PartDefinition with more than one specialization still becomes a TODO (malformed data, not DSL-reachable)") {
            val base = sysml2Model(name = "Test") { partDef(name = "Vehicle") }
            val vehicleDef = base.definitions.first() as PartDefinition
            val withSpecializations =
                base.copy(
                    definitions =
                        listOf(
                            vehicleDef.copy(
                                specializations =
                                    listOf(
                                        KermlSpecialization(specificId = vehicleDef.id, generalId = "Machine"),
                                        KermlSpecialization(specificId = vehicleDef.id, generalId = "Asset"),
                                    ),
                            ),
                        ),
                )
            val dsl = Sysml2DslPrinter.print(withSpecializations)
            dsl shouldContain "TODO: PartDefinition Vehicle"
            dsl shouldContain "2 specialization(s) that cannot be"
            // The specializesId argument is omitted from the actual call — only mentioned in the TODO prose.
            dsl shouldContain "val s2v0 = partDef(name = \"Vehicle\", id = \"Vehicle\")"
        }

        test("PartDefinition with a foreign specificId specialization still becomes a TODO (malformed data, not DSL-reachable)") {
            val base = sysml2Model(name = "Test") { partDef(name = "Vehicle") }
            val vehicleDef = base.definitions.first() as PartDefinition
            val withForeignSpec =
                base.copy(
                    definitions =
                        listOf(
                            vehicleDef.copy(
                                specializations = listOf(KermlSpecialization(specificId = "OtherPart", generalId = "Machine")),
                            ),
                        ),
                )
            val dsl = Sysml2DslPrinter.print(withForeignSpec)
            dsl shouldContain "TODO: PartDefinition Vehicle"
            dsl shouldContain "1 specialization(s) that cannot be"
            // The specializesId argument is omitted from the actual call — only mentioned in the TODO prose.
            dsl shouldContain "val s2v0 = partDef(name = \"Vehicle\", id = \"Vehicle\")"
        }

        test("ibd diagram with a dangling owner id becomes a TODO comment") {
            val base =
                sysml2Model(name = "Test") {
                    val vehicle = partDef(name = "Vehicle")
                    ibd(name = "Vehicle wiring", owner = vehicle)
                }
            val diagram = base.diagrams.filterIsInstance<IbdDiagram>().single()
            val mutatedDiagram = diagram.copy(ownerId = "NoSuchPart")
            val withMutatedDiagram = base.copy(diagrams = listOf(mutatedDiagram))
            val dsl = Sysml2DslPrinter.print(withMutatedDiagram)
            dsl shouldContain "TODO: IbdDiagram \"Vehicle wiring\""
            dsl shouldContain "owner id 'NoSuchPart'"
            dsl shouldNotContain "ibd(\"Vehicle wiring\""
        }

        test("ExecutionSpecificationUsage with a dangling lifeline id becomes a TODO comment") {
            val base =
                sysml2Model(name = "Test") {
                    val browser = lifelineDef(name = "Browser")
                    executionSpec(name = "validate", lifeline = browser, startSeqNo = 1, endSeqNo = 1)
                }
            val execUsage = base.usages.filterIsInstance<ExecutionSpecificationUsage>().single()
            val mutatedUsage = execUsage.copy(lifelineId = "NoSuchLifeline")
            val withMutatedUsage = base.copy(usages = base.usages.map { if (it.id == execUsage.id) mutatedUsage else it })
            val dsl = Sysml2DslPrinter.print(withMutatedUsage)
            dsl shouldContain "TODO: ExecutionSpecificationUsage"
            dsl shouldContain "lifeline id 'NoSuchLifeline'"
            dsl shouldNotContain "executionSpec(name = \"validate\""
        }

        test("all 8 diagram kinds print without any TODO fallback") {
            val model =
                sysml2Model(name = "AllDiagrams") {
                    val p = partDef(name = "P")
                    val a = actorDef(name = "A")
                    val uc = useCaseDef(name = "UC")
                    val req = requirementDef(name = "Req")
                    val s = stateDef(name = "S", isInitial = true)
                    val act = actionDef(name = "Act")
                    val lifeline = lifelineDef(name = "L")
                    val c = constraintDef(name = "C")
                    bdd(name = "bdd") { include(p) }
                    ibd(name = "ibd", owner = p)
                    ucDiagram(name = "uc") {
                        include(a)
                        include(uc)
                    }
                    reqDiagram(name = "req") { include(req) }
                    stmDiagram(name = "stm") { include(s) }
                    actDiagram(name = "act") { include(act) }
                    seqDiagram(name = "seq") { include(lifeline) }
                    parDiagram(name = "par") {
                        include(c)
                        include(p)
                    }
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "bdd(\"bdd\")"
            dsl shouldContain "ibd(\"ibd\", owner ="
            dsl shouldContain "ucDiagram(\"uc\")"
            dsl shouldContain "reqDiagram(\"req\")"
            dsl shouldContain "stmDiagram(\"stm\")"
            dsl shouldContain "actDiagram(\"act\")"
            dsl shouldContain "seqDiagram(\"seq\")"
            dsl shouldContain "parDiagram(\"par\")"
            dsl shouldNotContain "// TODO"
        }

        // ── ADR-0017 Wave B: specializesId on all 12 definition types ──────────

        test("specializesId round-trips on attributeDef/portDef/connectionDef (previously unsupportedSpecializationTodo)") {
            val model =
                sysml2Model(name = "Test") {
                    val length = attributeDef(name = "Length")
                    attributeDef(name = "Mass", specializesId = length.id)
                    val genericPort = portDef(name = "GenericPort")
                    portDef(name = "Inlet", specializesId = genericPort.id)
                    val genericConn = connectionDef(name = "GenericLine")
                    connectionDef(name = "PowerLine", specializesId = genericConn.id)
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "attributeDef(name = \"Mass\", id = \"Mass\", specializesId = \"Length\")"
            dsl shouldContain "portDef(name = \"Inlet\", id = \"Inlet\", specializesId = \"GenericPort\")"
            dsl shouldContain "connectionDef(name = \"PowerLine\", id = \"PowerLine\", specializesId = \"GenericLine\")"
            dsl shouldNotContain "// TODO"
        }

        test("specializesId round-trips on stateDef and constraintDef") {
            val model =
                sysml2Model(name = "Test") {
                    val genericState = stateDef(name = "GenericState")
                    stateDef(name = "Red", specializesId = genericState.id)
                    val genericConstraint = constraintDef(name = "GenericConstraint")
                    constraintDef(name = "NewtonsLaw", specializesId = genericConstraint.id)
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "stateDef(name = \"Red\", id = \"Red\", specializesId = \"GenericState\")"
            dsl shouldContain "constraintDef(name = \"NewtonsLaw\", id = \"NewtonsLaw\", specializesId = \"GenericConstraint\")"
            dsl shouldNotContain "// TODO"
        }

        // ── ADR-0017 Wave B: 8 previously-missing Sysml2Usage DSL constructors ─

        test("all 8 Wave B top-level usage types print without any TODO fallback") {
            val model =
                sysml2Model(name = "WaveBUsages") {
                    val actor = actorDef(name = "Reader")
                    val useCase = useCaseDef(name = "BorrowBook")
                    val requirement = requirementDef(name = "TopSpeed")
                    val state = stateDef(name = "Red")
                    val action = actionDef(name = "Validate")
                    val partition = activityPartition(name = "Customer")
                    val lifeline = lifelineDef(name = "Browser")
                    val constraint = constraintDef(name = "NewtonsLaw")
                    actorUsage(name = "reader1", actor = actor)
                    useCaseUsage(name = "borrow1", useCase = useCase)
                    requirementUsage(name = "topSpeed1", requirement = requirement)
                    stateUsage(name = "red1", state = state)
                    actionUsage(name = "validate1", action = action)
                    activityPartitionUsage(name = "customer1", partition = partition)
                    lifelineUsage(name = "browser1", lifeline = lifeline)
                    constraintUsage(name = "newton1", constraint = constraint)
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "actorUsageById(name = \"reader1\", definitionId = \"Reader\""
            dsl shouldContain "useCaseUsageById(name = \"borrow1\", definitionId = \"BorrowBook\""
            dsl shouldContain "requirementUsageById(name = \"topSpeed1\", definitionId = \"TopSpeed\""
            dsl shouldContain "stateUsageById(name = \"red1\", definitionId = \"Red\""
            dsl shouldContain "actionUsageById(name = \"validate1\", definitionId = \"Validate\""
            dsl shouldContain "activityPartitionUsageById(name = \"customer1\", definitionId = \"Customer\""
            dsl shouldContain "lifelineUsageById(name = \"browser1\", definitionId = \"Browser\""
            dsl shouldContain "constraintUsageById(name = \"newton1\", definitionId = \"NewtonsLaw\""
            dsl shouldNotContain "// TODO"
        }

        test("non-default multiplicity on a Wave B usage type triggers the KermlMultiplicity import") {
            val model =
                sysml2Model(name = "Test") {
                    val state = stateDef(name = "Red")
                    stateUsage(name = "reds", state = state, multiplicity = KermlMultiplicity.ZERO_OR_MORE)
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldContain "import dev.kuml.kerml.KermlMultiplicity"
            dsl shouldContain "stateUsageById(name = \"reds\", definitionId = \"Red\", multiplicity = KermlMultiplicity.ZERO_OR_MORE"
        }

        // ── comment-injection regression tests ─────────────────────────────
        // Sysml2Definition.name/.id (and the analogous usage/feature fields) are unvalidated
        // Strings and can contain newlines. TODO diagnostic comments interpolate several of
        // these fields *outside* of a quote()d Kotlin string literal — a raw embedded '\n'
        // there would terminate the '//' comment early and let attacker-controlled text
        // re-appear as live, uncommented code on the next line of the generated *.kuml.kts
        // script.

        test("specialization TODO with an embedded newline in the definition name cannot break out of the comment") {
            val base = sysml2Model(name = "Test") { partDef(name = "Vehicle") }
            val vehicleDef = base.definitions.first() as PartDefinition
            val withSpecializations =
                base.copy(
                    definitions =
                        listOf(
                            vehicleDef.copy(
                                name = "Vehicle\nfun injected() = Unit",
                                specializations =
                                    listOf(
                                        KermlSpecialization(specificId = vehicleDef.id, generalId = "Machine"),
                                        KermlSpecialization(specificId = vehicleDef.id, generalId = "Asset"),
                                    ),
                            ),
                        ),
                )
            val dsl = Sysml2DslPrinter.print(withSpecializations)
            // The raw newline must not survive verbatim — it would split the injected text
            // onto its own, uncommented line.
            dsl shouldNotContain "Vehicle\nfun injected"
            // It must instead show up escaped, still on the same '// TODO' line. (`def.name` is
            // also printed, correctly quote()d, as the partDef(...) name argument — so we check
            // the TODO comment line specifically, not every line mentioning "injected".)
            dsl shouldContain "Vehicle\\nfun injected"
            val todoLine = dsl.lines().single { "// TODO" in it && "injected" in it }
            todoLine.trimStart() shouldStartWith "//"
        }

        test("AttributeUsage default-expression TODO with an embedded newline in the usage name cannot break out of the comment") {
            val model =
                sysml2Model(name = "Test") {
                    attributeDef(name = "Mass")
                    partDef(name = "Vehicle") {
                        attribute(name = "mass\nfun injected() = Unit", typeId = "Mass", default = 1500.kg)
                    }
                }
            val dsl = Sysml2DslPrinter.print(model)
            dsl shouldNotContain "mass\nfun injected"
            // `usage.name` is also printed, correctly quote()d, as the attribute(...) name
            // argument — so we check the TODO comment line specifically, not every line
            // mentioning "injected".
            dsl shouldContain "mass\\nfun injected"
            val todoLine = dsl.lines().single { "// TODO" in it && "injected" in it }
            todoLine.trimStart() shouldStartWith "//"
        }

        test("unresolvable feature TODO with an embedded newline in the feature name cannot break out of the comment") {
            val base = sysml2Model(name = "Test") { partDef(name = "Vehicle") }
            val vehicleDef = base.definitions.first() as PartDefinition
            val orphanFeature = KermlFeature(id = "orphan-feature", name = "engine\nfun injected() = Unit")
            val withOrphanFeature =
                base.copy(
                    definitions =
                        listOf(vehicleDef.copy(features = vehicleDef.features + orphanFeature)),
                )
            val dsl = Sysml2DslPrinter.print(withOrphanFeature)
            dsl shouldNotContain "engine\nfun injected"
            dsl shouldContain "engine\\nfun injected"
            dsl.lines().filter { "injected" in it }.forEach { line -> line.trimStart() shouldStartWith "//" }
        }

        test("ibd diagram TODO with an embedded newline in the dangling owner id cannot break out of the comment") {
            val base =
                sysml2Model(name = "Test") {
                    val vehicle = partDef(name = "Vehicle")
                    ibd(name = "Vehicle wiring", owner = vehicle)
                }
            val diagram = base.diagrams.filterIsInstance<IbdDiagram>().single()
            val mutatedDiagram = diagram.copy(ownerId = "NoSuchPart\nfun injected() = Unit")
            val withMutatedDiagram = base.copy(diagrams = listOf(mutatedDiagram))
            val dsl = Sysml2DslPrinter.print(withMutatedDiagram)
            dsl shouldNotContain "NoSuchPart\nfun injected"
            dsl shouldContain "NoSuchPart\\nfun injected"
            dsl.lines().filter { "injected" in it }.forEach { line -> line.trimStart() shouldStartWith "//" }
        }

        test("ExecutionSpecificationUsage TODO with an embedded newline in the dangling lifeline id cannot break out of the comment") {
            val base =
                sysml2Model(name = "Test") {
                    val browser = lifelineDef(name = "Browser")
                    executionSpec(name = "validate", lifeline = browser, startSeqNo = 1, endSeqNo = 1)
                }
            val execUsage = base.usages.filterIsInstance<ExecutionSpecificationUsage>().single()
            val mutatedUsage = execUsage.copy(lifelineId = "NoSuchLifeline\nfun injected() = Unit")
            val withMutatedUsage = base.copy(usages = base.usages.map { if (it.id == execUsage.id) mutatedUsage else it })
            val dsl = Sysml2DslPrinter.print(withMutatedUsage)
            dsl shouldNotContain "NoSuchLifeline\nfun injected"
            dsl shouldContain "NoSuchLifeline\\nfun injected"
            dsl.lines().filter { "injected" in it }.forEach { line -> line.trimStart() shouldStartWith "//" }
        }
    })
