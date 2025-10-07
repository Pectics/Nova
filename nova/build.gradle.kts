import org.gradle.api.artifacts.ExternalModuleDependency

plugins {
    id("nova.kotlin-conventions")
    id("nova.paper-conventions")
    id("nova.dokka-conventions")
    id("nova.publish-conventions")
    alias(libs.plugins.kotlinx.serialization)
    id("xyz.xenondevs.bundler-plugin")
}

dependencies {
    // api dependencies
    novaLoaderApi(libs.bundles.cbf)
    novaLoaderApi(libs.bundles.xenondevs.commons)
    novaLoaderApi(libs.invui.kotlin)
    novaLoaderApi(libs.joml.primitives)

    // paper provided dependencies
    paperLibrary(libs.bundles.kotlin)
    paperLibrary(libs.kotlinx.serialization.json)
    
    // internal dependencies
    compileOnly(project(":nova-api"))
    novaLoader(libs.bundles.ktor)
    novaLoader(libs.bundles.minecraft.assets)
    novaLoader(libs.bstats)
    novaLoader(libs.bytebase.runtime)
    novaLoader(libs.fuzzywuzzy)
    novaLoader(libs.awssdk.s3)
    novaLoader(libs.jimfs)
    novaLoader(libs.caffeine)
    novaLoader(libs.lz4)
    novaLoader(libs.zstd)
    novaLoader(libs.bundles.jgrapht)
    novaLoader(libs.snakeyaml.engine)
    
    // test dependencies
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit)
    testRuntimeOnly(libs.junit.platformLauncher)
}

// configure java sources location
sourceSets.main { java.setSrcDirs(listOf("src/main/kotlin/")) }

val paperLibraryCoordinates = providers.provider {
    configurations.getByName("paperLibrary").dependencies
        .filterIsInstance<ExternalModuleDependency>()
        .mapNotNull { dep ->
            val group = dep.group ?: return@mapNotNull null
            val version = dep.version ?: return@mapNotNull null
            "$group:${dep.name}:$version"
        }
        .sorted()
}

tasks {
    withType<ProcessResources>().configureEach {
        val libraries = paperLibraryCoordinates.get()
        inputs.property("paperLibraries", libraries)

        filesMatching("paper-plugin.yml") {
            val properties = HashMap(project.properties)
            properties["apiVersion"] = libs.versions.paper.get().substring(0, 4)
            val librariesBlock = if (libraries.isEmpty()) {
                "libraries: []"
            } else {
                buildString {
                    append("libraries:\n")
                    libraries.forEach { coordinate ->
                        append("  - ")
                        append(coordinate)
                        append('\n')
                    }
                }.trimEnd()
            }
            properties["paperLibrariesYaml"] = librariesBlock
            expand(properties)
        }
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
                "xyz.xenondevs.invui.ExperimentalReactiveApi"
            )
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}