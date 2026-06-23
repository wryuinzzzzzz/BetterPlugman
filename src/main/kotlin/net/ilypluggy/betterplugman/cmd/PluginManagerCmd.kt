package net.ilypluggy.betterplugman.cmd

import net.ilypluggy.betterplugman.cfg.PluginConfig
import net.ilypluggy.betterplugman.dag.DagResult
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import kotlin.collections.emptyList

class PluginManagerCmd(
    private val plugin: JavaPlugin,
    private val registry: PluginRegistry,
    private val config: PluginConfig,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("betterplugman.admin")) {
            sender.sendMessage(Component.text("u dont have permission", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        val action = args[0].lowercase()

        when (action) {
            "load" -> {
                if (args.size < 2) {
                    sender.sendMessage(Component.text("Using: /pm load <File_Name>", NamedTextColor.RED))
                    return true
                }
                val target = args[1]
                sender.sendMessage(Component.text("try to load $target (resolving dependencies)...", NamedTextColor.GRAY))
                registry.loadPlugin(target)
                    .onSuccess { sender.sendMessage(Component.text("plugin ${it.name} successfully loaded and enabled!", NamedTextColor.GREEN)) }
                    .onFailure { sender.sendMessage(Component.text("loading error: ${it.message}", NamedTextColor.RED)) }
            }
            "unload" -> {
                if (args.size < 2) {
                    sender.sendMessage(Component.text("Using: /pm unload <plugin>", NamedTextColor.RED))
                    return true
                }
                val target = args[1]
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
            "graph" -> sendDependencyGraph(sender)
            "config" -> {
                if (args.size < 2 || args[1].lowercase() != "reload") {
                    sender.sendMessage(Component.text("Using: /pm config reload", NamedTextColor.RED))
                    return true
                }
                config.reload()
                sender.sendMessage(Component.text("config.yml reloaded.", NamedTextColor.GREEN))
            }
            else -> sendHelp(sender)
        }
        return true
    }

    /** `/pm graph` - shows the resolved load order (or the error blocking it) for everything in the plugins folder. */
    private fun sendDependencyGraph(sender: CommandSender) {
        val graph = registry.buildFullGraph()
        when (val result = graph.resolve()) {
            is DagResult.Success -> {
                sender.sendMessage(Component.text("Resolved load order (${result.order.size} plugins):", NamedTextColor.GOLD))
                result.order.forEachIndexed { index, descriptor ->
                    val loaded = Bukkit.getPluginManager().getPlugin(descriptor.name) != null
                    val color = if (loaded) NamedTextColor.GREEN else NamedTextColor.GRAY
                    sender.sendMessage(Component.text("  ${index + 1}. ${descriptor.name}", color))
                }
            }
            is DagResult.MissingDependency -> {
                sender.sendMessage(
                    Component.text(
                        "Cannot resolve graph: '${result.plugin}' requires missing dependency '${result.missing}'.",
                        NamedTextColor.RED,
                    )
                )
            }
            is DagResult.CycleDetected -> {
                sender.sendMessage(
                    Component.text(
                        "Circular dependency detected: ${result.cycle.joinToString(" -> ")}",
                        NamedTextColor.RED,
                    )
                )
            }
        }
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(Component.text("Using BetterPlugman:", NamedTextColor.GOLD))
        sender.sendMessage(Component.text("» /pm load <File_Name> — load .jar file (auto-resolves dependencies)", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("» /pm unload <plugin> — unload plugin", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("» /pm reload <plugin> — reload a plugin", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("» /pm list — list of all plugins", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("» /pm graph — show resolved dependency load order", NamedTextColor.YELLOW))
        sender.sendMessage(Component.text("» /pm config reload — reload config.yml", NamedTextColor.YELLOW))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("betterplugman.admin")) return emptyList()

        return when (args.size) {
            1 -> listOf("load", "unload", "reload", "list", "graph", "config")
                .filter { it.startsWith(args[0], ignoreCase = true) }
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
                "config" -> listOf("reload").filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}