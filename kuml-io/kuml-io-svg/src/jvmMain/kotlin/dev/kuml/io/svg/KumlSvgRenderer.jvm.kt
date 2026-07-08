package dev.kuml.io.svg

import dev.kuml.blueprint.model.BlueprintDiagram
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.bpmn.model.BpmnModel
import dev.kuml.bpmn.model.ChoreographyDiagram
import dev.kuml.bpmn.model.CollaborationDiagram
import dev.kuml.bpmn.model.ConversationDiagram
import dev.kuml.core.model.KumlDiagram
import dev.kuml.erm.model.ErmDiagram
import dev.kuml.erm.model.ErmModel
import dev.kuml.erm.model.ErmNotation
import dev.kuml.layout.LayoutResult
import dev.kuml.renderer.theme.core.KumlTheme
import dev.kuml.renderer.theme.core.PlainTheme
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.BdDiagram
import dev.kuml.sysml2.IbdDiagram
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.ReqDiagram
import dev.kuml.sysml2.SeqDiagram
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.UcDiagram
import java.io.File
import java.nio.file.Path

/**
 * JVM-only `toSvgFile` overloads for [KumlSvgRenderer].
 *
 * These write the rendered SVG string to a [java.io.File] via
 * [java.nio.file.Path]/[java.io.File] APIs, which are not available on
 * js/wasmJs. The pure `toSvg(...): String` producers remain in
 * `commonMain` and are usable from all targets — only the file-writing
 * convenience wrappers are JVM-only.
 *
 * V3.2.8/9 — KMP split: relocated from `KumlSvgRenderer.kt` (commonMain)
 * as part of the wasmJs conversion.
 */
public fun KumlSvgRenderer.toSvgFile(
    diagram: KumlDiagram,
    layoutResult: LayoutResult,
    out: Path,
    theme: KumlTheme = PlainTheme(),
    options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
): File {
    val svg = toSvg(diagram, layoutResult, theme, options)
    val file = out.toFile()
    file.parentFile?.mkdirs()
    file.writeText(svg, Charsets.UTF_8)
    return file
}

public fun KumlSvgRenderer.toSvgFile(
    model: Sysml2Model,
    diagram: BdDiagram,
    layoutResult: LayoutResult,
    out: Path,
    theme: KumlTheme = PlainTheme(),
    options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
): File {
    val svg = toSvg(model, diagram, layoutResult, theme, options)
    val file = out.toFile()
    file.parentFile?.mkdirs()
    file.writeText(svg, Charsets.UTF_8)
    return file
}

public fun KumlSvgRenderer.toSvgFile(
    model: Sysml2Model,
    diagram: IbdDiagram,
    layoutResult: LayoutResult,
    out: Path,
    theme: KumlTheme = PlainTheme(),
    options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
): File {
    val svg = toSvg(model, diagram, layoutResult, theme, options)
    val file = out.toFile()
    file.parentFile?.mkdirs()
    file.writeText(svg, Charsets.UTF_8)
    return file
}

public fun KumlSvgRenderer.toSvgFile(
    model: Sysml2Model,
    diagram: UcDiagram,
    layoutResult: LayoutResult,
    out: Path,
    theme: KumlTheme = PlainTheme(),
    options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
): File {
    val svg = toSvg(model, diagram, layoutResult, theme, options)
    val file = out.toFile()
    file.parentFile?.mkdirs()
    file.writeText(svg, Charsets.UTF_8)
    return file
}

public fun KumlSvgRenderer.toSvgFile(
    model: Sysml2Model,
    diagram: ReqDiagram,
    layoutResult: LayoutResult,
    out: Path,
    theme: KumlTheme = PlainTheme(),
    options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
): File {
    val svg = toSvg(model, diagram, layoutResult, theme, options)
    val file = out.toFile()
    file.parentFile?.mkdirs()
    file.writeText(svg, Charsets.UTF_8)
    return file
}

