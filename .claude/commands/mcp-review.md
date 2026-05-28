You are doing a security and correctness review of kUML MCP server tool implementations.

## MCP Tool Inventory

| Tool | Input | Output | Risk Level |
|---|---|---|---|
| `kuml.validate(code)` | kUML source string | JSON error list | HIGH — arbitrary code string |
| `kuml.render(code, format)` | kUML source + format enum | SVG/PNG/ASCII bytes | HIGH |
| `kuml.describe(code)` | kUML source string | Natural language string | HIGH |
| `kuml.list_elements(code)` | kUML source string | JSON structure | HIGH |
| `kuml.suggest(partial)` | Partial kUML string | Completion list | MEDIUM |
| `kuml.diff(a, b)` | Two kUML strings | Semantic diff JSON | HIGH |
| `kuml.transform(code, transformer)` | kUML + transformer name | Transformed kUML | HIGH + plugin execution |
| `kuml.generate(code, plugin)` | kUML + plugin name | Generated code | HIGH + plugin execution |

## Security Checklist

### Input Validation
- [ ] `code` parameter: maximum length enforced (suggested: 500 KB)
- [ ] `format` parameter: enum validation, not arbitrary string
- [ ] `transformer` / `plugin` names: allowlist validation (no path traversal)
- [ ] No shell execution with user-provided input
- [ ] No file system writes in tool execution path

### Resource Limits
- [ ] Rendering timeout enforced (suggested: 10 s)
- [ ] Memory limit for large models
- [ ] OCL constraint evaluation timeout
- [ ] M2M transformation step limit

### Output Safety
- [ ] SVG output sanitized (no `<script>`, no external `href`)
- [ ] Generated code not executed server-side
- [ ] Error messages don't leak server paths or stack traces

### Transport
- [ ] Stdio transport: no environment variable leakage in tool output
- [ ] SSE transport: authentication if exposed beyond localhost
- [ ] API keys (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`) never returned in tool output

## Correctness Checklist

- [ ] Tool returns structured JSON (not plain text)
- [ ] Error cases return `KumlError[]` not exceptions
- [ ] `--output json` flag respected in nested CLI calls
- [ ] Tool is idempotent (same input → same output, no side effects)

## Task
Review the provided MCP tool implementation against this checklist.
Report: which items pass, which fail, and concrete fix for each failure.
