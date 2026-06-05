package dev.kuml.codegen.kotlin

import dev.kuml.codegen.api.KumlCodeGenerator
import dev.kuml.codegen.api.KumlCodeGeneratorProvider

internal class KotlinCodeGeneratorProvider : KumlCodeGeneratorProvider {
    override fun generator(): KumlCodeGenerator = KotlinCodeGenerator()
}
