package dev.kuml.codegen.m2m.exposed

import dev.kuml.codegen.m2m.GeneratedFile
import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.sql.ErmSqlDdlGenerator
import dev.kuml.erm.dsl.ermModel
import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmForeignKey
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ReferentialAction
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

/**
 * Golden-model cross-emitter agreement test (fix/erm-fk-constraint-naming-mismatch).
 *
 * [dev.kuml.codegen.sql.ErmSqlEmitter] (`kuml-gen-sql`) and [ErmExposedEmitter]
 * (`kuml-codegen-m2m-exposed`) both derive their foreign-key constraint name and
 * referential-action rendering from the exact same [ErmModel] — but until this test
 * existed, nothing in kUML ever compared their two outputs against each other.
 * Each module's own tests only asserted their own emitter's output in isolation, so
 * the two emitters silently drifted apart: `ErmSqlEmitter` named constraints
 * `fk_<table>_<column>` and omitted `ON DELETE`/`ON UPDATE` clauses for
 * [ReferentialAction.NO_ACTION] (correct SQL-standard behaviour — an omitted clause
 * *means* `NO ACTION`), while `ErmExposedEmitter` left the `fkName`/`onDelete`/
 * `onUpdate` arguments of `reference()`/`optReference()` implicit for the
 * [ReferentialAction.NO_ACTION] case — which meant Exposed itself would compute its
 * *own*, different, defaults at runtime (`fk_<table>_<column>__<targetcolumn>` naming,
 * `RESTRICT` as the Postgres-dialect default reference option). This was invisible to
 * every kUML test because it can only be caught by actually applying the generated SQL
 * to a real database and asking Exposed's own `SchemaUtils.statementsRequiredToActualizeScheme`
 * whether the compiled `Table` objects agree with it — exactly what a downstream
 * consumer's Testcontainers-based CI run first caught in production.
 *
 * This test needs no live database: it renders both outputs from one shared model and
 * regex-compares the FK constraint name + onDelete + onUpdate triple each emitter
 * declares, per foreign-key-bearing attribute. It is the cheap, structural guard against
 * this exact class of regression recurring — see `ermDefaultForeignKeyConstraintName`'s
 * KDoc in `kuml-metamodel-erm` for the shared naming function both emitters now use.
 */
