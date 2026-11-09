package dev.kuml.layout.bridge.note

private val SINK = ThreadLocal<MutableList<UndrawnNote>?>()

internal actual fun currentUndrawnSink(): MutableList<UndrawnNote>? = SINK.get()

internal actual fun setUndrawnSink(sink: MutableList<UndrawnNote>?) {
    if (sink == null) SINK.remove() else SINK.set(sink)
}
