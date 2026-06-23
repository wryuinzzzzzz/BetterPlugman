package net.ilypluggy.betterplugman.loader

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Works around a known Paper/Leaf limitation: `ModernPluginLoadingStrategy`
 * registers every loaded plugin's `name + version` as a permanent identifier
 * in `ServerPluginProviderStorage`, and there is currently no supported (or
 * even reliably reflectable - this has been tried) way to remove that entry
 * once a plugin is unloaded. Loading the exact same name+version a second
 * time in the same server session always throws:
 *
 *   "attempted to add duplicate plugin identifier <Name> v<Version> THIS WILL CREATE BUGS!!!"
 *
 * even though the plugin was cleanly disabled and purged. This is not a bug
 * in BetterPlugman's own teardown logic - it's an upstream Paper limitation
 * that other plugin managers (e.g. PlugManX) hit on identical Paper/Leaf
 * builds. See: https://github.com/Test-Account666/PlugManX/issues/44
 *
 * The workaround: when reloading a plugin that BetterPlugman itself unloaded
 * earlier in this server session, copy the jar into a scratch file and
 * rewrite just the `version:` field inside its `plugin.yml`/`paper-plugin.yml`
 * before loading the copy. A different identifier means Paper's duplicate
 * check never triggers. Everything else about the jar (classes, resources,
 * manifest) is byte-for-byte untouched.
 *
 * This is opt-in via `cleanup.version-spoofing-on-reload` in config.yml,
 * defaulting to OFF, because it does mean `getDescription().getVersion()`
 * on the reloaded instance will report a synthetic value
 * (e.g. `3.3.3+reload-1719500000000`) rather than the jar's real version.
 * Most plugins never check this at runtime, but a few version-gate features
 * on it, so this is a deliberate trade-off the server owner opts into.
 */
class JarVersionSpoofer(private val logger: Logger) {

    private val yaml = Yaml(
        DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        }
    )

    /**
     * Produces a copy of [originalJar] in [scratchDirectory] with its
     * `version:` field suffixed by a unique tag, so the resulting jar
     * has a different name+version identifier than whatever Paper already
     * has on record for the original. Returns the new file, or null if the
     * jar couldn't be parsed/rewritten (caller should fall back to loading
     * the original jar as-is in that case).
     */
    fun spoofVersion(originalJar: File, scratchDirectory: File): File? {
        return runCatching {
            scratchDirectory.mkdirs()
            val outputFile = File(scratchDirectory, "${originalJar.nameWithoutExtension}-reload-${System.nanoTime()}.jar")

            JarFile(originalJar).use { input ->
                val ymlEntryName = sequenceOf("plugin.yml", "paper-plugin.yml")
                    .firstOrNull { input.getJarEntry(it) != null }
                    ?: return null // no descriptor found, nothing we can rewrite

                val descriptorEntry = input.getJarEntry(ymlEntryName)
                val rawYaml = input.getInputStream(descriptorEntry).use { it.readBytes() }
                val data: MutableMap<String, Any?> = (yaml.load(rawYaml.inputStream()) as? Map<*, *>)
                    ?.entries?.associate { it.key.toString() to it.value }?.toMutableMap()
                    ?: return null

                val originalVersion = data["version"]?.toString() ?: "0"
                val spoofedVersion = "$originalVersion+reload-${System.currentTimeMillis()}"
                data["version"] = spoofedVersion

                val newYamlBytes = yaml.dump(data).toByteArray()

                JarOutputStream(FileOutputStream(outputFile)).use { jos ->
                    val entries = input.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name == ymlEntryName) {
                            jos.putNextEntry(JarEntry(entry.name))
                            jos.write(newYamlBytes)
                            jos.closeEntry()
                        } else {
                            jos.putNextEntry(JarEntry(entry.name).apply { time = entry.time })
                            if (!entry.isDirectory) {
                                input.getInputStream(entry).use { it.copyTo(jos) }
                            }
                            jos.closeEntry()
                        }
                    }
                }
            }

            outputFile
        }.onFailure {
            logger.log(Level.WARNING, "Could not spoof version for ${originalJar.name}, will fall back to loading the original jar", it)
        }.getOrNull()
    }

    fun cleanupScratchDirectory(scratchDirectory: File) {
        runCatching {
            scratchDirectory.listFiles()?.forEach { it.delete() }
        }.onFailure { logger.log(Level.FINE, "Could not clean up version-spoof scratch directory", it) }
    }
}