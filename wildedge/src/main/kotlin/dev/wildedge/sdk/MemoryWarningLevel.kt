package dev.wildedge.sdk

sealed class MemoryWarningLevel(val value: String) {
    object Nominal : MemoryWarningLevel("nominal")
    object Warning : MemoryWarningLevel("warning")
    object Serious : MemoryWarningLevel("serious")
    object Critical : MemoryWarningLevel("critical")
    class Custom(value: String) : MemoryWarningLevel(value)
}
