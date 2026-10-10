package com.geoint.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.http.StreamResponse
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.WebFetchTool20250910
import com.anthropic.models.messages.WebFetchTool20260209
import com.anthropic.models.messages.WebSearchTool20250305
import com.anthropic.models.messages.WebSearchTool20260209
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration

enum class ClaudeModel(val id: String, val label: String, val inputPerM: Double, val outputPerM: Double, val cacheReadPerM: Double) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5 (best)", 4.0, 20.0, 0.20),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5 (cheaper)", 2.0, 10.0, 0.20),
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5 (cheapest, weakest)", 0.10, 0.50, 0.01);

    /** web_search/web_fetch _20260209 (dynamic filtering) are not available on Haiku. */
    val dynamicWebTools: Boolean get() = this != HAIKU

    /** Server-side refusal fallback ("default" routing) is offered for Opus 5.5 and Sonnet 5.5. */
    val serverFallback: Boolean get() = this != HAIKU

    companion object {
        fun fromId(id: String?): ClaudeModel = entries.firstOrNull { it.id == id } ?: OPUS
    }
}

enum class Mode { QUICK, DEEP }

data class AgentSettings(
    val apiKey: String,
    val model: ClaudeModel = ClaudeModel.OPUS,
    val effort: String = "high",
    val mode: Mode = Mode.QUICK,
    val webFetch: Boolean = true,
    val answerLanguage: String = "English",
)

class EncodedImage(val label: String, val base64Jpeg: String)

data class PhotoInput(
    val images: List<EncodedImage>,
    val metadata: String,
    val userNote: String = "",
)

sealed interface AgentEvent {
    data class Status(val text: String) : AgentEvent
    data class WebSearch(val query: String) : AgentEvent
    data class WebFetch(val url: String) : AgentEvent
    data class Library(val region: String) : AgentEvent
    data class Thinking(val delta: String) : AgentEvent
    data class Text(val delta: String) : AgentEvent
    /** A new assistant turn started: the visible text of the previous turn is superseded. */
    data object TurnStart : AgentEvent
}

data class Usage(
    var inputTokens: Long = 0,
    var outputTokens: Long = 0,
    var cacheReadTokens: Long = 0,
    var cacheWriteTokens: Long = 0,
    var webSearches: Long = 0,
) {
    /** Rough USD estimate from list prices (cache writes at 1.25x input, searches $10 / 1000). */
    fun estimatedCostUsd(model: ClaudeModel): Double =
        (inputTokens * model.inputPerM + cacheWriteTokens * model.inputPerM * 1.25 +
            cacheReadTokens * model.cacheReadPerM + outputTokens * model.outputPerM) / 1_000_000.0 +
            webSearches * 0.01
}

data class AgentOutcome(val report: String, val result: GeoResult?, val usage: Usage, val modelUsed: String)

class AgentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Runs the GeoInt loop against the Claude API: streamed turns, server-side web search/fetch, the client-side
 * read_clue_library tool, pause_turn resumption and refusal handling.
 */
