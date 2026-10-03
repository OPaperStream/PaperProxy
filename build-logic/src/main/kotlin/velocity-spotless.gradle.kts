import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessPlugin

apply<SpotlessPlugin>()

extensions.configure<SpotlessExtension> {
    java {
        if (project.name == "velocity-api") {
            licenseHeaderFile(file("HEADER.txt"))
            targetExclude(
                "**/java/com/velocitypowered/api/util/Ordered.java",
                "**/java/net/paperstream/**"
            )
        } else {
            licenseHeaderFile(rootProject.file("HEADER.txt"))
            targetExclude("**/java/net/paperstream/**")
        }
        removeUnusedImports()
    }
    // PaperProxy additions carry their own header.
    format("paperproxyJava", com.diffplug.gradle.spotless.JavaExtension::class.java) {
        target("src/*/java/net/paperstream/**/*.java")
        licenseHeaderFile(rootProject.file("HEADER-PAPERPROXY.txt"))
        removeUnusedImports()
    }
}
