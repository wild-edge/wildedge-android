import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
tasks.register("prepareKotlinBuildScriptModel"){}
android {
    namespace = "dev.wildedge.sample.carscanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wildedge.sample.carscanner"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        val localProps = Properties()
        rootProject.file("local.properties").takeIf { it.exists() }
            ?.inputStream()?.use { localProps.load(it) }
        resValue("string", "wildedge_dsn", localProps.getProperty("wildedge.dsn", ""))
        // Get a free key at https://openrouter.ai and add openrouter.api.key=sk-or-... to local.properties
        buildConfigField("String", "OPENROUTER_API_KEY", "\"${localProps.getProperty("openrouter.api.key", "")}\"")
        // Get a free key at https://aistudio.google.com and add google.ai.api.key=AIza... to local.properties
        buildConfigField("String", "GEMINI_API_KEY", "\"${localProps.getProperty("google.ai.api.key", "")}\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":wildedge"))
    implementation(libs.appcompat)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.recyclerview)
}
