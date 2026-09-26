import java.util.zip.ZipFile
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
// Upstream Plasmo Voice release this backport follows; the mod, mcmod.info and protocol report it unchanged.
val upstreamVersion = "2.1.17"
// Revision of the Forge 1.7.10 backport; only the artifact file names carry it.
val legacyRevision = "r1"

version = upstreamVersion

extensions.extraProperties.set("modVersion", upstreamVersion)

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set("$upstreamVersion-$legacyRevision")
}

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
    // Standalone jar: 1.7.10 ships none of these, so they are shaded (see shadowJar below).
    shadowImplementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.21")

    shadowImplementation("it.unimi.dsi:fastutil:8.5.18")
    // The protocol only needs McGameProfile and Pos3d; coroutines and guava 33 stay out.
    shadowImplementation("su.plo.slib:api-common:1.7.4") {
        isTransitive = false
    }

    // Upstream Opus stack: native JNI first, pure-Java Concentus fallback. Shipped inside the mod jar.
    shadowImplementation("com.plasmoverse:opus-jni-rust:1.0.3")
    // Upstream NoiseSuppressionFilter: RNNoise through the same JNI loader as Opus.
    shadowImplementation("com.plasmoverse:rnnoise-jni-rust:1.0.2")
    shadowImplementation("com.plasmoverse:concentus:1.0.0") {
        isTransitive = false
    }

    compileOnly("org.jetbrains:annotations:23.0.0")

    compileOnly("org.projectlombok:lombok:1.18.44")
    annotationProcessor("org.projectlombok:lombok:1.18.44")
    testImplementation("junit:junit:4.13.2")
}

// Upstream shadow.gradle.kts: libraries other mods may also ship live under su.plo.voice.libs.
// com.plasmoverse.opus keeps its package (JNI symbol names) and su.plo.slib stays as upstream ships it.
tasks.shadowJar {
    relocate("kotlin", "su.plo.voice.libs.kotlin")
    relocate("it.unimi.dsi.fastutil", "su.plo.voice.libs.fastutil")
    relocate("org.concentus", "su.plo.voice.libs.concentus")

    // fastutil is 23 MB; only the classes the mod reaches are kept. Natives and Concentus stay whole.
    minimize {
        exclude(dependency("com.plasmoverse:.*"))
    }
    mergeServiceFiles()

    dependencies {
        exclude(dependency("org.jetbrains:annotations"))
    }

    exclude("META-INF/versions/**")
    exclude("META-INF/maven/**")
    exclude("META-INF/proguard/**")
    exclude("**/*.kotlin_metadata")
    exclude("**/*.kotlin_module")
    exclude("**/*.kotlin_builtins")
}

// Upstream verifyShadedJar: every class in the release jar is own code, a relocated library or the JNI bindings.
val verifyShadedJar = tasks.register("verifyShadedJar") {
    val jar = tasks.named("reobfJar").map { it.outputs.files.singleFile }
    inputs.files(tasks.named("reobfJar"))
    doLast {
        val allowed = listOf("su/plo/", "com/plasmoverse/")
        val offenders = ZipFile(jar.get()).use { zip ->
            zip.entries().asSequence().map { it.name }
                .filter { it.endsWith(".class") && allowed.none(it::startsWith) }
                .map { it.substringBeforeLast('/', "(default package)") }
                .toSortedSet()
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("Unexpected packages in ${jar.get().name}:\n  " + offenders.joinToString("\n  "))
        }
    }
}

tasks.named("assemble") {
    dependsOn(verifyShadedJar)
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
