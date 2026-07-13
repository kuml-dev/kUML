package dev.kuml.erm.serialization

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.erm.model.Cardinality
import dev.kuml.erm.model.ErmAttribute
import dev.kuml.erm.model.ErmCategory
import dev.kuml.erm.model.ErmCheckConstraint
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmEntity
import dev.kuml.erm.model.ErmForeignKey
import dev.kuml.erm.model.ErmIndex
import dev.kuml.erm.model.ErmMetadataKeys
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.erm.model.ErmRelationship
import dev.kuml.erm.model.ErmView
import dev.kuml.erm.model.ReferentialAction
import dev.kuml.erm.model.RelationshipKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * Serialization roundtrip tests for V3.4.1.
 *
 * Uses the exact `Json { classDiscriminator = "@type" }` configuration that
 * `ExtractedDiagramCodec` (kuml-core-script) uses for the IPC boundary — this
 * verifies the `@SerialName` discriminators on [ErmDataType] and [dev.kuml.erm.model.ErmElement]
 * are stable and that `ErmModel` round-trips losslessly through that codec's
 * wire format without needing a dedicated `SerializersModule` (unlike UML's
 * open-polymorphic `KumlElement`).
 */
class ErmSerializationTest :
    StringSpec({
        val json = Json { classDiscriminator = "@type" }

        fun sampleModel(): ErmModel {
            val customer =
                ErmEntity(
                    id = "entity_0",
                    name = "Customer",
                    attributes =
                        listOf(
                            ErmAttribute("attr_0_0", "id", ErmDataType.Uuid, primaryKey = true, nullable = false),
                            ErmAttribute("attr_0_1", "email", ErmDataType.Varchar(255), unique = true),
                            ErmAttribute("attr_0_2", "balance", ErmDataType.Decimal(10, 2), default = "0.00"),
                            ErmAttribute("attr_0_3", "age", ErmDataType.Integer(16), autoIncrement = false),
                            ErmAttribute("attr_0_4", "rating", ErmDataType.Real(double = false)),
                            ErmAttribute("attr_0_5", "bio", ErmDataType.Text),
                            ErmAttribute("attr_0_6", "active", ErmDataType.Boolean),
                            ErmAttribute("attr_0_7", "birthday", ErmDataType.Date),
                            ErmAttribute("attr_0_8", "curfew", ErmDataType.Time),
                            ErmAttribute("attr_0_9", "created_at", ErmDataType.Timestamp(withTimeZone = true)),
                            ErmAttribute("attr_0_10", "avatar", ErmDataType.Blob),
                            ErmAttribute("attr_0_11", "prefs", ErmDataType.Json),
                            ErmAttribute("attr_0_12", "vector", ErmDataType.Custom("TSVECTOR")),
                            ErmAttribute("attr_0_13", "status", ErmDataType.Enum("Status", listOf("Active", "Inactive"))),
                        ),
                    indexes = listOf(ErmIndex("idx_0_0", "idx_email", listOf("attr_0_1"), unique = true)),
                    checks = listOf(ErmCheckConstraint("check_0_0", "positive_age", "age > 0")),
                )
            val order =
                ErmEntity(
                    id = "entity_1",
                    name = "Order",
                    weak = true,
                    attributes =
                        listOf(
                            ErmAttribute(
                                "attr_1_0",
                                "customer_id",
                                ErmDataType.Uuid,
                                foreignKey =
                                    ErmForeignKey(
                                        targetEntityId = "entity_0",
                                        targetAttributeId = "attr_0_0",
                                        onDelete = ReferentialAction.CASCADE,
                                        onUpdate = ReferentialAction.RESTRICT,
                                    ),
                            ),
                        ),
                )
            val rel =
                ErmRelationship(
                    id = "rel_0",
                    name = "places",
                    sourceEntityId = "entity_0",
                    targetEntityId = "entity_1",
                    sourceCardinality = Cardinality.ONE,
                    targetCardinality = Cardinality.ZERO_MANY,
                    kind = RelationshipKind.IDENTIFYING,
                    sourceRole = "customer",
                    targetRole = "orders",
                )
            val view =
                ErmView(
                    id = "view_0",
                    name = "active_customers",
                    query = "SELECT * FROM customer WHERE active",
                    referencedEntityIds = listOf("entity_0"),
                )
            val category =
                ErmCategory(
                    id = "category_0",
                    name = "CustomerType",
                    supertypeEntityId = "entity_0",
                    subtypeEntityIds = listOf("entity_1"),
                    complete = true,
                    discriminatorAttributeId = "attr_0_1",
                )
            return ErmModel(
                name = "Shop",
                entities = listOf(customer, order),
                relationships = listOf(rel),
                views = listOf(view),
                diagrams = listOf(ErmDiagram(name = "Overview", notation = ErmNotation.IDEF1X, showIndexes = true)),
                categories = listOf(category),
            )
        }

        "ErmModel round-trips losslessly through the IPC codec's Json config" {
            val model = sampleModel()
            val encoded = json.encodeToString(ErmModel.serializer(), model)
            val decoded = json.decodeFromString(ErmModel.serializer(), encoded)
            decoded shouldBe model
        }

        "every ErmDataType variant round-trips with a stable discriminator" {
            val types: List<ErmDataType> =
                listOf(
                    ErmDataType.Integer(16),
                    ErmDataType.Integer(32),
                    ErmDataType.Integer(64),
                    ErmDataType.Decimal(10, 2),
                    ErmDataType.Real(true),
                    ErmDataType.Real(false),
                    ErmDataType.Varchar(255),
                    ErmDataType.Text,
                    ErmDataType.Boolean,
                    ErmDataType.Date,
                    ErmDataType.Time,
                    ErmDataType.Timestamp(true),
                    ErmDataType.Timestamp(false),
                    ErmDataType.Uuid,
                    ErmDataType.Blob,
                    ErmDataType.Json,
                    ErmDataType.Custom("TSVECTOR"),
                    ErmDataType.Enum("Status", listOf("Active", "Inactive")),
                )
            types.forEach { type ->
                val encoded = json.encodeToString(ErmDataType.serializer(), type)
                val decoded = json.decodeFromString(ErmDataType.serializer(), encoded)
                decoded shouldBe type
            }
        }

        "ErmEntity.metadata (HYPERTABLE marker) round-trips through the IPC codec's Json config" {
            val entity =
                ErmEntity(
                    id = "entity_0",
                    name = "sensor_readings",
                    attributes = listOf(ErmAttribute("attr_0_0", "recorded_at", ErmDataType.Timestamp())),
                    metadata =
                        mapOf(
                            ErmMetadataKeys.HYPERTABLE to
                                KumlMetaValue.Entries(
                                    mapOf(
                                        ErmMetadataKeys.HT_TIME_COLUMN to KumlMetaValue.Text("recorded_at"),
                                        ErmMetadataKeys.HT_CHUNK_INTERVAL to KumlMetaValue.Text("7 days"),
                                    ),
                                ),
                        ),
                )
            val encoded = json.encodeToString(ErmEntity.serializer(), entity)
            val decoded = json.decodeFromString(ErmEntity.serializer(), encoded)
            decoded shouldBe entity
        }

        "ErmEntity.metadata (KOTLIN_OBJECT_NAME marker) round-trips through the IPC codec's Json config" {
            val entity =
                ErmEntity(
                    id = "entity_0",
                    name = "member",
                    attributes = listOf(ErmAttribute("attr_0_0", "id", ErmDataType.Uuid, primaryKey = true, nullable = false)),
                    metadata =
                        mapOf(
                            ErmMetadataKeys.KOTLIN_OBJECT_NAME to KumlMetaValue.Text("MemberTable"),
                        ),
                )
            val encoded = json.encodeToString(ErmEntity.serializer(), entity)
            val decoded = json.decodeFromString(ErmEntity.serializer(), encoded)
            decoded shouldBe entity
        }

        "every ErmElement variant round-trips through the sealed ErmElement serializer" {
            val elements =
                listOf(
                    ErmAttribute("a", "n", ErmDataType.Uuid, primaryKey = true, nullable = false),
                    ErmIndex("i", "n", listOf("a"), unique = true),
                    ErmCheckConstraint("c", "n", "x > 0"),
                    ErmView("v", "n", "SELECT 1", listOf("e")),
                    ErmRelationship("r", "n", "e1", "e2", Cardinality.ONE, Cardinality.ZERO_MANY),
                    ErmEntity("e", "n", listOf(ErmAttribute("a2", "n2", ErmDataType.Text))),
                    ErmCategory("cat", "n", "e1", listOf("e2")),
                )
            elements.forEach { element ->
                val encoded = json.encodeToString(dev.kuml.erm.model.ErmElement.serializer(), element)
                val decoded = json.decodeFromString(dev.kuml.erm.model.ErmElement.serializer(), encoded)
                decoded shouldBe element
            }
        }
    })
