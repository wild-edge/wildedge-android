package dev.wildedge.sdk

/** Modality of data consumed by a model. */
enum class InputModality(val value: String) {
    Image("image"),
    Audio("audio"),
    Text("text"),
    Tensor("tensor"),
    Video("video"),
    Multimodal("multimodal"),
}

/** Modality of data produced by a model. */
enum class OutputModality(val value: String) {
    Detection("detection"),
    Generation("generation"),
    Embedding("embedding"),
    Tensor("tensor"),
    Classification("classification"),
    Segmentation("segmentation"),
}
