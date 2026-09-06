import java.net.URI
import java.security.MessageDigest

plugins { java }

group = "gg.mira"
version = "0.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val miraCoreVersion = "0.5.1"
val miraCoreSha256 = "da63887ea952e74eb9ee3f78a2237b0ef6bcad1a52c1a5a313ab14767b095aa8"
val miraCoreJar = layout.projectDirectory.file("libs/MiraCore-$miraCoreVersion.jar").asFile

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(file.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
}

val downloadMiraCore by tasks.registering {
    doLast {
        if (miraCoreJar.exists() && sha256(miraCoreJar) == miraCoreSha256) return@doLast
        miraCoreJar.parentFile.mkdirs()
        URI("https://github.com/FiveSOCE/MIra-core/releases/download/v$miraCoreVersion/MiraCore-$miraCoreVersion.jar").toURL().openStream().use { input ->
            miraCoreJar.outputStream().use { output -> input.copyTo(output) }
        }
        check(sha256(miraCoreJar) == miraCoreSha256) { "Downloaded MiraCore JAR failed SHA-256 verification" }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files(miraCoreJar))
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

tasks.withType<JavaCompile>().configureEach {
    dependsOn(downloadMiraCore)
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar { archiveFileName.set("MiraBackpacks-${project.version}.jar") }


tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
