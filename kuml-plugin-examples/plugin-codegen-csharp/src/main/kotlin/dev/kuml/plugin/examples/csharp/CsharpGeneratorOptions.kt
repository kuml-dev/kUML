package dev.kuml.plugin.examples.csharp

/**
 * Configuration options for [CsharpCodeGenerator].
 *
 * @property targetFramework Target .NET framework moniker (informational, e.g. `net8.0`).
 * @property useNullableReferenceTypes When `true` (default), emits `#nullable enable` and appends
 *   `?` to optional reference/value type properties (multiplicity lower = 0).
 * @property generateUsings When `true` (default), emits `using` directives for any System.*
 *   types referenced in the generated code.
 * @property namespaceName Optional C# namespace to wrap all generated code in.
 *   `null` means no namespace wrapper is emitted.
 * @property naming Naming convention applied to property and method names.
 */
public data class CsharpGeneratorOptions(
    val targetFramework: String = "net8.0",
    val useNullableReferenceTypes: Boolean = true,
    val generateUsings: Boolean = true,
    val namespaceName: String? = null,
    val naming: CsharpNamingConvention = CsharpNamingConvention.DEFAULT,
) {
    public companion object {
        /** Parses generator options from a string-keyed options map. */
        public fun from(options: Map<String, String>): CsharpGeneratorOptions =
            CsharpGeneratorOptions(
                targetFramework = options["targetFramework"] ?: "net8.0",
                useNullableReferenceTypes =
                    options["useNullableReferenceTypes"]?.lowercase()?.let { it != "false" } ?: true,
                generateUsings =
                    options["generateUsings"]?.lowercase()?.let { it != "false" } ?: true,
                namespaceName = options["namespace"]?.takeIf { it.isNotBlank() },
                naming =
                    when (options["naming"]?.lowercase()) {
                        "camelcase", "camel_case" -> CsharpNamingConvention.DEFAULT
                        "pascalcase", "pascal_case" -> CsharpNamingConvention.PASCAL_CASE
                        else -> CsharpNamingConvention.DEFAULT
                    },
            )
    }
}
