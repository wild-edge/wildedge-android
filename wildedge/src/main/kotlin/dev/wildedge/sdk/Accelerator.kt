package dev.wildedge.sdk

@JvmInline
value class Accelerator(val value: String) {
    companion object {
        val CPU   = Accelerator("cpu")
        val GPU   = Accelerator("gpu")
        val NPU   = Accelerator("npu")
        val NNAPI = Accelerator("nnapi")
        val DSP   = Accelerator("dsp")
    }
}