public fun KumlSvgRenderer.toSvgFile(
    model: Sysml2Model,
    diagram: StmDiagram,
    layoutResult: LayoutResult,
    out: Path,
    theme: KumlTheme = PlainTheme(),
    options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
): File {
    val svg = toSvg(model, diagram, layoutResult, theme, options)
    val file = out.toFile()
    file.parentFile?.mkdirs()
    file.writeText(svg, Charsets.UTF_8)
    return file
}

public fun KumlSvgRenderer.toSvgFile(
    model: Sysml2Model,
    diagram: ActDiagram,
    layoutResult: LayoutResult,
    out: Path,
    theme: KumlTheme = PlainTheme(),
    options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
): File {
    val svg = toSvg(model, diagram, layoutResult, theme, options)
    val file = out.toFile()
    file.parentFile?.mkdirs()
    file.writeText(svg, Charsets.UTF_8)
    return file
}

public fun KumlSvgRenderer.toSvgFile(
    model: Sysml2Model,
    diagram: SeqDiagram,
    layoutResult: LayoutResult,
    out: Path,
    theme: KumlTheme = PlainTheme(),
    options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
): File {
    val svg = toSvg(model, diagram, layoutResult, theme, options)
    val file = out.toFile()
    file.parentFile?.mkdirs()
    file.writeText(svg, Charsets.UTF_8)
    return file
}

public fun KumlSvgRenderer.toSvgFile(
    model: Sysml2Model,
    diagram: ParDiagram,
    layoutResult: LayoutResult,
    out: Path,
    theme: KumlTheme = PlainTheme(),
    options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
): File {
    val svg = toSvg(model, diagram, layoutResult, theme, options)
    val file = out.toFile()
    file.parentFile?.mkdirs()
    file.writeText(svg, Charsets.UTF_8)
    return file
}

public fun KumlSvgRenderer.toSvgFile(
    model: BlueprintModel,
    diagram: BlueprintDiagram,
    out: Path,
    theme: KumlTheme = PlainTheme(),
): File {
    val svg = toSvg(model, diagram, theme)
    val file = out.toFile()
    file.parentFile?.mkdirs()
    file.writeText(svg, Charsets.UTF_8)
    return file
}

/** [KumlSvgRenderer.toSvg]-Variante für ERM-Diagramme (V3.4.2), schreibt direkt auf Platte. */
public fun KumlSvgRenderer.toSvgFile(
    model: ErmModel,
    diagram: ErmDiagram,
    layoutResult: LayoutResult,
    out: Path,
    theme: KumlTheme = PlainTheme(),
    options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
    notation: ErmNotation = diagram.notation,
): File {
    val svg = toSvg(model, diagram, layoutResult, theme, options, notation)
    val file = out.toFile()
    file.parentFile?.mkdirs()
    file.writeText(svg, Charsets.UTF_8)
    return file
}

public fun KumlSvgRenderer.toSvgFile(
    model: BpmnModel,
    diagram: CollaborationDiagram,
    layoutResult: LayoutResult,
    out: Path,
    theme: KumlTheme = PlainTheme(),
    options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
): File {
    val svg = toSvg(model, diagram, layoutResult, theme, options)
    val file = out.toFile()
    file.parentFile?.mkdirs()
    file.writeText(svg, Charsets.UTF_8)
    return file
}

public fun KumlSvgRenderer.toSvgFile(
    model: BpmnModel,
    diagram: ChoreographyDiagram,
    layoutResult: LayoutResult,
    out: Path,
    theme: KumlTheme = PlainTheme(),
    options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
): File {
    val svg = toSvg(model, diagram, layoutResult, theme, options)
    val file = out.toFile()
    file.parentFile?.mkdirs()
    file.writeText(svg, Charsets.UTF_8)
    return file
}

public fun KumlSvgRenderer.toSvgFile(
    model: BpmnModel,
    diagram: ConversationDiagram,
    layoutResult: LayoutResult,
    out: Path,
    theme: KumlTheme = PlainTheme(),
    options: SvgRenderOptions = SvgRenderOptions.DEFAULT,
): File {
    val svg = toSvg(model, diagram, layoutResult, theme, options)
    val file = out.toFile()
    file.parentFile?.mkdirs()
    file.writeText(svg, Charsets.UTF_8)
    return file
}
