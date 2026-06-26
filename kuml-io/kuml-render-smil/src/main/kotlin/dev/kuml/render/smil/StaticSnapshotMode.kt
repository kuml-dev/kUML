package dev.kuml.render.smil

/**
 * Controls whether SMIL animation elements are included in SVG output.
 *
 * - [ANIMATED]: inject SMIL elements into the SVG (default).
 * - [STRIPPED]: remove all SMIL elements from the SVG — suitable for PNG rendering and snapshot tests.
 */
public enum class StaticSnapshotMode {
    ANIMATED,
    STRIPPED,
}
