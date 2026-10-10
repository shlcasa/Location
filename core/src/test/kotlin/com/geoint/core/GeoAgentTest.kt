package com.geoint.core

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoAgentTest {
    private val mapper = ObjectMapper()

    @Test
    fun parsesLastJsonBlock() {
        val report = """
            ## Answer
            **Morocco / Fes**
            ```json
            {"country": "Spain"}
            ```
            ```json
            {"country": "Morocco", "country_code": "MA", "region": "Fes-Meknes", "city": "Fes", "place": null,
             "latitude": 34.0617, "longitude": "-4.9836", "radius_km": 0.5, "confidence": "Medium", "level": "street",
             "summary": "x"}
            ```
        """.trimIndent()
        val r = assertNotNull(GeoResult.parse(report))
        assertEquals("Morocco", r.country)
        assertEquals(-4.9836, r.longitude)
        assertEquals("medium", r.confidence)
        assertNull(r.place)
        assertTrue(r.hasPin)
        assertEquals("Fes, Fes-Meknes, Morocco", r.headline)
        assertTrue(!GeoResult.stripJson(report).contains("Fes-Meknes\""))
        assertNull(GeoResult.parse("no json here"))
        assertNull(GeoResult.parse("```json\n{broken\n```"))
    }

    @Test
    fun dms() {
        assertEquals("34°03'42.1\"N 4°59'01.0\"W", toDms(34.0617, -4.9836))
        assertEquals("1°00'00.0\"S 0°00'00.0\"E", toDms(-0.999999, 0.0))
    }

    @Test
    fun systemPromptAndLibrariesLoad() {
        val prompt = GeoIntPrompt.systemPrompt()
        assertTrue(prompt.contains("Image-reading checklist"))
        assertTrue(prompt.contains("Discriminators that work everywhere"))
        for (region in GeoIntPrompt.LIBRARIES.keys) assertTrue(GeoIntPrompt.library(region)!!.length > 10_000, region)
        assertNull(GeoIntPrompt.library("mars"))
    }

    @Test
    fun requestShapePerModel() {
        val opus = GeoAgent(AgentSettings(apiKey = "k"), client = dummyClient("http://127.0.0.1:9"))
        val body = mapper.readTree(mapper.writeValueAsString(opus.buildParams(emptyList())._body()))
        val types = body["tools"].map { it["type"]?.asText() ?: "custom:" + it["name"].asText() }
        assertEquals(listOf("custom:read_clue_library", "web_search_20260209", "web_fetch_20260209"), types)
        assertEquals("default", opus.buildParams(emptyList())._additionalBodyProperties()["fallbacks"].toString().trim('"'))

        val haiku = GeoAgent(AgentSettings(apiKey = "k", model = ClaudeModel.HAIKU, webFetch = false), client = dummyClient("http://127.0.0.1:9"))
        val p = haiku.buildParams(emptyList())
        val hb = mapper.readTree(mapper.writeValueAsString(p._body()))
        assertEquals(listOf("read_clue_library", "web_search"), hb["tools"].map { it["name"].asText() })
        assertEquals("web_search_20250305", hb["tools"][1]["type"].asText())
        assertTrue(p._additionalBodyProperties().isEmpty())
    }

    @Test
    fun fullLoopAgainstFakeServer() {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/messages") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += body
            val sse = if (requests.size == 1) TURN_TOOL else TURN_FINAL
            ex.responseHeaders.add("content-type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { it.write(sse.toByteArray()) }
        }
        server.start()
        try {
            val agent = GeoAgent(AgentSettings(apiKey = "k"), client = dummyClient("http://127.0.0.1:${server.address.port}"))
            val events = mutableListOf<AgentEvent>()
            val out = agent.run(
                PhotoInput(listOf(EncodedImage("Full photo", "AAAA")), metadata = "GPS: none", userNote = "trip"),
                events::add,
            )
            assertEquals(2, requests.size)
            assertTrue(AgentEvent.WebSearch("tajine restaurant fes") in events, events.toString())
            assertTrue(AgentEvent.Library("africa-middle-east") in events)
            assertEquals("Morocco", out.result?.country)
            assertEquals(34.06, out.result?.latitude)

            val second = mapper.readTree(requests[1])
            val msgs = second["messages"]
            assertEquals(3, msgs.size())
            assertEquals("assistant", msgs[1]["role"].asText())
            val toolResult = msgs[2]["content"][0]
            assertEquals("tool_result", toolResult["type"].asText())
            assertEquals("toolu_1", toolResult["tool_use_id"].asText())
            assertTrue(toolResult.toString().contains("Morocco"))
            assertEquals("default", second["fallbacks"].asText())
            assertEquals(20 + 30, out.usage.inputTokens)
            assertEquals(1, out.usage.webSearches)
        } finally {
            server.stop(0)
        }
    }

    private fun dummyClient(baseUrl: String) =
        AnthropicOkHttpClient.builder().apiKey("k").baseUrl(baseUrl).maxRetries(0).build()

    companion object {
        private fun sse(vararg events: String) = events.joinToString("") { e ->
            val type = Regex("\"type\":\"([a-z_]+)\"").find(e)!!.groupValues[1]
            "event: $type\ndata: $e\n\n"
        }

        private const val START =
            """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":%d,"output_tokens":1}}}"""

        val TURN_TOOL = sse(
            START.format(20),
            """{"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\": \"tajine "}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"restaurant fes\"}"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[{"type":"web_search_result","title":"t","url":"https://example.com","encrypted_content":"x","page_age":null}]}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"read_clue_library","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"region\":\"africa-middle-east\"}"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":40,"server_tool_use":{"web_search_requests":1}}}""",
            """{"type":"message_stop"}""",
        )

        val TURN_FINAL = sse(
            START.format(30),
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"## Answer\n**Morocco**\n```json\n{\"country\":\"Morocco\",\"latitude\":34.06,\"longitude\":-4.98,\"confidence\":\"high\"}\n```"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":50}}""",
            """{"type":"message_stop"}""",
        )
    }
}
