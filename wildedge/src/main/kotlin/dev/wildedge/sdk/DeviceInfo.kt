package dev.wildedge.sdk

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Hardware and software context for the device running inference.
 *
 * @property deviceId Anonymised, project-scoped device identifier.
 * @property deviceType Platform identifier; always "android".
 * @property deviceModel Manufacturer and model string.
 * @property osVersion Android OS version string.
 * @property appVersion Host application version, if detectable.
 * @property sdkVersion WildEdge SDK version.
 * @property locale BCP-47 language tag of the device locale.
 * @property timezone IANA timezone ID.
 * @property cpuArch Primary ABI (e.g. "arm64-v8a").
 * @property cpuCores Number of logical CPU cores.
 * @property ramTotalBytes Total physical RAM in bytes.
 * @property gpuModel GPU model string, if detectable.
 * @property accelerators Hardware accelerators available on the device.
 */
data class DeviceInfo(
    val deviceId: String,
    val deviceType: String = "android",
    val deviceModel: String,
    val osVersion: String,
    val appVersion: String?,
    val sdkVersion: String = Config.SDK_VERSION,
    val locale: String,
    val timezone: String,
    val cpuArch: String? = null,
    val cpuCores: Int? = null,
    val ramTotalBytes: Long? = null,
    val gpuModel: String? = null,
    val accelerators: List<Accelerator> = listOf(Accelerator.CPU),
) {
    internal fun toMap(): Map<String, Any?> = mapOf(
        "device_id" to deviceId,
        "device_type" to deviceType,
        "device_model" to deviceModel,
        "os_version" to osVersion,
        "app_version" to appVersion,
        "sdk_version" to sdkVersion,
        "locale" to locale,
        "timezone" to timezone,
        "gpu_model" to gpuModel,
        "accelerators" to accelerators.map { it.value },
    ).filterValues { it != null }

    /** Factory and utility methods for [DeviceInfo]. */
    companion object {
        /** Detects device information, generating and persisting an anonymised device ID if needed. */
        fun detect(context: Context, projectSecret: String, appVersion: String?): DeviceInfo {
            val prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
            var rawId = prefs.getString(Config.PREFS_DEVICE_ID, null)
            if (rawId == null) {
                rawId = UUID.randomUUID().toString()
                prefs.edit().putString(Config.PREFS_DEVICE_ID, rawId).apply()
            }

            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }

            return DeviceInfo(
                deviceId = hmac(projectSecret, rawId),
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                osVersion = "Android ${Build.VERSION.RELEASE}",
                appVersion = appVersion,
                locale = Locale.getDefault().toLanguageTag(),
                timezone = TimeZone.getDefault().id,
                cpuArch = Build.SUPPORTED_ABIS.firstOrNull(),
                cpuCores = Runtime.getRuntime().availableProcessors(),
                ramTotalBytes = if (memInfo.totalMem > 0) memInfo.totalMem else null,
                gpuModel = HardwareDetection.gpuModel(),
                accelerators = HardwareDetection.availableAccelerators(),
            )
        }

        /** Clears the persisted device ID so a new one is generated on the next [detect] call. */
        fun resetDeviceId(context: Context) {
            context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(Config.PREFS_DEVICE_ID).apply()
        }

        private fun hmac(key: String, message: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(message.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
