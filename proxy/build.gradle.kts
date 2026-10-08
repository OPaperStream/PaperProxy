import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import com.github.jengelman.gradle.plugins.shadow.transformers.Log4j2PluginsCacheFileTransformer

plugins {
    application
    id("velocity-init-manifest")
    alias(libs.plugins.shadow)
}

application {
    mainClass.set("com.velocitypowered.proxy.Velocity")
    applicationDefaultJvmArgs += listOf("-Dvelocity.packet-decode-logging=true")
}

tasks {
    withType<Checkstyle> {
        exclude("**/com/velocitypowered/proxy/protocol/packet/**")
    }

    jar {
        manifest {
            attributes["Implementation-Title"] = "PaperProxy"
            attributes["Implementation-Vendor"] = "PaperProxy Contributors"
            attributes["Multi-Release"] = "true"
            attributes["Velocity-Commit"] = project.property("velocityCommit") as String
        }
    }

    processResources {
        val velocityVersion = project.property("velocityVersion") as String
        val velocityCommit = project.property("velocityCommit") as String
        inputs.property("velocityVersion", velocityVersion)
        inputs.property("velocityCommit", velocityCommit)
        filesMatching("paperproxy/build.properties") {
            expand("velocityVersion" to velocityVersion, "velocityCommit" to velocityCommit)
        }
    }

    shadowJar {
        // Everything in one jar, for servers without internet access.
        archiveBaseName.set("paperproxy")
        archiveClassifier.set("full")

        // The BungeeCord layer ships as a nested jar, loaded in its own class loader on demand.
        val bungeeLayer = project(":paperproxy-bungee").tasks.named("shadowJar")
        dependsOn(bungeeLayer)
        from(bungeeLayer.map { it.outputs.files.singleFile }) {
            into("paperproxy")
            rename { "bungee-layer.jar" }
        }
        filesMatching("META-INF/org/apache/logging/log4j/core/config/plugins/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }

        transform(Log4j2PluginsCacheFileTransformer::class.java)

        // Exclude Checker Framework annotations
        exclude("org/checkerframework/checker/**")

        relocate("org.bstats", "com.velocitypowered.proxy.bstats")

        // Include Configurate 3
        val configurateBuildTask = project(":deprecated-configurate3").tasks.named("shadowJar")
        dependsOn(configurateBuildTask)
        from(zipTree(configurateBuildTask.map { it.outputs.files.singleFile }))
    }

    runShadow {
        workingDir = file("run").also(File::mkdirs)
        standardInput = System.`in`
        jvmArgs("-Dvelocity.packet-decode-logging=true")
    }
    named<JavaExec>("run") {
        workingDir = file("run").also(File::mkdirs)
        standardInput = System.`in` // Doesn't work?
    }

    withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(
            listOf(
                "-Alog4j.graalvm.groupId=${project.group}",
                "-Alog4j.graalvm.artifactId=${project.name}"
            )
        )
    }
}

// ---------------------------------------------------------------------------------------------
// PaperProxy: small launcher jar. It contains PaperProxy's own code ("core") and a list of all
// libraries with their SHA-256; the launcher downloads them on first start.

val launcher: SourceSet = sourceSets.create("launcher")

tasks.named<JavaCompile>("compileLauncherJava") {
    // Java 8 so that a too old Java gets a readable message instead of a class version error.
    options.release.set(8)
    options.compilerArgs.add("-Xlint:-options")
}

val coreJar = tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("coreJar") {
    archiveBaseName.set("paperproxy-core")
    archiveClassifier.set("")
    from(sourceSets.main.get().output)
    configurations = listOf(project.configurations.runtimeClasspath.get())
    // Only PaperProxy's own modules (and bStats, which must stay relocated) go into the core;
    // every other library is downloaded by the launcher.
    dependencies {
        exclude { dep ->
            !(dep.moduleGroup == "com.velocitypowered" || dep.moduleGroup == "org.bstats")
        }
    }
    relocate("org.bstats", "com.velocitypowered.proxy.bstats")
    exclude("org/checkerframework/checker/**")
    // Thin BungeeCord layer; its libraries are downloaded only when a BungeeCord plugin exists.
    from(project(":paperproxy-bungee").tasks.named("thinJar")) {
        into("paperproxy")
        rename { "bungee-layer.jar" }
    }
    from(project(":paperproxy-bungee").tasks.named("libraryList")) {
        into("paperproxy")
    }
    val configurateBuildTask = project(":deprecated-configurate3").tasks.named("shadowJar")
    dependsOn(configurateBuildTask)
    from(zipTree(configurateBuildTask.map { it.outputs.files.singleFile }))
    manifest {
        from(tasks.jar.get().manifest)
    }
}

