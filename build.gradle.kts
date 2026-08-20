import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt)
    kotlin("jvm") version "1.9.25" apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    group = "com.pkware.filesystem"

    repositories {
        mavenCentral()
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {

            jvmTarget.set(JvmTarget.JVM_1_8)

            freeCompilerArgs.addAll(
                "-Xjvm-default=all",
                "-Xinline-classes",
                "-opt-in=kotlin.contracts.ExperimentalContracts",
                "-Xjsr305=strict",

                // Ensure assertions don't add performance cost. See https://youtrack.jetbrains.com/issue/KT-22292
                "-Xassertions=jvm"
            )
        }
    }

    dependencies {
        detektPlugins(project.dependencies.create("com.pkware.detekt:import-extension:1.2.0"))
    }

    tasks.withType<Test> { useJUnitPlatform() }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = tasks.named<KotlinCompile>("compileKotlin").get().compilerOptions.jvmTarget.get().target
        parallel = true
        config.from(rootProject.file("detekt.yml"))
        buildUponDefaultConfig = true
    }
}
