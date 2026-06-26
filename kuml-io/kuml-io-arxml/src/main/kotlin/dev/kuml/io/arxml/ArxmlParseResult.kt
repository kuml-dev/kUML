package dev.kuml.io.arxml

import dev.kuml.uml.UmlPackage

/**
 * Result of parsing an AUTOSAR ARXML file.
 *
 * The AUTOSAR `AUTOSAR/AR-PACKAGES/AR-PACKAGE` tree maps onto nested
 * [UmlPackage] instances because the kUML metamodel has no top-level `UmlModel`
 * type. Each AR-PACKAGE becomes a [UmlPackage] whose [UmlPackage.members] contain
 * the classified elements (SWCs, interfaces, runnables) and nested sub-packages.
 *
 * @property rootPackage  Top-level package representing the root AR-PACKAGES container.
 * @property version      Detected (or configured) AUTOSAR schema release.
 * @property warnings     Non-fatal parse warnings (unknown elements, missing schemaLocation, etc.).
 *
 * V3.1.33 — initial implementation.
 */
public data class ArxmlParseResult(
    val rootPackage: UmlPackage,
    val version: ArxmlVersion,
    val warnings: List<String> = emptyList(),
)
