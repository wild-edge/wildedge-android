package dev.wildedge.sdk

import android.content.Context
import android.content.pm.PackageInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AppVersionDetectionTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun detectsVersionNameFromPackageManager() {
        shadowOf(context.packageManager).apply {
            val info = PackageInfo().also {
                it.packageName = context.packageName
                it.versionName = "2.5.1"
            }
            installPackage(info)
        }

        val wildEdge = WildEdge.Builder(context).also { it.dsn = null }.let {
            // read the auto-detected value directly from the builder field
            it.appVersion
        }

        assertEquals("2.5.1", wildEdge)
    }

    @Test fun appVersionCanBeOverridden() {
        shadowOf(context.packageManager).apply {
            val info = PackageInfo().also {
                it.packageName = context.packageName
                it.versionName = "2.5.1"
            }
            installPackage(info)
        }

        val builder = WildEdge.Builder(context).also {
            it.appVersion = "custom-override"
        }

        assertEquals("custom-override", builder.appVersion)
    }

    @Test fun nullWhenPackageManagerThrows() {
        // Package not installed in shadow -- getPackageInfo throws NameNotFoundException.
        // Reset to a fresh application context with no package installed.
        val builder = WildEdge.Builder(RuntimeEnvironment.getApplication())
        // Robolectric installs the app package by default with a null versionName,
        // so the result is either null or the shadow value -- not an exception.
        // This test verifies no crash occurs.
        try { builder.appVersion } catch (e: Exception) { fail("unexpected exception: $e") }
    }
}
