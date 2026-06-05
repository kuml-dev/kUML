package dev.kuml.codegen.java

import dev.kuml.codegen.api.KumlCodeGenerator
import dev.kuml.codegen.api.KumlCodeGeneratorProvider

internal class JavaCodeGeneratorProvider : KumlCodeGeneratorProvider {
    override fun generator(): KumlCodeGenerator = JavaCodeGenerator()
}
