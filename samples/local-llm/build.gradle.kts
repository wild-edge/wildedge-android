import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
tasks.register("prepareKotlinBuildScriptModel"){}
android {
    namespace = "dev.wildedge.sample.localllm"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wildedge.sample.localllm"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        val localProps = Properties()
        rootProject.file("local.properties").takeIf { it.exists() }
            ?.inputStream()?.use { localProps.load(it) }
        resValue("string", "wildedge_dsn", localProps.getProperty("wildedge.dsn", ""))
        // Optional: Hugging Face read token for swapping in a gated model (e.g. Gemma).
        // Generate one at https://huggingface.co/settings/tokens and add hf.token=hf_... to local.properties.
        buildConfigField("String", "HF_TOKEN", "\"${localProps.getProperty("hf.token", "")}\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        named("main") {
            kotlin.srcDir("../../examples")
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
    implementation(libs.litertlm)
    implementation(libs.appcompat)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)

    // compileOnly for example files — not bundled in the APK
    compileOnly(libs.tflite)
    compileOnly(libs.tflite.gpu)
    compileOnly(libs.onnxruntime)
    compileOnly(libs.mlkit.face)
    compileOnly(libs.googleai)
}
