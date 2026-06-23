package net.ilypluggy.betterplugman.loader

import net.ilypluggy.betterplugman.cfg.PluginConfig
import org.bukkit.Bukkit
import org.bukkit.command.PluginCommand
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.SimplePluginManager
import org.bukkit.plugin.java.JavaPlugin
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.logging.Level
import java.util.logging.Logger


class PluginPurger(
    private val host: JavaPlugin,
    private val config: PluginConfig,
) {
    private val logger: Logger get() = host.logger

    fun purge(target: Plugin) {
        cancelScheduledTasks(target)
        unregisterListeners(target)
        unregisterCommands(target)
        removeFromPluginManagerInternals(target)

        if (config.nullStaticFields) {
            nullOutStaticFields(target)
        }

        closeClassLoader(target)

        if (config.suggestGc) {
            System.gc()
        }
    }

    private fun cancelScheduledTasks(target: Plugin) {
        runCatching {
            Bukkit.getScheduler().cancelTasks(target)
        }.onFailure { logger.log(Level.WARNING, "Failed to cancel tasks for ${target.name}", it) }
    }

    private fun unregisterListeners(target: Plugin) {
        runCatching {
            HandlerList.unregisterAll(target)
        }.onFailure { logger.log(Level.WARNING, "Failed to unregister listeners for ${target.name}", it) }
    }

    private fun unregisterCommands(target: Plugin) {
        if (!config.aggressiveCleanup) return

        runCatching {
            val commandMap = liveCommandMap()
            val knownCommandsField = findField(commandMap.javaClass, "knownCommands") ?: return
            knownCommandsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val knownCommands = knownCommandsField.get(commandMap) as? MutableMap<String, org.bukkit.command.Command>
                ?: return

            val toRemove = knownCommands.filterValues { cmd ->
                (cmd is PluginCommand && cmd.plugin == target) ||
                        cmd.javaClass.classLoader === target.javaClass.classLoader
            }.keys.toList()

            toRemove.forEach { knownCommands.remove(it) }

            if (toRemove.isNotEmpty()) {
                Bukkit.getOnlinePlayers().forEach { it.updateCommands() }
            }
        }.onFailure { logger.log(Level.WARNING, "Failed to unregister commands for ${target.name}", it) }
    }

    private fun liveCommandMap(): org.bukkit.command.CommandMap {
        return runCatching { Bukkit.getServer().commandMap }.getOrNull()
            ?: run {
                val pm = Bukkit.getPluginManager()
                val field = findField(pm.javaClass, "commandMap")
                    ?: throw NoSuchFieldException("commandMap not found on ${pm.javaClass}")
                field.isAccessible = true
                field.get(pm) as org.bukkit.command.CommandMap
            }
    }

    private fun removeFromPluginManagerInternals(target: Plugin) {
        if (!config.aggressiveCleanup) return
        runCatching {
            val handlerClass = Class.forName("io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler")
            val instanceField = handlerClass.getDeclaredField("INSTANCE").apply { isAccessible = true }
            val handler = instanceField.get(null)

            val storageField = findField(handler.javaClass, "storage") ?: return@runCatching
            storageField.isAccessible = true
            val storage = storageField.get(handler)

            val pluginsField = findField(storage.javaClass, "plugins") ?: return@runCatching
            pluginsField.isAccessible = true
            val pluginsMap = pluginsField.get(storage) as? MutableMap<*, *>
            pluginsMap?.remove(target.name.lowercase())
        }.onFailure {
            logger.log(Level.FINE, "Paper internal plugin storage not present/changed shape, skipping", it)
        }

        runCatching {
            val pm = Bukkit.getPluginManager()
            if (pm !is SimplePluginManager) return@runCatching

            val pluginsField = findField(pm.javaClass, "plugins")
            val lookupNamesField = findField(pm.javaClass, "lookupNames")

            pluginsField?.let {
                it.isAccessible = true
                (it.get(pm) as? MutableList<*>)?.remove(target)
            }
            lookupNamesField?.let {
                it.isAccessible = true
                (it.get(pm) as? MutableMap<*, *>)?.remove(target.name.lowercase())
            }
        }.onFailure { logger.log(Level.FINE, "Bukkit SimplePluginManager internals not present/changed shape, skipping", it) }
    }

    private fun nullOutStaticFields(target: Plugin) {
        val loader = target.javaClass.classLoader
        val classes = collectLoadedClasses(loader)

        for (clazz in classes) {
            for (field in clazz.declaredFields) {
                val mods = field.modifiers
                if (!Modifier.isStatic(mods)) continue
                if (Modifier.isFinal(mods)) continue
                if (field.type.isPrimitive) continue

                runCatching {
                    field.isAccessible = true
                    field.set(null, null)
                }.onFailure {
                    logger.log(Level.FINEST, "Could not null static field ${clazz.name}#${field.name}", it)
                }
            }
        }
    }

    private fun collectLoadedClasses(loader: ClassLoader): List<Class<*>> {
        return runCatching {
            val classesField = ClassLoader::class.java.getDeclaredField("classes").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val vector = classesField.get(loader) as java.util.Vector<Class<*>>
            synchronized(vector) { vector.toList() }
        }.getOrElse {
            logger.log(Level.FINE, "Could not enumerate classes for classloader (JVM internals changed?)", it)
            emptyList()
        }
    }

    private fun closeClassLoader(target: Plugin) {
        val loader = target.javaClass.classLoader
        if (loader !is URLClassLoader) return

        runCatching {
            loader.close()
        }.onFailure { logger.log(Level.SEVERE, "Could not close ClassLoader for ${target.name}", it) }
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching { return current.getDeclaredField(name) }
            current = current.superclass
        }
        return null
    }
}