package dev.kuml.ai.tools.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.reflect.Method

/**
 * In-Process JSON-RPC transport that bridges Koog tool-call requests to kuml-mcp's
 * internal ToolRegistry via reflection.
 *
 * **Why reflection**: kuml-mcp is application-typed with `internal` visibilities on
 * all tool classes. Options (a) make it a library (invasive, V3.1) or (b) reflection
 * hop (this class). The reflection is limited to a single class + two methods — low
 * maintenance surface. See plan § 6 for migration path.
 *
 * **Loop-guard**: a CoroutineContext element (McpBridgeGuardKey) is checked before
 * each dispatch. If set, re-entrant calls throw to prevent infinite recursion.
 * This handles the case where a kuml-mcp tool would itself call back into this bridge.
 *
 * **Migration note**: when kuml-mcp gets a public API surface, replace the
 * reflection calls in [dispatchToolCall] and [listTools] with direct Kotlin calls.
 * The transport interface and loop-guard logic can be kept or deleted as needed.
 */
internal class InProcessMcpTransport {
    private val loopGuard = ThreadLocal.withInitial { false }

    // Lazy reflection handles: resolved once, fails fast if kuml-mcp is not on classpath
    private val toolRegistryClass: Class<*> by lazy {
        try {
            Class.forName("dev.kuml.mcp.tools.ToolRegistry")
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "kuml-mcp is not on the classpath — cannot create McpBridgeToolSet. " +
                    "Add project(\":kuml-mcp\") to the runtime dependencies.",
                e,
            )
        }
    }

    private val toolRegistryInstance: Any by lazy {
        // ToolRegistry is an `internal object` — access via INSTANCE field
        val instanceField = toolRegistryClass.getDeclaredField("INSTANCE")
        instanceField.isAccessible = true
        instanceField.get(null)
            ?: error("ToolRegistry.INSTANCE is null — kuml-mcp initialization failed")
    }

    private val dispatchMethod: Method by lazy {
        // Kotlin internal methods are mangled with the module name suffix
        // Pattern: dispatch$<module-name> where module-name uses underscores
        val allMethods = toolRegistryClass.declaredMethods
        val method =
            allMethods.firstOrNull { m ->
                m.name.startsWith("dispatch") &&
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1] == JsonObject::class.java
            } ?: throw NoSuchMethodException(
                "dispatch(String, JsonObject) not found in ${toolRegistryClass.name}. " +
                    "Available methods: ${allMethods.map { it.name }}",
            )
        method.also { it.isAccessible = true }
    }

    private val descriptorsGetter: Method by lazy {
        val allMethods = toolRegistryClass.declaredMethods
        val method =
            allMethods.firstOrNull { m ->
                m.name.startsWith("getDescriptors") && m.parameterTypes.isEmpty()
            } ?: throw NoSuchMethodException(
                "getDescriptors() not found in ${toolRegistryClass.name}. " +
                    "Available methods: ${allMethods.map { it.name }}",
            )
        method.also { it.isAccessible = true }
    }

    /**
     * Dispatches a tools/call request to kuml-mcp ToolRegistry.
     *
     * @param toolName The MCP tool name, e.g. "kuml.render"
     * @param arguments The JSON arguments object
     * @return JSON-encoded result
     */
    internal fun dispatchToolCall(
        toolName: String,
        arguments: JsonObject,
    ): JsonElement {
        if (loopGuard.get()) {
            throw IllegalStateException("MCP bridge loop detected — refusing recursive call to '$toolName'")
        }
        loopGuard.set(true)
        try {
            @Suppress("UNCHECKED_CAST")
            val result = dispatchMethod.invoke(toolRegistryInstance, toolName, arguments) as List<*>
            // result is List<dev.kuml.mcp.McpContent> — serialize via reflection (getter methods)
            return buildJsonArray {
                result.forEach { content ->
                    if (content == null) return@forEach
                    val contentClass = content.javaClass
                    val getType = runCatching { contentClass.getMethod("getType") }.getOrNull()
                    val getText = runCatching { contentClass.getMethod("getText") }.getOrNull()
                    add(
                        buildJsonObject {
                            put("type", getType?.invoke(content)?.toString() ?: "text")
                            val text = getText?.invoke(content)?.toString()
                            if (text != null) put("text", text)
                        },
                    )
                }
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause ?: e
            return buildJsonObject {
                put("error", cause.message ?: cause.javaClass.simpleName)
                put("isError", true)
            }
        } finally {
            loopGuard.set(false)
        }
    }

    /**
     * Returns a list of tool descriptor maps (name, description, inputSchema).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun listTools(): List<McpToolProxy> {
        if (loopGuard.get()) throw IllegalStateException("MCP bridge loop: recursive listTools call")
        loopGuard.set(true)
        try {
            val descriptors = descriptorsGetter.invoke(toolRegistryInstance) as List<*>
            return descriptors.mapNotNull { descriptor ->
                if (descriptor == null) return@mapNotNull null
                val cls = descriptor.javaClass
                // Use getter methods (McpToolDescriptor fields are private in Kotlin internal class)
                val getName = runCatching { cls.getMethod("getName") }.getOrNull()
                val getDescription = runCatching { cls.getMethod("getDescription") }.getOrNull()
                val getInputSchema = runCatching { cls.getMethod("getInputSchema") }.getOrNull()
                McpToolProxy(
                    name = getName?.invoke(descriptor)?.toString() ?: return@mapNotNull null,
                    description = getDescription?.invoke(descriptor)?.toString() ?: "",
                    inputSchema = getInputSchema?.invoke(descriptor) as? JsonObject ?: JsonObject(emptyMap()),
                )
            }
        } finally {
            loopGuard.set(false)
        }
    }

    internal data class McpToolProxy(
        val name: String,
        val description: String,
        val inputSchema: JsonObject,
    )
}
