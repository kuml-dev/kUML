You are helping create or review structured error definitions for kUML.

## KumlError Schema

```kotlin
data class KumlError(
    val code: String,           // Format: "KUML-[E|W|I]-NNN" (E=Error, W=Warning, I=Info)
    val severity: Severity,     // ERROR, WARNING, INFO
    val file: String,
    val line: Int,
    val column: Int,
    val symbol: String,         // The offending symbol/token
    val message: String,        // Clear, actionable English message
    val expected: List<String> = emptyList(),  // What was expected instead
    val suggestion: String? = null,            // How to fix it (LLM-actionable)
    val docUrl: String? = null,               // https://kuml.dev/docs/errors/KUML-E-NNN
)

enum class Severity { ERROR, WARNING, INFO }
```

## Error Code Ranges

| Range | Domain |
|---|---|
| KUML-E-1xx | Parser / Syntax errors |
| KUML-E-2xx | Type checking / semantic errors |
| KUML-E-3xx | OCL constraint violations |
| KUML-E-4xx | XMI import/export errors |
| KUML-E-5xx | M2M transformation errors |
| KUML-E-6xx | Code generation errors |
| KUML-W-1xx | Parser warnings |
| KUML-W-2xx | Semantic warnings |
| KUML-I-1xx | Informational messages |

## JSON Output Format (--output json)

```json
{
  "errors": [
    {
      "code": "KUML-E-201",
      "severity": "error",
      "file": "model.kuml",
      "line": 14,
      "column": 9,
      "symbol": "OrderStatus",
      "message": "Type 'OrderStatus' is not declared in this scope.",
      "expected": ["enum", "class", "interface"],
      "suggestion": "Did you mean to add `enumOf(\"OrderStatus\") { ... }` first?",
      "docUrl": "https://kuml.dev/docs/errors/KUML-E-201"
    }
  ]
}
```

## Quality criteria for error messages
1. `message` — precise, states what went wrong, not what the tool did
2. `suggestion` — must be directly actionable by an LLM (self-correctable)
3. `expected` — list all valid alternatives, not just one
4. `symbol` — exact token from source, not a paraphrase

## Task
When asked to create or review a KumlError:
1. Assign the correct code from the right range
2. Write a clear, LLM-actionable `suggestion`
3. List realistic `expected` alternatives
4. Ensure the message is in English and precise
5. If reviewing existing errors, flag: vague suggestions, missing `expected`, wrong code range, non-English messages
