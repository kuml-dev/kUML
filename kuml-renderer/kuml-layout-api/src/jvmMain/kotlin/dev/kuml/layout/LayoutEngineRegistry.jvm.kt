package dev.kuml.layout

import java.util.ServiceLoader

internal actual fun loadProvidersFromClasspath(): List<KumlLayoutEngineProvider> =
    ServiceLoader.load(KumlLayoutEngineProvider::class.java).toList()
