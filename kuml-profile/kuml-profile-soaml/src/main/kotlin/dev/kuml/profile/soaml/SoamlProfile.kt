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
    profile(name = "SoaML") {
        namespace = "dev.kuml.profiles.soaml"
        description = "OMG Service oriented architecture Modeling Language"
        version = "1.0.0"

        // ── Participants and interfaces ───────────────────────────────────────────

        stereotype(name = "Participant") {
            extends(UmlMetaclass.Component)
            constraint(name = "participant-has-port") {
                ocl("self.ownedPort->notEmpty()")
            }
        }

        stereotype(name = "ServiceInterface") {
            extends(UmlMetaclass.Interface)
        }

        // ── Ports ────────────────────────────────────────────────────────────────

        stereotype(name = "Service") {
            extends(UmlMetaclass.Port)
        }

        stereotype(name = "Request") {
            extends(UmlMetaclass.Port)
        }

        // ── Contracts and architectures ──────────────────────────────────────────

        stereotype(name = "ServiceContract") {
            extends(UmlMetaclass.Collaboration)
            constraint(name = "contract-has-two-roles") {
                ocl("self.role->size() >= 2")
            }
        }

        stereotype(name = "ServicesArchitecture") {
            extends(UmlMetaclass.Collaboration)
        }

        // ── Channels and message types ───────────────────────────────────────────

        stereotype(name = "ServiceChannel") {
            extends(UmlMetaclass.Connector)
        }

        stereotype(name = "MessageType") {
            extends(UmlMetaclass.Class)
        }
    }
