package dev.kuml.codegen.sql

import dev.kuml.uml.AppliedStereotype
import dev.kuml.uml.TagValue

internal data class TestStereo(
    override val profileNamespace: String = "test",
    override val stereotypeName: String,
    override val tags: Map<String, TagValue> = emptyMap(),
) : AppliedStereotype
