package dev.kuml.renderer.theme.core

import java.util.ServiceLoader

internal actual fun loadThemeProvidersFromClasspath(): List<KumlThemeProvider> =
    ServiceLoader.load(KumlThemeProvider::class.java).toList()
