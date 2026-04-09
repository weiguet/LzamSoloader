package com.demo.soloader.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL extension exposed to consuming projects:
 *
 * ```groovy
 * soLoader {
 *     soSrcDir     = file("tools/so_src/arm64-v8a")
 *     manifestFile = file("tools/so_manifest.json")
 *     outDir       = file("src/main/assets/so")   // optional
 *     algorithm    = "lzma"                         // optional
 * }
 * ```
 *
 * pack_so.py is bundled inside the plugin jar — no need to ship it separately.
 */
abstract class SoLoaderExtension @Inject constructor(objects: ObjectFactory) {

    /** Directory containing the raw .so files to compress. */
    val soSrcDir: DirectoryProperty = objects.directoryProperty()

    /** so_manifest.json describing levels, deps, etc. */
    val manifestFile: RegularFileProperty = objects.fileProperty()

    /**
     * Output directory inside assets.
     * Defaults to `<project>/src/main/assets/so`.
     */
    val outDir: DirectoryProperty = objects.directoryProperty()

    /** Compression algorithm: "lzma" or "gzip". Defaults to "lzma". */
    val algorithm: Property<String> = objects.property(String::class.java)
        .convention("lzma")
}
