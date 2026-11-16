package dev.kuml.layout.bridge.note

private val SINK = ThreadLocal<MutableList<PlacementHintMiss>?>()

internal actual fun currentPlacementMissSink(): MutableList<PlacementHintMiss>? = SINK.get()

internal actual fun setPlacementMissSink(sink: MutableList<PlacementHintMiss>?) {
    if (sink == null) SINK.remove() else SINK.set(sink)
}
