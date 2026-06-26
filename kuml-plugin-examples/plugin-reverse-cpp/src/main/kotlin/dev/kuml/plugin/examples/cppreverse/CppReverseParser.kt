package dev.kuml.plugin.examples.cppreverse

import dev.kuml.codegen.reverse.ReverseDiagnostic

/**
 * Recursive-descent structural parser for C++ header files.
 *
 * Limitations (by design — out of scope):
 * - Template meta-programming beyond detecting `template<...>` prefix.
 * - Preprocessor macros (stripped by CppLexer before parsing).
 * - Complex declarators (function pointers, array types in params).
 *
 * Safety guards:
 * - Max namespace nesting depth of [MAX_NAMESPACE_DEPTH] → emits REV-CPP-002 WARN and bails.
 *   Note: class nesting is handled non-recursively via skipNestedClassOrStruct(), so it does
 *   not contribute to this counter.
 * - Never throws on malformed input — collects diagnostics and continues.
 */
internal class CppReverseParser(
    private val tokens: List<CppToken>,
) {
    private var pos = 0

    // Tracks nested namespace depth only; class nesting is handled non-recursively
    // via skipNestedClassOrStruct() so it does not contribute to this counter.
    private var namespaceDepth = 0
    private val namespaceStack = mutableListOf<String>()
    private val diagnostics = mutableListOf<ReverseDiagnostic>()
    private val declarations = mutableListOf<CppDeclaration>()

    fun parse(): CppFileAst {
        while (!atEof()) {
            parseTopLevel()
        }
        return CppFileAst(declarations = declarations.toList(), diagnostics = diagnostics.toList())
    }

    // ── Top-level dispatch ────────────────────────────────────────────────────

    private fun parseTopLevel() {
        if (namespaceDepth > MAX_NAMESPACE_DEPTH) {
            diagnostics +=
                ReverseDiagnostic(
                    severity = ReverseDiagnostic.Severity.WARN,
                    code = "REV-CPP-002",
                    message =
                        "Maximum namespace nesting depth ($MAX_NAMESPACE_DEPTH) exceeded — aborting parse.",
                )
            // Skip to end
            while (!atEof()) advance()
            return
        }

        skipModifiers()

        val tok = peek()
        when {
            tok.type == CppTokenType.EOF -> return
            tok.text == "namespace" -> parseNamespace()
            tok.text == "template" -> parseTemplatePrefix()
            tok.text == "class" || tok.text == "struct" -> parseClassOrStruct(isTemplate = false)
            tok.text == "enum" -> parseEnum()
            tok.text == "}" -> {
                // Closing brace for namespace or other block — pop namespace
                advance()
                if (namespaceStack.isNotEmpty()) {
                    namespaceStack.removeLast()
                    if (namespaceDepth > 0) namespaceDepth--
                }
            }
            else -> advance() // skip unknown top-level tokens
        }
    }

    // ── Namespace ─────────────────────────────────────────────────────────────

    private fun parseNamespace() {
        expect("namespace")
        val name = if (peek().type == CppTokenType.IDENTIFIER) advance().text else ""
        if (peek().text == "{") {
            advance() // {
            if (name.isNotEmpty()) namespaceStack += name
            namespaceDepth++
        } else {
            advance() // skip unexpected token
        }
    }

    // ── Template prefix ───────────────────────────────────────────────────────

    private fun parseTemplatePrefix() {
        expect("template")
        // Skip angle-bracket parameter list
        if (peek().text == "<") {
            skipAngles()
        }
        skipModifiers()
        val tok = peek()
        when {
            tok.text == "class" || tok.text == "struct" -> parseClassOrStruct(isTemplate = true)
            else -> advance() // template function or variable — skip
        }
    }

    // ── Class / struct ────────────────────────────────────────────────────────

    private fun parseClassOrStruct(isTemplate: Boolean) {
        val isStruct = peek().text == "struct"
        advance() // consume "class" or "struct"

        skipModifiers() // e.g. __attribute__, alignas, final, etc.

        val name =
            if (peek().type == CppTokenType.IDENTIFIER || peek().type == CppTokenType.KEYWORD) {
                advance().text
            } else {
                return // anonymous struct/class — skip
            }

        // Check for forward declaration: class Foo;
        if (peek().text == ";") {
            advance()
            declarations +=
                CppClassDecl(
                    name = name,
                    namespace = currentNamespace(),
                    isStruct = isStruct,
                    bases = emptyList(),
                    members = emptyList(),
                    isTemplate = isTemplate,
                    isForwardDecl = true,
                )
            return
        }

        if (isTemplate) {
            diagnostics +=
                ReverseDiagnostic(
                    severity = ReverseDiagnostic.Severity.WARN,
                    code = "REV-CPP-001",
                    message = "Template class '$name' — structural parse only; template parameters ignored.",
                )
        }

        // Parse base specifiers
        val bases = mutableListOf<CppBaseSpec>()
        if (peek().text == ":") {
            advance()
            bases += parseBaseList()
        }

        // Expect opening brace
        if (peek().text != "{") {
            // Malformed — skip to semicolon
            skipToSemicolon()
            return
        }
        advance() // {

        // Parse members
        val defaultAccess = if (isStruct) "public" else "private"
        val members = parseMemberList(defaultAccess, name)

        // Expect closing brace
        if (peek().text == "}") advance()
        // Consume optional class name after } (typedef pattern)
        if (peek().type == CppTokenType.IDENTIFIER) advance()
        if (peek().text == ";") advance()

        declarations +=
            CppClassDecl(
                name = name,
                namespace = currentNamespace(),
                isStruct = isStruct,
                bases = bases,
                members = members,
                isTemplate = isTemplate,
                isForwardDecl = false,
            )
    }

    // ── Base specifier list ───────────────────────────────────────────────────

    private fun parseBaseList(): List<CppBaseSpec> {
        val bases = mutableListOf<CppBaseSpec>()
        while (!atEof() && peek().text != "{" && peek().text != ";") {
            var access = "private"
            var isVirtual = false
            // Optional access specifier and/or virtual
            loop@ while (true) {
                when (peek().text) {
                    "public" -> {
                        access = "public"
                        advance()
                    }
                    "protected" -> {
                        access = "protected"
                        advance()
                    }
                    "private" -> {
                        access = "private"
                        advance()
                    }
                    "virtual" -> {
                        isVirtual = true
                        advance()
                    }
                    else -> break@loop
                }
            }
            // Base class name (possibly qualified: Ns::Base)
            val baseName = parseQualifiedName()
            // Skip template args if present
            if (peek().text == "<") skipAngles()
            if (baseName.isNotEmpty()) {
                bases +=
                    CppBaseSpec(
                        name = baseName,
                        access = access,
                        isVirtual = isVirtual,
                    )
            }
            if (peek().text == ",") advance() else break
        }
        return bases
    }

    // ── Member list ───────────────────────────────────────────────────────────

    private fun parseMemberList(
        defaultAccess: String,
        className: String,
    ): List<CppMember> {
        val members = mutableListOf<CppMember>()
        var currentAccess = defaultAccess

        while (!atEof() && peek().text != "}") {
            skipModifiers()
            val tok = peek()

            when {
                tok.text == "}" -> break
                tok.type == CppTokenType.EOF -> break
                // Access label: public: / protected: / private:
                tok.text in ACCESS_KEYWORDS && peekAt(1).text == ":" -> {
                    currentAccess = tok.text
                    advance()
                    advance() // consume access + ":"
                }
                // Nested class/struct — skip body
                tok.text == "class" || tok.text == "struct" -> {
                    skipNestedClassOrStruct()
                }
                // Nested enum — skip body
                tok.text == "enum" -> {
                    skipNestedEnum()
                }
                // Friend declaration — skip
                tok.text == "friend" -> {
                    skipToSemicolon()
                }
                // Using declaration — skip
                tok.text == "using" -> {
                    skipToSemicolon()
                }
                // Typedef — skip
                tok.text == "typedef" -> {
                    skipToSemicolon()
                }
                else -> {
                    val member = parseMember(currentAccess, className)
                    if (member != null) members += member
                }
            }
        }
        return members
    }

    // ── Single member ─────────────────────────────────────────────────────────

    private fun parseMember(
        access: String,
        className: String,
    ): CppMember? {
        var isStatic = false
        var isVirtual = false
        var isConst = false

        // Collect pre-type modifiers
        loop@ while (true) {
            when (peek().text) {
                "static" -> {
                    isStatic = true
                    advance()
                }
                "virtual" -> {
                    isVirtual = true
                    advance()
                }
                "explicit", "inline", "constexpr", "consteval", "mutable", "volatile", "override", "final" ->
                    advance()
                "~" -> {
                    // Destructor — consume ~ name ()  [optional body]
                    advance() // ~
                    if (peek().type == CppTokenType.IDENTIFIER) advance() // name
                    if (peek().text == "(") {
                        advance() // consume '(' before calling skipToClose, which starts
                        // counting nested opens from depth 0 (not 1). This matches the
                        // contract: skipToClose returns when it sees ')' at depth 0,
                        // meaning the opener was already consumed by the caller.
                        // Works correctly for ~Foo(), ~Foo(void), and ~Foo(T x) alike.
                        skipToClose(')', '(')
                    }
                    skipOptionalBody()
                    skipToSemicolonOrBrace()
                    return null
                }
                else -> break@loop
            }
        }

        // Parse type (may span multiple tokens: unsigned long, std::vector<int>, etc.)
        val typeRef =
            parseTypeRef() ?: run {
                skipToSemicolonOrBrace()
                return null
            }

        // Member name — could be operator keyword
        val memberName: String
        if (peek().text == "operator") {
            advance()
            // Skip operator symbol(s)
            val sb = StringBuilder("operator")
            while (!atEof() && peek().text != "(" && peek().text != ";") {
                sb.append(advance().text)
            }
            memberName = sb.toString()
        } else if (peek().type == CppTokenType.IDENTIFIER || peek().type == CppTokenType.KEYWORD) {
            memberName = advance().text
        } else if (peek().text == "(") {
            // Constructor: ClassName(...)
            memberName = typeRef.name
        } else {
            skipToSemicolonOrBrace()
            return null
        }

        // Skip template args on member name if any
        if (peek().text == "<") skipAngles()

        return when {
            peek().text == "(" -> {
                // Method
                advance() // (
                val params = parseParamList()
                skipToClose(')', '(')
                // Post-method qualifiers
                loop@ while (true) {
                    when (peek().text) {
                        "const" -> {
                            isConst = true
                            advance()
                        }
                        "override", "final", "noexcept", "volatile" -> advance()
                        else -> break@loop
                    }
                }
                // Pure virtual: = 0
                var isPureVirtual = false
                if (peek().text == "=" && peekAt(1).text == "0") {
                    isPureVirtual = true
                    advance()
                    advance()
                }
                skipOptionalBody()
                if (peek().text == ";") advance()
                CppMember(
                    name = memberName,
                    type = typeRef,
                    isMethod = true,
                    access = access,
                    isStatic = isStatic,
                    isVirtual = isVirtual,
                    isPureVirtual = isPureVirtual,
                    isConst = isConst,
                    params = params,
                )
            }
            else -> {
                // Field
                // Skip array brackets, initializer, etc.
                while (!atEof() && peek().text != ";" && peek().text != "}") {
                    if (peek().text == "{") {
                        advance()
                        skipToClose('}', '{')
                    } else {
                        advance()
                    }
                }
                if (peek().text == ";") advance()
                CppMember(
                    name = memberName,
                    type = typeRef,
                    isMethod = false,
                    access = access,
                    isStatic = isStatic,
                    isVirtual = false,
                    isPureVirtual = false,
                    isConst = isConst,
                )
            }
        }
    }

    // ── Type reference ────────────────────────────────────────────────────────

    private fun parseTypeRef(): CppTypeRef? {
        var isConst = false
        if (peek().text == "const") {
            isConst = true
            advance()
        }
        // unsigned/signed prefix
        if (peek().text == "unsigned" || peek().text == "signed") advance()

        val nameParts = mutableListOf<String>()

        // First type token
        val first = peek()
        if (first.type != CppTokenType.IDENTIFIER && first.type != CppTokenType.KEYWORD) {
            return null
        }
        nameParts += advance().text

        // Handle qualified names: std::vector etc.
        while (peek().text == "::") {
            advance() // ::
            if (peek().type == CppTokenType.IDENTIFIER || peek().type == CppTokenType.KEYWORD) {
                nameParts += advance().text
            }
        }

        // long long / long double
        if (nameParts.last() == "long" && peek().text == "long") {
            nameParts += advance().text
        }

        // Template args
        val templateArgs = mutableListOf<CppTypeRef>()
        if (peek().text == "<") {
            advance() // <
            var angleDepth = 1
            val argTokens = mutableListOf<CppToken>()
            while (!atEof() && angleDepth > 0) {
                val t = peek()
                when (t.text) {
                    "<" -> {
                        angleDepth++
                        argTokens += advance()
                    }
                    ">" -> {
                        angleDepth--
                        if (angleDepth > 0) argTokens += advance() else advance()
                    }
                    ">>" -> {
                        // Treat >> as two >
                        angleDepth -= 2
                        advance()
                    }
                    ";" -> break
                    else -> argTokens += advance()
                }
            }
            // Parse template args from collected tokens (best-effort: split by comma at depth 0)
            templateArgs += parseTemplateArgTokens(argTokens)
        }

        // Trailing const
        if (peek().text == "const") {
            isConst = true
            advance()
        }

        val isPointer = peek().text == "*"
        val isReference = peek().text == "&"
        if (isPointer || isReference) advance()

        // Second * or & (e.g. char**)
        if (peek().text == "*" || peek().text == "&") advance()

        return CppTypeRef(
            name = nameParts.joinToString("::"),
            isPointer = isPointer,
            isReference = isReference,
            isConst = isConst,
            templateArgs = templateArgs,
        )
    }

    private fun parseTemplateArgTokens(argTokens: List<CppToken>): List<CppTypeRef> {
        if (argTokens.isEmpty()) return emptyList()
        // Split by comma at depth 0
        val args = mutableListOf<CppTypeRef>()
        val current = mutableListOf<CppToken>()
        var depth = 0
        for (tok in argTokens) {
            when {
                tok.text == "<" -> {
                    depth++
                    current += tok
                }
                tok.text == ">" -> {
                    depth--
                    current += tok
                }
                tok.text == "," && depth == 0 -> {
                    val ref = parseTypeRefFromTokens(current)
                    if (ref != null) args += ref
                    current.clear()
                }
                else -> current += tok
            }
        }
        if (current.isNotEmpty()) {
            val ref = parseTypeRefFromTokens(current)
            if (ref != null) args += ref
        }
        return args
    }

    private fun parseTypeRefFromTokens(toks: List<CppToken>): CppTypeRef? {
        if (toks.isEmpty()) return null
        val filtered = toks.filter { it.type != CppTokenType.EOF }
        val nameParts =
            filtered
                .filter { it.type == CppTokenType.IDENTIFIER || it.type == CppTokenType.KEYWORD }
                .map { it.text }
                .filter { it != "const" && it != "unsigned" && it != "signed" }
        if (nameParts.isEmpty()) return null
        val isPointer = filtered.any { it.text == "*" }
        val isRef = filtered.any { it.text == "&" }
        return CppTypeRef(name = nameParts.joinToString("::"), isPointer = isPointer, isReference = isRef)
    }

    // ── Parameter list ────────────────────────────────────────────────────────

    private fun parseParamList(): List<CppParam> {
        val params = mutableListOf<CppParam>()
        var parenDepth = 0
        while (!atEof()) {
            val t = peek()
            if (t.text == ")" && parenDepth == 0) break
            if (t.text == "(") {
                parenDepth++
                advance()
                continue
            }
            if (t.text == ")" && parenDepth > 0) {
                parenDepth--
                advance()
                continue
            }
            if (t.text == "," && parenDepth == 0) {
                advance()
                continue
            }
            // Parse a parameter
            val param = parseOneParam()
            if (param != null) params += param
        }
        return params
    }

    private fun parseOneParam(): CppParam? {
        // Skip modifiers
        while (peek().text in listOf("const", "unsigned", "signed", "volatile")) advance()
        val typeRef =
            parseTypeRef() ?: run {
                skipToParamBoundary()
                return null
            }
        // Optional parameter name
        val name =
            if (peek().type == CppTokenType.IDENTIFIER) {
                advance().text
            } else {
                ""
            }
        // Skip default value
        if (peek().text == "=") {
            advance()
            while (!atEof() && peek().text != "," && peek().text != ")") advance()
        }
        return CppParam(name = name, type = typeRef)
    }

    private fun skipToParamBoundary() {
        while (!atEof() && peek().text != "," && peek().text != ")") advance()
    }

    // ── Enum ──────────────────────────────────────────────────────────────────

    private fun parseEnum() {
        expect("enum")
        var isEnumClass = false
        if (peek().text == "class" || peek().text == "struct") {
            isEnumClass = true
            advance()
        }
        val name =
            if (peek().type == CppTokenType.IDENTIFIER || peek().type == CppTokenType.KEYWORD) {
                advance().text
            } else {
                skipToSemicolon()
                return
            }
        // Skip optional base type : int
        if (peek().text == ":") {
            advance()
            if (peek().type != CppTokenType.IDENTIFIER && peek().type != CppTokenType.KEYWORD) {
                // nothing
            } else {
                advance()
            }
        }
        // Forward declaration
        if (peek().text == ";") {
            advance()
            return
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
            CppEnumDecl(
                name = name,
                namespace = currentNamespace(),
                isEnumClass = isEnumClass,
                literals = literals,
            )
    }

    private fun parseEnumLiterals(): List<String> {
        val literals = mutableListOf<String>()
        while (!atEof() && peek().text != "}") {
            val tok = peek()
            if (tok.type == CppTokenType.IDENTIFIER || tok.type == CppTokenType.KEYWORD) {
                literals += advance().text
                // Skip = value
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

    private fun skipNestedClassOrStruct() {
        advance() // class/struct
        // Skip name
        if (peek().type == CppTokenType.IDENTIFIER) advance()
        // Skip base list
        if (peek().text == ":") {
            advance()
            while (!atEof() && peek().text != "{" && peek().text != ";") advance()
        }
        if (peek().text == "{") {
            advance()
            skipToClose('}', '{')
        }
        if (peek().type == CppTokenType.IDENTIFIER) advance()
        if (peek().text == ";") advance()
    }

    private fun skipNestedEnum() {
        advance() // enum
        if (peek().text == "class" || peek().text == "struct") advance()
        if (peek().type == CppTokenType.IDENTIFIER) advance()
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
                ">", ">>" -> depth--
            }
        }
    }

    /** Skip tokens until the closing char, counting matching open chars. */
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

    private fun skipModifiers() {
        loop@ while (true) {
            when (peek().text) {
                "extern", "inline", "constexpr", "consteval", "constinit",
                "mutable", "volatile", "register", "explicit",
                -> advance()
                else -> break@loop
            }
        }
    }

    private fun parseQualifiedName(): String {
        val parts = mutableListOf<String>()
        if (peek().type == CppTokenType.IDENTIFIER || peek().type == CppTokenType.KEYWORD) {
            parts += advance().text
        }
        while (peek().text == "::") {
            advance()
            if (peek().type == CppTokenType.IDENTIFIER || peek().type == CppTokenType.KEYWORD) {
                parts += advance().text
            }
        }
        return parts.joinToString("::")
    }

    // ── Token primitives ──────────────────────────────────────────────────────

    private fun peek(): CppToken = tokens.getOrElse(pos) { CppToken(CppTokenType.EOF, "", 0) }

    private fun peekAt(offset: Int): CppToken = tokens.getOrElse(pos + offset) { CppToken(CppTokenType.EOF, "", 0) }

    private fun advance(): CppToken {
        val t = peek()
        if (pos < tokens.size) pos++
        return t
    }

    private fun expect(text: String) {
        if (peek().text == text) advance()
    }

    private fun atEof(): Boolean = pos >= tokens.size || peek().type == CppTokenType.EOF

    private fun currentNamespace(): String? = if (namespaceStack.isEmpty()) null else namespaceStack.joinToString("::")

    private companion object {
        const val MAX_NAMESPACE_DEPTH: Int = 256
        val ACCESS_KEYWORDS: Set<String> = setOf("public", "protected", "private")
    }
}
