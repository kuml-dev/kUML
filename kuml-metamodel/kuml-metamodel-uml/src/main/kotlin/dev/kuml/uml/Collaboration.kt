package dev.kuml.uml

import dev.kuml.core.model.KumlMetaValue
import kotlinx.serialization.Serializable

// ── Collaboration ─────────────────────────────────────────────────────────────

/**
 * A UML 2.x collaboration — a structured description of how a set of roles
 * cooperate to fulfil a particular purpose.
 *
 * Renders as a dashed ellipse with the collaboration name centred inside
 * (standard UML 2.5 notation). Roles are modelled as [UmlCollaborationRole]
 * instances; connector rendering between roles and participants is deferred
 * to Ticket 4 (SoaML pilot).
 *
 * Implements [Stereotypable] so that `stereotype("ServiceContract")` works
 * in Ticket 4 without a refactor.
 *
 * @property roles The collaboration roles that define the participating parts.
 */
@Serializable
data class UmlCollaboration(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val roles: List<UmlCollaborationRole> = emptyList(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
    override val appliedStereotypes: List<AppliedStereotype> = emptyList(),
) : UmlClassifier,
    Stereotypable

// ── CollaborationRole ─────────────────────────────────────────────────────────

/**
 * A collaboration role — one slot in a [UmlCollaboration] that can be filled
 * by a typed classifier at usage sites.
 *
 * @property type Reference to the classifier that fills this role.
 * @property multiplicity How many instances of the classifier fill this role.
 */
@Serializable
data class UmlCollaborationRole(
    override val id: String,
    override val name: String,
    override val visibility: Visibility = Visibility.PUBLIC,
    val type: UmlTypeRef,
    val multiplicity: Multiplicity = Multiplicity(),
    override val stereotypes: List<String> = emptyList(),
    override val metadata: Map<String, KumlMetaValue> = emptyMap(),
    override val appliedStereotypes: List<AppliedStereotype> = emptyList(),
) : UmlNamedElement,
    Stereotypable
