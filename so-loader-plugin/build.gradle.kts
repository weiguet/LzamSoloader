plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
    `maven-publish`
}

group   = "com.demo"
version = "1.0.0"

repositories {
    google()
    mavenCentral()
}

dependencies {
    // AGP API — compileOnly so the plugin doesn't bundle it
    compileOnly("com.android.tools.build:gradle:8.1.0")
    // GSON for JSON parsing in GenerateSoManifestTask (avoids Groovy JsonSlurper coupling)
    implementation("com.google.code.gson:gson:2.10.1")
}

gradlePlugin {
    plugins {
        create("soLoaderPlugin") {
            id                  = "com.demo.so-loader"
            implementationClass = "com.demo.soloader.gradle.SoLoaderPlugin"
            displayName         = "SO Loader Plugin"
            description         = "Automates LZMA SO packing and wires the runtime soloader library."
        }
    }
}

publishing {
    publications {
        // The gradle-plugin block already registers a MavenPublication named
        // "soLoaderPluginPluginMarkerMaven". We add the main artifact too.
        create<MavenPublication>("pluginMaven") {
            groupId    = "com.demo"
            artifactId = "so-loader-plugin"
            version    = "1.0.0"
        }
    }
    repositories {
        mavenLocal()
    }
}
