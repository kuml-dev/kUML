@file:Suppress("unused")

import dev.kuml.core.dsl.useCaseDiagram
import dev.kuml.uml.dsl.actor
import dev.kuml.uml.dsl.association
import dev.kuml.uml.dsl.extend
import dev.kuml.uml.dsl.generalization
import dev.kuml.uml.dsl.include
import dev.kuml.uml.dsl.subject
import dev.kuml.uml.dsl.useCase

/**
 * kuml-desktop — use-case diagram (self-referential dogfooding example).
 *
 * Grounded in an inspection of the actual `kuml-desktop` sources (MainWindow,
 * AppState, EditorPane, PreviewPane, Engineering/KnowledgeWorkspaceScreen,
 * WorkspaceTreePane, TrustDialog, PluginManagerPane, AiPanelState + AI
 * components, DesktopRenderPipeline, FileMenu). Only use cases with
 * `Status: implemented` in the underlying brainstorm are modeled here — the
 * diagram documents what the application actually does today, not the
 * (numerous) planned gaps. Relationships were chosen where the code shows a
 * genuine unconditional ("always happens as part of") or conditional
 * ("optional, user- or event-triggered") coupling; not every brainstormed
 * item forces a relationship.
 */
useCaseDiagram(name = "kuml-desktop") {

    // ── Actors ────────────────────────────────────────────────────────────────

    val modeler = actor(name = "Modeler")
    val aiAssistedModeler = actor(name = "AI-Assisted Modeler")
    val curator = actor(name = "Knowledge-Workspace Curator")
    val reviewer = actor(name = "Reviewer / Team Lead")
    val pluginAuthor = actor(name = "Plugin Author")
    val pluginUser = actor(name = "Plugin User")

    // An AI-Assisted Modeler is still a Modeler (same editor/preview core),
    // and a Reviewer performs the same read-only browsing a Curator does.
    generalization(specific = aiAssistedModeler, general = modeler)
    generalization(specific = reviewer, general = curator)

    // ── Use cases — editing & rendering ─────────────────────────────────────────

    val editScript = useCase(name = "Edit kUML Script")
    val liveDiagramPreview = useCase(name = "See Live Diagram Preview")
    val manageScriptFiles = useCase(name = "Manage Script Files")
    val openEngineeringWorkspace = useCase(name = "Open Engineering Workspace")
    val switchDiagramTheme = useCase(name = "Switch Diagram Theme")
    val switchUiLanguage = useCase(name = "Switch UI Language")

    // ── Use cases — AI-assisted modeling ─────────────────────────────────────────

    val chatWithAiAssistant = useCase(name = "Chat with AI Assistant")
    val reviewAiPatches = useCase(name = "Review AI Patches Before Apply")
    val trackTokenCostAndBudget = useCase(name = "Track Token Cost and Budget")
    val inspectAiToolCalls = useCase(name = "Inspect AI Tool Calls")

    // ── Use cases — knowledge workspace (OKF) ────────────────────────────────────

    val openWorkspaceWithModeDetection = useCase(name = "Open Workspace with Mode Detection")
    val grantOrWithholdWorkspaceTrust = useCase(name = "Grant or Withhold Workspace Trust")
    val browseKnowledgeDocuments = useCase(name = "Browse Knowledge Documents")
    val previewDocumentDiagram = useCase(name = "Preview Document Diagram")
    val followWikiLinksAndBacklinks = useCase(name = "Follow Wiki Links and Backlinks")

    // ── Use cases — review ──────────────────────────────────────────────────────

    val readModelsWithoutExecuting = useCase(name = "Read Models Without Executing")

    // ── Use cases — plugins ──────────────────────────────────────────────────────

    val verifyPluginLoadsLocally = useCase(name = "Verify Plugin Loads Locally")
    val presentPluginInRegistry = useCase(name = "Present Plugin in Registry")
    val discoverRegistryPlugins = useCase(name = "Discover Registry Plugins")
    val verifyPluginSignature = useCase(name = "Verify Plugin Signature Before Install")

    // ── System boundary ──────────────────────────────────────────────────────────

    subject(
        name = "kuml-desktop",
        containedUseCases =
            arrayOf(
                editScript,
                liveDiagramPreview,
                manageScriptFiles,
                openEngineeringWorkspace,
                switchDiagramTheme,
                switchUiLanguage,
                chatWithAiAssistant,
                reviewAiPatches,
                trackTokenCostAndBudget,
                inspectAiToolCalls,
                openWorkspaceWithModeDetection,
                grantOrWithholdWorkspaceTrust,
                browseKnowledgeDocuments,
                previewDocumentDiagram,
                followWikiLinksAndBacklinks,
                readModelsWithoutExecuting,
                verifyPluginLoadsLocally,
                presentPluginInRegistry,
                discoverRegistryPlugins,
                verifyPluginSignature,
            ),
    )

    // ── Actor ↔ use-case associations ────────────────────────────────────────────

    association(source = modeler, target = editScript)
    association(source = modeler, target = manageScriptFiles)
    association(source = modeler, target = openEngineeringWorkspace)
    association(source = modeler, target = switchDiagramTheme)
    association(source = modeler, target = switchUiLanguage)

    association(source = aiAssistedModeler, target = chatWithAiAssistant)

    association(source = curator, target = openWorkspaceWithModeDetection)
    association(source = curator, target = browseKnowledgeDocuments)

    association(source = reviewer, target = readModelsWithoutExecuting)

    association(source = pluginAuthor, target = verifyPluginLoadsLocally)
    association(source = pluginAuthor, target = presentPluginInRegistry)

    association(source = pluginUser, target = discoverRegistryPlugins)

    // ── Include — unconditional, always happens as part of the base ────────────

    // DesktopRenderController debounces every edit straight into a render pass.
    include(base = editScript, addition = liveDiagramPreview)

    // Every applied AI edit goes through the patch-preview dialog, and every
    // session accrues cost via TokenUsageTracker — both are unconditional.
    include(base = chatWithAiAssistant, addition = reviewAiPatches)
    include(base = chatWithAiAssistant, addition = trackTokenCostAndBudget)

    // TrustDialog gates any script evaluation when a workspace is opened.
    include(base = openWorkspaceWithModeDetection, addition = grantOrWithholdWorkspaceTrust)

    // The document tree pane always attempts to render the selected note's
    // first kuml block alongside the rendered Markdown.
    include(base = browseKnowledgeDocuments, addition = previewDocumentDiagram)

    // A Reviewer's read-only inspection still passes through the same trust gate.
    include(base = readModelsWithoutExecuting, addition = grantOrWithholdWorkspaceTrust)

    // ── Extend — optional, triggered by a specific user action or event ─────────

    // Inspecting a tool call's JSON args is an optional click on a trace card.
    extend(base = chatWithAiAssistant, extension = inspectAiToolCalls, at = "ToolCallExecuted")

    // Following a link is optional and only happens when the reader clicks one.
    extend(base = browseKnowledgeDocuments, extension = followWikiLinksAndBacklinks, at = "LinkClicked")

    // Signing-key badges are only inspected when a specific plugin's card is opened.
    extend(base = discoverRegistryPlugins, extension = verifyPluginSignature, at = "PluginDetailsOpened")
}
