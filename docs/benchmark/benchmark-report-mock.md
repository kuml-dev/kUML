# kUML LLM Benchmark Report

**Model:** mock  
**Backend:** mock  
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
| KUML | 6 | 6 | 100,0% |
| PLANTUML | 2 | 0 | 0,0% |
| MERMAID | 2 | 0 | 0,0% |

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
| kuml-class-de-002 | KUML | DE | CLASS | ✅ | OK |
| kuml-class-en-002 | KUML | EN | CLASS | ✅ | OK |
| kuml-c4-de-001 | KUML | DE | C4_SYSTEM_CONTEXT | ✅ | OK |
| kuml-c4-en-001 | KUML | EN | C4_SYSTEM_CONTEXT | ✅ | OK |
| plantuml-class-de-001 | PLANTUML | DE | CLASS | ❌ | Missing @startuml/@enduml markers |
| plantuml-class-en-001 | PLANTUML | EN | CLASS | ❌ | Missing @startuml/@enduml markers |
| mermaid-class-de-001 | MERMAID | DE | CLASS | ❌ | Missing Mermaid diagram type declaration |
| mermaid-class-en-001 | MERMAID | EN | CLASS | ❌ | Missing Mermaid diagram type declaration |

