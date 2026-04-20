package dev.wildedge.sdk

import dev.wildedge.sdk.integrations.PlayServicesTfliteDecorator
import org.junit.Assert.*
import org.junit.Assert.assertNull
import org.junit.Test
import org.tensorflow.lite.InterpreterApi

class PlayServicesTfliteDecoratorTest {

    // Minimal fake, no native code, just records calls
    private class FakeInterpreter : InterpreterApi {
        var runCalls = 0
        var closedCalled = false
        var failRun = false
        var failMultiRun = false

        override fun run(input: Any, output: Any) {
            check(!failRun) { "run failed" }
            runCalls++
        }

        override fun runForMultipleInputsOutputs(inputs: Array<Any>, outputs: Map<Int, Any>) {
            require(!failMultiRun) { "multi run failed" }
            runCalls++
        }
        override fun getInputTensorCount() = 1
        override fun getOutputTensorCount() = 1
        override fun getInputTensor(index: Int): org.tensorflow.lite.Tensor? = null
        override fun getOutputTensor(index: Int): org.tensorflow.lite.Tensor? = null
        override fun resizeInput(idx: Int, dims: IntArray) {}
        override fun resizeInput(idx: Int, dims: IntArray, strict: Boolean) {}
        override fun getInputIndex(opName: String): Int = 0
        override fun getOutputIndex(opName: String): Int = 0
        override fun getLastNativeInferenceDurationNanoseconds(): Long = 0L
        override fun allocateTensors() {}
        override fun close() { closedCalled = true }
    }

    private fun makeDecorator(): Triple<FakeInterpreter, WildEdgeClient, PlayServicesTfliteDecorator> {
        val fake = FakeInterpreter()
        val wildEdge = testWildEdge()
        val decorator = PlayServicesTfliteDecorator(fake, wildEdge, modelId = "test-model")
        return Triple(fake, wildEdge, decorator)
    }

    @Test fun delegatesRunToInterpreter() {
        val (fake, _, decorator) = makeDecorator()
        decorator.run(Any(), Any())
        assertEquals(1, fake.runCalls)
    }

    @Test fun delegatesRunForMultipleInputsOutputs() {
        val (fake, _, decorator) = makeDecorator()
        decorator.runForMultipleInputsOutputs(arrayOf(Any()), mapOf(0 to Any()))
        assertEquals(1, fake.runCalls)
    }

    @Test fun closeCallsInterpreterClose() {
        val (fake, _, decorator) = makeDecorator()
        decorator.close()
        assertTrue(fake.closedCalled)
    }

    @Test fun handleRegisteredWithCorrectModelId() {
        val (_, _, decorator) = makeDecorator()
        assertEquals("test-model", decorator.handle.modelId)
    }

    @Test fun runFailureTracksInferenceAndRethrows() {
        val (fake, wildEdge, decorator) = makeDecorator()
        fake.failRun = true

        assertThrows(IllegalStateException::class.java) {
            decorator.run(Any(), Any())
        }

        assertEquals(1, wildEdge.pendingCount)
        assertNotNull(decorator.handle.lastInferenceId)
    }

    @Test fun multiRunFailureTracksInferenceAndRethrows() {
        val (fake, wildEdge, decorator) = makeDecorator()
        fake.failMultiRun = true

        assertThrows(IllegalArgumentException::class.java) {
            decorator.runForMultipleInputsOutputs(arrayOf(Any()), mapOf(0 to Any()))
        }

        assertEquals(1, wildEdge.pendingCount)
        assertNotNull(decorator.handle.lastInferenceId)
    }

    @Test fun defaultAcceleratorIsNull() {
        val (_, _, decorator) = makeDecorator()
        assertNull(decorator.handle.acceleratorActual)
    }

    @Test fun customAcceleratorFlowsToHandle() {
        val fake = FakeInterpreter()
        val decorator = PlayServicesTfliteDecorator(fake, testWildEdge(), modelId = "m", accelerator = Accelerator.GPU)
        assertEquals(Accelerator.GPU, decorator.handle.acceleratorActual)
    }

    @Test fun noOutputMetaByDefault() {
        val (wildEdge, queue) = testWildEdgeWithQueue()
        val decorator = PlayServicesTfliteDecorator(FakeInterpreter(), wildEdge, modelId = "m")
        decorator.run(Any(), Array(1) { FloatArray(3) { 1f } })
        @Suppress("UNCHECKED_CAST")
        val inference = queue.peekMany(1).first()["inference"] as Map<String, Any?>
        assertNull(inference["output_meta"])
    }

    @Test fun outputMetaPresentWhenNumClassesSet() {
        val (wildEdge, queue) = testWildEdgeWithQueue()
        val output = Array(1) { FloatArray(3) { 0f }.also { it[1] = 10f } }
        val decorator = PlayServicesTfliteDecorator(FakeInterpreter(), wildEdge, modelId = "m", numClasses = 3)
        decorator.run(Any(), output)
        @Suppress("UNCHECKED_CAST")
        val inference = queue.peekMany(1).first()["inference"] as Map<String, Any?>
        assertNotNull(inference["output_meta"])
        @Suppress("UNCHECKED_CAST")
        val meta = inference["output_meta"] as Map<String, Any?>
        assertEquals("classification", meta["task"])
    }

    @Test fun outputMetaUsesLabelsWhenProvided() {
        val (wildEdge, queue) = testWildEdgeWithQueue()
        val output = Array(1) { FloatArray(3) { 0f }.also { it[2] = 10f } }
        val labels = listOf("cat", "dog", "bird")
        val decorator = PlayServicesTfliteDecorator(FakeInterpreter(), wildEdge, modelId = "m", labels = labels)
        decorator.run(Any(), output)
        @Suppress("UNCHECKED_CAST")
        val inference = queue.peekMany(1).first()["inference"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val topK = (inference["output_meta"] as Map<String, Any?>)["top_k"] as List<Map<String, Any?>>
        assertEquals("bird", topK.first()["label"])
    }
}
