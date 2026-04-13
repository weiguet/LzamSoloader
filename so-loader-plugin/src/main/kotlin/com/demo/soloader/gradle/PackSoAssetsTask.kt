package com.demo.soloader.gradle

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecSpec

abstract class PackSoAssetsTask : DefaultTask() {

    @get:InputDirectory
    abstract val soSrcDir: DirectoryProperty

    @get:InputFile
    abstract val manifestFile: RegularFileProperty

    @get:Input
    abstract val algorithm: Property<String>

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun pack() {
        // Extract the bundled pack_so.py from plugin resources to a temp file
        val scriptBytes = PackSoAssetsTask::class.java
            .getResourceAsStream("/pack_so.py")
            ?: error("[so-loader] pack_so.py not found in plugin jar")

        val tempScript = temporaryDir.resolve("pack_so.py")
        tempScript.outputStream().use { scriptBytes.copyTo(it) }

        val result = project.exec(Action<ExecSpec> {
            commandLine(
                "python3",
                tempScript.absolutePath,
                "--so-dir",   soSrcDir.get().asFile.absolutePath,
                "--manifest", manifestFile.get().asFile.absolutePath,
                "--out",      outDir.get().asFile.absolutePath,
                "--algo",     algorithm.get()
            )
            isIgnoreExitValue = true
        })
        if (result.exitValue != 0) {
            throw org.gradle.api.GradleException(
                "[so-loader] pack_so.py exited with code ${result.exitValue}. " +
                "Check that python3 is on PATH and the manifest is valid."
            )
        }
    }
}
