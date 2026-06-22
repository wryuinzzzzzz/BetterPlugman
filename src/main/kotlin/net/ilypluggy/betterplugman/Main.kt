package net.ilypluggy.betterplugman

import net.ilypluggy.betterplugman.cmd.PluginManagerCmd
import org.bukkit.plugin.java.JavaPlugin

class Main : JavaPlugin() {

    override fun onEnable() {
        val cmd = getCommand("pm") ?: return
        val executor = PluginManagerCmd(this)
        cmd.setExecutor(executor)
        cmd.tabCompleter = executor

        logger.info("BetterPlugman is loaded!")
    }

    override fun onDisable() {
        logger.info("BetterPlugman disabled, bye bye")
    }
}