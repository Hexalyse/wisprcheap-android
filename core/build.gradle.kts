import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-neutral logic (no Android dependency), unit-tested on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    useJUnit()
}
