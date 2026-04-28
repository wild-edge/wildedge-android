version = property("VERSION_NAME") as String

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.nmcp)
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/detekt.yml")
    source.setFrom(
        "wildedge/src/main/kotlin",
        "wildedge/src/test/kotlin",
        "sample/src/main/kotlin",
    )
}

dependencies {
    detektPlugins(libs.detekt.formatting)
}

nmcp {
    publishAllProjectsProbablyBreakingProjectIsolation {
        username = providers.environmentVariable("SONATYPE_USERNAME").orNull ?: ""
        password = providers.environmentVariable("SONATYPE_PASSWORD").orNull ?: ""
        publicationType = "AUTOMATIC"
    }
}
