package com.chattriggers.ctjs.engine.module

import com.chattriggers.ctjs.CTJS
import com.chattriggers.ctjs.Reference
import com.chattriggers.ctjs.console.LogType
import com.chattriggers.ctjs.console.printToConsole
import com.chattriggers.ctjs.engine.js.JSContextFactory
import com.chattriggers.ctjs.engine.js.JSLoader
import com.chattriggers.ctjs.minecraft.libs.ChatLib
import com.chattriggers.ctjs.minecraft.wrappers.World
import gg.essential.vigilance.impl.nightconfig.core.file.FileConfig
import kotlinx.serialization.json.Json
import org.apache.commons.io.FileUtils
import org.mozilla.javascript.Context
import java.io.File
import java.net.URLClassLoader

object ModuleManager {
    val cachedModules = mutableListOf<Module>()
    val modulesFolder = File(Reference.MODULES_FOLDER)
    private val pendingOldModules = mutableListOf<Module>()

    fun setup() {
        modulesFolder.mkdirs()

        // Get existing modules
        val installedModules = getFoldersInDir(modulesFolder).map(::parseModule).distinctBy {
            it.name.lowercase()
        }

        // Check if those modules have updates
        installedModules.forEach(ModuleUpdater::updateModule)
        cachedModules.addAll(installedModules)

        // Import required modules
        installedModules.distinct().forEach { module ->
            module.metadata.requires?.forEach { ModuleUpdater.importModule(it, module.name) }
        }

        loadAssetsAndJars(cachedModules)
    }

    private fun loadAssetsAndJars(modules: List<Module>) {
        // Load assets
        loadAssets(modules)

        // Normalize all metadata
        modules.forEach {
            it.metadata.entry = it.metadata.entry?.replace('/', File.separatorChar)?.replace('\\', File.separatorChar)
            it.metadata.mixinEntry = it.metadata.mixinEntry?.replace('/', File.separatorChar)?.replace('\\', File.separatorChar)
        }

        // Get all jars
        val jars = modules.map { module ->
            module.folder.walk().filter {
                it.isFile && it.extension == "jar"
            }.map {
                it.toURI().toURL()
            }.toList()
        }.flatten()

        JSLoader.setup(jars)
    }

    fun entryPass(modules: List<Module> = cachedModules, completionListener: (percentComplete: Float) -> Unit = {}) {
        JSLoader.entrySetup()

        val total = modules.count { it.metadata.entry != null }
        var completed = 0

        // Load the modules
        modules.filter {
            it.metadata.entry != null
        }.forEach {
            JSLoader.entryPass(it, File(it.folder, it.metadata.entry!!).toURI())
            completed++
            completionListener(completed.toFloat() / total)
        }
    }

    private fun getFoldersInDir(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()

        return dir.listFiles()?.filter {
            it.isDirectory
        } ?: listOf()
    }

    fun parseModule(directory: File): Module {
        val metadataFile = File(directory, "metadata.json")
        var metadata = ModuleMetadata()

        if (metadataFile.exists()) {
            try {
                // A lot of older modules have "author" instead of "creator", and it's easy enough to
                // support that here
                val text = metadataFile.readText().replace("\"author\"", "\"creator\"")
                metadata = Json.decodeFromString(text)
            } catch (e: Exception) {
                "Module $directory has invalid metadata.json".printToConsole(logType = LogType.ERROR)
            }
        }

        return Module(directory.name, metadata, directory)
    }

    data class ImportedModule(val module: Module?, val dependencies: List<Module>)

    fun importModule(moduleName: String): ImportedModule {
        val newModules = ModuleUpdater.importModule(moduleName)

        loadAssetsAndJars(newModules)

        newModules.forEach {
            if (it.metadata.mixinEntry != null)
                ChatLib.chat("&cModule ${it.name} has dynamic mixins which require a restart to take effect")
        }

        entryPass(newModules)

        return ImportedModule(newModules.getOrNull(0), newModules.drop(1))
    }

    fun deleteModule(name: String): Boolean {
        val module = cachedModules.find { it.name.lowercase() == name.lowercase() } ?: return false

        val file = File(modulesFolder, module.name)
        check(file.exists()) { "Expected module to have an existing folder!" }

        val context = JSContextFactory.enterContext()
        try {
            val classLoader = context.applicationClassLoader as URLClassLoader

            classLoader.close()

            if (file.deleteRecursively()) {
                Reference.loadCT()
                return true
            }
        } finally {
            Context.exit()
        }

        return false
    }

    fun reportOldVersions() {
        pendingOldModules.forEach(::reportOldVersion)
        pendingOldModules.clear()
    }

    fun tryReportOldVersion(module: Module) {
        if (World.isLoaded()) {
            reportOldVersion(module)
        } else {
            pendingOldModules.add(module)
        }
    }

    private fun reportOldVersion(module: Module) {
        ChatLib.chat(
            "&cWarning: the module \"${module.name}\" was made for an older version of CT, " +
                "so it may not work correctly."
        )
    }

    private fun loadAssets(modules: List<Module>) {
        modules.map {
            File(it.folder, "assets")
        }.filter {
            it.exists() && !it.isFile
        }.map {
            it.listFiles()?.toList() ?: emptyList()
        }.flatten().forEach {
            FileUtils.copyFileToDirectory(it, CTJS.assetsDir)
        }
    }

    fun teardown() {
        cachedModules.clear()
        JSLoader.clearTriggers()
    }
}
