plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.nmcp)
}

nmcp {
    publishAllProjectsProbablyBreakingProjectIsolation {
        username = providers.environmentVariable("SONATYPE_USERNAME").orNull ?: ""
        password = providers.environmentVariable("SONATYPE_PASSWORD").orNull ?: ""
        publicationType = "AUTOMATIC"
    }
}
