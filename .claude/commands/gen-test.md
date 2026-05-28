You are generating or reviewing tests for the kUML project. Follow these conventions exactly.

## Test Framework Matrix

| Test Type | Module | Frameworks |
|---|---|---|
| DSL Unit Tests | `kuml-dsl-tests` | Kotlin Test + JUnit 5 + Kotest |
| OCL Tests | `kuml-ocl-tests` | Kotlin Test + JUnit 5 + Kotest |
| SVG Snapshot Tests | `kuml-renderer-tests` | Kotlin Test + AssertJ + `assertMatchesSnapshot()` |
| XMI Roundtrip | `kuml-xmi-tests` | Kotlin Test + AssertJ |
| M2M Tests | `kuml-transform-tests` | Kotlin Test + JUnit 5 + Kotest |
| CLI Tests | `kuml-cli-tests` | Kotlin Test (system/end-to-end) |
| Web UI Tests | `kuml-web-tests` | Playwright (JVM), Headless Chromium |
| Desktop UI Tests | `kuml-desktop-tests` | Compose UI Testing (ui-test-junit4) |
| IDE Plugin Tests | `kuml-intellij-plugin-tests` | IntelliJ Platform Test Framework (LightCodeInsightFixtureTestCase) |
| LLM Tests | `kuml-llm-tests` | Kotlin Test + JUnit 5 + `@Tag("live")` |

## Naming Conventions

```kotlin
// Class naming
class ClassDiagramTest              // UML structural
class SequenceDiagramTest           // UML behavioral
class SysMLBlockDefinitionTest      // SysML
class OclConstraintValidationTest   // OCL
class SvgSnapshotTest               // Renderer

// Method naming — English, backtick syntax
@Test
fun `minimal class diagram creates valid EMF model`() { }

@Test
fun `inheritance relationship renders correct arrow type`() { }

@Test
fun `invalid multiplicity is reported as KUML-E-201`() { }
```

## Test Templates

### DSL Unit Test
```kotlin
class ClassDiagramTest {
    @Test
    fun `minimal class diagram creates valid EMF model`() {
        val model = diagram(name = "Test") {
            classOf(name = "User") {
                visibility = PUBLIC
                attribute(name = "id", type = UUID, visibility = PRIVATE)
            }
        }.toEMF()

        assertThat(model.elements).hasSize(1)
        assertThat(model.elements.first().name).isEqualTo("User")
    }
}
```

### SVG Snapshot Test
```kotlin
class SvgSnapshotTest {
    @Test
    fun `class diagram with inheritance renders correctly`() {
        val svg = diagram(name = "Inheritance") {
            classOf(name = "Animal") { visibility = PUBLIC }
            classOf(name = "Dog") {
                visibility = PUBLIC
                extends(Animal::class)
            }
        }.render(format = RenderFormat.SVG)

        assertMatchesSnapshot(
            actual = svg,
            snapshotName = "class-diagram-inheritance"
        )
    }
}
// Update snapshots: ./gradlew :kuml-tests:kuml-renderer-tests:test -PupdateSnapshots
```

### LLM Live Test
```kotlin
@Test
@Tag("live")  // Excluded from standard CI: ./gradlew test -DexcludeTags=live
fun `anthropic backend generates valid kuml for simple class prompt`() {
    val backend = AnthropicLlmBackend(apiKey = System.getenv("ANTHROPIC_API_KEY"))
    val result = backend.generate(prompt = "A User class with id and email")
    assertThat(result).isNotBlank()
    val errors = KumlValidator.validate(source = result)
    assertThat(errors).isEmpty()
}
```

### Web UI Test (Playwright)
```kotlin
class KumlWebEditorTest {
    @Test
    fun `editor renders svg preview on valid kuml input`() {
        val code = """
            diagram(name = "Test") {
                classOf(name = "User") { visibility = PUBLIC }
            }
        """.trimIndent()

        page.locator("[data-testid='kuml-editor']").fill(code)
        page.waitForSelector("[data-testid='kuml-preview'] svg")
        assertThat(page.locator("[data-testid='kuml-preview'] svg")).isVisible()
    }
}
```

### Compose Desktop Test
```kotlin
@Test
fun `editor input updates svg preview after debounce`() {
    onNodeWithTag("kuml-editor").performTextInput(code)
    mainClock.advanceTimeBy(400)   // Debounce-Zeit überbrücken
    onNodeWithTag("kuml-preview").assertIsDisplayed()
}
```

## Task
Generate complete test class(es) for the feature described. Include:
1. Correct module placement based on the matrix above
2. Backtick method names in English
3. Named parameters in all DSL calls
4. All relevant test cases (happy path, error cases, edge cases)
5. For snapshot tests: note the update command
6. For LLM tests: add `@Tag("live")` and guard with env var check
