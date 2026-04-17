package dev.wildedge.sdk

enum class InputModality(val value: String) {
    Image("image"),
    Audio("audio"),
    Text("text"),
    Tensor("tensor"),
    Video("video"),
    Multimodal("multimodal"),
}

enum class OutputModality(val value: String) {
    Detection("detection"),
    Generation("generation"),
    Embedding("embedding"),
    Tensor("tensor"),
    Classification("classification"),
    Segmentation("segmentation"),
}
