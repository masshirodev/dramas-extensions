// One Aniyomi extension per module: src/<lang>/<name>/, described by its extension.json.
// That file is the single place an extension's name, package, class and version live:
// this plugin reads it for the manifest and the APK's version, and scripts/make-index.py
// reads it for the repository index (doc/RELEASING.md).

import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

@Suppress("UNCHECKED_CAST")
val info = JsonSlurper().parse(file("extension.json")) as Map<String, Any>
val extName = info.getValue("name") as String
val extPkg = info.getValue("pkg") as String // e.g. "en.reelshort"
val extClass = info.getValue("class") as String // e.g. ".ReelShort", relative to the package
val extCode = (info.getValue("versionCode") as Number).toInt()
val extLib = info.getValue("lib") as String // Aniyomi's extension API: "14"
val extNsfw = (info.getValue("nsfw") as Number).toInt()

check(extLib == "14") { "$extName: only lib 14 is built here (doc/ARCHITECTURE.md, \"Why lib 14\")" }
check(extClass.startsWith(".")) { "$extName: class is relative to the package and starts with '.'" }

val catalog = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = catalog.findLibrary(alias).get()

android {
    namespace = "eu.kanade.tachiyomi.animeextension.$extPkg"
    compileSdk = 34

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.animeextension.$extPkg"
        minSdk = 21
        targetSdk = 34
        versionCode = extCode
        // Aniyomi reads the lib from the version name: "14.<code>".
        versionName = "$extLib.$extCode"
        manifestPlaceholders["appName"] = "Aniyomi: $extName"
        manifestPlaceholders["extClass"] = extClass
        manifestPlaceholders["nsfw"] = extNsfw
    }

    sourceSets["main"].manifest.srcFile(rootProject.file("common/AndroidManifest.xml"))

    // The release key never lives in the repository (doc/SIGNING.md). Without it a
    // release build comes out unsigned, and scripts/release.sh refuses to publish it.
    val keystore = System.getenv("DRAMAS_EXT_KEYSTORE")
        ?: "${System.getProperty("user.home")}/.config/myconf/secrets/dramas-extensions/release.jks"
    val storePass = System.getenv("DRAMAS_EXT_KEYSTORE_PASS")
    val keyPass = System.getenv("DRAMAS_EXT_KEY_PASS")
    val canSign = file(keystore).isFile && !storePass.isNullOrEmpty() && !keyPass.isNullOrEmpty()
    signingConfigs {
        create("release") {
            if (canSign) {
                storeFile = file(keystore)
                storePassword = storePass
                keyAlias = System.getenv("DRAMAS_EXT_KEY_ALIAS") ?: "dramas-extensions"
                keyPassword = keyPass
                // tsundoku checks APK Signature Scheme v2 (its apk-signature.ts); Aniyomi accepts it too.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // No R8: an extension is a few classes, and minifying renames overrides the
            // host matches by name (a known trap with legacy video methods).
            isMinifyEnabled = false
            signingConfig = if (canSign) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += setOf("kotlin-tooling-metadata.json", "META-INF/*.version")
    }

    lint {
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        // The host's Kotlin runtime may be older than this compiler: call nothing past 2.0.
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

base {
    archivesName.set("aniyomi-$extPkg-v$extLib.$extCode")
}

dependencies {
    implementation(project(":core"))

    "compileOnly"(kotlin("stdlib"))
    "compileOnly"(lib("aniyomi-lib"))
    "compileOnly"(lib("kotlin-json"))
    "compileOnly"(lib("jsoup"))
    "compileOnly"(lib("okhttp"))
    "compileOnly"(lib("rxjava"))

    "testImplementation"(kotlin("stdlib"))
    "testImplementation"(lib("kotlin-json"))
    "testImplementation"(lib("jsoup"))
    "testImplementation"(lib("okhttp"))
    "testImplementation"(lib("junit"))
}
