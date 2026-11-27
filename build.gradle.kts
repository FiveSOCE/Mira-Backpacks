import java.net.URI
import java.security.MessageDigest

plugins { java }

group = "gg.mira"
version = "0.3.2-1.18.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val miraCoreVersion = "0.6.1-1.18.2"
val miraCoreSha256 = "8abf9f26ea889ffe1648c6f35fcb7b20eef4decd870e8385fb1603120f363b95"
val miraCoreJar = layout.projectDirectory.file("libs/MiraCore_1.18.2.jar").asFile

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(file.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
}

val downloadMiraCore by tasks.registering {
    doLast {
        if (miraCoreJar.exists() && sha256(miraCoreJar) == miraCoreSha256) return@doLast
        miraCoreJar.parentFile.mkdirs()
        URI("https://github.com/FiveSOCE/Mira-core/releases/download/dev-1.18.2/MiraCore_1.18.2.jar").toURL().openStream().use { input ->
            miraCoreJar.outputStream().use { output -> input.copyTo(output) }
        }
        check(sha256(miraCoreJar) == miraCoreSha256) { "Downloaded MiraCore JAR failed SHA-256 verification" }
    }
}

val paperApiVersion = providers.gradleProperty("paperApiVersion").orElse("1.18.2-R0.1-SNAPSHOT")
val compileJavaVersion = providers.gradleProperty("compileJavaVersion").map(String::toInt).orElse(17)
val bytecodeJavaVersion = providers.gradleProperty("bytecodeJavaVersion").map(String::toInt).orElse(17)

dependencies {
    compileOnly("io.papermc.paper:paper-api:${paperApiVersion.get()}")
    compileOnly(files(miraCoreJar))
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(compileJavaVersion.get())) }

tasks.withType<JavaCompile>().configureEach {
    dependsOn(downloadMiraCore)
    options.encoding = "UTF-8"
    options.release.set(bytecodeJavaVersion.get())
}

tasks.jar { archiveFileName.set("MiraBackpacks_1.18.2.jar") }


tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
