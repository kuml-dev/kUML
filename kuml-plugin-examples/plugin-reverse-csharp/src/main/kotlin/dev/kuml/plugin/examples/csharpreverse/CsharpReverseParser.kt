package dev.kuml.plugin.examples.csharpreverse

import dev.kuml.codegen.reverse.ReverseDiagnostic

/**
 * Recursive-descent structural parser for C# source files.
 *
 * Supports:
 * - File-scoped namespaces (C# 10): `namespace X.Y;` — no braces, applies to whole file.
 * - Block namespaces: `namespace X.Y { ... }`.
 * - class / abstract class / sealed class / static class / interface / struct / record / enum.
 * - [Attribute] lists — collected as pending attributes and attached to the next declaration.
 * - Properties: `T Name { get; set; }` — detected by `{` after member name with get/set inside.
 * - Methods: `T Name(params)` — detected by `(` after member name.
 * - Fields: anything else ending with `;`.
 * - Base list after `:` — single syntax covers both base class and interfaces in C#.
 * - Generic type parameters `<T>` and constraints `where T : class` are skipped structurally.
 *
 * Limitations (by design):
 * - Method bodies are skipped.
 * - LINQ, lambdas, complex expressions are out of scope.
 * - Nested types are skipped non-recursively.
 *
 * Safety guards:
 * - Max namespace nesting depth [MAX_NAMESPACE_DEPTH] → emits REV-CS-002 WARN and bails.
 * - Never throws on malformed input — collects diagnostics and continues.
 */
