package dev.kuml.profile.soaml

import dev.kuml.profile.KumlProfile
import dev.kuml.profile.UmlMetaclass
import dev.kuml.profile.builder.profile

/**
 * OMG SoaML core profile — eight stereotypes for V1.1.
 *
 * Provides the foundation for service-oriented architecture modelling with kUML.
 * No stereotype tagged-value properties in V1.1 (empty tags are valid per the
 * `tags: Map<String, TagValue>` contract).
 *
 * Reference: OMG Service oriented architecture Modeling Language (SoaML) 1.0
 */
public val soamlProfile: KumlProfile =
    profile("SoaML") {
        namespace = "dev.kuml.profiles.soaml"
        description = "OMG Service oriented architecture Modeling Language"
        version = "1.0.0"

        // ── Participants and interfaces ───────────────────────────────────────────

        stereotype("Participant") {
            extends(UmlMetaclass.Class)
            constraint("participant-has-port") {
                ocl("self.ownedPort->notEmpty()")
            }
        }

        stereotype("ServiceInterface") {
            extends(UmlMetaclass.Interface)
        }

        // ── Ports ────────────────────────────────────────────────────────────────

        stereotype("Service") {
            extends(UmlMetaclass.Port)
        }

        stereotype("Request") {
            extends(UmlMetaclass.Port)
        }

        // ── Contracts and architectures ──────────────────────────────────────────

        stereotype("ServiceContract") {
            extends(UmlMetaclass.Collaboration)
            constraint("contract-has-two-roles") {
                ocl("self.role->size() >= 2")
            }
        }

        stereotype("ServicesArchitecture") {
            extends(UmlMetaclass.Collaboration)
        }

        // ── Channels and message types ───────────────────────────────────────────

        stereotype("ServiceChannel") {
            extends(UmlMetaclass.Connector)
        }

        stereotype("MessageType") {
            extends(UmlMetaclass.Class)
        }
    }
