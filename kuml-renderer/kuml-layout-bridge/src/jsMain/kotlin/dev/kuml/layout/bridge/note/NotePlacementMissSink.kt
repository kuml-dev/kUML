package dev.kuml.layout.bridge.note

private var currentSink: MutableList<PlacementHintMiss>? = null

internal actual fun currentPlacementMissSink(): MutableList<PlacementHintMiss>? = currentSink

internal actual fun setPlacementMissSink(sink: MutableList<PlacementHintMiss>?) {
    currentSink = sink
}
