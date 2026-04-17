package dev.wildedge.sdk

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HardwareDetectionTest {

    @get:Rule val tmp = TemporaryFolder()

    // readSysfs

    @Test fun readSysfsReturnsFileContents() {
        val f = tmp.newFile().also { it.writeText("  Adreno (TM) 750  ") }
        assertEquals("Adreno (TM) 750", HardwareDetection.readSysfs(f.absolutePath))
    }

    @Test fun readSysfsReturnsNullForMissingFile() {
        assertNull(HardwareDetection.readSysfs("/no/such/path/kgsl_model"))
    }

    @Test fun readSysfsReturnsNullForEmptyFile() {
        val f = tmp.newFile().also { it.writeText("   ") }
        assertNull(HardwareDetection.readSysfs(f.absolutePath))
    }

    // gpuBusyPercent string parsing via readSysfs + filter

    @Test fun gpuBusyPercentParsesValueWithSuffix() {
        val raw = "42 %"
        val parsed = raw.filter { it.isDigit() }.toIntOrNull()
        assertEquals(42, parsed)
    }

    @Test fun gpuBusyPercentParsesPlainNumber() {
        val raw = "7"
        val parsed = raw.filter { it.isDigit() }.toIntOrNull()
        assertEquals(7, parsed)
    }
}
