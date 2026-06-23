package net.ilypluggy.betterplugman.dag

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.jar.JarFile
import java.util.logging.Level
import java.util.logging.Logger

class PluginYmlParser(private val logger: Logger) {

    private val yaml = Yaml()

    /**
     * Scans [directory] for .jar files and parses their plugin.yml.
     * Jars that fail to parse js skippping
     */
    fun scanDirectory(directory: File): List<PluginDescriptor> {
        val jars = directory.listFiles { f -> f.isFile && f.name.endsWith(".jar") } ?: return emptyList()
        return jars.mapNotNull { jar ->
            runCatching { parseJar(jar) }
                .onFailure { logger.log(Level.FINE, "Skipping ${jar.name}: not a readable plugin jar", it) }
                .getOrNull()
        }
    }

    fun parseJar(jarFile: File): PluginDescriptor? {
        JarFile(jarFile).use { jar ->
            val entry = jar.getJarEntry("plugin.yml") ?: jar.getJarEntry("paper-plugin.yml") ?: return null
            val data: Map<*, *> = jar.getInputStream(entry).use { stream ->
                yaml.load(stream) ?: return null
            }

            val name = (data["name"] as? String)?.trim() ?: return null

            return PluginDescriptor(
                name = name,
                file = jarFile,
                depend = data.stringList("depend"),
                softDepend = data.stringList("softdepend"),
                loadBefore = data.stringList("loadbefore"),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.stringList(key: String): List<String> {
        val raw = this[key] ?: return emptyList()
        return when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString() }
            is String -> listOf(raw)
            else -> emptyList()
        }
    }
}