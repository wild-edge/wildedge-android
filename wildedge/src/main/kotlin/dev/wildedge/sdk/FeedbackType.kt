package dev.wildedge.sdk

sealed class FeedbackType(val value: String) {
    object ThumbsUp : FeedbackType("thumbs_up")
    object ThumbsDown : FeedbackType("thumbs_down")
    object Accepted : FeedbackType("accepted")
    object Edited : FeedbackType("edited")
    object Rejected : FeedbackType("rejected")
    class Custom(value: String) : FeedbackType(value)
}
