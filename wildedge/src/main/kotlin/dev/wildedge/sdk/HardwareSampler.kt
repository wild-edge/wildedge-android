package dev.wildedge.sdk

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import dev.wildedge.sdk.events.HardwareContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class HardwareSampler(
    private val context: Context,
    private val intervalMs: Long = Config.DEFAULT_SAMPLING_INTERVAL_MS,
) {
    @Volatile private var current: HardwareContext = HardwareContext()
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "wildedge-hw-sampler").also { it.isDaemon = true }
    }
    private var future: ScheduledFuture<*>? = null

    fun start() {
        current = sample()
        future = executor.scheduleAtFixedRate(
            { current = sample() },
            intervalMs, intervalMs, TimeUnit.MILLISECONDS,
        )
    }

    fun stop() {
        future?.cancel(false)
        executor.shutdown()
    }

    fun snapshot(): HardwareContext = current

    private fun sample(): HardwareContext {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        val thermalState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (pm?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> "nominal"
                PowerManager.THERMAL_STATUS_MODERATE -> "fair"
                PowerManager.THERMAL_STATUS_SEVERE -> "serious"
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "critical"
                else -> null
            }
        } else {
            null
        }

        val thermalStateRaw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (pm?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "THERMAL_STATUS_NONE"
                PowerManager.THERMAL_STATUS_LIGHT -> "THERMAL_STATUS_LIGHT"
                PowerManager.THERMAL_STATUS_MODERATE -> "THERMAL_STATUS_MODERATE"
                PowerManager.THERMAL_STATUS_SEVERE -> "THERMAL_STATUS_SEVERE"
                PowerManager.THERMAL_STATUS_CRITICAL -> "THERMAL_STATUS_CRITICAL"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "THERMAL_STATUS_EMERGENCY"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "THERMAL_STATUS_SHUTDOWN"
                else -> null
            }
        } else {
            null
        }

        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val batteryLevel = batteryIntent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) level.toFloat() / scale else null
        }
        val batteryCharging = batteryIntent?.let {
            when (it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL -> true
                else -> false
            }
        }

        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val memAvailable = if (memInfo.availMem > 0) memInfo.availMem else null

        val cpuFreqMhz = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            readCpuFreqMhz("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
        }.maxOrNull()
        val cpuFreqMaxMhz = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            readCpuFreqMhz("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
        }.maxOrNull()

        return HardwareContext(
            thermalState = thermalState,
            thermalStateRaw = thermalStateRaw,
            batteryLevel = batteryLevel,
            batteryCharging = batteryCharging,
            memoryAvailableBytes = memAvailable,
            cpuFreqMhz = cpuFreqMhz,
            cpuFreqMaxMhz = cpuFreqMaxMhz,
            gpuBusyPercent = HardwareDetection.readGpuBusyPercent(),
        )
    }

    private fun readCpuFreqMhz(path: String): Int? = try {
        File(path).readText().trim().toLongOrNull()?.let { (it / 1000).toInt() }
    } catch (_: Exception) { null }
}
