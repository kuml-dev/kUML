package dev.kuml.codegen.sql

import dev.kuml.uml.Multiplicity
import dev.kuml.uml.UmlTypeRef
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SqlTypeMapperTest :
    FunSpec({

        test("Int maps to INTEGER for all dialects") {
            SqlDialect.entries.forEach { dialect ->
                SqlTypeMapper.toSqlType(UmlTypeRef("Int"), dialect) shouldBe "INTEGER"
            }
        }

        test("UUID dialect-specific") {
            SqlTypeMapper.toSqlType(UmlTypeRef("UUID"), SqlDialect.POSTGRES) shouldBe "UUID"
            SqlTypeMapper.toSqlType(UmlTypeRef("UUID"), SqlDialect.MYSQL) shouldBe "CHAR(36)"
            SqlTypeMapper.toSqlType(UmlTypeRef("UUID"), SqlDialect.SQLITE) shouldBe "TEXT"
        }

        test("Boolean dialect-specific") {
            SqlTypeMapper.toSqlType(UmlTypeRef("Boolean"), SqlDialect.POSTGRES) shouldBe "BOOLEAN"
            SqlTypeMapper.toSqlType(UmlTypeRef("Boolean"), SqlDialect.MYSQL) shouldBe "TINYINT(1)"
            SqlTypeMapper.toSqlType(UmlTypeRef("Boolean"), SqlDialect.SQLITE) shouldBe "INTEGER"
        }

        test("DateTime dialect-specific") {
            SqlTypeMapper.toSqlType(UmlTypeRef("DateTime"), SqlDialect.POSTGRES) shouldBe "TIMESTAMP"
            SqlTypeMapper.toSqlType(UmlTypeRef("DateTime"), SqlDialect.MYSQL) shouldBe "DATETIME"
        }

        test("Multiplicity (1,1) NOT NULL") {
            SqlTypeMapper.nullClause(Multiplicity(1, 1)) shouldBe "NOT NULL"
        }

        test("Multiplicity (0,1) NULL") {
            SqlTypeMapper.nullClause(Multiplicity(0, 1)) shouldBe "NULL"
        }

        test("Multiplicity (0,*) is many-to-many candidate") {
            SqlTypeMapper.isManyToManyCandidate(Multiplicity(0, null)) shouldBe true
            SqlTypeMapper.isManyToManyCandidate(Multiplicity(1, null)) shouldBe true
        }

        test("Multiplicity (1,1) is not a many-to-many candidate") {
            SqlTypeMapper.isManyToManyCandidate(Multiplicity(1, 1)) shouldBe false
        }
    })
