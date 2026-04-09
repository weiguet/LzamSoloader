package com.demo.soloader.gradle

import groovy.json.JsonOutput
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
        val srcDir   = soSrcDir.get().asFile
        val outFile  = manifestFile.get().asFile

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
        val levels = merged.entries
            .groupBy({ it.value.level }, { mapOf("name" to it.key, "deps" to it.value.deps) })
            .toSortedMap()
            .mapKeys { it.key.toString() }

        outFile.parentFile.mkdirs()
        outFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("levels" to levels))))

        val added = if (newEntries.isEmpty()) "no new SOs" else "added: ${newEntries.joinToString()}"
        logger.lifecycle("[so-loader] Manifest written to ${outFile.path} ($added)")
        if (newEntries.isNotEmpty()) {
            logger.lifecycle("[so-loader] Edit the manifest to set correct level (0-3) and deps for each SO.")
        }
    }

    /** Parse existing manifest JSON into a flat map of soName → SoEntry */
    private fun parseExistingManifest(json: String): Map<String, SoEntry> {
        val result = mutableMapOf<String, SoEntry>()
        try {
            // Simple regex-free parse using Groovy's JsonSlurper (available in Gradle runtime)
            @Suppress("UNCHECKED_CAST")
            val root    = groovy.json.JsonSlurper().parseText(json) as Map<String, Any>
            val levels  = root["levels"] as? Map<String, List<Map<String, Any>>> ?: return result
            levels.forEach { (levelStr, entries) ->
                val level = levelStr.toIntOrNull() ?: 1
                entries.forEach { entry ->
                    val name = entry["name"] as? String ?: return@forEach
                    @Suppress("UNCHECKED_CAST")
                    val deps = (entry["deps"] as? List<String>) ?: emptyList()
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
