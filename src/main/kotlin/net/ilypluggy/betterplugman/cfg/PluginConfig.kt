package net.ilypluggy.betterplugman.cfg

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin

class PluginConfig(private val plugin: JavaPlugin) {

    private val cfg: FileConfiguration get() = plugin.config

    val hotReloadEnabled: Boolean get() = cfg.getBoolean("hot-reload.enabled", true)
    val debounceMillis: Long get() = cfg.getLong("hot-reload.debounce-millis", 1500L)
    val autoLoadNewJars: Boolean get() = cfg.getBoolean("hot-reload.auto-load-new-jars", true)
    val hotReloadIgnore: Set<String> get() = cfg.getStringList("hot-reload.ignore").map { it.lowercase() }.toSet()

    val aggressiveCleanup: Boolean get() = cfg.getBoolean("cleanup.aggressive", true)
    val nullStaticFields: Boolean get() = cfg.getBoolean("cleanup.null-static-fields", true)
    val suggestGc: Boolean get() = cfg.getBoolean("cleanup.suggest-gc", true)
    val versionSpoofingOnReload: Boolean get() = cfg.getBoolean("cleanup.version-spoofing-on-reload", false)

    val autoResolveDependencies: Boolean get() = cfg.getBoolean("dag.auto-resolve-dependencies", true)

    fun reload() {
        plugin.reloadConfig()
    }
}