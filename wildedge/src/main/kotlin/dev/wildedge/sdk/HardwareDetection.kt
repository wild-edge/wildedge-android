package dev.wildedge.sdk

import android.os.Build
import java.io.File

internal object HardwareDetection {

    // First readable file wins.
    private val GPU_MODEL_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_model",        // Qualcomm Adreno
        "/sys/kernel/gpu/gpu_model",                   // Mali (Samsung Exynos, MediaTek Dimensity)
        "/sys/class/misc/mali0/device/gpuinfo",        // Mali alternate
        "/sys/class/gpu/mali0/device/gpuinfo",         // Mali alternate
    )

    // Adreno returns "42 %" and Mali returns "42". The digit filter handles both.
    private val GPU_BUSY_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", // Qualcomm Adreno
        "/sys/kernel/gpu/gpu_loading",                    // Mali (Samsung Exynos, MediaTek Dimensity)
        "/sys/class/misc/mali0/device/utilization",       // Mali alternate
        "/sys/class/gpu/mali0/device/utilization",        // Mali alternate
    )

    private val GPU_PRESENCE_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0",  // Qualcomm Adreno
        "/sys/class/gpu/mali0",       // Mali
        "/sys/class/misc/mali0",      // Mali alternate
        "/sys/kernel/gpu",            // Samsung Exynos, MediaTek Dimensity
    )

    fun gpuModel(): String? = GPU_MODEL_PATHS.firstNotNullOfOrNull { readSysfs(it) }

    fun availableAccelerators(): List<Accelerator> = buildList {
        add(Accelerator.CPU)
        if (hasGpu()) add(Accelerator.GPU)
        if (hasNnapi()) add(Accelerator.NNAPI)
        if (hasDsp()) add(Accelerator.DSP)
    }

    fun readGpuBusyPercent(): Int? = GPU_BUSY_PATHS.firstNotNullOfOrNull { path ->
        readSysfs(path)?.filter { it.isDigit() }?.toIntOrNull()
    }

    private fun hasGpu(): Boolean = GPU_PRESENCE_PATHS.any { File(it).exists() }

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

    // Internal for testability: test code in the same module can call this directly.
    internal fun readSysfs(path: String): String? = try {
        File(path).readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}