class ErmSqlExposedFkAgreementTest :
    FunSpec({

        /**
         * One model exercising every FK shape both emitters need to agree on:
         *  - plain not-null many-to-one FK (NO_ACTION/NO_ACTION — the case that regressed)
         *  - nullable FK (optReference() on the Exposed side)
         *  - FK with non-default onDelete/onUpdate (CASCADE/RESTRICT)
         *  - FK with an explicit targetAttributeId (targets a unique non-PK column)
         *  - composite-PK junction entity (two FKs forming the composite key together)
         */
        val model: ErmModel =
            ermModel(name = "Agreement") {
                lateinit var departmentCodeAttrId: String
                val departments =
                    entity(name = "departments") {
                        id(name = "id", type = ErmDataType.Integer(64))
                        departmentCodeAttrId = attribute(name = "code", type = ErmDataType.Varchar(20), nullable = false, unique = true)
                    }

                entity(name = "employees") {
                    id(name = "id", type = ErmDataType.Integer(64))
                    attribute(
                        name = "department_id",
                        type = ErmDataType.Integer(64),
                        nullable = false,
                        foreignKey = ErmForeignKey(targetEntityId = departments),
                    )
                    attribute(
                        name = "backup_department_id",
                        type = ErmDataType.Integer(64),
                        nullable = true,
                        foreignKey = ErmForeignKey(targetEntityId = departments),
                    )
                    attribute(
                        name = "home_department_code",
                        type = ErmDataType.Varchar(20),
                        nullable = false,
                        foreignKey = ErmForeignKey(targetEntityId = departments, targetAttributeId = departmentCodeAttrId),
                    )
                    attribute(
                        name = "cascade_department_id",
                        type = ErmDataType.Integer(64),
                        nullable = false,
                        foreignKey =
                            ErmForeignKey(
                                targetEntityId = departments,
                                onDelete = ReferentialAction.CASCADE,
                                onUpdate = ReferentialAction.RESTRICT,
                            ),
                    )
                }

                val students = entity(name = "students") { id(name = "id", type = ErmDataType.Uuid) }
                val courses = entity(name = "courses") { id(name = "id", type = ErmDataType.Uuid) }
                entity(name = "enrollments", weak = true) {
                    attribute(
                        name = "student_id",
                        type = ErmDataType.Uuid,
                        primaryKey = true,
                        nullable = false,
                        foreignKey = ErmForeignKey(targetEntityId = students),
                    )
                    attribute(
                        name = "course_id",
                        type = ErmDataType.Uuid,
                        primaryKey = true,
                        nullable = false,
                        foreignKey = ErmForeignKey(targetEntityId = courses),
                    )
                }
            }

        /** `constraintName -> "<onDelete>/<onUpdate>"`, extracted from `ErmSqlEmitter`'s DDL. */
        fun sqlConstraints(): Map<String, String> {
            val outputDir = Files.createTempDirectory("erm-sql-fk-agreement").toFile()
            val files = ErmSqlDdlGenerator().generate(model = model, outputDir = outputDir, options = emptyMap())
            val ddl = files.single { it.name == "schema.sql" }.readText()

            val fkLine =
                Regex(
                    """ALTER TABLE \w+ ADD CONSTRAINT (\w+) FOREIGN KEY \(\w+\) REFERENCES \w+\(\w+\)""" +
                        """(?: ON DELETE (\w+(?: \w+)?))?(?: ON UPDATE (\w+(?: \w+)?))?;""",
                )
            return fkLine.findAll(ddl).associate { m ->
                val (name, onDelete, onUpdate) = m.destructured
                name to "${normalizeAction(onDelete)}/${normalizeAction(onUpdate)}"
            }
        }

        /** `fkName -> "<onDelete>/<onUpdate>"`, extracted from `ErmExposedEmitter`'s generated `reference()`/`optReference()` calls. */
        fun exposedConstraints(): Map<String, String> {
            val result = ErmToExposedTransformer().transform(source = model, ctx = TransformContext(emptyMap()))
            val files = result.shouldBeInstanceOf<TransformResult.Success<List<GeneratedFile>>>().output
            val allContent = files.joinToString("\n") { it.content }

            val refCall =
                Regex(
                    """(?:reference|optReference)\("[^"]+",\s*[\w.]+,\s*""" +
                        """onDelete = ReferenceOption\.(\w+),\s*onUpdate = ReferenceOption\.(\w+),\s*""" +
                        """fkName = "([^"]+)"\)""",
                )
            return refCall.findAll(allContent).associate { m ->
                val (onDelete, onUpdate, fkName) = m.destructured
                fkName to "$onDelete/$onUpdate"
            }
        }

        test("ErmSqlEmitter and ErmExposedEmitter agree on FK constraint name + onDelete + onUpdate for every FK") {
            val sql = sqlConstraints()
            val exposed = exposedConstraints()

            // Sanity: the regexes above actually matched something in both outputs — an empty
            // map on either side would make the loop below vacuously pass and mask a change
            // to either emitter's output shape that broke the extraction itself.
            sql.shouldNotBeEmpty()
            exposed.shouldNotBeEmpty()

            // 6 FK-bearing attributes declared above: department_id, backup_department_id,
            // home_department_code, cascade_department_id, student_id, course_id.
            sql shouldBe
                mapOf(
                    "fk_employees_department_id" to "NO_ACTION/NO_ACTION",
                    "fk_employees_backup_department_id" to "NO_ACTION/NO_ACTION",
                    "fk_employees_home_department_code" to "NO_ACTION/NO_ACTION",
                    "fk_employees_cascade_department_id" to "CASCADE/RESTRICT",
                    "fk_enrollments_student_id" to "NO_ACTION/NO_ACTION",
                    "fk_enrollments_course_id" to "NO_ACTION/NO_ACTION",
                )

            for ((constraintName, actions) in sql) {
                exposed shouldContainKey constraintName
                exposed.getValue(constraintName) shouldBe actions
            }
            exposed.keys shouldBe sql.keys
        }
    })

/** `""` (omitted clause) → `NO_ACTION`; `"SET NULL"`/`"SET DEFAULT"` → underscore form; else as-is. */
private fun normalizeAction(raw: String?): String =
    when {
        raw.isNullOrBlank() -> "NO_ACTION"
        else -> raw.trim().replace(' ', '_')
    }
