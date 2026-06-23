package dev.kuml.plugin.examples.tsreverse

internal class TsParser(
    private val tokens: List<TsToken>,
) {
    private var pos = 0

    /**
     * Mutable recursion-depth counter for [parseSingleTypeRef].
     *
     * Incremented on every entry and decremented in a `finally` block on every exit. When the
     * counter exceeds [MAX_TYPE_REF_DEPTH] the method returns `TsTypeRef(name = "Object")`
     * immediately without recursing further, preventing a [StackOverflowError] on crafted inputs
     * with deeply nested generic type arguments (e.g. `A<A<A<…>>>` repeated thousands of times).
     *
     * The counter is an instance field rather than a call-stack parameter so that depth is tracked
     * correctly across the full mutual-recursion chain
     * `parseSingleTypeRef → parseTypeArgList → parseTypeRef → parseSingleTypeRef`.
     */
    private var typeRefDepth = 0

    private fun peek(): TsToken = tokens[pos.coerceAtMost(tokens.size - 1)]

    private fun peekKind(): TsTokenKind = peek().kind

    private fun peekText(): String = peek().text

    private fun advance(): TsToken {
        val t = peek()
        if (t.kind != TsTokenKind.EOF) pos++
        return t
    }

    private fun consume(kind: TsTokenKind): TsToken {
        val t = peek()
        if (t.kind == kind) return advance()
        return t
    }

    private fun consumeKeyword(text: String): Boolean {
        if (peekKind() == TsTokenKind.KEYWORD && peekText() == text) {
            advance()
            return true
        }
        return false
    }

    private fun at(kind: TsTokenKind): Boolean = peekKind() == kind

    private fun atKeyword(text: String): Boolean = peekKind() == TsTokenKind.KEYWORD && peekText() == text

    private fun atIdent(): Boolean = peekKind() == TsTokenKind.IDENT || isContextualKeywordAsIdent()

    private fun isContextualKeywordAsIdent(): Boolean {
        if (peekKind() != TsTokenKind.KEYWORD) return false
        return peekText() in
            setOf(
                "type",
                "readonly",
                "async",
                "from",
                "module",
                "namespace",
                "declare",
                "abstract",
                "override",
                "get",
                "set",
                "as",
            )
    }

    private fun consumeIdent(): String {
        val t = peek()
        if (t.kind == TsTokenKind.IDENT || isContextualKeywordAsIdent()) {
            advance()
            return t.text
        }
        return ""
    }

    fun parse(): TsFileAst {
        val imports = mutableListOf<TsImport>()
        val decls = mutableListOf<TsDeclaration>()

        while (!at(TsTokenKind.EOF)) {
            when {
                atKeyword("import") -> parseImport()?.let { imports += it }
                else -> {
                    val decorators = parseDecorators()
                    skipModifiers()
                    when {
                        atKeyword("interface") -> parseInterface(decorators)?.let { decls += it }
                        atKeyword("class") -> parseClass(decorators, isAbstract = false)?.let { decls += it }
                        atKeyword("enum") -> parseEnum(decorators)?.let { decls += it }
                        atKeyword("abstract") && lookaheadKeyword("class") -> {
                            consumeKeyword("abstract")
                            parseClass(decorators, isAbstract = true)?.let { decls += it }
                        }
                        else -> skipTopLevelStatement()
                    }
                }
            }
        }

        return TsFileAst(imports = imports, declarations = decls)
    }

    private fun lookaheadKeyword(text: String): Boolean {
        val saved = pos
        advance()
        val result = atKeyword(text)
        pos = saved
        return result
    }

    private fun parseImport(): TsImport? {
        consumeKeyword("import")
        if (atKeyword("type")) consumeKeyword("type")

        if (at(TsTokenKind.STRING_LIT)) {
            advance()
            return null
        }

        val names = mutableListOf<String>()
        when {
            at(TsTokenKind.LBRACE) -> {
                advance()
                while (!at(TsTokenKind.RBRACE) && !at(TsTokenKind.EOF)) {
                    if (atKeyword("type")) advance()
                    if (atIdent()) names += consumeIdent()
                    if (atKeyword("as")) {
                        advance()
                        consumeIdent()
                    }
                    consume(TsTokenKind.COMMA)
                }
                consume(TsTokenKind.RBRACE)
            }
            at(TsTokenKind.STAR) -> {
                advance()
                if (atKeyword("as")) {
                    advance()
                    consumeIdent()
                }
            }
            atIdent() -> {
                names += consumeIdent()
                if (at(TsTokenKind.COMMA)) {
                    advance()
                    if (at(TsTokenKind.LBRACE)) {
                        advance()
                        while (!at(TsTokenKind.RBRACE) && !at(TsTokenKind.EOF)) {
                            if (atKeyword("type")) advance()
                            if (atIdent()) names += consumeIdent()
                            if (atKeyword("as")) {
                                advance()
                                consumeIdent()
                            }
                            consume(TsTokenKind.COMMA)
                        }
                        consume(TsTokenKind.RBRACE)
                    }
                }
            }
            else -> {
                skipToNewlineOrSemi()
                return null
            }
        }

        val from =
            if (atKeyword("from")) {
                advance()
                val t = peek()
                if (at(TsTokenKind.STRING_LIT)) {
                    advance()
                    t.text.trim('\'', '"', '`')
                } else {
                    ""
                }
            } else {
                ""
            }

        skipToSemi()
        return if (names.isNotEmpty() && from.isNotEmpty()) TsImport(names = names, from = from) else null
    }

    private fun parseDecorators(): List<String> {
        val decorators = mutableListOf<String>()
        while (at(TsTokenKind.AT)) {
            advance()
            val name = consumeIdent()
            if (at(TsTokenKind.LPAREN)) skipBalancedParens()
            if (name.isNotEmpty()) decorators += name
        }
        return decorators
    }

    private fun skipModifiers() {
        while (true) {
            when {
                atKeyword("export") -> consumeKeyword("export")
                atKeyword("default") -> consumeKeyword("default")
                atKeyword("declare") -> consumeKeyword("declare")
                atKeyword("const") && !lookaheadKeyword("enum") -> break
                else -> break
            }
        }
        if (atKeyword("const") && lookaheadKeyword("enum")) consumeKeyword("const")
    }

    private fun parseInterface(decorators: List<String>): TsInterfaceDecl? {
        if (!consumeKeyword("interface")) return null
        val name = consumeIdent()
        if (name.isEmpty()) {
            skipBody()
            return null
        }

        val typeParams = if (at(TsTokenKind.LANGLE)) parseTypeParams() else emptyList()
        val extendsTypes = mutableListOf<TsTypeRef>()
        if (atKeyword("extends")) {
            consumeKeyword("extends")
            extendsTypes += parseTypeRef()
            while (at(TsTokenKind.COMMA)) {
                advance()
                extendsTypes += parseTypeRef()
            }
        }

        val members = if (at(TsTokenKind.LBRACE)) parseMembers() else emptyList()
        return TsInterfaceDecl(
            name = name,
            decorators = decorators,
            typeParams = typeParams,
            extendsTypes = extendsTypes,
            members = members,
        )
    }

    private fun parseClass(
        decorators: List<String>,
        isAbstract: Boolean,
    ): TsClassDecl? {
        if (!consumeKeyword("class")) return null
        val name = consumeIdent()
        if (name.isEmpty()) {
            skipBody()
            return null
        }

        val typeParams = if (at(TsTokenKind.LANGLE)) parseTypeParams() else emptyList()

        var superClass: TsTypeRef? = null
        if (atKeyword("extends")) {
            consumeKeyword("extends")
            superClass = parseTypeRef()
        }

        val implTypes = mutableListOf<TsTypeRef>()
        if (atKeyword("implements")) {
            consumeKeyword("implements")
            implTypes += parseTypeRef()
            while (at(TsTokenKind.COMMA)) {
                advance()
                implTypes += parseTypeRef()
            }
        }

        val members = if (at(TsTokenKind.LBRACE)) parseMembers() else emptyList()
        return TsClassDecl(
            name = name,
            decorators = decorators,
            isAbstract = isAbstract,
            typeParams = typeParams,
            superClass = superClass,
            implementsTypes = implTypes,
            members = members,
        )
    }

    private fun parseEnum(decorators: List<String>): TsEnumDecl? {
        if (!consumeKeyword("enum")) return null
        val name = consumeIdent()
        if (name.isEmpty()) {
            skipBody()
            return null
        }

        val literals = mutableListOf<String>()
        if (at(TsTokenKind.LBRACE)) {
            advance()
            while (!at(TsTokenKind.RBRACE) && !at(TsTokenKind.EOF)) {
                val lit = if (atIdent()) consumeIdent() else ""
                if (lit.isNotEmpty()) literals += lit
                if (at(TsTokenKind.EQUALS)) {
                    advance()
                    skipEnumValue()
                }
                consume(TsTokenKind.COMMA)
            }
            consume(TsTokenKind.RBRACE)
        }
        return TsEnumDecl(name = name, decorators = decorators, literals = literals)
    }

    private fun skipEnumValue() {
        while (!at(TsTokenKind.COMMA) && !at(TsTokenKind.RBRACE) && !at(TsTokenKind.EOF)) {
            when {
                at(TsTokenKind.LBRACE) -> skipBalancedBraces()
                at(TsTokenKind.LPAREN) -> skipBalancedParens()
                at(TsTokenKind.LBRACKET) -> skipBalancedBrackets()
                else -> advance()
            }
        }
    }

    private fun parseMembers(): List<TsMember> {
        consume(TsTokenKind.LBRACE)
        val members = mutableListOf<TsMember>()
        while (!at(TsTokenKind.RBRACE) && !at(TsTokenKind.EOF)) {
            val decorators = parseDecorators()
            val posBefore = pos
            val memberOpt = parseMember(decorators)
            when {
                memberOpt != null -> members += memberOpt
                pos == posBefore -> skipMemberFallback() // parseMember made no progress — use fallback
                // else: parseMember handled the member (constructor/accessor) and advanced pos — no fallback needed
            }
        }
        consume(TsTokenKind.RBRACE)
        return members
    }

    private fun parseMember(decorators: List<String>): TsMember? {
        var visibility = "public"
        var isStatic = false
        var isReadonly = false
        var isAbstract = false

        var scanning = true
        while (scanning) {
            when {
                atKeyword("public") -> {
                    visibility = "public"
                    advance()
                }
                atKeyword("private") -> {
                    visibility = "private"
                    advance()
                }
                atKeyword("protected") -> {
                    visibility = "protected"
                    advance()
                }
                atKeyword("static") -> {
                    isStatic = true
                    advance()
                }
                atKeyword("readonly") -> {
                    isReadonly = true
                    advance()
                }
                atKeyword("abstract") -> {
                    isAbstract = true
                    advance()
                }
                atKeyword("override") -> advance()
                atKeyword("async") -> advance()
                else -> scanning = false
            }
        }

        if (at(TsTokenKind.LBRACKET)) {
            skipIndexSignature()
            return null
        }

        if (atKeyword("constructor")) {
            advance()
            if (at(TsTokenKind.LPAREN)) skipBalancedParens()
            if (at(TsTokenKind.LBRACE)) skipBalancedBraces()
            return null
        }

        if (atKeyword("get") || atKeyword("set")) {
            val accessorKind = peekText()
            advance()
            val name = consumeIdent()
            if (name.isEmpty()) return null
            if (at(TsTokenKind.LPAREN)) skipBalancedParens()
            val retType =
                if (at(TsTokenKind.COLON)) {
                    advance()
                    parseTypeRef()
                } else {
                    null
                }
            if (at(TsTokenKind.LBRACE)) skipBalancedBraces()
            consume(TsTokenKind.SEMICOLON)
            return TsMember(
                name = name,
                type = retType,
                isMethod = false,
                visibility = visibility,
                isStatic = isStatic,
                isReadonly = accessorKind == "get",
                decorators = decorators,
            )
        }

        val name = consumeIdent()
        if (name.isEmpty()) return null

        val typeParams = if (at(TsTokenKind.LANGLE)) parseTypeParams() else emptyList()

        val isMethod = at(TsTokenKind.LPAREN)
        return if (isMethod) {
            val params = parseParams()
            val retType =
                if (at(TsTokenKind.COLON)) {
                    advance()
                    parseTypeRef()
                } else {
                    null
                }
            if (at(TsTokenKind.LBRACE)) skipBalancedBraces()
            consume(TsTokenKind.SEMICOLON)
            TsMember(
                name = name,
                type = retType,
                isMethod = true,
                isAbstract = isAbstract,
                isStatic = isStatic,
                visibility = visibility,
                params = params,
                typeParams = typeParams,
                decorators = decorators,
            )
        } else {
            val isOptional = at(TsTokenKind.QUESTION).also { if (it) advance() }
            val propType =
                if (at(TsTokenKind.COLON)) {
                    advance()
                    parseTypeRef()
                } else {
                    null
                }
            if (at(TsTokenKind.EQUALS)) {
                advance()
                skipInitializer()
            }
            consume(TsTokenKind.SEMICOLON)
            TsMember(
                name = name,
                type = propType,
                isMethod = false,
                isOptional = isOptional,
                isReadonly = isReadonly,
                isStatic = isStatic,
                visibility = visibility,
                decorators = decorators,
            )
        }
    }

    private fun skipIndexSignature() {
        advance()
        while (!at(TsTokenKind.RBRACKET) && !at(TsTokenKind.EOF)) advance()
        consume(TsTokenKind.RBRACKET)
        consume(TsTokenKind.COLON)
        parseTypeRef()
        consume(TsTokenKind.SEMICOLON)
    }

    private fun skipMemberFallback() {
        var depth = 0
        while (!at(TsTokenKind.EOF)) {
            when {
                at(TsTokenKind.LBRACE) || at(TsTokenKind.LPAREN) || at(TsTokenKind.LBRACKET) -> {
                    depth++
                    advance()
                }
                at(TsTokenKind.RBRACE) || at(TsTokenKind.RPAREN) || at(TsTokenKind.RBRACKET) -> {
                    if (depth == 0) return
                    depth--
                    advance()
                }
                at(TsTokenKind.SEMICOLON) && depth == 0 -> {
                    advance()
                    return
                }
                else -> advance()
            }
        }
    }

    private fun parseParams(): List<TsParam> {
        consume(TsTokenKind.LPAREN)
        val params = mutableListOf<TsParam>()
        while (!at(TsTokenKind.RPAREN) && !at(TsTokenKind.EOF)) {
            if (at(TsTokenKind.DOT) && tokens.getOrNull(pos + 1)?.text == "." && tokens.getOrNull(pos + 2)?.text == ".") {
                repeat(3) { advance() }
            }
            if (atKeyword("readonly")) advance()
            if (atKeyword("public") || atKeyword("private") || atKeyword("protected")) advance()

            val name =
                if (atIdent()) {
                    consumeIdent()
                } else {
                    skipToCommaOrParen()
                    ""
                }
            val isOpt = at(TsTokenKind.QUESTION).also { if (it) advance() }
            val type =
                if (at(TsTokenKind.COLON)) {
                    advance()
                    parseTypeRef()
                } else {
                    null
                }
            if (at(TsTokenKind.EQUALS)) {
                advance()
                skipInitializer()
            }
            if (name.isNotEmpty()) params += TsParam(name = name, type = type, isOptional = isOpt)
            consume(TsTokenKind.COMMA)
        }
        consume(TsTokenKind.RPAREN)
        return params
    }

    private fun skipToCommaOrParen() {
        while (!at(TsTokenKind.COMMA) && !at(TsTokenKind.RPAREN) && !at(TsTokenKind.EOF)) advance()
    }

    private fun parseTypeParams(): List<TsTypeParam> {
        consume(TsTokenKind.LANGLE)
        val result = mutableListOf<TsTypeParam>()
        var depth = 1
        while (depth > 0 && !at(TsTokenKind.EOF)) {
            if (at(TsTokenKind.LANGLE)) {
                depth++
                advance()
                continue
            }
            if (at(TsTokenKind.RANGLE)) {
                depth--
                advance()
                continue
            }
            if (depth == 1 && atIdent()) {
                val name = consumeIdent()
                result += TsTypeParam(name = name)
                if (atKeyword("extends") || at(TsTokenKind.COMMA)) {
                    while (!at(TsTokenKind.COMMA) && !at(TsTokenKind.RANGLE) && !at(TsTokenKind.EOF)) advance()
                    consume(TsTokenKind.COMMA)
                }
            } else {
                advance()
            }
        }
        return result
    }

    private fun parseTypeRef(): TsTypeRef {
        val base = parseSingleTypeRef()
        if (!at(TsTokenKind.PIPE) && !at(TsTokenKind.AMPERSAND)) return base

        val union = mutableListOf(base)
        while ((at(TsTokenKind.PIPE) || at(TsTokenKind.AMPERSAND)) && !at(TsTokenKind.EOF)) {
            advance()
            union += parseSingleTypeRef()
        }
        return if (union.size == 1) {
            base
        } else {
            TsTypeRef(name = union.joinToString(" | ") { it.name }, isUnion = true, unionTypes = union)
        }
    }

    /**
     * Parse a single (non-union) type reference.
     *
     * Uses [typeRefDepth] as a mutable instance-level recursion counter so that depth is tracked
     * correctly across the mutual-recursion chain
     * `parseSingleTypeRef → parseTypeArgList → parseTypeRef → parseSingleTypeRef`.
     * When the counter exceeds [MAX_TYPE_REF_DEPTH] a safe `TsTypeRef(name = "Object")` fallback
     * is returned immediately without recursing further.
     */
    private fun parseSingleTypeRef(): TsTypeRef {
        if (typeRefDepth > MAX_TYPE_REF_DEPTH) {
            // Depth limit reached: consume this token non-recursively and return a safe fallback.
            advance()
            return TsTypeRef(name = "Object")
        }

        typeRefDepth++
        try {
            var name = ""
            var typeArgs = listOf<TsTypeRef>()

            when {
                atKeyword("readonly") -> {
                    advance()
                    return parseSingleTypeRef()
                }
                at(TsTokenKind.LPAREN) -> {
                    skipBalancedParens()
                    if (at(TsTokenKind.LBRACKET)) {
                        advance()
                        consume(TsTokenKind.RBRACKET)
                    }
                    return TsTypeRef(name = "Function")
                }
                at(TsTokenKind.LBRACE) -> {
                    skipBalancedBraces()
                    return TsTypeRef(name = "Object")
                }
                at(TsTokenKind.LBRACKET) -> {
                    skipBalancedBrackets()
                    return TsTypeRef(name = "Array")
                }
                atKeyword("typeof") -> {
                    advance()
                    name = if (atIdent()) consumeIdent() else "Object"
                }
                atKeyword("keyof") -> {
                    advance()
                    parseSingleTypeRef()
                    return TsTypeRef(name = "string")
                }
                atIdent() || peekKind() == TsTokenKind.KEYWORD -> {
                    name = consumeIdent()
                    if (name.isEmpty() && peekKind() == TsTokenKind.KEYWORD) name = advance().text
                    while (at(TsTokenKind.DOT) && !at(TsTokenKind.EOF)) {
                        advance()
                        val part = if (atIdent()) consumeIdent() else break
                        name += ".$part"
                    }
                    if (at(TsTokenKind.LANGLE)) {
                        typeArgs = parseTypeArgList()
                    }
                }
                else -> {
                    advance()
                    name = "Object"
                }
            }

            var isArray = false
            while (at(TsTokenKind.LBRACKET) && tokens.getOrNull(pos + 1)?.kind == TsTokenKind.RBRACKET) {
                advance()
                advance()
                isArray = true
            }

            return TsTypeRef(name = name, typeArgs = typeArgs, isArray = isArray)
        } finally {
            typeRefDepth--
        }
    }

    private companion object {
        /**
         * Maximum recursion depth for [parseSingleTypeRef] before returning `Object` fallback.
         *
         * Tracked via the mutable [typeRefDepth] instance field so that depth is accumulated
         * correctly across the mutual recursion chain
         * `parseSingleTypeRef → parseTypeArgList → parseTypeRef → parseSingleTypeRef`.
         * 64 levels is more than sufficient for any real-world TypeScript generic type expression;
         * a crafted input with thousands of nested `<` would otherwise exhaust the JVM stack.
         */
        const val MAX_TYPE_REF_DEPTH: Int = 64
    }

    private fun parseTypeArgList(): List<TsTypeRef> {
        consume(TsTokenKind.LANGLE)
        val args = mutableListOf<TsTypeRef>()
        var depth = 1
        while (depth > 0 && !at(TsTokenKind.EOF)) {
            when {
                at(TsTokenKind.LANGLE) -> {
                    depth++
                    advance()
                }
                at(TsTokenKind.RANGLE) -> {
                    depth--
                    if (depth == 0) {
                        advance()
                        break
                    }
                    advance()
                }
                depth == 1 -> {
                    val before = pos
                    args += parseTypeRef()
                    if (pos == before) advance()
                    consume(TsTokenKind.COMMA)
                }
                else -> advance()
            }
        }
        return args
    }

    private fun skipInitializer() {
        var depth = 0
        while (!at(TsTokenKind.EOF)) {
            when {
                at(TsTokenKind.LBRACE) || at(TsTokenKind.LPAREN) || at(TsTokenKind.LBRACKET) -> {
                    depth++
                    advance()
                }
                at(TsTokenKind.RBRACE) || at(TsTokenKind.RPAREN) || at(TsTokenKind.RBRACKET) -> {
                    if (depth == 0) return
                    depth--
                    advance()
                }
                (at(TsTokenKind.COMMA) || at(TsTokenKind.SEMICOLON)) && depth == 0 -> return
                else -> advance()
            }
        }
    }

    private fun skipBalancedParens() {
        if (!at(TsTokenKind.LPAREN)) return
        advance()
        var depth = 1
        while (depth > 0 && !at(TsTokenKind.EOF)) {
            when {
                at(TsTokenKind.LPAREN) -> {
                    depth++
                    advance()
                }
                at(TsTokenKind.RPAREN) -> {
                    depth--
                    advance()
                }
                else -> advance()
            }
        }
    }

    private fun skipBalancedBraces() {
        if (!at(TsTokenKind.LBRACE)) return
        advance()
        var depth = 1
        while (depth > 0 && !at(TsTokenKind.EOF)) {
            when {
                at(TsTokenKind.LBRACE) -> {
                    depth++
                    advance()
                }
                at(TsTokenKind.RBRACE) -> {
                    depth--
                    advance()
                }
                else -> advance()
            }
        }
    }

    private fun skipBalancedBrackets() {
        if (!at(TsTokenKind.LBRACKET)) return
        advance()
        var depth = 1
        while (depth > 0 && !at(TsTokenKind.EOF)) {
            when {
                at(TsTokenKind.LBRACKET) -> {
                    depth++
                    advance()
                }
                at(TsTokenKind.RBRACKET) -> {
                    depth--
                    advance()
                }
                else -> advance()
            }
        }
    }

    private fun skipBody() {
        if (at(TsTokenKind.LBRACE)) {
            skipBalancedBraces()
        } else {
            skipToNewlineOrSemi()
        }
    }

    private fun skipTopLevelStatement() {
        var depth = 0
        while (!at(TsTokenKind.EOF)) {
            when {
                at(TsTokenKind.LBRACE) -> {
                    depth++
                    advance()
                }
                at(TsTokenKind.RBRACE) -> {
                    depth--
                    advance()
                    if (depth <= 0) return
                }
                at(TsTokenKind.SEMICOLON) && depth == 0 -> {
                    advance()
                    return
                }
                else -> advance()
            }
        }
    }

    private fun skipToNewlineOrSemi() {
        while (!at(TsTokenKind.SEMICOLON) && !at(TsTokenKind.EOF)) advance()
        consume(TsTokenKind.SEMICOLON)
    }

    private fun skipToSemi() {
        while (!at(TsTokenKind.SEMICOLON) && !at(TsTokenKind.EOF) && !at(TsTokenKind.LBRACE)) advance()
        if (at(TsTokenKind.SEMICOLON)) advance()
    }
}
