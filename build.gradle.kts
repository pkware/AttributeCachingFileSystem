import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt)
    kotlin("jvm") version "1.7.21" apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    group = "com.pkware.file"

    val kotlinJvmTarget = "1.8"

    repositories {
        mavenCentral()
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = kotlinJvmTarget
            freeCompilerArgs = listOf(
                "-Xjvm-default=all",
                "-Xopt-in=kotlin.RequiresOptIn",
                "-Xjsr305=strict",

                // Ensure assertions don't add performance cost. See https://youtrack.jetbrains.com/issue/KT-22292
                "-Xassertions=jvm"
            )
        }
    }

    tasks.withType<Test> { useJUnitPlatform() }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = kotlinJvmTarget
        parallel = true
        config.from(rootProject.file("detekt.yml"))
        buildUponDefaultConfig = true
    }
}
