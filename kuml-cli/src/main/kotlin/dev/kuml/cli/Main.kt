package dev.kuml.cli

import com.github.ajalt.clikt.core.main as cliktMain

/**
 * Entry point for the kUML CLI.
 *
 * Delegates to [KumlCli] which registers all subcommands.
 */
public fun main(args: Array<String>): Unit = KumlCli().cliktMain(args)
