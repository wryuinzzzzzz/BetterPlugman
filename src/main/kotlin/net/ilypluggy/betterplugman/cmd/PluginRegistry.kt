package net.ilypluggy.betterplugman.cmd

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.net.URLClassLoader
import java.util.logging.Level

class PluginRegistry(private val plugin: JavaPlugin) {

    private val pluginManager = Bukkit.getPluginManager()

    fun loadPlugin(fileName: String): Result<Plugin> = runCatching {
        val pluginDirectory = plugin.dataFolder.parentFile
        val formattedName = if (fileName.endsWith(".jar")) fileName else "$fileName.jar"
        val pluginFile = File(pluginDirectory, formattedName)

        if (!pluginFile.exists()) {
            throw IllegalArgumentException("File ${pluginFile.name} was not found in plugins folder")
        }

        val targetPlugin = pluginManager.loadPlugin(pluginFile)
            ?: throw IllegalStateException("Error with plugin structure")

        targetPlugin.onLoad()
        pluginManager.enablePlugin(targetPlugin)
        Bukkit.getOnlinePlayers().forEach { it.updateCommands() }
        targetPlugin
    }

    fun unloadPlugin(targetPlugin: Plugin): Result<Unit> = runCatching {
        if (targetPlugin == plugin) {
            throw IllegalArgumentException("You cannot unload BetterPlugman itself!")
        }

        if (targetPlugin.isEnabled) {
            pluginManager.disablePlugin(targetPlugin)
        }

        runCatching {
            val paperPluginManagerClass = Class.forName("io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler")
            val handlerField = paperPluginManagerClass.getDeclaredField("INSTANCE").apply { isAccessible = true }
            val handler = handlerField.get(null)

            val storageField = handler.javaClass.getDeclaredField("storage").apply { isAccessible = true }
            val storage = storageField.get(handler)

            val pluginsMapField = storage.javaClass.getDeclaredField("plugins").apply { isAccessible = true }
            val pluginsMap = pluginsMapField.get(storage) as? MutableMap<*, *>
            pluginsMap?.remove(targetPlugin.name.lowercase())
        }.onFailure {
            plugin.logger.log(Level.WARNING, "Failed to remove plugin from Paper storage fallback to Bukkit", it)
        }

        runCatching {
            val bukkitPm = pluginManager
            val pluginsField = bukkitPm.javaClass.getDeclaredField("plugins").apply { isAccessible = true }
            val lookupNamesField = bukkitPm.javaClass.getDeclaredField("lookupNames").apply { isAccessible = true }
            val plugins = pluginsField.get(bukkitPm) as? MutableList<*>
            val lookupNames = lookupNamesField.get(bukkitPm) as? MutableMap<*, *>
            plugins?.remove(targetPlugin)
            lookupNames?.remove(targetPlugin.name.lowercase())
        }

        val classLoader = targetPlugin.javaClass.classLoader
        if (classLoader is URLClassLoader) {
            runCatching {
                classLoader.close()
            }.onFailure {
                plugin.logger.log(Level.SEVERE, "Could not close ClassLoader for ${targetPlugin.name}", it)
            }
        }

        System.gc()
        Bukkit.getOnlinePlayers().forEach { it.updateCommands() }
    }
    fun reloadPlugin(targetPlugin: Plugin): Result<Plugin> = runCatching {
        val fileName = targetPlugin.javaClass.protectionDomain.codeSource.location.file
        val file = File(fileName)
        val jarName = file.name
        unloadPlugin(targetPlugin).getOrThrow()
        loadPlugin(jarName).getOrThrow()
    }
}