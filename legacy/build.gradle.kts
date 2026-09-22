import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("com.gtnewhorizons.gtnhconvention")

    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.lombok") version "2.2.21"
}

group = "su.plo.voice"
version = "2.1.17"

extensions.extraProperties.set("modVersion", "2.1.17")

repositories {
    mavenCentral()

    maven(url = "https://repo.plo.su")
    maven(url = "https://repo.plasmoverse.com/releases")
    maven(url = "https://repo.plasmoverse.com/snapshots")
}

sourceSets {
    main {
        java.srcDir("../protocol/src/main/java")
    }
}

extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.named("main") {
        kotlin.srcDir("../protocol/src/main/kotlin")
    }
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(21)
}

tasks.named<KotlinJvmCompile>("compileKotlin") {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.21")

    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("su.plo.slib:api-common:1.7.4")

    compileOnly("org.jetbrains:annotations:23.0.0")

    compileOnly("org.projectlombok:lombok:1.18.44")
    annotationProcessor("org.projectlombok:lombok:1.18.44")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    workingDir(layout.buildDirectory.dir("transport-test"))
    doFirst {
        workingDir.mkdirs()
    }
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    testLogging {
        events("passed", "failed")
    }
}
