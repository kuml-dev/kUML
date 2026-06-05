You are reviewing kUML DSL code for consistency with the project's strict conventions.

## kUML DSL Rules (non-negotiable)

### 1. Named Parameters — REQUIRED everywhere
Every function and constructor call MUST use named parameters where the API allows it.

```kotlin
// ✅ CORRECT
classOf("Order") {
    attribute(name = "id", type = UUID, visibility = PRIVATE)
    operation(name = "confirm", visibility = PUBLIC, returns = Unit::class)
}
association(source = Order::class, target = OrderItem::class) {
    aggregation = COMPOSITE
    source { multiplicity = "1" }
    target { multiplicity = "1..*" }
}

// ❌ REJECT
attribute("id", UUID, PRIVATE)
association(Order::class, OrderItem::class)
```

### 2. Visibility — always explicit in DSL
```kotlin
// ✅ CORRECT
classOf("User") {
    visibility = PUBLIC
    attribute(name = "id", type = UUID, visibility = PRIVATE)
}
// ❌ REJECT — implicit visibility
```

### 3. One concept, one keyword — no synonyms
| Concept | Keyword | NOT |
|---|---|---|
| Class | `classOf` | `klass`, `clazz`, `umlClass` |
| Interface | `interfaceOf` | `iface`, `interface` |
| Enum | `enumOf` | `enum`, `enumeration` |
| Abstract class | `classOf` + `isAbstract = true` | `abstractClass` |
| Composition | `association` + `aggregation = COMPOSITE` | `composition` |
| Aggregation | `association` + `aggregation = SHARED` | `aggregation` |
| Dependency | `dependency` | `uses`, `depends` |
| Realization | `realization` | `implements` |

### 4. No layout directives in the model
Models must NOT contain pixel values, colors, positions, or layout hints.
```kotlin
// ❌ REJECT
classOf("User") {
    x = 100; y = 200  // No coordinates in model
    color = "#FF0000"  // No colors in model
}
```

### 5. Immutability
- Prefer `val` over `var`
- Use `data class` for data containers
- Use `when` instead of `if-else if` chains

## Task
Review the provided kUML DSL code or implementation. For each violation:
1. Quote the exact line
2. Name the violated rule
3. Show the corrected version

If the code is correct, confirm it and explain why it follows the conventions.
