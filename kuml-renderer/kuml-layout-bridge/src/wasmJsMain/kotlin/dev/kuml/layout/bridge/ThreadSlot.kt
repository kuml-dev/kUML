package dev.kuml.layout.bridge

internal actual class ThreadSlot<T : Any> actual constructor() {
    private var value: T? = null

    actual fun get(): T? = value

    actual fun set(value: T?) {
        this.value = value
    }
}
