package dev.wildedge.sdk

import dev.wildedge.sdk.integrations.inferQuantization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class InferQuantizationTest {

    private fun file(name: String) = File(name)

    // -- existing patterns --

    @Test fun int8InNameReturnsInt8() {
        assertEquals("int8", inferQuantization(file("model_int8.tflite")))
    }

    @Test fun int4InNameReturnsInt4() {
        assertEquals("int4", inferQuantization(file("model_int4.tflite")))
    }

    @Test fun fp16InNameReturnsF16() {
        assertEquals("f16", inferQuantization(file("model_fp16.tflite")))
    }

    @Test fun float16InNameReturnsF16() {
        assertEquals("f16", inferQuantization(file("model_float16.tflite")))
    }

    @Test fun fp32InNameReturnsF32() {
        assertEquals("f32", inferQuantization(file("model_fp32.tflite")))
    }

    @Test fun float32InNameReturnsF32() {
        assertEquals("f32", inferQuantization(file("model_float32.tflite")))
    }

    // -- new _qN / _fN patterns --

    @Test fun q8SegmentReturnsInt8() {
        assertEquals("int8", inferQuantization(file("model_seq128_q8_ekv1280.task")))
    }

    @Test fun q4SegmentReturnsInt4() {
        assertEquals("int4", inferQuantization(file("model_seq128_q4_ekv1280.task")))
    }

    @Test fun f16SegmentReturnsF16() {
        assertEquals("f16", inferQuantization(file("model_seq128_f16_ekv1280.task")))
    }

    @Test fun f32SegmentReturnsF32() {
        assertEquals("f32", inferQuantization(file("model_seq128_f32_ekv1280.task")))
    }

    // -- real-world LiteRT LM filenames --

    @Test fun qwen25Q8TaskFileReturnsInt8() {
        assertEquals(
            "int8",
            inferQuantization(file("Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task")),
        )
    }

    @Test fun gemma3Int4TaskFileReturnsInt4() {
        assertEquals("int4", inferQuantization(file("gemma3-1b-it-int4.task")))
    }

    @Test fun gemma3Q4TaskFileReturnsInt4() {
        assertEquals(
            "int4",
            inferQuantization(file("Gemma3-1B-IT_multi-prefill-seq_q4_block128_ekv1280.task")),
        )
    }

    // -- case insensitivity --

    @Test fun uppercaseInt8IsRecognised() {
        assertEquals("int8", inferQuantization(file("Model_INT8.tflite")))
    }

    @Test fun uppercaseQ8IsRecognised() {
        assertEquals("int8", inferQuantization(file("Model_Q8_Weights.task")))
    }

    // -- no match --

    @Test fun unknownQuantizationReturnsNull() {
        assertNull(inferQuantization(file("mobilenet_v1_224.tflite")))
    }

    @Test fun emptyNameReturnsNull() {
        assertNull(inferQuantization(file(".tflite")))
    }
}
