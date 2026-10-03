dependencies {
    // Oldest API the bridge supports; it runs unchanged on every newer Paper and on Folia.
    compileOnly("com.destroystokyo.paper:paper-api:1.12.2-R0.1-SNAPSHOT")
    testImplementation("com.google.code.gson:gson:2.8.9")
    testImplementation(project(":velocity-proxy"))
}

tasks {
    withType<JavaCompile>().configureEach {
        // Paper 1.12.2 to 1.16 servers run on Java 8.
        options.release.set(8)
        options.compilerArgs.add("-Xlint:-options")
    }
    named<JavaCompile>("compileTestJava") {
        options.release.set(25)
    }
    processResources {
        val version = project.version.toString()
        inputs.property("version", version)
        filesMatching("plugin.yml") {
            expand("version" to version)
        }
    }
    jar {
        archiveBaseName.set("PaperProxy-Bridge")
    }
}
