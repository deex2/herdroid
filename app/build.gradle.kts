import com.android.build.api.variant.Variant
import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val releaseStoreFile = providers.environmentVariable("HERDROID_SIGNING_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("HERDROID_SIGNING_PASSWORD").orNull

android {
    namespace = "dev.herdroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.herdroid"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.1.5"
        testInstrumentationRunner = "dev.herdroid.core.testing.HerdroidTestRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null && !releaseStorePassword.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = "herdroid-release"
                keyPassword = releaseStorePassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures.compose = true
    packaging.resources.pickFirsts += "META-INF/LICENSE.md"

    val sharedTest = "src/sharedTest/java"
    sourceSets {
        getByName("test") {
            java.directories.add(sharedTest)
            kotlin.directories.add(sharedTest)
        }
        getByName("androidTest") {
            java.directories.add(sharedTest)
            kotlin.directories.add(sharedTest)
        }
    }
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose) {
        version { strictly(libs.versions.lifecycle.get()) }
    }
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sshj)
    implementation(libs.bouncycastle)
    implementation("org.bouncycastle:bcpkix-jdk18on")
    implementation("org.bouncycastle:bcutil-jdk18on")
    implementation(libs.termlib)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.room.runtime)
    implementation(libs.hilt.android)

    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)
    ksp(libs.kotlin.metadata.jvm)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    add("kspAndroidTest", libs.hilt.compiler)

    constraints {
        implementation("org.bouncycastle:bcprov-jdk18on") {
            version { strictly(libs.versions.bouncycastleProvider.get()) }
        }
        implementation("org.bouncycastle:bcpkix-jdk18on") {
            version { strictly(libs.versions.bouncycastle.get()) }
        }
        implementation("org.bouncycastle:bcutil-jdk18on") {
            version { strictly(libs.versions.bouncycastle.get()) }
        }
    }
}

val bridgeArtifactDir = providers.gradleProperty("bridgeArtifactDir")
    .orElse(providers.environmentVariable("HERDROID_BRIDGE_ARTIFACT_DIR"))
val approvedBridgeTargets = setOf(
    "x86_64-unknown-linux-gnu",
    "aarch64-unknown-linux-gnu",
    "x86_64-pc-windows-msvc",
)

androidComponents {
    onVariants(selector().withBuildType("debug")) { registerBridgeAssets(it, false) }
    onVariants(selector().withBuildType("release")) { registerBridgeAssets(it, true) }
}

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    .joinToString("") { "%02x".format(it) }

fun registerBridgeAssets(variant: Variant, release: Boolean) {
    val output = objects.directoryProperty().convention(
        layout.buildDirectory.dir("generated/bridgeAssets/${variant.name}/assets"),
    )
    val task = tasks.register("generate${variant.name.replaceFirstChar { it.uppercase() }}BridgeAssets") {
        outputs.dir(output)
        inputs.property("release", release)
        inputs.file(rootProject.file("THIRD_PARTY_NOTICES.md"))
            .withPathSensitivity(PathSensitivity.RELATIVE)
        bridgeArtifactDir.orNull?.let {
            inputs.dir(rootProject.file(it)).withPathSensitivity(PathSensitivity.RELATIVE)
        }
        doLast {
            val assetRoot = output.get().asFile
            rootProject.file("THIRD_PARTY_NOTICES.md").copyTo(
                File(assetRoot, "THIRD_PARTY_NOTICES.md"),
                overwrite = true,
            )
            val destination = File(assetRoot, "bridge")
            destination.deleteRecursively()
            destination.mkdirs()
            val source = bridgeArtifactDir.orNull?.let(rootProject::file)
            if (source == null) {
                if (release) throw GradleException("Release requires bridgeArtifactDir or HERDROID_BRIDGE_ARTIFACT_DIR")
                File(destination, "catalog.json").writeText(
                    """{"plugin_id":"dev.herdroid.bridge","plugin_version":"0.1.0","min_herdr_version":"0.8.0","protocol":1,"targets":[]}""",
                )
                return@doLast
            }
            val catalog = File(source, "catalog.json")
            if (!catalog.isFile) throw GradleException("Bridge artifact directory has no catalog.json")
            @Suppress("UNCHECKED_CAST")
            val root = JsonSlurper().parse(catalog) as? Map<String, Any?>
                ?: throw GradleException("Bridge catalog is invalid")
            if (root["plugin_id"] != "dev.herdroid.bridge" || root["plugin_version"] != "0.1.0" ||
                root["min_herdr_version"] != "0.8.0" || root["protocol"] != 1
            ) throw GradleException("Bridge catalog pins are invalid")
            val entries = (root["targets"] as? List<*>)?.map {
                it as? Map<*, *> ?: throw GradleException("Bridge target is invalid")
            } ?: throw GradleException("Bridge catalog has no targets")
            val names = entries.map { it["target"] as? String ?: throw GradleException("Bridge target missing name") }
            if (names.toSet() != approvedBridgeTargets || names.size != approvedBridgeTargets.size) {
                throw GradleException("Bridge catalog must contain exactly all approved targets")
            }
            val approvedPaths = mutableListOf("catalog.json")
            entries.forEach { entry ->
                val target = entry["target"] as? String ?: throw GradleException("Bridge target missing name")
                val hash = entry["sha256"] as? String ?: throw GradleException("Bridge target $target has no hash")
                val binary = entry["binary"] as? String ?: throw GradleException("Bridge target $target has no binary")
                val manifest = entry["manifest"] as? String ?: throw GradleException("Bridge target $target has no manifest")
                val binaryFile = File(source, binary)
                val manifestFile = File(source, manifest)
                if (!hash.matches(Regex("[0-9a-f]{64}")) || binary != bridgeBinaryPath(target) ||
                    manifest != "$target/herdr-plugin.toml" || !binaryFile.isFile || !manifestFile.isFile ||
                    sha256(binaryFile) != hash || normalizedBridgeManifest(manifestFile.readText()) != trustedBridgeManifest
                ) throw GradleException("Bridge target $target failed validation")
                approvedPaths += manifest
                approvedPaths += binary
            }
            copy {
                from(source)
                include(*approvedPaths.toTypedArray())
                into(destination)
            }
        }
    }
    variant.sources.assets?.addGeneratedSourceDirectory(task) { output }
}

fun bridgeBinaryPath(target: String) = "$target/bin/herdroid-bridge" + if (target == "x86_64-pc-windows-msvc") ".exe" else ""

fun normalizedBridgeManifest(value: String) = value.replace("\r\n", "\n").removeSuffix("\n")

val trustedBridgeManifest = """id = "dev.herdroid.bridge"
name = "Herdroid Bridge"
version = "0.1.0"
min_herdr_version = "0.8.0"
description = "SSH-stdio companion for the Herdroid Android client"
platforms = ["linux", "windows"]"""
