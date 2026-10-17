import buildlogic.internalVersion
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("buildlogic.platform")
}

dependencies {
    "api"(project(":worldguard-core"))
    "api"(libs.worldedit.bukkit) { isTransitive = false }
    "compileOnly"(libs.commandbook) { isTransitive = false }

    "compileOnly"(libs.jetbrains.annotations) {
        because("Resolving Spigot annotations")
    }
    "testCompileOnly"(libs.jetbrains.annotations) {
        because("Resolving Spigot annotations")
    }
    "compileOnly"(libs.paperApi) {
        exclude("org.slf4j", "slf4j-api")
        exclude("junit", "junit")
    }

    "implementation"(libs.paperLib)
    "implementation"(libs.bstats.bukkit)
}

tasks.named<Copy>("processResources") {
    val internalVersion = project.internalVersion
    inputs.property("internalVersion", internalVersion)
    filesMatching("plugin.yml") {
        expand("internalVersion" to internalVersion.get())
    }
}

tasks.named<ShadowJar>("shadowJar") {
    dependencies {
        include(dependency(":worldguard-core"))
        include(dependency("org.bstats:bstats-bukkit"))
        include(dependency("org.bstats:bstats-base"))
        include(dependency("io.papermc:paperlib"))

        relocate("org.bstats", "com.sk89q.worldguard.bukkit.bstats")
        relocate("io.papermc.lib", "com.sk89q.worldguard.bukkit.paperlib")
    }
}

tasks.named("assemble").configure {
    dependsOn("shadowJar")
}

// Only our adapter is embedded. The user's proprietary Lands JAR is a local
// compile input, never a runtime dependency or part of the distribution.
val landsInput = providers.gradleProperty("landsJar")
val landsCompileJar by tasks.registering(Zip::class) {
    archiveFileName = "lands-compile-only.jar"
    destinationDirectory = layout.buildDirectory.dir("lands")
    from(provider { zipTree(file(landsInput.get())) })
    // These unused obfuscated classes have the same name as Java packages.
    exclude("me/angeschossen/lands/ztTjN.class", "me/angeschossen/lands/SknVT.class")
}
val lands by sourceSets.creating {
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
}
dependencies {
    add(lands.compileOnlyConfigurationName, files(landsCompileJar))
}
val landsAdapterJar by tasks.registering(Jar::class) {
    archiveFileName = "lands728-adapter.jar"
    from(lands.output)
}
tasks.named<ShadowJar>("shadowJar") {
    from(landsAdapterJar) { into("compat") }
}

configure<PublishingExtension> {
    publications.named<MavenPublication>("maven") {
        from(components["java"])
    }
}
