package dev.wildedge.sdk

/** Type of user feedback attached to an inference result. */
sealed class FeedbackType(val value: String) {
    /** User explicitly approved the output. */
    object ThumbsUp : FeedbackType("thumbs_up")

    /** User explicitly rejected the output. */
    object ThumbsDown : FeedbackType("thumbs_down")

    /** User accepted a suggestion without modification. */
    object Accepted : FeedbackType("accepted")

    /** User edited the output before using it. */
    object Edited : FeedbackType("edited")

    /** User discarded the output entirely. */
    object Rejected : FeedbackType("rejected")

    /** Application-defined custom feedback type. */
    class Custom(value: String) : FeedbackType(value)
}
