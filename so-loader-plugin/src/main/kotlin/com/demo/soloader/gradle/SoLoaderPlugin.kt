package com.demo.soloader.gradle

import com.android.build.gradle.AppExtension
import com.android.build.gradle.api.ApplicationVariant
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task

class SoLoaderPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("soLoader", SoLoaderExtension::class.java)

        ext.outDir.convention(
            project.layout.projectDirectory.dir("src/main/assets/so")
        )

        // Register generateSoManifest immediately (doesn't need afterEvaluate)
        project.tasks.register(
            "generateSoManifest",
            GenerateSoManifestTask::class.java,
            Action<GenerateSoManifestTask> {
                group       = "so-loader"
                description = "Scan soSrcDir and generate/update so_manifest.json template"
                soSrcDir.set(ext.soSrcDir)
                manifestFile.set(ext.manifestFile)
            }
        )

        project.afterEvaluate {
            val android = project.extensions.findByType(AppExtension::class.java)
                ?: error("[so-loader] Plugin must be applied to an Android application module.")

            @Suppress("DEPRECATION")
            android.aaptOptions.noCompress("lzma", "so")
            android.buildFeatures.buildConfig = true

            // kotlin-dsl maps Action<T> → T.() -> Unit, so lambdas use implicit `this`
            android.applicationVariants.all(Action<ApplicationVariant> {
                // `this` is ApplicationVariant
                val variant = this
                val cap = variant.name.replaceFirstChar { it.uppercaseChar() }

                val packTask = project.tasks.register(
                    "packSoAssets${cap}",
                    PackSoAssetsTask::class.java,
                    Action<PackSoAssetsTask> {
                        // `this` is PackSoAssetsTask; capture `variant` from outer scope
                        group       = "so-loader"
                        description = "Pack SO assets for ${variant.name}"
                        soSrcDir.set(ext.soSrcDir)
                        manifestFile.set(ext.manifestFile)
                        algorithm.set(ext.algorithm)
                        outDir.set(ext.outDir)
                    }
                )

                project.tasks.named("merge${cap}Assets").configure(Action<Task> {
                    dependsOn(packTask)
                })

                // Lint reads src/main/assets, so it must run after pack to avoid
                // Gradle implicit-dependency warnings.
                project.tasks.matching { it.name.contains("lint", ignoreCase = true) && it.name.contains(cap) }
                    .configureEach(Action<Task> {
                        mustRunAfter(packTask)
                    })
            })

            project.dependencies.add("implementation", "com.demo:soloader:1.0.0")
        }
    }
}
