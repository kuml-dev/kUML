package dev.kuml.codegen.sql

import dev.kuml.codegen.api.KumlCodeGenerator
import dev.kuml.codegen.api.KumlCodeGeneratorProvider

internal class FlywayBaselineGeneratorProvider : KumlCodeGeneratorProvider {
    override fun generator(): KumlCodeGenerator = FlywayBaselineGenerator()
}
