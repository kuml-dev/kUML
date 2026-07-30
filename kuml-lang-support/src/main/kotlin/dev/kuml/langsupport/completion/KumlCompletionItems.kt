package dev.kuml.langsupport.completion

/**
 * Pure-Kotlin catalogue of all kUML DSL completion items.
 *
 * No IntelliJ Platform dependency — fully testable in plain Kotest.
 * `KumlCompletionContributor` (in the kuml-jetbrains module) consumes [ALL] to
 * build `com.intellij.codeInsight.lookup.LookupElement`s.
 *
 * V2.0.41
 */
public object KumlCompletionItems {
    public enum class Group { ENTRY, UML, SYSML2, C4, SHARED }

    public data class Item(
        val name: String,
        val insertText: String,
        val tail: String,
        val description: String,
        val group: Group,
    )

    public val ALL: List<Item> =
        listOf(
            // ENTRY
            Item(
                name = "umlModel",
                insertText = "umlModel {\n    \n}",
                tail = " { … }",
                description = "Top-Level UML model container",
                group = Group.ENTRY,
            ),
            Item(
                name = "c4Model",
                insertText = "c4Model(name = \"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "Top-Level C4 model container",
                group = Group.ENTRY,
            ),
            Item(
                name = "sysml2Model",
                insertText = "sysml2Model {\n    \n}",
                tail = " { … }",
                description = "Top-Level SysML 2 model",
                group = Group.ENTRY,
            ),
            Item(
                name = "classDiagram",
                insertText = "classDiagram(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "UML class diagram",
                group = Group.ENTRY,
            ),
            Item(
                name = "stateDiagram",
                insertText = "stateDiagram(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "UML state machine diagram",
                group = Group.ENTRY,
            ),
            Item(
                name = "sequenceDiagram",
                insertText = "sequenceDiagram(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "UML sequence diagram",
                group = Group.ENTRY,
            ),
            Item(
                name = "useCaseDiagram",
                insertText = "useCaseDiagram(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "UML use case diagram",
                group = Group.ENTRY,
            ),
            Item(
                name = "componentDiagram",
                insertText = "componentDiagram(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "UML component diagram",
                group = Group.ENTRY,
            ),
            Item(
                name = "diagram",
                insertText = "diagram(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "Generic diagram",
                group = Group.ENTRY,
            ),
            // UML
            Item(
                name = "classOf",
                insertText = "classOf(name = \"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "UML class definition",
                group = Group.UML,
            ),
            Item(
                name = "interfaceOf",
                insertText = "interfaceOf(name = \"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "UML interface definition",
                group = Group.UML,
            ),
            Item(
                name = "enumOf",
                insertText = "enumOf(name = \"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "UML enumeration",
                group = Group.UML,
            ),
            Item(
                name = "componentOf",
                insertText = "componentOf(name = \"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "UML component",
                group = Group.UML,
            ),
            Item(
                name = "association",
                insertText = "association(source = \"\", target = \"\")",
                tail = "(source, target)",
                description = "UML association",
                group = Group.UML,
            ),
            Item(
                name = "generalization",
                insertText = "generalization(child = \"\", parent = \"\")",
                tail = "(child, parent)",
                description = "UML generalization (extends)",
                group = Group.UML,
            ),
            Item(
                name = "realization",
                insertText = "realization(client = \"\", supplier = \"\")",
                tail = "(client, supplier)",
                description = "UML realization (implements)",
                group = Group.UML,
            ),
            Item(
                name = "dependency",
                insertText = "dependency(source = \"\", target = \"\")",
                tail = "(source, target)",
                description = "UML dependency",
                group = Group.UML,
            ),
            Item(
                name = "stateMachine",
                insertText = "stateMachine(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "UML state machine",
                group = Group.UML,
            ),
            // SYSML2
            Item(
                name = "partDef",
                insertText = "partDef(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "SysML 2 part definition",
                group = Group.SYSML2,
            ),
            Item(
                name = "stateDef",
                insertText = "stateDef(\"\")",
                tail = "(name: String)",
                description = "SysML 2 state definition",
                group = Group.SYSML2,
            ),
            Item(
                name = "actionDef",
                insertText = "actionDef(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "SysML 2 action definition",
                group = Group.SYSML2,
            ),
            Item(
                name = "attributeDef",
                insertText = "attributeDef(\"\")",
                tail = "(name: String)",
                description = "SysML 2 attribute definition",
                group = Group.SYSML2,
            ),
            Item(
                name = "portDef",
                insertText = "portDef(\"\")",
                tail = "(name: String)",
                description = "SysML 2 port definition",
                group = Group.SYSML2,
            ),
            Item(
                name = "connectionDef",
                insertText = "connectionDef(\"\")",
                tail = "(name: String)",
                description = "SysML 2 connection definition",
                group = Group.SYSML2,
            ),
            Item(
                name = "enumDef",
                insertText = "enumDef(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "SysML 2 enum definition",
                group = Group.SYSML2,
            ),
            Item(
                name = "requirementDef",
                insertText = "requirementDef(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "SysML 2 requirement",
                group = Group.SYSML2,
            ),
            Item(
                name = "bdd",
                insertText = "bdd(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "SysML 2 Block Definition Diagram",
                group = Group.SYSML2,
            ),
            Item(
                name = "ibd",
                insertText = "ibd(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "SysML 2 Internal Block Diagram",
                group = Group.SYSML2,
            ),
            Item(
                name = "actDiagram",
                insertText = "actDiagram(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "SysML 2 activity diagram",
                group = Group.SYSML2,
            ),
            Item(
                name = "stmDiagram",
                insertText = "stmDiagram(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "SysML 2 state machine diagram",
                group = Group.SYSML2,
            ),
            // C4
            Item(
                name = "systemContext",
                insertText = "systemContext(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "C4 System Context diagram",
                group = Group.C4,
            ),
            Item(
                name = "containerView",
                insertText = "containerView(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "C4 Container diagram",
                group = Group.C4,
            ),
            Item(
                name = "componentView",
                insertText = "componentView(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "C4 Component diagram",
                group = Group.C4,
            ),
            Item(
                name = "deployment",
                insertText = "deployment(\"\") {\n    \n}",
                tail = "(name: String) { … }",
                description = "C4 Deployment diagram",
                group = Group.C4,
            ),
            Item(name = "person", insertText = "person(\"\")", tail = "(name: String)", description = "C4 Person actor", group = Group.C4),
            Item(
                name = "softwareSystem",
                insertText = "softwareSystem(\"\")",
                tail = "(name: String)",
                description = "C4 Software System",
                group = Group.C4,
            ),
            // SHARED
            Item(
                name = "typeRef",
                insertText = "typeRef(\"\")",
                tail = "(name: String)",
                description = "Type reference (String, Int…)",
                group = Group.SHARED,
            ),
            Item(
                name = "literal",
                insertText = "literal(\"\")",
                tail = "(value: String)",
                description = "Enum literal",
                group = Group.SHARED,
            ),
        )

    public fun byName(name: String): Item? = ALL.firstOrNull { it.name == name }

    public fun startingWith(prefix: String): List<Item> = ALL.filter { it.name.startsWith(prefix, ignoreCase = true) }
}
