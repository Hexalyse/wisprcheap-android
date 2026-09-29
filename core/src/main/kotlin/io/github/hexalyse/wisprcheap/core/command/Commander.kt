package io.github.hexalyse.wisprcheap.core.command

import io.github.hexalyse.wisprcheap.core.http.ApiException
import io.github.hexalyse.wisprcheap.core.llm.ChatClient
import io.github.hexalyse.wisprcheap.core.llm.ChatResult
import io.github.hexalyse.wisprcheap.core.polish.PolishPrompt
import io.github.hexalyse.wisprcheap.core.settings.DictionaryEntry
import io.github.hexalyse.wisprcheap.core.settings.LlmOptions

/**
 * Command mode prompt, verbatim from the desktop `command.rs` except the first sentence: Android has no hotkey,
 * so "The user holds a hotkey and speaks an instruction" became "The user speaks an instruction".
 */
object CommandPrompt {
    val SYSTEM = listOf(
        "You are a text assistant driven by voice. The user speaks an instruction; you receive its speech-to-text " +
            "transcript (it may contain transcription errors, filler words or self-corrections: go by the intent).",
        "",
        "If a <selection> is provided, apply the instruction to that text (rewrite, shorten, translate, fix, " +
            "reformat, change the tone...). Your output replaces the selection.",
        "If there is no selection, write the text the instruction asks for (a reply, a message, a list...). Your " +
            "output is inserted at the cursor.",
        "",
        "Rules:",
        "- Return only the final text: no preamble, explanation, quotes or commentary.",
        "- Keep the language of the selection unless the instruction asks for another language. Without a " +
            "selection, write in the language of the instruction.",
        "- Keep the selection's formatting (line breaks, lists, markdown, code) unless the instruction asks to " +
            "change it.",
        "- Change only what the instruction asks for.",
        "- If the selection is code, return code only, without code fences unless the selection had them.",
    ).joinToString("\n")

    fun system(dictionary: List<DictionaryEntry>): String {
        val block = PolishPrompt.dictionaryBlock(dictionary)
        return if (block.isEmpty()) SYSTEM else "$SYSTEM\n\n$block"
    }

    fun userMessage(instruction: String, selection: String?): String =
        if (!selection.isNullOrEmpty()) {
            "<instruction>\n$instruction\n</instruction>\n\n<selection>\n$selection\n</selection>"
        } else {
            "<instruction>\n$instruction\n</instruction>\n\n(no selection)"
        }

    /** Unwraps a code fence around everything: "```lang\n...\n```" -> "...". */
    fun unwrapFence(text: String): String? {
        if (!text.startsWith("```")) return null
        var rest = text.substring(3).dropWhile { it in 'a'..'z' }
        if (!rest.startsWith("\n")) return null
        rest = rest.substring(1)
        if (!rest.endsWith("```")) return null
        return rest.substring(0, rest.length - 3).removeSuffix("\n")
    }

    fun cleanOutput(raw: String, selection: String?): String {
        var text = raw.trim()
        if (selection?.contains("```") != true) unwrapFence(text)?.let { text = it }
        if (text.startsWith("<selection>")) text = text.substring("<selection>".length).trimStart()
        if (text.endsWith("</selection>")) text = text.substring(0, text.length - "</selection>".length).trimEnd()
        return text
    }
}

class Commander(private val chat: ChatClient, private val opts: LlmOptions, dictionary: List<DictionaryEntry>) {
    val model: String get() = opts.model
    val systemPrompt: String = CommandPrompt.system(dictionary)

    /** Throws [ApiException] on failure or an empty answer. */
    suspend fun run(instruction: String, selection: String?): ChatResult {
        val result = chat.complete(opts, systemPrompt, CommandPrompt.userMessage(instruction, selection), "Command")
        val text = CommandPrompt.cleanOutput(result.text, selection)
        if (text.isBlank()) throw ApiException("Command: empty response")
        return result.copy(text = text)
    }
}
