<h1 align="center">BetterPlugman</h1>

<p align="center">
  <img src="logo.png" alt="BetterPlugman Logo" width="650"/>
</p>

<p align="center">
  <strong>A modern, lightweight, and clean plugin manager for Paper 1.21.4 built with Kotlin</strong>
</p>

---

## 🚀 Why BetterPlugman?

* **No Legacy Code:** Built completely from scratch for the modern **Paper 1.21.4** provider architecture (`LaunchEntryPointHandler`), moving away from decade-old Spigot implementations.
* **Full Adventure API Support:** All messages leverage text components (`Component.text()`), ensuring crisp formatting and zero encoding bugs in your console or chat.
* **Proper Resource Cleanup:** Properly closes the `URLClassLoader` on unload. Say goodbye to Windows file locking issues where you couldn't replace a JAR because it was "in use by another process".
* **Live Command Sync:** Instantly updates the command map for all online players whenever a plugin is loaded or unloaded.

## 🛠 Commands

| Command | Description |
| :--- | :--- |
| `/pm load <filename.jar>` | Search and dynamically load a plugin from the `plugins/` folder. |
| `/pm unload <plugin>` | Safely unload a plugin from the server memory. |
| `/pm reload <plugin>` | Quickly reload a plugin (atomic `unload` + `load` call). |
| `/pm list` | Display an alphabetical list of plugins with color-coded status (enabled/disabled). |

## 🔒 Permissions

| Permission Node | Default | Description |
| :--- | :--- | :--- |
| `betterplugman.admin` | OP | Allows use of all BetterPlugman commands. |

## 🏗 Building the Project

The project is managed using modern Gradle (Kotlin DSL).

1. **Clone the repository:**
```bash
   git clone [https://github.com/yourusername/BetterPlugman.git](https://github.com/yourusername/BetterPlugman.git)
   cd BetterPlugman
```

## TODO
https://github.com/wryuinzzzzzz/BetterPlugman/issues/1
