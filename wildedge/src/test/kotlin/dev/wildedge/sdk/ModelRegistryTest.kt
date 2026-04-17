package dev.wildedge.sdk

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelRegistryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun info(name: String = "MyModel") = ModelInfo(
        modelName = name,
        modelVersion = "1.0",
        modelSource = "local",
        modelFormat = "tflite",
    )

    @Test fun registerAndSnapshot() {
        val reg = ModelRegistry()
        reg.register("m1", info())
        val snap = reg.snapshot()
        assertTrue(snap.containsKey("m1"))
        assertEquals("MyModel", (snap["m1"] as Map<*, *>)["model_name"])
    }

    @Test fun registerIsIdempotent() {
        val reg = ModelRegistry()
        val first = reg.register("m1", info("First"))
        val second = reg.register("m1", info("Second"))
        assertTrue(first)
        assertFalse(second)
        assertEquals("First", (reg.snapshot()["m1"] as Map<*, *>)["model_name"])
    }

    @Test fun persistsAndReloadsFromDisk() {
        val file = tmp.newFile("registry.json")
        val reg1 = ModelRegistry(file)
        reg1.register("m1", info("Persisted"))

        val reg2 = ModelRegistry(file)
        val snap = reg2.snapshot()
        assertTrue(snap.containsKey("m1"))
        assertEquals("Persisted", (snap["m1"] as Map<*, *>)["model_name"])
    }

    @Test fun persistsOptionalFieldsAcrossReload() {
        val file = tmp.newFile("registry_optional.json")
        val reg1 = ModelRegistry(file)
        reg1.register(
            "m_opt",
            ModelInfo(
                modelName = "OptionalModel",
                modelVersion = "2.0",
                modelSource = "download",
                modelFormat = "onnx",
                modelFamily = "vision",
                quantization = "int8",
            ),
        )

        val reg2 = ModelRegistry(file)
        val snap = reg2.snapshot()
        val model = snap["m_opt"] as Map<*, *>

        assertEquals("OptionalModel", model["model_name"])
        assertEquals("2.0", model["model_version"])
        assertEquals("download", model["model_source"])
        assertEquals("onnx", model["model_format"])
        assertEquals("vision", model["model_family"])
        assertEquals("int8", model["quantization"])
    }

    @Test fun persistsAndReloadsSpecialCharacters() {
        val file = tmp.newFile("registry_special_chars.json")
        val reg1 = ModelRegistry(file)
        val modelId = "m\\\"1/with\\\\path"
        val modelName = "Name \\\"quoted\\\" and \\\\ slash"

        reg1.register(
            modelId,
            ModelInfo(
                modelName = modelName,
                modelVersion = "1.0",
                modelSource = "local",
                modelFormat = "tflite",
            ),
        )

        val reg2 = ModelRegistry(file)
        val snap = reg2.snapshot()

        assertTrue(snap.containsKey(modelId))
        assertEquals(modelName, (snap[modelId] as Map<*, *>)["model_name"])
    }

    @Test fun invalidModelEntriesAreSkippedButValidEntriesLoad() {
        val file = tmp.newFile("registry_partial_invalid.json")
        file.writeText(
            """{
              "valid_model": {
                "model_name": "Valid",
                "model_version": "1.0",
                "model_source": "local",
                "model_format": "tflite"
              },
              "invalid_model": {
                "model_name": "Invalid",
                "model_source": "local"
              }
            }""".trimIndent(),
        )

        val reg = ModelRegistry(file)
        val snap = reg.snapshot()

        assertTrue(snap.containsKey("valid_model"))
        assertFalse(snap.containsKey("invalid_model"))
    }

    @Test fun corruptFileStartsFresh() {
        val file = tmp.newFile("bad.json")
        file.writeText("not valid json {{{{")
        val reg = ModelRegistry(file)
        assertTrue(reg.snapshot().isEmpty())
    }
}
