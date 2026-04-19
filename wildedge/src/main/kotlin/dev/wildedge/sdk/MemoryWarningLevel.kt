package dev.wildedge.sdk

/** Severity of an on-device memory pressure event. */
sealed class MemoryWarningLevel(val value: String) {
    /** No memory pressure. */
    object Nominal : MemoryWarningLevel("nominal")

    /** Low-level pressure; non-critical caches should be released. */
    object Warning : MemoryWarningLevel("warning")

    /** Significant pressure; memory-intensive resources should be released. */
    object Serious : MemoryWarningLevel("serious")

    /** Critical pressure; models may be forcibly unloaded. */
    object Critical : MemoryWarningLevel("critical")

    /** Application-defined custom severity level. */
    class Custom(value: String) : MemoryWarningLevel(value)
}
