package com.architact.hermesvoice.speech

/**
 * One spoken request assembled from several recognizer segments. Android recognizers end a
 * session at the first short pause, so listening restarts after each segment and the pieces are
 * joined here until the user stays quiet for the chosen patience.
 */
class Utterance {
    private val segments = mutableListOf<String>()

    /** Text of the segment currently being spoken (partial results), not yet final. */
    var partial: String = ""
        set(value) { field = value.trim() }

    val hasFinalText get() = segments.isNotEmpty()

    fun addSegment(text: String) {
        val clean = text.trim()
        if (clean.isNotEmpty()) segments += clean
        partial = ""
    }

    /** Everything heard so far, including the segment still in progress. */
    fun text(): String = (segments + partial).filter { it.isNotEmpty() }.joinToString(" ")
}

/** How long to wait after a pause before treating a spoken request as finished. */
enum class ListeningPatience(val millis: Long, val label: String) {
    Short(1_200, "짧게"),
    Normal(2_200, "보통"),
    Long(3_500, "길게");

    fun next(): ListeningPatience = entries[(ordinal + 1) % entries.size]
}
