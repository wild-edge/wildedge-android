import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    signing
}

android {
    namespace = "dev.wildedge.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // No required runtime dependencies

    // Optional integrations: compileOnly so the host app brings its own version.
    // Unused integrations are never loaded at runtime -- no ClassNotFoundException risk.
    compileOnly(libs.coroutines.android)
    compileOnly(libs.tflite)
    compileOnly(libs.tflite.play.services)
    compileOnly(libs.onnxruntime)
    compileOnly(libs.litertlm)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.android)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation("org.json:json:20231013")
    testImplementation(libs.tflite.play.services)
    testCompileOnly(libs.litertlm)
}

android {
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = project.property("GROUP") as String
                artifactId = "wildedge-android"
                version = project.property("VERSION_NAME") as String

                pom {
                    name.set(project.property("POM_NAME") as String)
                    description.set(project.property("POM_DESCRIPTION") as String)
                    url.set(project.property("POM_URL") as String)
                    licenses {
                        license {
                            name.set(project.property("POM_LICENSE_NAME") as String)
                            url.set(project.property("POM_LICENSE_URL") as String)
                        }
                    }
                    developers {
                        developer {
                            id.set(project.property("POM_DEVELOPER_ID") as String)
                            name.set(project.property("POM_DEVELOPER_NAME") as String)
                            email.set(project.property("POM_DEVELOPER_EMAIL") as String)
                        }
                    }
                    scm {
                        url.set(project.property("POM_SCM_URL") as String)
                        connection.set(project.property("POM_SCM_CONNECTION") as String)
                        developerConnection.set(project.property("POM_SCM_DEV_CONNECTION") as String)
                    }
                }
            }
        }

        repositories {
            maven {
                name = "sonatype"
                val releasesUrl = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                val snapshotsUrl = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                url = uri(if ((version as String).endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl)
                credentials {
                    username = providers.gradleProperty("sonatypeUsername")
                        .orElse(providers.environmentVariable("SONATYPE_USERNAME"))
                        .orNull
                    password = providers.gradleProperty("sonatypePassword")
                        .orElse(providers.environmentVariable("SONATYPE_PASSWORD"))
                        .orNull
                }
            }
        }
    }

    signing {
        val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
        val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
        if (signingKey != null && signingPassword != null) {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(publishing.publications["release"])
        }
    }
}
