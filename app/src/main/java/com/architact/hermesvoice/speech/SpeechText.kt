package com.architact.hermesvoice.speech

/** Turns an agent reply (often Markdown) into text that reads naturally through TTS. */
object SpeechText {
    private val codeBlock = Regex("```[\\s\\S]*?```")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val url = Regex("https?://\\S+")
    private val inlineCode = Regex("`([^`]*)`")
    private val emphasis = Regex("(\\*\\*|__|\\*|~~)(\\S(?:.*?\\S)?)\\1")
    private val heading = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
    private val bullet = Regex("(?m)^\\s*(?:[-*+]|\\d+[.)])\\s+")
    private val quote = Regex("(?m)^\\s*>\\s?")
    private val tableRule = Regex("(?m)^\\s*\\|?[\\s:|-]+\\|[\\s:|-]*$")
    private val spaces = Regex("[ \\t]+")
    private val blankLines = Regex("\\n{2,}")

    fun forSpeech(markdown: String): String = markdown
        .replace(codeBlock, " 코드는 화면을 확인해 주세요. ")
        .replace(link, "$1")
        .replace(url, "링크")
        .replace(inlineCode, "$1")
        .replace(emphasis, "$2")
        .replace(heading, "")
        .replace(bullet, "")
        .replace(quote, "")
        .replace(tableRule, "")
        .replace("|", " ")
        .replace(spaces, " ")
        .replace(blankLines, "\n")
        .trim()

    /** Splits [text] into pieces no longer than [maxLength], preferring sentence then word boundaries. */
    fun chunks(text: String, maxLength: Int): List<String> {
        require(maxLength > 0)
        val result = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > maxLength) {
            val window = rest.substring(0, maxLength)
            val sentenceEnd = window.indexOfLast { it == '.' || it == '?' || it == '!' || it == '\n' || it == '。' }
            val cut = when {
                sentenceEnd >= maxLength / 2 -> sentenceEnd + 1
                window.lastIndexOf(' ') >= maxLength / 2 -> window.lastIndexOf(' ') + 1
                else -> maxLength
            }
            result += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) result += rest
        return result
    }
}
