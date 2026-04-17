package dev.wildedge.sdk

import android.os.Build
import java.io.File

internal object HardwareDetection {

    fun gpuModel(): String? =
        readSysfs("/sys/class/kgsl/kgsl-3d0/gpu_model")   // Qualcomm Adreno
            ?: readSysfs("/sys/kernel/gpu/gpu_model")       // some Samsung/MediaTek

    fun availableAccelerators(): List<String> = buildList {
        add("cpu")
        if (hasGpu()) add("gpu")
        if (hasNnapi()) add("nnapi")
        if (hasDsp()) add("dsp")
    }

    fun readGpuBusyPercent(): Int? {
        val raw = readSysfs("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage") ?: return null
        return raw.filter { it.isDigit() }.toIntOrNull()
    }

    private fun hasGpu(): Boolean =
        File("/sys/class/kgsl/kgsl-3d0").exists() ||
        File("/sys/class/gpu/mali0").exists()

    private fun hasNnapi(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    private fun hasDsp(): Boolean {
        val hw = try {
            File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("Hardware") }
                ?.substringAfter(":")?.trim()?.lowercase()
        } catch (_: Exception) { null } ?: return false
        return hw.contains("qcom") || hw.contains("sm8") || hw.contains("sm7") ||
               hw.contains("msm") || hw.contains("snapdragon")
    }

    // Internal for testability — test code in the same module can call this directly.
    internal fun readSysfs(path: String): String? = try {
        File(path).readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}