class GeoAgent(
    private val settings: AgentSettings,
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(settings.apiKey)
        .timeout(Duration.ofMinutes(10))
        .maxRetries(2)
        .build(),
) {
    @Volatile private var cancelled = false
    @Volatile private var openStream: StreamResponse<RawMessageStreamEvent>? = null

    fun cancel() {
        cancelled = true
        runCatching { openStream?.close() }
    }

    fun run(input: PhotoInput, onEvent: (AgentEvent) -> Unit): AgentOutcome {
        val usage = Usage()
        val history = mutableListOf<MessageParam>()
        history += MessageParam.builder()
            .role(MessageParam.Role.USER)
            .contentOfBlockParams(firstUserContent(input))
            .build()

        var modelUsed = settings.model.id
        repeat(MAX_TURNS) { turn ->
            checkCancelled()
            onEvent(AgentEvent.Status(if (turn == 0) "Analysing the photo…" else "Continuing (step ${turn + 1})…"))
            onEvent(AgentEvent.TurnStart)
            val message = streamTurn(buildParams(history), onEvent)
            addUsage(usage, message)
            modelUsed = message.model().asString()
            history += message.toParam()

            when (message.stopReason().orElse(null)) {
                StopReason.REFUSAL -> {
                    val why = message.stopDetails().flatMap { it.explanation() }.orElse("no explanation given")
                    throw AgentException("Claude declined this photo: $why")
                }
                StopReason.PAUSE_TURN -> return@repeat // server tool loop paused; resend as is to resume
                StopReason.TOOL_USE -> {
                    history += MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(runClientTools(message, onEvent))
                        .build()
                }
                StopReason.MAX_TOKENS -> throw AgentException("The answer hit the output limit. Try Quick mode or lower effort.")
                StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> throw AgentException("The conversation got too long for the model.")
                else -> {
                    val report = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
                    if (report.isBlank()) throw AgentException("Claude returned an empty answer.")
                    return AgentOutcome(report, GeoResult.parse(report), usage, modelUsed)
                }
            }
        }
        throw AgentException("Stopped after $MAX_TURNS steps without a final answer.")
    }

    private fun checkCancelled() {
        if (cancelled) throw AgentException("Cancelled")
    }

    private fun firstUserContent(input: PhotoInput): List<ContentBlockParam> {
        val blocks = mutableListOf<ContentBlockParam>()
        for (image in input.images) {
            blocks += text(image.label)
            blocks += ContentBlockParam.ofImage(
                ImageBlockParam.builder()
                    .source(
                        Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .data(image.base64Jpeg)
                            .build()
                    )
                    .build()
            )
        }
        val modeLine = when (settings.mode) {
            Mode.QUICK -> "Speed: QUICK - gut call, read, one to three decisive checks, answer."
            Mode.DEEP -> "Speed: DEEP - pin it down as far as the evidence allows; check the strongest alternative."
        }
        blocks += text(
            buildString {
                append("Where was this photo taken?\n\n")
                append(modeLine).append('\n')
                append("Write the report in: ").append(settings.answerLanguage).append(".\n\n")
                append("## Metadata read on the phone (hypotheses, not proof)\n")
                append(input.metadata.ifBlank { "(none found)" }).append('\n')
                if (input.userNote.isNotBlank()) {
                    append("\n## Note from the user (a hypothesis too)\n").append(input.userNote.trim()).append('\n')
                }
            }
        )
        return blocks
    }

    private fun text(s: String) = ContentBlockParam.ofText(TextBlockParam.builder().text(s).build())

    internal fun buildParams(history: List<MessageParam>): MessageCreateParams {
        val model = settings.model
        val searches = if (settings.mode == Mode.DEEP) 25L else 8L
        val builder = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(64_000L)
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(SYSTEM_PROMPT)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()
                )
            )
            // Top-level automatic caching: the growing conversation (images included) is reused across turns.
            .cacheControl(CacheControlEphemeral.builder().build())
            .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(settings.effort)).build())
            .addTool(CLUE_TOOL)
            .messages(history)

        if (model.dynamicWebTools) {
            builder.addTool(WebSearchTool20260209.builder().maxUses(searches).build())
            if (settings.webFetch) builder.addTool(WebFetchTool20260209.builder().maxUses(searches).build())
        } else {
            builder.addTool(WebSearchTool20250305.builder().maxUses(searches).build())
            if (settings.webFetch) builder.addTool(WebFetchTool20250910.builder().maxUses(searches).build())
        }
        if (model.serverFallback) {
            // If a safety classifier declines, the server retries on a suitable model instead of failing.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }

    private fun streamTurn(params: MessageCreateParams, onEvent: (AgentEvent) -> Unit): Message {
        val accumulator = MessageAccumulator.create()
        // index -> (tool name, partial JSON) for server tool calls, so the UI can show queries as they happen
        val pendingServerTools = HashMap<Long, Pair<String, StringBuilder>>()
        client.messages().createStreaming(params).use { stream ->
            openStream = stream
            try {
                stream.stream().forEach { event ->
                    checkCancelled()
                    accumulator.accumulate(event)
                    event.contentBlockStart().ifPresent { start ->
                        start.contentBlock().serverToolUse().ifPresent { stu ->
                            pendingServerTools[start.index()] = stu.name().asString() to StringBuilder()
                        }
                    }
                    event.contentBlockDelta().ifPresent { d ->
                        val delta = d.delta()
                        delta.text().ifPresent { onEvent(AgentEvent.Text(it.text())) }
                        delta.thinking().ifPresent { onEvent(AgentEvent.Thinking(it.thinking())) }
                        delta.inputJson().ifPresent { j -> pendingServerTools[d.index()]?.second?.append(j.partialJson()) }
                    }
                    event.contentBlockStop().ifPresent { stop ->
                        pendingServerTools.remove(stop.index())?.let { (name, json) -> announceServerTool(name, json.toString(), onEvent) }
                    }
                }
            } catch (e: Exception) {
                if (cancelled) throw AgentException("Cancelled")
                throw e
            } finally {
                openStream = null
            }
        }
        return accumulator.message()
    }

    private fun announceServerTool(name: String, json: String, onEvent: (AgentEvent) -> Unit) {
        val input = runCatching { MAPPER.readTree(json) }.getOrNull()
        when (name) {
            "web_search" -> onEvent(AgentEvent.WebSearch(input?.get("query")?.asText() ?: "…"))
            "web_fetch" -> onEvent(AgentEvent.WebFetch(input?.get("url")?.asText() ?: "…"))
            else -> onEvent(AgentEvent.Status("Using $name…"))
        }
    }

    private fun runClientTools(message: Message, onEvent: (AgentEvent) -> Unit): List<ContentBlockParam> =
        message.content().mapNotNull { it.toolUse().orElse(null) }.map { call ->
            val result = ToolResultBlockParam.builder().toolUseId(call.id())
            if (call.name() != CLUE_TOOL_NAME) {
                result.content("Unknown tool: ${call.name()}").isError(true)
            } else {
                val region = runCatching {
                    MAPPER.readTree(MAPPER.writeValueAsString(call._input())).get("region")?.asText()
                }.getOrNull().orEmpty()
                val library = GeoIntPrompt.library(region)
                if (library == null) {
                    result.content("No library named '$region'. Use one of: ${GeoIntPrompt.LIBRARIES.keys.joinToString()}").isError(true)
                } else {
                    onEvent(AgentEvent.Library(region))
                    result.content(library)
                }
            }
            ContentBlockParam.ofToolResult(result.build())
        }

    private fun addUsage(total: Usage, message: Message) {
        val u = message.usage()
        total.inputTokens += u.inputTokens()
        total.outputTokens += u.outputTokens()
        total.cacheReadTokens += u.cacheReadInputTokens().orElse(0L)
        total.cacheWriteTokens += u.cacheCreationInputTokens().orElse(0L)
        total.webSearches += u.serverToolUse().map { it.webSearchRequests() }.orElse(0L)
    }

    companion object {
        const val MAX_TURNS = 12
        const val CLUE_TOOL_NAME = "read_clue_library"
        private val MAPPER = ObjectMapper()
        private val SYSTEM_PROMPT: String by lazy { GeoIntPrompt.systemPrompt() }

        val CLUE_TOOL: Tool = Tool.builder()
            .name(CLUE_TOOL_NAME)
            .description(
                "Load the full GeoInt clue library for one region: plates, road paint, signs, poles, " +
                    "architecture, vegetation and 'commonly confused' pairs. Load it once your shortlist points to " +
                    "a region, to separate look-alike countries. Use 'streetview' for Street View/GeoGuessr screenshots."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty(
                                "region",
                                JsonValue.from(mapOf("type" to "string", "enum" to GeoIntPrompt.LIBRARIES.keys.toList()))
                            )
                            .build()
                    )
                    .required(listOf("region"))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build()
            )
            .strict(true)
            .build()
    }
}
