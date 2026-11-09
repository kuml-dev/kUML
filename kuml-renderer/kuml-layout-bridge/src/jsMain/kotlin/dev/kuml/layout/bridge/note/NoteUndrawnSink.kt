package dev.kuml.layout.bridge.note

private var currentSink: MutableList<UndrawnNote>? = null

internal actual fun currentUndrawnSink(): MutableList<UndrawnNote>? = currentSink

internal actual fun setUndrawnSink(sink: MutableList<UndrawnNote>?) {
    currentSink = sink
}
