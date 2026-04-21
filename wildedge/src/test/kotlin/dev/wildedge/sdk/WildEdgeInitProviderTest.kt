package dev.wildedge.sdk

import android.os.Bundle
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WildEdgeInitProviderTest {

    @After fun tearDown() = WildEdge.clearInstance()

    // --- getInstance / instanceOrNull ---

    @Test fun instanceOrNullReturnsNullBeforeInit() {
        assertNull(WildEdge.instanceOrNull())
    }

    @Test fun getInstanceThrowsBeforeInit() {
        try {
            WildEdge.getInstance()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("WildEdge is not initialized"))
        }
    }

    @Test fun initSetsSharedInstance() {
        val ctx = RuntimeEnvironment.getApplication()
        val client = WildEdge.init(ctx) { /* no DSN → noop */ }
        assertSame(client, WildEdge.getInstance())
    }

    @Test fun initOverwritesPreviousInstance() {
        val ctx = RuntimeEnvironment.getApplication()
        val first = WildEdge.init(ctx) { }
        val second = WildEdge.init(ctx) { }
        assertNotSame(first, second)
        assertSame(second, WildEdge.getInstance())
    }

    // --- ContentProvider auto-init ---

    @Test fun providerInitializesInstanceFromManifestDsn() {
        val ctx = RuntimeEnvironment.getApplication()
        val metaData = Bundle().apply {
            putString(WildEdgeInitProvider.META_DSN, "https://secret@ingest.wildedge.dev/key")
        }
        shadowOf(ctx.packageManager)
            .getInternalMutablePackageInfo(ctx.packageName)
            .applicationInfo!!
            .metaData = metaData

        Robolectric.buildContentProvider(WildEdgeInitProvider::class.java).create()

        assertNotNull(WildEdge.instanceOrNull())
    }

    @Test fun providerSetsNoopInstanceWithoutDsn() {
        Robolectric.buildContentProvider(WildEdgeInitProvider::class.java).create()
        // Provider always sets the instance so getInstance() works; no DSN produces a noop client.
        assertNotNull(WildEdge.instanceOrNull())
    }

    @Test fun providerSetsNoopInstanceWithBlankDsn() {
        val ctx = RuntimeEnvironment.getApplication()
        val metaData = Bundle().apply { putString(WildEdgeInitProvider.META_DSN, "   ") }
        shadowOf(ctx.packageManager)
            .getInternalMutablePackageInfo(ctx.packageName)
            .applicationInfo!!
            .metaData = metaData

        Robolectric.buildContentProvider(WildEdgeInitProvider::class.java).create()

        assertNotNull(WildEdge.instanceOrNull())
    }
}
