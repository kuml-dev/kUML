package dev.kuml.render.smil

/**
 * SVG transform type for [SmilAnimation.AnimateTransform].
 *
 * Maps to the SMIL `type` attribute on `<animateTransform>`.
 */
public enum class TransformType(
    public val svgToken: String,
) {
    TRANSLATE("translate"),
    SCALE("scale"),
    ROTATE("rotate"),
    SKEW_X("skewX"),
    SKEW_Y("skewY"),
}
