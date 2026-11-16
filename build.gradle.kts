import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.0"
    id("fabric-loom") version "1.17-SNAPSHOT"
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 21
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

dependencies {
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    // HHA-Basis-Mod (Addon-API, Telegraph, Vfx, Cine). Das Jar ist der Build des lokalen HHA-Stands
    // (Branch "new", Boss-Überarbeitung) und liegt in libs/.
    modImplementation(files(project.property("hha_jar") as String))
}

tasks.processResources {
    inputs.property("version", project.version)
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand(
            mapOf(
                "version" to project.version.toString(),
                "minecraft_version" to project.property("minecraft_version").toString(),
                "loader_version" to project.property("loader_version").toString(),
                "kotlin_loader_version" to project.property("kotlin_loader_version").toString(),
            ),
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
    from("LICENSE.txt") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

// Start the real client to catch entrypoint/loading failures that compilation cannot detect.
fabricApi {
    configureTests {
        createSourceSet.set(true)
        modId.set("hha_arius-gametest")
        enableGameTests.set(false)
        enableClientGameTests.set(true)
        eula.set(true)
    }
}

// HHA sendet unterhalb der Fabric-Paket-API; der Netzwerk-Synchronizer des Client-Tests bricht sonst ab.
loom {
    runs.matching { it.name == "clientGameTest" }.configureEach {
        vmArg("-Dfabric.client.gametest.disableNetworkSynchronizer=true")
    }
}
