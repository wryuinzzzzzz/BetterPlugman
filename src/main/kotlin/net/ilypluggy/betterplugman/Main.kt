package net.ilypluggy.betterplugman

import net.ilypluggy.betterplugman.cmd.PluginManagerCmd
import net.ilypluggy.betterplugman.cmd.PluginRegistry
import net.ilypluggy.betterplugman.cfg.PluginConfig
import net.ilypluggy.betterplugman.watcher.HotReloadWatcher
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import org.bukkit.Bukkit

class Main : JavaPlugin() {

    private lateinit var pluginConfig: PluginConfig
    private lateinit var registry: PluginRegistry
    private var hotReloadWatcher: HotReloadWatcher? = null

    override fun onEnable() {
        saveDefaultConfig()
        pluginConfig = PluginConfig(this)
        registry = PluginRegistry(this, pluginConfig)

        val cmd = getCommand("pm") ?: return
        val executor = PluginManagerCmd(this, registry, pluginConfig)
        cmd.setExecutor(executor)
        cmd.tabCompleter = executor

        startHotReload()

        logger.info("BetterPlugman is loaded!")
    }

    override fun onDisable() {
        hotReloadWatcher?.stop()
        registry.cleanup()
        logger.info("BetterPlugman disabled, bye bye")
    }

    fun restartHotReload() {
        hotReloadWatcher?.stop()
        startHotReload()
    }

    private fun startHotReload() {
        val pluginsDir = dataFolder.parentFile
        val watcher = HotReloadWatcher(
            plugin = this,
            config = pluginConfig,
            pluginsDirectory = pluginsDir,
        ) { changedJar ->
            handleJarChanged(changedJar)
        }
        hotReloadWatcher = watcher
        watcher.start()
    }

    private fun handleJarChanged(jarFile: File) {
        val existing = Bukkit.getPluginManager().plugins
            .find { runCatching { registry.jarFileOf(it).canonicalPath == jarFile.canonicalPath }.getOrDefault(false) }

        if (existing == null && !pluginConfig.autoLoadNewJars) {
            return
        }

        logger.info("Detected change in ${jarFile.name}, hot-reloading...")
        registry.reloadByFile(jarFile)
            .onSuccess { logger.info("Hot-reloaded ${it.name} successfully.") }
            .onFailure { logger.warning("Hot-reload failed for ${jarFile.name}: ${it.message}") }
    }
}