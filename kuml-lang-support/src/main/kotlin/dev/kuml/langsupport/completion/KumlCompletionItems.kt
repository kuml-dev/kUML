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
            Item("umlModel", "umlModel {\n    \n}", " { … }", "Top-Level UML model container", Group.ENTRY),
            Item("c4Model", "c4Model(name = \"\") {\n    \n}", "(name: String) { … }", "Top-Level C4 model container", Group.ENTRY),
            Item("sysml2Model", "sysml2Model {\n    \n}", " { … }", "Top-Level SysML 2 model", Group.ENTRY),
            Item("classDiagram", "classDiagram(\"\") {\n    \n}", "(name: String) { … }", "UML class diagram", Group.ENTRY),
            Item("stateDiagram", "stateDiagram(\"\") {\n    \n}", "(name: String) { … }", "UML state machine diagram", Group.ENTRY),
            Item("sequenceDiagram", "sequenceDiagram(\"\") {\n    \n}", "(name: String) { … }", "UML sequence diagram", Group.ENTRY),
            Item("useCaseDiagram", "useCaseDiagram(\"\") {\n    \n}", "(name: String) { … }", "UML use case diagram", Group.ENTRY),
            Item("componentDiagram", "componentDiagram(\"\") {\n    \n}", "(name: String) { … }", "UML component diagram", Group.ENTRY),
            Item("diagram", "diagram(\"\") {\n    \n}", "(name: String) { … }", "Generic diagram", Group.ENTRY),
            // UML
            Item("classOf", "classOf(name = \"\") {\n    \n}", "(name: String) { … }", "UML class definition", Group.UML),
            Item("interfaceOf", "interfaceOf(name = \"\") {\n    \n}", "(name: String) { … }", "UML interface definition", Group.UML),
            Item("enumOf", "enumOf(name = \"\") {\n    \n}", "(name: String) { … }", "UML enumeration", Group.UML),
            Item("componentOf", "componentOf(name = \"\") {\n    \n}", "(name: String) { … }", "UML component", Group.UML),
            Item("association", "association(source = \"\", target = \"\")", "(source, target)", "UML association", Group.UML),
            Item(
                "generalization",
                "generalization(child = \"\", parent = \"\")",
                "(child, parent)",
                "UML generalization (extends)",
                Group.UML,
            ),
            Item(
                "realization",
                "realization(client = \"\", supplier = \"\")",
                "(client, supplier)",
                "UML realization (implements)",
                Group.UML,
            ),
            Item("dependency", "dependency(source = \"\", target = \"\")", "(source, target)", "UML dependency", Group.UML),
            Item("stateMachine", "stateMachine(\"\") {\n    \n}", "(name: String) { … }", "UML state machine", Group.UML),
            // SYSML2
            Item("partDef", "partDef(\"\") {\n    \n}", "(name: String) { … }", "SysML 2 part definition", Group.SYSML2),
            Item("stateDef", "stateDef(\"\")", "(name: String)", "SysML 2 state definition", Group.SYSML2),
            Item("actionDef", "actionDef(\"\") {\n    \n}", "(name: String) { … }", "SysML 2 action definition", Group.SYSML2),
            Item("attributeDef", "attributeDef(\"\")", "(name: String)", "SysML 2 attribute definition", Group.SYSML2),
            Item("portDef", "portDef(\"\")", "(name: String)", "SysML 2 port definition", Group.SYSML2),
            Item("connectionDef", "connectionDef(\"\")", "(name: String)", "SysML 2 connection definition", Group.SYSML2),
            Item("enumDef", "enumDef(\"\") {\n    \n}", "(name: String) { … }", "SysML 2 enum definition", Group.SYSML2),
            Item("requirementDef", "requirementDef(\"\") {\n    \n}", "(name: String) { … }", "SysML 2 requirement", Group.SYSML2),
            Item("bdd", "bdd(\"\") {\n    \n}", "(name: String) { … }", "SysML 2 Block Definition Diagram", Group.SYSML2),
            Item("ibd", "ibd(\"\") {\n    \n}", "(name: String) { … }", "SysML 2 Internal Block Diagram", Group.SYSML2),
            Item("actDiagram", "actDiagram(\"\") {\n    \n}", "(name: String) { … }", "SysML 2 activity diagram", Group.SYSML2),
            Item("stmDiagram", "stmDiagram(\"\") {\n    \n}", "(name: String) { … }", "SysML 2 state machine diagram", Group.SYSML2),
            // C4
            Item("systemContext", "systemContext(\"\") {\n    \n}", "(name: String) { … }", "C4 System Context diagram", Group.C4),
            Item("containerView", "containerView(\"\") {\n    \n}", "(name: String) { … }", "C4 Container diagram", Group.C4),
            Item("componentView", "componentView(\"\") {\n    \n}", "(name: String) { … }", "C4 Component diagram", Group.C4),
            Item("deployment", "deployment(\"\") {\n    \n}", "(name: String) { … }", "C4 Deployment diagram", Group.C4),
            Item("person", "person(\"\")", "(name: String)", "C4 Person actor", Group.C4),
            Item("softwareSystem", "softwareSystem(\"\")", "(name: String)", "C4 Software System", Group.C4),
            // SHARED
            Item("typeRef", "typeRef(\"\")", "(name: String)", "Type reference (String, Int…)", Group.SHARED),
            Item("literal", "literal(\"\")", "(value: String)", "Enum literal", Group.SHARED),
        )

    public fun byName(name: String): Item? = ALL.firstOrNull { it.name == name }

    public fun startingWith(prefix: String): List<Item> = ALL.filter { it.name.startsWith(prefix, ignoreCase = true) }
}
