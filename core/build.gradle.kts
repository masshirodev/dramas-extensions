// Code both extensions share, compiled into each APK. Plain JVM: it touches no
// Android or Aniyomi class, so its tests run without either.
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.kotlin.json)
    compileOnly(libs.jsoup)

    testImplementation(kotlin("stdlib"))
    testImplementation(libs.kotlin.json)
    testImplementation(libs.jsoup)
    testImplementation(libs.junit)
}
