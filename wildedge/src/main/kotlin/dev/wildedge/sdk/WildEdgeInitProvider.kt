package dev.wildedge.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * Initializes WildEdge before Application.onCreate() runs.
 *
 * Reads [META_DSN] (and optionally [META_DEBUG]) from the app's `<application>` meta-data
 * and calls [WildEdge.init]. If [META_DSN] is absent, does nothing; manual [WildEdge.init]
 * still works.
 *
 * Add to AndroidManifest.xml:
 * ```xml
 * <meta-data
 *     android:name="dev.wildedge.dsn"
 *     android:value="@string/wildedge_dsn" />
 * ```
 */
internal class WildEdgeInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return false

        @Suppress("TooGenericExceptionCaught")
        val metaData = try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.getApplicationInfo(
                    ctx.packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
            }
            appInfo.metaData
        } catch (e: Exception) {
            Log.w(TAG, "Could not read manifest meta-data: ${e.message}")
            null
        }

        val dsn = metaData?.getString(META_DSN)
        val debug = metaData?.getBoolean(META_DEBUG, false) ?: false

        // Always call init so getInstance() works regardless of whether a DSN was supplied.
        // A blank DSN produces a noop client, same as calling init() without a DSN manually.
        WildEdge.init(ctx) {
            if (!dsn.isNullOrBlank()) this.dsn = dsn
            this.debug = debug
        }
        return false
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    internal companion object {
        private const val TAG = "wildedge"
        const val META_DSN = "dev.wildedge.dsn"
        const val META_DEBUG = "dev.wildedge.debug"
    }
}