val libraryList = tasks.register("libraryList") {
    val output = layout.buildDirectory.file("generated/paperproxy/libraries.list")
    val runtime = project.configurations.runtimeClasspath
    inputs.files(runtime)
    outputs.file(output)
    doLast {
        val lines = mutableListOf("# group:artifact:version:classifier:extension sha256")
        runtime.get().resolvedConfiguration.resolvedArtifacts
            .filter { it.id.componentIdentifier is ModuleComponentIdentifier }
            .filter { it.moduleVersion.id.group !in setOf("com.velocitypowered", "org.bstats") }
            .sortedBy { it.id.componentIdentifier.displayName + (it.classifier ?: "") }
            .forEach { artifact ->
                val id = artifact.moduleVersion.id
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(artifact.file.readBytes())
                    .joinToString("") { "%02x".format(it) }
                lines += "${id.group}:${id.name}:${id.version}:${artifact.classifier ?: ""}:" +
                    "${artifact.extension} $digest"
            }
        output.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

val launcherJar = tasks.register<Jar>("launcherJar") {
    archiveBaseName.set("paperproxy")
    archiveClassifier.set("")
    from(launcher.output)
    from(coreJar) {
        into("META-INF/paperproxy")
        rename { "core.jar" }
    }
    from(libraryList) {
        into("META-INF/paperproxy")
    }
    manifest {
        attributes["Main-Class"] = "net.paperstream.paperproxy.launcher.Launcher"
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
        attributes["Implementation-Title"] = "PaperProxy"
    }
}

tasks.named("assemble") {
    dependsOn(launcherJar)
}

dependencies {
    implementation(project(":velocity-api"))
    implementation(project(":velocity-native"))

    implementation(libs.bundles.log4j)
    implementation(libs.kyori.ansi)
    implementation(libs.netty.codec)
    implementation(libs.netty.codec.haproxy)
    implementation(libs.netty.codec.http)
    implementation(libs.netty.handler)
    implementation(libs.netty.transport.native.epoll)
    implementation(variantOf(libs.netty.transport.native.epoll) { classifier("linux-x86_64") })
    implementation(variantOf(libs.netty.transport.native.epoll) { classifier("linux-aarch_64") })
    implementation(libs.netty.transport.native.iouring)
    implementation(variantOf(libs.netty.transport.native.iouring) { classifier("linux-x86_64") })
    implementation(variantOf(libs.netty.transport.native.iouring) { classifier("linux-aarch_64") })
    implementation(libs.netty.transport.native.kqueue)
    implementation(variantOf(libs.netty.transport.native.kqueue) { classifier("osx-x86_64") })
    implementation(variantOf(libs.netty.transport.native.kqueue) { classifier("osx-aarch_64") })

    implementation(libs.jopt)
    implementation(libs.terminalconsoleappender)
    runtimeOnly(libs.jline)
    runtimeOnly(libs.disruptor)
    implementation(libs.fastutil)
    implementation(platform(libs.adventure.bom))
    implementation(libs.adventure.text.serializer.json.legacy.impl)
    implementation(libs.completablefutures)
    implementation(libs.nightconfig)
    implementation(libs.bstats)
    implementation(libs.lmbda)
    implementation(libs.asm)
    implementation(libs.bundles.flare)
    compileOnly(libs.spotbugs.annotations)
    compileOnly(libs.auto.service.annotations)
    testImplementation(libs.mockito)

    annotationProcessor(libs.auto.service)
    annotationProcessor(libs.log4j.core)
}
