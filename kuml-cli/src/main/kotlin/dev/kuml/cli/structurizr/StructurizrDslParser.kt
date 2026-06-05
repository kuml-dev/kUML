package dev.kuml.cli.structurizr

/**
 * Hand-written recursive-descent parser for the Structurizr DSL.
 *
 * Parses workspace files into [StructurizrWorkspace] data classes.
 * Unknown directives are silently ignored so the parser is forwards-compatible.
 */
internal object StructurizrDslParser {
    internal fun parse(input: String): StructurizrWorkspace {
        val tokens = tokenize(input)
        val cursor = TokenCursor(tokens)
        return parseWorkspace(cursor)
    }

    // ─── Tokenizer ────────────────────────────────────────────────────────────

    private sealed class Token {
        data class Word(
            val value: String,
        ) : Token()

        data class Quoted(
            val value: String,
        ) : Token()

        data object OpenBrace : Token()

        data object CloseBrace : Token()

        data object Arrow : Token() // ->
    }

    private fun tokenize(input: String): List<Token> {
        val result = mutableListOf<Token>()
        for (rawLine in input.lines()) {
            val line = stripComment(rawLine)
            var i = 0
            while (i < line.length) {
                when {
                    line[i].isWhitespace() -> i++
                    line[i] == '"' -> {
                        val (str, end) = readQuoted(line, i)
                        result += Token.Quoted(str)
                        i = end
                    }
                    line[i] == '{' -> {
                        result += Token.OpenBrace
                        i++
                    }
                    line[i] == '}' -> {
                        result += Token.CloseBrace
                        i++
                    }
                    line.startsWith("->", i) -> {
                        result += Token.Arrow
                        i += 2
                    }
                    else -> {
                        val (word, end) = readWord(line, i)
                        result += Token.Word(word)
                        i = end
                    }
                }
            }
        }
        return result
    }

    private fun stripComment(line: String): String {
        var inQuote = false
        var i = 0
        while (i < line.length) {
            when {
                line[i] == '"' -> {
                    inQuote = !inQuote
                    i++
                }
                !inQuote && line[i] == '/' && i + 1 < line.length && line[i + 1] == '/' -> return line.substring(0, i)
                !inQuote && line[i] == '#' -> return line.substring(0, i)
                else -> i++
            }
        }
        return line
    }

