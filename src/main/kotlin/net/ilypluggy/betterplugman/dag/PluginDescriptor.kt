package net.ilypluggy.betterplugman.dag

import java.io.File

data class PluginDescriptor(
    val name: String,
    val file: File,
    val depend: List<String> = emptyList(),
    val softDepend: List<String> = emptyList(),
    val loadBefore: List<String> = emptyList(),
) {
    /** Case-insensitive key used to identify this node in the graph. */
    val key: String get() = name.lowercase()
}