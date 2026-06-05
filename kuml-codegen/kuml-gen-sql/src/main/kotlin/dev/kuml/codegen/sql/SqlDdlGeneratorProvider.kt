package dev.kuml.codegen.sql

import dev.kuml.codegen.api.KumlCodeGenerator
import dev.kuml.codegen.api.KumlCodeGeneratorProvider

internal class SqlDdlGeneratorProvider : KumlCodeGeneratorProvider {
    override fun generator(): KumlCodeGenerator = SqlDdlGenerator()
}
