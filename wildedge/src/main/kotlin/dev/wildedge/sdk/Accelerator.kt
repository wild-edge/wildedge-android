package dev.wildedge.sdk

/** Hardware accelerator used for model inference. */
@JvmInline
value class Accelerator(val value: String) {
    /** Predefined accelerator constants. */
    companion object {
        val CPU = Accelerator("cpu")
        val GPU = Accelerator("gpu")
        val NPU = Accelerator("npu")
        val NNAPI = Accelerator("nnapi")
        val DSP = Accelerator("dsp")
    }
}
