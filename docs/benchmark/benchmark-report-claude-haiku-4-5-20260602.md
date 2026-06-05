# kUML LLM Benchmark Report

**Model:** claude-haiku-4-5-20251001  
**Backend:** anthropic-claude-haiku-4-5-20251001  
**Date:** 2026-06-02

## Summary

| Metric | Value |
|---|---|
| Total tasks | 10 |
| Successful | 6 |
| Success rate | 60,0% |

## Results by Tool

| Tool | Tasks | Success | Rate |
|---|---|---|---|
| KUML | 6 | 2 | 33,3% |
| PLANTUML | 2 | 2 | 100,0% |
| MERMAID | 2 | 2 | 100,0% |

## Results by Language

| Language | Tasks | Success | Rate |
|---|---|---|---|
| DE | 5 | 3 | 60,0% |
| EN | 5 | 3 | 60,0% |

## Task Detail

| Task | Tool | Lang | Type | Valid | Message |
|---|---|---|---|---|---|
| kuml-class-de-001 | KUML | DE | CLASS | ✅ | OK |
| kuml-class-en-001 | KUML | EN | CLASS | ✅ | OK |
| kuml-class-de-002 | KUML | DE | CLASS | ❌ | Argument type mismatch: actual type is 'String', but 'UmlCla |
| kuml-class-en-002 | KUML | EN | CLASS | ❌ | Unresolved reference 'stereotype'.; Argument type mismatch:  |
| kuml-c4-de-001 | KUML | DE | C4_SYSTEM_CONTEXT | ❌ | Unresolved reference 'c4Model'.; Unresolved reference 'perso |
| kuml-c4-en-001 | KUML | EN | C4_SYSTEM_CONTEXT | ❌ | Unresolved reference 'c4Model'.; Unresolved reference 'perso |
| plantuml-class-de-001 | PLANTUML | DE | CLASS | ✅ | OK |
| plantuml-class-en-001 | PLANTUML | EN | CLASS | ✅ | OK |
| mermaid-class-de-001 | MERMAID | DE | CLASS | ✅ | OK |
| mermaid-class-en-001 | MERMAID | EN | CLASS | ✅ | OK |