internal class CsharpReverseParser(
    private val tokens: List<CsharpToken>,
) {
    private var pos = 0
    private var namespaceDepth = 0
    private val namespaceStack = mutableListOf<String>()
    private val diagnostics = mutableListOf<ReverseDiagnostic>()
    private val declarations = mutableListOf<CsharpDeclaration>()
    private var fileScopedNamespace: String? = null

    fun parse(): CsharpFileAst {
        while (!atEof()) {
            parseTopLevel()
        }
        return CsharpFileAst(declarations = declarations.toList(), diagnostics = diagnostics.toList())
    }

    // ── Top-level dispatch ────────────────────────────────────────────────────

    @Suppress("CyclomaticComplexMethod")
    private fun parseTopLevel() {
        if (namespaceDepth > MAX_NAMESPACE_DEPTH) {
            diagnostics +=
                ReverseDiagnostic(
                    severity = ReverseDiagnostic.Severity.WARN,
                    code = "REV-CS-002",
                    message = "Maximum namespace nesting depth ($MAX_NAMESPACE_DEPTH) exceeded — aborting parse.",
                )
            while (!atEof()) advance()
            return
        }

        // Collect pending attributes: [Attribute]
        val pendingAttributes = mutableListOf<String>()
        while (peek().text == "[") {
            pendingAttributes += parseAttributeList()
        }

        // Collect declaration modifiers
        var isAbstract = false
        var isSealed = false
        var isStatic = false
        var isPartial = false
        var isPublic = false
        var isInternal = false
        var isProtected = false
        var isPrivate = false

        loop@ while (true) {
            when (peek().text) {
                "abstract" -> {
                    isAbstract = true
                    advance()
                }
                "sealed" -> {
                    isSealed = true
                    advance()
                }
                "static" -> {
                    isStatic = true
                    advance()
                }
                "partial" -> {
                    isPartial = true
                    advance()
                }
                "public" -> {
                    isPublic = true
                    advance()
                }
                "internal" -> {
                    isInternal = true
                    advance()
                }
                "protected" -> {
                    isProtected = true
                    advance()
                }
                "private" -> {
                    isPrivate = true
                    advance()
                }
                "new", "unsafe", "extern", "override", "virtual", "readonly" -> advance()
                else -> break@loop
            }
        }

        val tok = peek()
        when {
            tok.type == CsharpTokenType.EOF -> return
            tok.text == "namespace" -> parseNamespace()
            tok.text == "}" -> {
                advance()
                if (namespaceStack.isNotEmpty() && fileScopedNamespace == null) {
                    namespaceStack.removeLast()
                    if (namespaceDepth > 0) namespaceDepth--
                }
            }
            tok.text == "class" ->
                parseClassDecl(
                    kind = CsharpDeclKind.CLASS,
                    isAbstract = isAbstract,
                    isSealed = isSealed,
                    isStatic = isStatic,
                    attributes = pendingAttributes,
                )
            tok.text == "interface" ->
                parseClassDecl(
                    kind = CsharpDeclKind.INTERFACE,
                    isAbstract = false,
                    isSealed = false,
                    isStatic = false,
                    attributes = pendingAttributes,
                )
            tok.text == "struct" ->
                parseClassDecl(
                    kind = CsharpDeclKind.STRUCT,
                    isAbstract = false,
                    isSealed = false,
                    isStatic = false,
                    attributes = pendingAttributes,
                )
            tok.text == "record" ->
                parseClassDecl(
                    kind = CsharpDeclKind.RECORD,
                    isAbstract = isAbstract,
                    isSealed = isSealed,
                    isStatic = false,
                    attributes = pendingAttributes,
                )
            tok.text == "enum" -> parseEnumDecl()
            tok.text == "using" -> skipToSemicolon()
            tok.text == "[" -> {
                // Stray attribute bracket — skip
                skipToClose(']', '[')
            }
            else -> advance()
        }
    }

    // ── Namespace ─────────────────────────────────────────────────────────────

    private fun parseNamespace() {
        expect("namespace")
        val name = parseQualifiedDottedName()
        when {
            peek().text == ";" -> {
                // File-scoped namespace (C# 10)
                advance()
                fileScopedNamespace = name
                namespaceStack.clear()
                if (name.isNotEmpty()) namespaceStack += name
                // Do NOT increment namespaceDepth for file-scoped — it covers the whole file
                // and there is no matching '}' to pop it.
            }
            peek().text == "{" -> {
                advance()
                if (name.isNotEmpty()) namespaceStack += name
                namespaceDepth++
            }
            else -> advance() // malformed — skip
        }
    }

    // ── Attribute list ────────────────────────────────────────────────────────

    private fun parseAttributeList(): List<String> {
        val attrs = mutableListOf<String>()
        if (peek().text != "[") return attrs
        advance() // [
        var depth = 1
        val sb = StringBuilder()
        while (!atEof() && depth > 0) {
            val t = advance()
            when (t.text) {
                "[" -> {
                    depth++
                    sb.append(t.text)
                }
                "]" -> {
                    depth--
                    if (depth > 0) sb.append(t.text)
                }
                "," -> {
                    val attr = sb.toString().trim()
                    if (attr.isNotEmpty()) attrs += attr.substringBefore("(").trim()
                    sb.clear()
                }
                else -> sb.append(t.text)
            }
        }
        val last = sb.toString().trim()
        if (last.isNotEmpty()) attrs += last.substringBefore("(").trim()
        return attrs
    }

    // ── Class / interface / struct / record ───────────────────────────────────

    @Suppress("CyclomaticComplexMethod", "LongParameterList")
    private fun parseClassDecl(
        kind: CsharpDeclKind,
        isAbstract: Boolean,
        isSealed: Boolean,
        isStatic: Boolean,
        attributes: List<String>,
    ) {
        advance() // consume "class" / "interface" / "struct" / "record"

        val name =
            if (peek().type == CsharpTokenType.IDENTIFIER || peek().type == CsharpTokenType.KEYWORD) {
                advance().text
            } else {
                skipToSemicolon()
                return
            }

        // Generic type parameters: <T, U>
        val typeParams = mutableListOf<String>()
        if (peek().text == "<") {
            typeParams += parseTypeParamNames()
        }

        // Positional record params: record Point(int X, int Y) — skip
        if (peek().text == "(") {
            advance()
            skipToClose(')', '(')
        }

        // Base list: : BaseClass, IInterface1, IInterface2
        val bases = mutableListOf<CsharpBaseSpec>()
        if (peek().text == ":") {
            advance()
            bases += parseBaseList()
        }

        // Generic constraints: where T : class — skip to '{'
        while (!atEof() && peek().text != "{" && peek().text != ";") {
            advance()
        }

        if (peek().text == ";") {
            // Forward declaration or record with no body
            advance()
            declarations +=
                CsharpClassDecl(
                    name = name,
                    namespace = currentNamespace(),
                    kind = kind,
                    isAbstract = isAbstract,
                    isSealed = isSealed,
                    isStatic = isStatic,
                    bases = bases,
                    members = emptyList(),
                    attributes = attributes,
                    typeParams = typeParams,
                )
            return
        }

        if (peek().text != "{") {
            skipToSemicolon()
            return
        }
        advance() // {

        val defaultAccess = if (kind == CsharpDeclKind.INTERFACE) "public" else "private"
        val members = parseMemberList(defaultAccess)

        if (peek().text == "}") advance()
        if (peek().text == ";") advance()

        declarations +=
            CsharpClassDecl(
                name = name,
                namespace = currentNamespace(),
                kind = kind,
                isAbstract = isAbstract,
                isSealed = isSealed,
                isStatic = isStatic,
                bases = bases,
                members = members,
                attributes = attributes,
                typeParams = typeParams,
            )
    }

    // ── Base list ─────────────────────────────────────────────────────────────

    private fun parseBaseList(): List<CsharpBaseSpec> {
        val bases = mutableListOf<CsharpBaseSpec>()
        while (!atEof() && peek().text != "{" && peek().text != ";" && peek().text != "where") {
            val name = parseQualifiedDottedName()
            // Skip generic args on base type: IComparable<T>
            if (peek().text == "<") skipAngles()
            if (name.isNotEmpty()) bases += CsharpBaseSpec(name)
            if (peek().text == ",") advance() else break
        }
        return bases
    }

    // ── Type parameter names ──────────────────────────────────────────────────

    private fun parseTypeParamNames(): List<String> {
        val params = mutableListOf<String>()
        if (peek().text != "<") return params
        advance() // <
        var depth = 1
        while (!atEof() && depth > 0) {
            val t = peek()
            when {
                t.text == "<" -> {
                    depth++
                    advance()
                }
                t.text == ">" -> {
                    depth--
                    if (depth > 0) advance() else advance()
                }
                t.type == CsharpTokenType.IDENTIFIER && depth == 1 -> {
                    params += advance().text
                    if (peek().text == ",") advance()
                }
                else -> advance()
            }
        }
        return params
    }

    // ── Member list ───────────────────────────────────────────────────────────

    @Suppress("CyclomaticComplexMethod")
    private fun parseMemberList(defaultAccess: String): List<CsharpMember> {
        val members = mutableListOf<CsharpMember>()

        while (!atEof() && peek().text != "}") {
            // Collect member attributes
            val memberAttrs = mutableListOf<String>()
            while (peek().text == "[") {
                memberAttrs += parseAttributeList()
            }

            // Collect member modifiers
            var access = defaultAccess
            var isStatic = false
            var isAbstract = false
            var isReadOnly = false
            var isOverride = false
            var isVirtual = false
            var isNew = false

            loop@ while (true) {
                when (peek().text) {
                    "public" -> {
                        access = "public"
                        advance()
                    }
                    "private" -> {
                        access = "private"
                        advance()
                    }
                    "protected" -> {
                        access = "protected"
                        advance()
                    }
                    "internal" -> {
                        // "protected internal" or just "internal"
                        advance()
                        if (peek().text == "protected") advance()
                        // treat as internal — map to package-private (closer to "internal")
                        if (access == defaultAccess) access = "internal"
                    }
                    "static" -> {
                        isStatic = true
                        advance()
                    }
                    "abstract" -> {
                        isAbstract = true
                        advance()
                    }
                    "readonly" -> {
                        isReadOnly = true
                        advance()
                    }
                    "override" -> {
                        isOverride = true
                        advance()
                    }
                    "virtual" -> {
                        isVirtual = true
                        advance()
                    }
                    "sealed" -> advance()
                    "new" -> {
                        isNew = true
                        advance()
                    }
                    "partial", "unsafe", "extern", "volatile", "async" -> advance()
                    "const" -> {
                        isReadOnly = true
                        advance()
                    }
                    else -> break@loop
                }
            }

            val tok = peek()
            when {
                tok.type == CsharpTokenType.EOF -> break
                tok.text == "}" -> break
                tok.text == "~" -> {
                    // Destructor
                    advance() // ~
                    if (peek().type == CsharpTokenType.IDENTIFIER) advance()
                    if (peek().text == "(") {
                        advance()
                        skipToClose(')', '(')
                    }
                    skipOptionalBody()
                    if (peek().text == ";") advance()
                }
                tok.text == "class" ||
                    tok.text == "interface" ||
                    tok.text == "struct" ||
                    tok.text == "record" ||
                    tok.text == "enum" -> {
                    // Nested type — skip
                    skipNestedType()
                }
                tok.text == "using" || tok.text == "event" -> skipToSemicolon()
                tok.text == "[" -> {
                    // Stray attribute not consumed — skip
                    skipToClose(']', '[')
                }
                else -> {
                    val member = parseMember(access, isStatic, isAbstract, isReadOnly, memberAttrs)
                    if (member != null) members += member
                }
            }
        }
        return members
    }

    // ── Single member ─────────────────────────────────────────────────────────

    @Suppress("CyclomaticComplexMethod", "LongParameterList")
    private fun parseMember(
        access: String,
        isStatic: Boolean,
        isAbstract: Boolean,
        isReadOnly: Boolean,
        attributes: List<String>,
    ): CsharpMember? {
        // Parse the type
        val typeRef =
            parseTypeRef() ?: run {
                skipToSemicolonOrBrace()
                return null
            }

        // Optional generic type params on method: T Method<U>(...)
        if (peek().text == "<") skipAngles()

        // Member name
        val memberName: String =
            when {
                peek().text == "operator" -> {
                    advance()
                    val sb = StringBuilder("operator")
                    while (!atEof() && peek().text != "(" && peek().text != ";") {
                        sb.append(advance().text)
                    }
                    sb.toString()
                }
                peek().type == CsharpTokenType.IDENTIFIER || peek().type == CsharpTokenType.KEYWORD -> {
                    advance().text
                }
                peek().text == "(" -> {
                    // Constructor: ClassName(...) — the type ref was consumed as the ctor name
                    typeRef.name
                }
                else -> {
                    skipToSemicolonOrBrace()
                    return null
                }
            }

        // Skip generic type params on member name
        if (peek().text == "<") skipAngles()

        return when {
            peek().text == "(" -> {
                // Method or constructor
                advance() // (
                val params = parseParamList()
                skipToClose(')', '(')
                // Skip constraints
                while (!atEof() && peek().text == "where") {
                    advance()
                    while (!atEof() && peek().text != "{" && peek().text != ";" && peek().text != "where") advance()
                }
                skipOptionalBody()
                if (peek().text == ";") advance()
                CsharpMember(
                    name = memberName,
                    type = typeRef,
                    kind = CsharpMemberKind.METHOD,
                    access = access,
                    isStatic = isStatic,
                    isAbstract = isAbstract,
                    isReadOnly = false,
                    params = params,
                    attributes = attributes,
                )
            }
            peek().text == "{" -> {
                // Property: T Name { get; set; } or { get; } or auto-implemented
                advance() // {
                // Peek inside for get/set/init
                skipToClose('}', '{')
                // Optional initializer: = value;
                if (peek().text == "=") {
                    advance()
                    skipToSemicolon()
                } else if (peek().text == ";") {
                    advance()
                }
                CsharpMember(
                    name = memberName,
                    type = typeRef,
                    kind = CsharpMemberKind.PROPERTY,
                    access = access,
                    isStatic = isStatic,
                    isAbstract = isAbstract,
                    isReadOnly = isReadOnly,
                    params = emptyList(),
                    attributes = attributes,
                )
            }
            peek().text == "=>" -> {
                // Expression-bodied member
                advance() // =>
                skipToSemicolonOrBrace()
                if (peek().text == ";") advance()
                CsharpMember(
                    name = memberName,
                    type = typeRef,
                    kind = CsharpMemberKind.PROPERTY,
                    access = access,
                    isStatic = isStatic,
                    isAbstract = isAbstract,
                    isReadOnly = true,
                    params = emptyList(),
                    attributes = attributes,
                )
            }
            else -> {
                // Field or unrecognized — skip to semicolon
                while (!atEof() && peek().text != ";" && peek().text != "}") {
                    if (peek().text == "{") {
                        advance()
                        skipToClose('}', '{')
                    } else {
                        advance()
                    }
                }
                if (peek().text == ";") advance()
                CsharpMember(
                    name = memberName,
                    type = typeRef,
                    kind = CsharpMemberKind.FIELD,
                    access = access,
                    isStatic = isStatic,
                    isAbstract = false,
                    isReadOnly = isReadOnly,
                    params = emptyList(),
                    attributes = attributes,
                )
            }
        }
    }

    // ── Type reference ────────────────────────────────────────────────────────

    @Suppress("CyclomaticComplexMethod")
    private fun parseTypeRef(): CsharpTypeRef? {
        val first = peek()
        if (first.type != CsharpTokenType.IDENTIFIER && first.type != CsharpTokenType.KEYWORD) return null
        // Skip "void" properly
        val baseName = advance().text

        // Qualified name: Namespace.Type or Namespace::Type
        val nameParts = mutableListOf(baseName)
        while (peek().text == "." || peek().text == "::") {
            advance()
            if (peek().type == CsharpTokenType.IDENTIFIER || peek().type == CsharpTokenType.KEYWORD) {
                nameParts += advance().text
            }
        }

        // Generic args: <T, U>
        val typeArgs = mutableListOf<CsharpTypeRef>()
        if (peek().text == "<") {
            typeArgs += parseTypeArgList()
        }

        val name = nameParts.joinToString(".")

        // Nullable: T? — consume the '?'
        var isNullable = false
        if (peek().text == "?") {
            isNullable = true
            advance()
        }

        // Array: T[]
        var isArray = false
        if (peek().text == "[") {
            advance()
            if (peek().text == "]") advance()
            isArray = true
        }

        return CsharpTypeRef(
            name = name,
            isNullable = isNullable,
            isArray = isArray,
            typeArgs = typeArgs,
        )
    }

    private fun parseTypeArgList(): List<CsharpTypeRef> {
        if (peek().text != "<") return emptyList()
        advance() // <
        val args = mutableListOf<CsharpTypeRef>()
        var depth = 1
        val currentArgTokens = mutableListOf<CsharpToken>()

        while (!atEof() && depth > 0) {
            val t = peek()
            when {
                t.text == "<" -> {
                    depth++
                    currentArgTokens += advance()
                }
                t.text == ">" -> {
                    depth--
                    if (depth > 0) {
                        currentArgTokens += advance()
                    } else {
                        advance()
                    }
                    if (depth == 0) {
                        val ref = parseTypeRefFromTokenList(currentArgTokens)
                        if (ref != null) args += ref
                        currentArgTokens.clear()
                    }
                }
                t.text == "," && depth == 1 -> {
                    advance()
                    val ref = parseTypeRefFromTokenList(currentArgTokens)
                    if (ref != null) args += ref
                    currentArgTokens.clear()
                }
                else -> currentArgTokens += advance()
            }
        }
        if (currentArgTokens.isNotEmpty()) {
            val ref = parseTypeRefFromTokenList(currentArgTokens)
            if (ref != null) args += ref
        }
        return args
    }

    private fun parseTypeRefFromTokenList(toks: List<CsharpToken>): CsharpTypeRef? {
        val meaningful =
            toks
                .filter { it.type == CsharpTokenType.IDENTIFIER || it.type == CsharpTokenType.KEYWORD }
                .filter { it.text != "?" }
        if (meaningful.isEmpty()) return null
        val name = meaningful.first().text
        val isNullable = toks.any { it.text == "?" }
        val isArray = toks.any { it.text == "[" }
        return CsharpTypeRef(name = name, isNullable = isNullable, isArray = isArray)
    }

    // ── Parameter list ────────────────────────────────────────────────────────

    private fun parseParamList(): List<CsharpParam> {
        val params = mutableListOf<CsharpParam>()
        while (!atEof() && peek().text != ")") {
            if (peek().text == ",") {
                advance()
                continue
            }
            // Skip attributes on params
            while (peek().text == "[") {
                advance()
                skipToClose(']', '[')
            }
            // Skip param modifiers
            while (peek().text in listOf("this", "ref", "out", "in", "params")) {
                advance()
            }
            val typeRef =
                parseTypeRef() ?: run {
                    skipToParamBoundary()
                    continue
                }
            val name =
                if (peek().type == CsharpTokenType.IDENTIFIER) {
                    advance().text
                } else {
                    ""
                }
            // Skip default value
            if (peek().text == "=") {
                advance()
                while (!atEof() && peek().text != "," && peek().text != ")") {
                    if (peek().text == "{") {
                        advance()
                        skipToClose('}', '{')
                    } else {
                        advance()
                    }
                }
            }
            params += CsharpParam(name = name, type = typeRef)
        }
        return params
    }

    private fun skipToParamBoundary() {
        while (!atEof() && peek().text != "," && peek().text != ")") advance()
    }

    // ── Enum ──────────────────────────────────────────────────────────────────

    private fun parseEnumDecl() {
        expect("enum")
        val name =
            if (peek().type == CsharpTokenType.IDENTIFIER || peek().type == CsharpTokenType.KEYWORD) {
                advance().text
            } else {
                skipToSemicolon()
                return
            }
        // Optional base type: enum Color : byte
        if (peek().text == ":") {
            advance()
            if (peek().type == CsharpTokenType.IDENTIFIER || peek().type == CsharpTokenType.KEYWORD) advance()
        }
        if (peek().text != "{") {
            skipToSemicolon()
            return
        }
        advance() // {
        val literals = parseEnumLiterals()
        if (peek().text == "}") advance()
        if (peek().text == ";") advance()
        declarations +=
            CsharpEnumDecl(
                name = name,
                namespace = currentNamespace(),
                literals = literals,
            )
    }

    private fun parseEnumLiterals(): List<String> {
        val literals = mutableListOf<String>()
        while (!atEof() && peek().text != "}") {
            val tok = peek()
            if (tok.type == CsharpTokenType.IDENTIFIER || tok.type == CsharpTokenType.KEYWORD) {
                literals += advance().text
                if (peek().text == "=") {
                    advance()
                    while (!atEof() && peek().text != "," && peek().text != "}") advance()
                }
                if (peek().text == ",") advance()
            } else {
                advance()
            }
        }
        return literals
    }

    // ── Skip helpers ──────────────────────────────────────────────────────────

    private fun skipNestedType() {
        advance() // consume keyword
        if (peek().type == CsharpTokenType.IDENTIFIER) advance() // name
        // Skip generic params
        if (peek().text == "<") skipAngles()
        // Skip positional record params
        if (peek().text == "(") {
            advance()
            skipToClose(')', '(')
        }
        // Skip base list
        if (peek().text == ":") {
            advance()
            while (!atEof() && peek().text != "{" && peek().text != ";") advance()
        }
        if (peek().text == "{") {
            advance()
            skipToClose('}', '{')
        }
        if (peek().text == ";") advance()
    }

    private fun skipOptionalBody() {
        if (peek().text == "{") {
            advance()
            skipToClose('}', '{')
        }
    }

    private fun skipToSemicolon() {
        while (!atEof() && peek().text != ";") advance()
        if (!atEof()) advance()
    }

    private fun skipToSemicolonOrBrace() {
        while (!atEof() && peek().text != ";" && peek().text != "}") advance()
        if (!atEof() && peek().text == ";") advance()
    }

    private fun skipAngles() {
        if (peek().text != "<") return
        advance()
        var depth = 1
        while (!atEof() && depth > 0) {
            when (advance().text) {
                "<" -> depth++
                ">" -> depth--
            }
        }
    }

    private fun skipToClose(
        close: Char,
        open: Char,
    ) {
        var d = 0
        while (!atEof()) {
            val t = advance().text
            when {
                t == open.toString() -> d++
                t == close.toString() -> {
                    if (d == 0) return else d--
                }
            }
        }
    }

    private fun parseQualifiedDottedName(): String {
        val parts = mutableListOf<String>()
        if (peek().type == CsharpTokenType.IDENTIFIER || peek().type == CsharpTokenType.KEYWORD) {
            parts += advance().text
        }
        while (peek().text == "." || peek().text == "::") {
            advance()
            if (peek().type == CsharpTokenType.IDENTIFIER || peek().type == CsharpTokenType.KEYWORD) {
                parts += advance().text
            }
        }
        return parts.joinToString(".")
    }

    // ── Token primitives ──────────────────────────────────────────────────────

    private fun peek(): CsharpToken = tokens.getOrElse(pos) { CsharpToken(CsharpTokenType.EOF, "", 0) }

    private fun advance(): CsharpToken {
        val t = peek()
        if (pos < tokens.size) pos++
        return t
    }

    private fun expect(text: String) {
        if (peek().text == text) advance()
    }

    private fun atEof(): Boolean = pos >= tokens.size || peek().type == CsharpTokenType.EOF

    private fun currentNamespace(): String? {
        if (fileScopedNamespace != null && namespaceStack.isNotEmpty()) {
            return namespaceStack.joinToString(".")
        }
        return if (namespaceStack.isEmpty()) null else namespaceStack.joinToString(".")
    }

    private companion object {
        const val MAX_NAMESPACE_DEPTH: Int = 256
    }
}
