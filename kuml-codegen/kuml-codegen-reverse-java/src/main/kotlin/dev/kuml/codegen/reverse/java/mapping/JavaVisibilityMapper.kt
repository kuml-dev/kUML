package dev.kuml.codegen.reverse.java.mapping

import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers
import dev.kuml.uml.Visibility

/** Maps a JavaParser modifier set to a kUML [Visibility] enum value. */
internal object JavaVisibilityMapper {
    fun map(node: NodeWithModifiers<*>): Visibility {
        val modifiers = node.modifiers.map { it.keyword }
        return when {
            Modifier.Keyword.PUBLIC in modifiers -> Visibility.PUBLIC
            Modifier.Keyword.PROTECTED in modifiers -> Visibility.PROTECTED
            Modifier.Keyword.PRIVATE in modifiers -> Visibility.PRIVATE
            else -> Visibility.PACKAGE
        }
    }
}
