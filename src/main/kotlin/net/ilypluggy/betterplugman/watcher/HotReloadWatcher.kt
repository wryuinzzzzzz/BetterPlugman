package net.ilypluggy.betterplugman.watcher

import net.ilypluggy.betterplugman.cfg.PluginConfig
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class HotReloadWatcher(
    private val plugin: JavaPlugin,
    private val config: PluginConfig,
    private val pluginsDirectory: File,
    private val onJarChanged: (File) -> Unit,
) {
    private val watchService = FileSystems.getDefault().newWatchService()
    private var watchKey: WatchKey? = null

    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val debounceExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "BetterPlugman-HotReload-Debounce").apply { isDaemon = true }
    }

    @Volatile
    private var running = false
    private var watcherThread: Thread? = null

    fun start() {
        if (!config.hotReloadEnabled) {
            plugin.logger.info("Hot-reload disabled in config.yml, skipping watcher startup.")
            return
        }

        val path: Path = pluginsDirectory.toPath()
        watchKey = path.register(
            watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
        )

        running = true
        watcherThread = Thread(::watchLoop, "BetterPlugman-HotReload-Watcher").apply {
            isDaemon = true
            start()
        }
        plugin.logger.info("Hot-reload watcher started on ${pluginsDirectory.path}")
    }

    fun stop() {
        running = false
        runCatching { watchKey?.cancel() }
        runCatching { watchService.close() }
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        debounceExecutor.shutdownNow()
        watcherThread?.interrupt()
    }

    private fun watchLoop() {
        while (running) {
            val key = runCatching { watchService.take() }.getOrElse {
                if (running) plugin.logger.log(Level.FINE, "Watch service interrupted", it)
                return
            }

            for (event in key.pollEvents()) {
                val kind = event.kind()
                if (kind == StandardWatchEventKinds.OVERFLOW) continue

                @Suppress("UNCHECKED_CAST")
                val changedPathEvent = event as java.nio.file.WatchEvent<Path>
                val fileName = changedPathEvent.context().toString()

                if (!fileName.endsWith(".jar")) continue
                if (fileName.lowercase() in config.hotReloadIgnore) continue

                val file = File(pluginsDirectory, fileName)
                if (isSelf(file)) continue // BetterPlugman never hot-reloads itself

                if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    pending.remove(fileName)?.cancel(false)
                    continue
                }

                scheduleDebouncedReload(fileName, file)
            }

            val valid = key.reset()
            if (!valid) {
                plugin.logger.warning("Plugins directory watch key became invalid - hot-reload stopped. " +
                        "This usually means the folder was deleted or moved.")
                running = false
            }
        }
    }

    private fun scheduleDebouncedReload(fileName: String, file: File) {
        pending[fileName]?.cancel(false)
        val future = debounceExecutor.schedule(
            debounce@{
                pending.remove(fileName)
                if (!file.exists()) return@debounce
                Bukkit.getScheduler().runTask(plugin, Runnable { onJarChanged(file) })
            },
            config.debounceMillis,
            TimeUnit.MILLISECONDS,
        )
        pending[fileName] = future
    }

    private fun isSelf(file: File): Boolean {
        val selfFile = runCatching {
            File(plugin.javaClass.protectionDomain.codeSource.location.toURI())
        }.getOrNull() ?: return false
        return selfFile.canonicalPath == file.canonicalPath
    }
}