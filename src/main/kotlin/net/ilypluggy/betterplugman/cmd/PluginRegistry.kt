package net.ilypluggy.betterplugman.cmd

import net.ilypluggy.betterplugman.cfg.PluginConfig
import net.ilypluggy.betterplugman.dag.DagResult
import net.ilypluggy.betterplugman.dag.DependencyGraph
import net.ilypluggy.betterplugman.dag.PluginYmlParser
import net.ilypluggy.betterplugman.loader.JarVersionSpoofer
import net.ilypluggy.betterplugman.loader.PluginPurger
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class PluginRegistry(
    private val plugin: JavaPlugin,
    private val config: PluginConfig,
) {
    private val pluginManager get() = Bukkit.getPluginManager()
    private val purger = PluginPurger(plugin, config)
    private val ymlParser = PluginYmlParser(plugin.logger)
    private val versionSpoofer = JarVersionSpoofer(plugin.logger)
    
    /**
     * Tracks plugin names that were unloaded in the current session.
     * When version-spoofing-on-reload is enabled, these plugins will have
     * their version rewritten before being reloaded to bypass Paper's
     * duplicate identifier check.
     */
    private val unloadedInSession = mutableSetOf<String>()

    private val pluginsDirectory: File get() = plugin.dataFolder.parentFile
    private val scratchDirectory: File get() = File(plugin.dataFolder, ".reload-scratch")

    fun loadPlugin(fileName: String): Result<Plugin> = runCatching {
        val formattedName = if (fileName.endsWith(".jar")) fileName else "$fileName.jar"
        val pluginFile = File(pluginsDirectory, formattedName)

        if (!pluginFile.exists()) {
            throw IllegalArgumentException("File ${pluginFile.name} was not found in plugins folder")
        }

        if (config.autoResolveDependencies) {
            loadWithDependencies(pluginFile).getOrThrow()
        } else {
            loadSingleJar(pluginFile).getOrThrow()
        }
    }

    fun unloadPlugin(targetPlugin: Plugin): Result<Unit> = runCatching {
        if (targetPlugin == plugin) {
            throw IllegalArgumentException("You cannot unload BetterPlugman itself!")
        }

        if (targetPlugin.isEnabled) {
            pluginManager.disablePlugin(targetPlugin)
        }

        purger.purge(targetPlugin)
        
        // Track this plugin as unloaded in the current session
        unloadedInSession.add(targetPlugin.name)

        Bukkit.getOnlinePlayers().forEach { it.updateCommands() }
    }

    fun reloadPlugin(targetPlugin: Plugin): Result<Plugin> = runCatching {
        val sourceFile = jarFileOf(targetPlugin)
        unloadPlugin(targetPlugin).getOrThrow()
        loadSingleJar(sourceFile).getOrThrow()
    }

    fun reloadByFile(jarFile: File): Result<Plugin> = runCatching {
        val descriptor = ymlParser.parseJar(jarFile)
            ?: throw IllegalArgumentException("${jarFile.name} has no readable plugin.yml")

        val existing = Bukkit.getPluginManager().getPlugin(descriptor.name)
        if (existing != null) {
            unloadPlugin(existing).getOrThrow()
        }
        loadSingleJar(jarFile).getOrThrow()
    }

    fun buildFullGraph(): DependencyGraph = DependencyGraph(ymlParser.scanDirectory(pluginsDirectory))

    fun jarFileOf(targetPlugin: Plugin): File {
        val location = targetPlugin.javaClass.protectionDomain.codeSource.location.file
        return File(location)
    }

    private fun loadWithDependencies(pluginFile: File): Result<Plugin> = runCatching {
        val graph = buildFullGraph()
        val target = ymlParser.parseJar(pluginFile)
            ?: throw IllegalArgumentException("${pluginFile.name} has no readable plugin.yml")

        val resolved = graph.descriptorOf(target.name) ?: target
        val effectiveGraph = if (graph.descriptorOf(target.name) != null) graph else DependencyGraph(graph.all() + target)

        when (val result = effectiveGraph.resolveFor(resolved)) {
            is DagResult.MissingDependency -> throw IllegalStateException(
                "Cannot load ${result.plugin}: missing required dependency '${result.missing}'. " +
                        "Drop its jar into the plugins folder and try again."
            )
            is DagResult.CycleDetected -> throw IllegalStateException(
                "Circular dependency detected between: ${result.cycle.joinToString(" -> ")}"
            )
            is DagResult.Success -> {
                var loadedTarget: Plugin? = null
                for (descriptor in result.order) {
                    val alreadyLoaded = Bukkit.getPluginManager().getPlugin(descriptor.name)
                    val loaded = if (alreadyLoaded != null && alreadyLoaded.isEnabled) {
                        alreadyLoaded
                    } else {
                        loadSingleJar(descriptor.file).getOrThrow()
                    }
                    if (descriptor.key == resolved.key) loadedTarget = loaded
                }
                loadedTarget ?: throw IllegalStateException("Resolved load order never produced the target plugin")
            }
        }
    }

    private fun loadSingleJar(pluginFile: File): Result<Plugin> = runCatching {
        // Determine if we need to spoof the version
        val effectiveFile = if (shouldSpoofVersion(pluginFile)) {
            plugin.logger.info("Plugin was previously unloaded this session and version-spoofing-on-reload is enabled. Creating version-spoofed copy of ${pluginFile.name}...")
            versionSpoofer.spoofVersion(pluginFile, scratchDirectory) ?: pluginFile.also {
                plugin.logger.warning("Version spoofing failed for ${pluginFile.name}, loading original jar")
            }
        } else {
            pluginFile
        }

        val targetPlugin = pluginManager.loadPlugin(effectiveFile)
            ?: throw IllegalStateException("Error with plugin structure")

        targetPlugin.onLoad()
        pluginManager.enablePlugin(targetPlugin)
        Bukkit.getOnlinePlayers().forEach { it.updateCommands() }
        targetPlugin
    }
    
    /**
     * Returns true if this jar should have its version spoofed before loading.
     * Requires: version-spoofing-on-reload enabled, AND the plugin's name
     * appears in our unloadedInSession set (meaning we unloaded it earlier).
     */
    private fun shouldSpoofVersion(pluginFile: File): Boolean {
        if (!config.versionSpoofingOnReload) return false
        
        val descriptor = ymlParser.parseJar(pluginFile) ?: return false
        return unloadedInSession.contains(descriptor.name)
    }
    
    /**
     * Cleans up temporary version-spoofed jars. Should be called on plugin disable.
     */
    fun cleanup() {
        versionSpoofer.cleanupScratchDirectory(scratchDirectory)
    }
}