    private fun readQuoted(
        line: String,
        start: Int,
    ): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start + 1 // skip opening quote
        while (i < line.length && line[i] != '"') {
            if (line[i] == '\\' && i + 1 < line.length) {
                sb.append(line[i + 1])
                i += 2
            } else {
                sb.append(line[i])
                i++
            }
        }
        return Pair(sb.toString(), if (i < line.length) i + 1 else i)
    }

    private fun readWord(
        line: String,
        start: Int,
    ): Pair<String, Int> {
        var i = start
        while (i < line.length &&
            !line[i].isWhitespace() &&
            line[i] != '"' &&
            line[i] != '{' &&
            line[i] != '}' &&
            !line.startsWith("->", i)
        ) {
            i++
        }
        return Pair(line.substring(start, i), i)
    }

    // ─── Token Cursor ─────────────────────────────────────────────────────────

    private class TokenCursor(
        private val tokens: List<Token>,
    ) {
        var pos: Int = 0

        fun hasMore(): Boolean = pos < tokens.size

        fun peek(): Token? = if (pos < tokens.size) tokens[pos] else null

        fun next(): Token? = if (pos < tokens.size) tokens[pos++] else null

        /** Consume the next string (Word or Quoted) and return it, or return null. */
        fun nextString(): String? =
            when (val t = peek()) {
                is Token.Word -> {
                    pos++
                    t.value
                }
                is Token.Quoted -> {
                    pos++
                    t.value
                }
                else -> null
            }

        /** Peek at the next string without consuming. */
        fun peekString(): String? =
            when (val t = peek()) {
                is Token.Word -> t.value
                is Token.Quoted -> t.value
                else -> null
            }

        fun expectOpenBrace() {
            if (peek() == Token.OpenBrace) pos++ else error("Expected '{' at pos $pos, got ${peek()}")
        }

        fun skipBlock() {
            // We must already be at the '{' or just past an expected '{'
            if (peek() == Token.OpenBrace) pos++ else return
            var depth = 1
            while (hasMore() && depth > 0) {
                when (next()) {
                    Token.OpenBrace -> depth++
                    Token.CloseBrace -> depth--
                    else -> {}
                }
            }
        }

        fun isAtOpenBrace(): Boolean = peek() == Token.OpenBrace

        fun isAtCloseBrace(): Boolean = peek() == Token.CloseBrace

        fun isAtArrow(): Boolean = peek() == Token.Arrow

        fun consumeCloseBrace() {
            if (peek() == Token.CloseBrace) pos++
        }
    }

    // ─── Workspace ────────────────────────────────────────────────────────────

    private fun parseWorkspace(cursor: TokenCursor): StructurizrWorkspace {
        // workspace ["Name"] ["Description"] {
        val keyword = cursor.nextString()
        if (keyword?.lowercase() != "workspace") {
            // Might not start with workspace keyword; try to be lenient
        }

        var workspaceName = "Workspace"
        var workspaceDesc: String? = null

        // Collect optional name/description before '{'
        while (cursor.hasMore() && !cursor.isAtOpenBrace()) {
            val s = cursor.nextString() ?: break
            if (workspaceName == "Workspace") workspaceName = s else workspaceDesc = s
        }

        var model = StructurizrModel(emptyList(), emptyList())
        var views = StructurizrViews()

        if (cursor.isAtOpenBrace()) {
            cursor.next() // consume '{'
            while (cursor.hasMore() && !cursor.isAtCloseBrace()) {
                val kw =
                    cursor.nextString() ?: run {
                        cursor.next()
                        continue
                    }
                when (kw.lowercase()) {
                    "model" -> model = parseModel(cursor)
                    "views" -> views = parseViews(cursor)
                    else -> {
                        // Skip unknown top-level block or directive
                        if (cursor.isAtOpenBrace()) cursor.skipBlock()
                    }
                }
            }
            cursor.consumeCloseBrace()
        }

        return StructurizrWorkspace(
            name = workspaceName,
            description = workspaceDesc,
            model = model,
            views = views,
        )
    }

    // ─── Model ────────────────────────────────────────────────────────────────

    private fun parseModel(cursor: TokenCursor): StructurizrModel {
        cursor.expectOpenBrace()
        val elements = mutableListOf<StructurizrElement>()
        val relationships = mutableListOf<StructurizrRelationship>()

        while (cursor.hasMore() && !cursor.isAtCloseBrace()) {
            parseModelStatement(cursor, elements, relationships, parentSystemId = null)
        }
        cursor.consumeCloseBrace()
        return StructurizrModel(elements, relationships)
    }

    /**
     * Parse a single statement inside the model (or inside a group/enterprise block).
     * Handles: assignments (`id = person ...`), plain elements, relationships, directives.
     */
    private fun parseModelStatement(
        cursor: TokenCursor,
        elements: MutableList<StructurizrElement>,
        relationships: MutableList<StructurizrRelationship>,
        parentSystemId: String?,
    ) {
        // If the next token isn't a string (e.g., a stray Arrow left by a previous parse),
        // consume and discard it to prevent infinite loops in the caller's while loop.
        val first =
            cursor.nextString() ?: run {
                cursor.next()
                return
            }

        // Directives to skip: !identifiers, !include, !constant, etc.
        // These are always single-line constructs. We consume the immediate args
        // but stop before anything that looks like the start of the next statement
        // (a bare Word that could be an identifier, since directive args are typically
        // quoted strings or a single bare value like "hierarchical").
        if (first.startsWith("!")) {
            // Consume at most one bare-word argument and any immediately following
            // quoted strings. Stop at identifiers that look like the next statement.
            if (cursor.peek() is Token.Word) {
                cursor.next() // consume single bare-word directive argument (e.g. "hierarchical")
            }
            while (cursor.peek() is Token.Quoted) {
                cursor.next() // consume quoted string args (e.g. in !constant NAME "value")
            }
            // Skip optional block if directive has one (uncommon but possible)
            if (cursor.isAtOpenBrace()) cursor.skipBlock()
            return
        }

        // Check if next token is '=' (assignment: identifier = keyword ...)
        if (cursor.peek() is Token.Word && (cursor.peek() as Token.Word).value == "=") {
            cursor.next() // consume '='
            val identifier = first
            val typeKw = cursor.nextString() ?: return
            parseElementByKeyword(typeKw, identifier, cursor, elements, relationships, parentSystemId)
            return
        }

        // Could be a relationship: first -> second ...
        if (cursor.isAtArrow()) {
            cursor.next() // consume '->'
            val target = cursor.nextString() ?: return
            // Description and technology are quoted in standard Structurizr DSL.
            // We only consume Quoted tokens to avoid accidentally consuming the next
            // statement's identifier.
            val description = readOptionalQuotedOrBareNonIdentifier(cursor)
            val technology = readOptionalQuotedOrBareNonIdentifier(cursor)
            // Consume optional trailing quoted tags (e.g. "Tag1,Tag2") — bare Words
            // are NOT consumed here because they are the start of the next statement.
            while (cursor.peek() is Token.Quoted) {
                cursor.next()
            }
            // Skip optional relationship block
            if (cursor.isAtOpenBrace()) cursor.skipBlock()
            relationships +=
                StructurizrRelationship(
                    sourceIdentifier = first,
                    targetIdentifier = target,
                    description = description?.ifBlank { null },
                    technology = technology?.ifBlank { null },
                )
            return
        }

        // Plain element without assignment
        parseElementByKeyword(first, null, cursor, elements, relationships, parentSystemId)
    }

    private fun parseElementByKeyword(
        keyword: String,
        identifier: String?,
        cursor: TokenCursor,
        elements: MutableList<StructurizrElement>,
        relationships: MutableList<StructurizrRelationship>,
        parentSystemId: String?,
    ) {
        when (keyword.lowercase()) {
            "person" -> {
                val elem = parsePerson(identifier, cursor)
                elements += elem
            }
            "softwaresystem" -> {
                val elem = parseSoftwareSystem(identifier, cursor, relationships)
                elements += elem
            }
            "container" -> {
                // Container at model level (with !identifiers hierarchical, identifiers use dots)
                // We handle this when we encounter it; parentSystemId should be non-null normally
                val elem = parseContainer(identifier, cursor, parentSystemId, relationships)
                elements += elem
            }
            "component" -> {
                val elem = parseComponent(identifier, cursor, parentContainerId = null)
                elements += elem
            }
            "deploymentnode" -> {
                val elem = parseDeploymentNode(identifier, cursor, environment = null)
                elements += elem
            }
            "group", "enterprise" -> {
                // Ignore the group wrapper, parse children as regular elements
                // group "Name" { ... }
                if (!cursor.isAtOpenBrace()) cursor.nextString() // consume group name if not at brace
                if (cursor.isAtOpenBrace()) {
                    cursor.next() // consume '{'
                    while (cursor.hasMore() && !cursor.isAtCloseBrace()) {
                        parseModelStatement(cursor, elements, relationships, parentSystemId)
                    }
                    cursor.consumeCloseBrace()
                }
            }
            "tags", "description", "url", "technology", "properties", "perspectives" -> {
                // Inline metadata we ignore
                if (cursor.isAtOpenBrace()) {
                    cursor.skipBlock()
                } else {
                    // consume string args until brace or close
                    while (!cursor.isAtOpenBrace() && !cursor.isAtCloseBrace() && cursor.peekString() != null) {
                        cursor.nextString()
                    }
                    if (cursor.isAtOpenBrace()) cursor.skipBlock()
                }
            }
            else -> {
                // Unknown keyword: skip until next statement
                if (cursor.isAtOpenBrace()) cursor.skipBlock()
            }
        }
    }

    // ─── Elements ─────────────────────────────────────────────────────────────

    private fun parsePerson(
        identifier: String?,
        cursor: TokenCursor,
    ): StructurizrElement.Person {
        val name = cursor.nextString() ?: "Person"
        val description = readOptionalQuotedOrBareNonIdentifier(cursor)
        var external = false
        // Consume optional tags
        val tagsArg = readOptionalQuotedOrBareNonIdentifier(cursor)
        if (tagsArg != null && tagsArg.contains("External", ignoreCase = true)) {
            external = true
        }
        // Skip optional block
        if (cursor.isAtOpenBrace()) {
            cursor.next() // '{'
            while (cursor.hasMore() && !cursor.isAtCloseBrace()) {
                val kw =
                    cursor.nextString() ?: run {
                        cursor.next()
                        continue
                    }
                when (kw.lowercase()) {
                    "tags" -> {
                        val t = cursor.nextString()
                        if (t != null && t.contains("External", ignoreCase = true)) external = true
                    }
                    else -> if (cursor.isAtOpenBrace()) cursor.skipBlock() else cursor.next()
                }
            }
            cursor.consumeCloseBrace()
        }
        return StructurizrElement.Person(identifier, name, description?.ifBlank { null }, external)
    }

    private fun parseSoftwareSystem(
        identifier: String?,
        cursor: TokenCursor,
        relationships: MutableList<StructurizrRelationship>,
    ): StructurizrElement.SoftwareSystem {
        val name = cursor.nextString() ?: "SoftwareSystem"
        val description = readOptionalQuotedOrBareNonIdentifier(cursor)
        val tagsArg = readOptionalQuotedOrBareNonIdentifier(cursor)
        var external = tagsArg != null && tagsArg.contains("External", ignoreCase = true)
        val containers = mutableListOf<StructurizrElement.Container>()

        if (cursor.isAtOpenBrace()) {
            cursor.next() // '{'
            while (cursor.hasMore() && !cursor.isAtCloseBrace()) {
                // If we can't read a string (e.g., at Arrow), skip the token to avoid infinite loop
                val kw =
                    cursor.nextString() ?: run {
                        cursor.next()
                        continue
                    }
                when (kw.lowercase()) {
                    "tags" -> {
                        val t = cursor.nextString()
                        if (t != null && t.contains("External", ignoreCase = true)) external = true
                    }
                    "container" -> {
                        val container = parseContainer(null, cursor, identifier, relationships)
                        containers += container
                    }
                    else -> {
                        // Could be an identifier = container assignment inside a system block
                        // Check if next is '='
                        if (cursor.peek() is Token.Word && (cursor.peek() as? Token.Word)?.value == "=") {
                            cursor.next() // consume '='
                            val typeKw =
                                cursor.nextString() ?: run {
                                    cursor.next()
                                    continue
                                }
                            if (typeKw.lowercase() == "container") {
                                val container = parseContainer(kw, cursor, identifier, relationships)
                                containers += container
                            } else if (cursor.isAtOpenBrace()) {
                                cursor.skipBlock()
                            }
                        } else if (cursor.isAtOpenBrace()) {
                            cursor.skipBlock()
                        } else if (cursor.isAtArrow()) {
                            // relationship inside system
                            cursor.next() // ->
                            val target =
                                cursor.nextString() ?: run {
                                    cursor.next()
                                    continue
                                }
                            val relDesc = readOptionalQuotedOrBareNonIdentifier(cursor)
                            val relTech = readOptionalQuotedOrBareNonIdentifier(cursor)
                            // Only consume quoted trailing tags (bare Words are the next statement)
                            while (cursor.peek() is Token.Quoted) cursor.next()
                            if (cursor.isAtOpenBrace()) cursor.skipBlock()
                            relationships += StructurizrRelationship(kw, target, relDesc?.ifBlank { null }, relTech?.ifBlank { null })
                        }
                        // else: unknown keyword after reading, skip silently
                    }
                }
            }
            cursor.consumeCloseBrace()
        }

        return StructurizrElement.SoftwareSystem(identifier, name, description?.ifBlank { null }, external, containers)
    }

    private fun parseContainer(
        identifier: String?,
        cursor: TokenCursor,
        parentSystemId: String?,
        relationships: MutableList<StructurizrRelationship>,
    ): StructurizrElement.Container {
        val name = cursor.nextString() ?: "Container"
        val description = readOptionalQuotedOrBareNonIdentifier(cursor)
        val technology = readOptionalQuotedOrBareNonIdentifier(cursor)
        // Consume optional tags arg
        val tagsArg = readOptionalQuotedOrBareNonIdentifier(cursor)
        val components = mutableListOf<StructurizrElement.Component>()

        if (cursor.isAtOpenBrace()) {
            cursor.next() // '{'
            while (cursor.hasMore() && !cursor.isAtCloseBrace()) {
                val kw =
                    cursor.nextString() ?: run {
                        cursor.next()
                        continue
                    }
                when (kw.lowercase()) {
                    "component" -> {
                        val comp = parseComponent(null, cursor, identifier)
                        components += comp
                    }
                    "tags", "description", "url", "technology", "properties", "perspectives" -> {
                        if (cursor.isAtOpenBrace()) {
                            cursor.skipBlock()
                        } else {
                            while (!cursor.isAtOpenBrace() && !cursor.isAtCloseBrace() && cursor.peekString() != null) cursor.nextString()
                            if (cursor.isAtOpenBrace()) cursor.skipBlock()
                        }
                    }
                    else -> {
                        // assignment inside container
                        if (cursor.peek() is Token.Word && (cursor.peek() as? Token.Word)?.value == "=") {
                            cursor.next() // '='
                            val typeKw =
                                cursor.nextString() ?: run {
                                    cursor.next()
                                    continue
                                }
                            if (typeKw.lowercase() == "component") {
                                components += parseComponent(kw, cursor, identifier)
                            } else if (cursor.isAtOpenBrace()) {
                                cursor.skipBlock()
                            }
                        } else if (cursor.isAtArrow()) {
                            cursor.next() // ->
                            val target =
                                cursor.nextString() ?: run {
                                    cursor.next()
                                    continue
                                }
                            val relDesc = readOptionalQuotedOrBareNonIdentifier(cursor)
                            val relTech = readOptionalQuotedOrBareNonIdentifier(cursor)
                            // Only consume quoted trailing tags (bare Words are the next statement)
                            while (cursor.peek() is Token.Quoted) cursor.next()
                            if (cursor.isAtOpenBrace()) cursor.skipBlock()
                            relationships += StructurizrRelationship(kw, target, relDesc?.ifBlank { null }, relTech?.ifBlank { null })
                        } else if (cursor.isAtOpenBrace()) {
                            cursor.skipBlock()
                        }
                    }
                }
            }
            cursor.consumeCloseBrace()
        }

        return StructurizrElement.Container(
            identifier = identifier,
            name = name,
            description = description?.ifBlank { null },
            technology = technology?.ifBlank { null },
            system = parentSystemId,
            components = components,
        )
    }

    private fun parseComponent(
        identifier: String?,
        cursor: TokenCursor,
        parentContainerId: String?,
    ): StructurizrElement.Component {
        val name = cursor.nextString() ?: "Component"
        val description = readOptionalQuotedOrBareNonIdentifier(cursor)
        val technology = readOptionalQuotedOrBareNonIdentifier(cursor)
        // Skip optional block
        if (cursor.isAtOpenBrace()) cursor.skipBlock()
        return StructurizrElement.Component(
            identifier = identifier,
            name = name,
            description = description?.ifBlank { null },
            technology = technology?.ifBlank { null },
            container = parentContainerId,
        )
    }

    private fun parseDeploymentNode(
        identifier: String?,
        cursor: TokenCursor,
        environment: String?,
    ): StructurizrElement.DeploymentNode {
        val name = cursor.nextString() ?: "DeploymentNode"
        val description = readOptionalQuotedOrBareNonIdentifier(cursor)
        val technology = readOptionalQuotedOrBareNonIdentifier(cursor)
        val children = mutableListOf<StructurizrElement.DeploymentNode>()

        if (cursor.isAtOpenBrace()) {
            cursor.next() // '{'
            while (cursor.hasMore() && !cursor.isAtCloseBrace()) {
                val kw =
                    cursor.nextString() ?: run {
                        cursor.next()
                        continue
                    }
                when (kw.lowercase()) {
                    "deploymentnode" -> {
                        children += parseDeploymentNode(null, cursor, environment)
                    }
                    else -> if (cursor.isAtOpenBrace()) cursor.skipBlock() else cursor.next()
                }
            }
            cursor.consumeCloseBrace()
        }

        return StructurizrElement.DeploymentNode(
            identifier,
            name,
            description?.ifBlank {
                null
            },
            technology?.ifBlank { null },
            environment,
            children,
        )
    }

    // ─── Views ────────────────────────────────────────────────────────────────

    private fun parseViews(cursor: TokenCursor): StructurizrViews {
        cursor.expectOpenBrace()
        val views = mutableListOf<StructurizrView>()

        while (cursor.hasMore() && !cursor.isAtCloseBrace()) {
            val kw =
                cursor.nextString() ?: run {
                    cursor.next()
                    continue
                }
            when (kw.lowercase()) {
                "systemcontext" -> views += parseSystemContextView(cursor)
                "container" -> views += parseContainerView(cursor)
                "component" -> views += parseComponentView(cursor)
                "deployment" -> views += parseDeploymentView(cursor)
                "systemlandscape" -> views += parseSystemLandscapeView(cursor)
                "filtered" -> {
                    if (cursor.isAtOpenBrace()) {
                        cursor.skipBlock()
                    } else {
                        while (!cursor.isAtOpenBrace() && !cursor.isAtCloseBrace() && cursor.peekString() != null) cursor.nextString()
                        if (cursor.isAtOpenBrace()) cursor.skipBlock()
                    }
                }
                else -> {
                    // theme, styles, etc. — skip
                    if (cursor.isAtOpenBrace()) {
                        cursor.skipBlock()
                    } else {
                        while (!cursor.isAtOpenBrace() && !cursor.isAtCloseBrace() && cursor.peekString() != null) cursor.nextString()
                        if (cursor.isAtOpenBrace()) cursor.skipBlock()
                    }
                }
            }
        }

        cursor.consumeCloseBrace()
        return StructurizrViews(views)
    }

    private fun parseSystemContextView(cursor: TokenCursor): StructurizrView.SystemContext {
        // systemContext <systemId> [key] [description] { ... }
        val systemId = readOptionalString(cursor)
        val key = readOptionalString(cursor)
        val description = readOptionalString(cursor)
        if (cursor.isAtOpenBrace()) cursor.skipBlock()
        return StructurizrView.SystemContext(systemId, key, description?.ifBlank { null })
    }

    private fun parseContainerView(cursor: TokenCursor): StructurizrView.Container {
        val systemId = readOptionalString(cursor)
        val key = readOptionalString(cursor)
        val description = readOptionalString(cursor)
        if (cursor.isAtOpenBrace()) cursor.skipBlock()
        return StructurizrView.Container(systemId, key, description?.ifBlank { null })
    }

    private fun parseComponentView(cursor: TokenCursor): StructurizrView.Component {
        val containerId = readOptionalString(cursor)
        val key = readOptionalString(cursor)
        val description = readOptionalString(cursor)
        if (cursor.isAtOpenBrace()) cursor.skipBlock()
        return StructurizrView.Component(containerId, key, description?.ifBlank { null })
    }

    private fun parseDeploymentView(cursor: TokenCursor): StructurizrView.Deployment {
        // deployment [scope] [environment] [key] [description] { ... }
        val first = readOptionalString(cursor)
        val second = readOptionalString(cursor)
        val key = readOptionalString(cursor)
        val description = readOptionalString(cursor)
        if (cursor.isAtOpenBrace()) cursor.skipBlock()
        // environment is second positional if first is "*", otherwise first
        val env = if (first == "*") second else first
        return StructurizrView.Deployment(env, key, description?.ifBlank { null })
    }

    private fun parseSystemLandscapeView(cursor: TokenCursor): StructurizrView.SystemLandscape {
        val key = readOptionalString(cursor)
        val description = readOptionalString(cursor)
        if (cursor.isAtOpenBrace()) cursor.skipBlock()
        return StructurizrView.SystemLandscape(key, description?.ifBlank { null })
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Read the next token as a string only if it isn't a structural token
     * (brace, arrow). Returns null if no string is available.
     */
    private fun readOptionalString(cursor: TokenCursor): String? {
        val p = cursor.peek() ?: return null
        return when {
            p == Token.OpenBrace || p == Token.CloseBrace || p == Token.Arrow -> null
            else -> cursor.nextString()
        }
    }

    /**
     * Read the next token as a string ONLY if it is a quoted string.
     * Bare words are NOT consumed — they are identifier tokens that belong to the next statement.
     *
     * Use this for optional positional arguments (description, technology, tags) in element
     * definitions, to prevent accidentally consuming the next statement's identifier.
     */
    private fun readOptionalQuotedOrBareNonIdentifier(cursor: TokenCursor): String? {
        val p = cursor.peek() ?: return null
        return when {
            p == Token.OpenBrace || p == Token.CloseBrace || p == Token.Arrow -> null
            p is Token.Quoted -> cursor.nextString()
            // A bare Word at this position is very likely the start of the next statement
            // (an identifier). Don't consume it.
            else -> null
        }
    }
}
