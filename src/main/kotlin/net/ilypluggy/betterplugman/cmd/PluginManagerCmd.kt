package net.ilypluggy.betterplugman.cmd

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import kotlin.collections.emptyList

class PluginManagerCmd(private val plugin: JavaPlugin) : CommandExecutor, TabCompleter {
    private val registry = PluginRegistry(plugin)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("betterplugman.admin")) {
            sender.sendMessage(Component.text("u dont have permission", NamedTextColor.RED))
            return true
        }

        if (args.size < 2) {
            sendHelp(sender)
            return true
        }

        val action = args[0].lowercase()
        val target = args[1]

        when (action) {
            "load" -> {
                sender.sendMessage(Component.text("try to load $target...", NamedTextColor.GRAY))
                registry.loadPlugin(target)
                    .onSuccess { sender.sendMessage(Component.text("plugin ${it.name} successfully loaded and enabled!", NamedTextColor.GREEN)) }
                    .onFailure { sender.sendMessage(Component.text("loading error: ${it.message}", NamedTextColor.RED)) }
            }
            "unload" -> {
                val targetPlugin = Bukkit.getPluginManager().getPlugin(target)
                if (targetPlugin == null) {
                    sender.sendMessage(Component.text("plugin '$target' doesnt founded ", NamedTextColor.RED))
                    return true
                }

                sender.sendMessage(Component.text("try to unload ${targetPlugin.name}...", NamedTextColor.GRAY))
                registry.unloadPlugin(targetPlugin)
                    .onSuccess { sender.sendMessage(Component.text("plugin $target successfully unloaded!", NamedTextColor.GREEN)) }
                    .onFailure { sender.sendMessage(Component.text("unload error: ${it.message}", NamedTextColor.RED)) }
            }
            "reload" -> {
                if (args.size < 2) {
                    sender.sendMessage(Component.text("Using: /pm reload <plugin>", NamedTextColor.RED))
                    return true
                }
                val targetPlugin = Bukkit.getPluginManager().getPlugin(args[1])
                if (targetPlugin == null) {
                    sender.sendMessage(Component.text("plugin '${args[1]}' doesn't founded.", NamedTextColor.RED))
                    return true
                }
                sender.sendMessage(Component.text("Перезагрузка ${targetPlugin.name}...", NamedTextColor.GRAY))
                registry.reloadPlugin(targetPlugin)
                    .onSuccess { sender.sendMessage(Component.text("plugin ${it.name} successfully reloaded!", NamedTextColor.GREEN)) }
                    .onFailure { sender.sendMessage(Component.text("error: ${it.message}", NamedTextColor.RED)) }
            }
            "list" -> {
                val plugins = Bukkit.getPluginManager().plugins.sortedBy { it.name }
                val component = Component.text("plugins (${plugins.size}): ", NamedTextColor.GOLD)

                val listBuilder = Component.text()
                plugins.forEachIndexed { index, p ->
                    val color = if (p.isEnabled) NamedTextColor.GREEN else NamedTextColor.RED
                    listBuilder.append(Component.text(p.name, color))
                    if (index < plugins.size - 1) {
                        listBuilder.append(Component.text(", ", NamedTextColor.WHITE))
                    }
                }
                sender.sendMessage(component.append(listBuilder.build()))
            }
            else -> sendHelp(sender)
        }
        return true
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(Component.text("Using BetterPlugman:", NamedTextColor.GOLD))
        sender.sendMessage(Component.text("» /pm load <File_Name> — load .jar file", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("» /pm unload <plugin> — unload plugin", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("» /pm reload <plugin> — reload a plugin", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("» /pm list — list of all plugins", NamedTextColor.YELLOW))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("betterplugman.admin")) return emptyList()

        return when (args.size) {
            1 -> listOf("load", "unload", "reload", "list").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> when (args[0].lowercase()) {
                "unload", "reload" -> {
                    Bukkit.getPluginManager().plugins
                        .map { it.name }
                        .filter { it.startsWith(args[1], ignoreCase = true) }
                }
                "load" -> {
                    val pluginsDir = plugin.dataFolder.parentFile
                    val files = pluginsDir.listFiles()

                    if (files != null) {
                        files.filter { it.isFile && it.name.endsWith(".jar") }
                            .map { it.name }
                            .filter { it.startsWith(args[1], ignoreCase = true) }
                    } else {
                        emptyList()
                    }
                }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}