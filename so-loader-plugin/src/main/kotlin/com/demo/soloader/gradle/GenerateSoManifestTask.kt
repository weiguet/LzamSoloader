package com.demo.soloader.gradle

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

/**
 * Scans [soSrcDir] for .so files and generates a manifest template at [manifestFile].
 *
 * - If the manifest doesn't exist: creates it with all SOs at level 1, no deps.
 * - If it already exists: appends only newly added SOs (preserves existing entries).
 *
 * Run once when adding SOs, then hand-edit level / deps as needed.
 */
abstract class GenerateSoManifestTask : DefaultTask() {

    @get:InputDirectory
    abstract val soSrcDir: DirectoryProperty

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val srcDir  = soSrcDir.get().asFile
        val outFile = manifestFile.get().asFile

        val foundSos = srcDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".so") }
            .map { it.name }
            .sorted()
            .toList()

        if (foundSos.isEmpty()) {
            logger.warn("[so-loader] No .so files found in ${srcDir.absolutePath}")
            return
        }

        // Load existing manifest so we don't overwrite hand-edited entries
        val existing = if (outFile.exists()) parseExistingManifest(outFile.readText()) else emptyMap()

        val newEntries = foundSos.filter { it !in existing }
        if (newEntries.isEmpty() && outFile.exists()) {
            logger.lifecycle("[so-loader] Manifest already up-to-date, nothing to add.")
            return
        }

        // Merge: keep existing + append new ones at level 1
        val merged = existing.toMutableMap()
        newEntries.forEach { name ->
            merged[name] = SoEntry(level = 1, deps = emptyList())
        }

        // Rebuild the levels map (group by level)
        val levels = JsonObject()
        merged.entries
            .groupBy { it.value.level }
            .toSortedMap()
            .forEach { (level, entries) ->
                val arr = JsonArray()
                entries.forEach { (name, entry) ->
                    val obj = JsonObject()
                    obj.addProperty("name", name)
                    val depsArr = JsonArray()
                    entry.deps.forEach { depsArr.add(it) }
                    obj.add("deps", depsArr)
                    arr.add(obj)
                }
                levels.add(level.toString(), arr)
            }

        val root = JsonObject()
        root.add("levels", levels)

        outFile.parentFile.mkdirs()
        outFile.writeText(Gson().newBuilder().setPrettyPrinting().create().toJson(root))

        val added = if (newEntries.isEmpty()) "no new SOs" else "added: ${newEntries.joinToString()}"
        logger.lifecycle("[so-loader] Manifest written to ${outFile.path} ($added)")
        if (newEntries.isNotEmpty()) {
            logger.lifecycle("[so-loader] Edit the manifest to set correct level (0-3) and deps for each SO.")
        }
    }

    /** Parse existing manifest JSON into a flat map of soName → SoEntry (uses GSON, not Groovy) */
    private fun parseExistingManifest(json: String): Map<String, SoEntry> {
        val result = mutableMapOf<String, SoEntry>()
        try {
            val root   = JsonParser.parseString(json).asJsonObject
            val levels = root.getAsJsonObject("levels") ?: return result
            levels.entrySet().forEach { (levelStr, levelVal) ->
                val level = levelStr.toIntOrNull() ?: 1
                levelVal.asJsonArray.forEach { elem ->
                    val entry = elem.asJsonObject
                    val name  = entry.get("name")?.asString ?: return@forEach
                    val deps  = entry.getAsJsonArray("deps")
                        ?.map { it.asString }
                        ?: emptyList()
                    result[name] = SoEntry(level, deps)
                }
            }
        } catch (e: Exception) {
            logger.warn("[so-loader] Could not parse existing manifest, will overwrite: ${e.message}")
        }
        return result
    }

    private data class SoEntry(val level: Int, val deps: List<String>)
}
