import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
tasks.register("prepareKotlinBuildScriptModel"){}
android {
    namespace = "dev.wildedge.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wildedge.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Add wildedge.dsn=<your_dsn> to local.properties to enable reporting.
        // Without it the app runs in noop mode: all API calls work, events are discarded.
        val localProps = Properties()
        rootProject.file("local.properties").takeIf { it.exists() }
            ?.inputStream()?.use { localProps.load(it) }
        buildConfigField("String", "WILDEDGE_DSN", "\"${localProps.getProperty("wildedge.dsn", "")}\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Option C: include examples in compilation so API breakages are caught at build time.
    sourceSets {
        named("main") {
            kotlin.srcDir("../examples")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":wildedge"))
    implementation(libs.tflite)
    implementation(libs.appcompat)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)

    // compileOnly for example files (Option C) -- not bundled in the APK
    compileOnly(libs.tflite.gpu)
    compileOnly(libs.onnxruntime)
    compileOnly(libs.litertlm)
    compileOnly(libs.mlkit.face)
}
