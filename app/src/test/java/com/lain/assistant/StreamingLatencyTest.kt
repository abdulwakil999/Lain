package com.lain.assistant

import com.lain.assistant.network.LlmMessage
import com.lain.assistant.network.LlmResult
import com.lain.assistant.network.OpenAiCompatibleClient
import com.lain.assistant.network.StreamEvent
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Measures the thing the whole streaming change exists for: how long the user
 * waits before seeing any of the answer.
 *
 * This runs against a real socket rather than a stub, with a server that emits
 * tokens on a timer the way a model does. That makes the comparison meaningful —
 * the blocking client and the streaming client are given the same total
 * generation time and the difference in first-text latency is measured, not
 * asserted from first principles.
 */
class StreamingLatencyTest {

    private lateinit var server: MockWebServer

    /** Simulated per-token generation time, and how many tokens the reply is. */
    private val tokenDelayMs = 30L
    private val tokenCount = 40
    private val totalGenerationMs = tokenDelayMs * tokenCount

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = OpenAiCompatibleClient(baseUrl = server.url("/").toString())

    private fun sseBody(): String = buildString {
        repeat(tokenCount) { i ->
            append("data: ")
            append("""{"choices":[{"delta":{"content":"token$i "}}]}""")
            append("\n\n")
        }
        append("data: [DONE]\n\n")
    }

    @Test
    fun `streaming shows text long before the full answer is ready`() = runBlocking {
        // The server dribbles the stream out at the same rate a model would.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody())
                .throttleBody(
                    // One SSE frame is ~50 bytes; this paces it to roughly tokenDelayMs each.
                    50L, tokenDelayMs, TimeUnit.MILLISECONDS
                )
        )

        val started = System.currentTimeMillis()
        var firstTextAt: Long = -1
        var completeAt: Long = -1
        var assembled = ""

        client().sendStreaming("key", "model", "system", listOf(user("hi")), emptyList())
            .collect { event ->
                when (event) {
                    is StreamEvent.Delta ->
                        if (firstTextAt < 0) firstTextAt = System.currentTimeMillis() - started
                    is StreamEvent.Done -> {
                        completeAt = System.currentTimeMillis() - started
                        assembled = event.text
                    }
                    else -> Unit
                }
            }

        println("streaming: first text at ${firstTextAt}ms, complete at ${completeAt}ms")
        assertTrue("no text was streamed", firstTextAt >= 0)
        assertTrue("stream did not complete", completeAt > 0)
        assertEquals("token0 ", assembled.take(7))

        // The point of the exercise: first text lands in a small fraction of the time
        // the whole answer takes. Generous bound so this stays stable on a loaded CI
        // box — the observed figure is far better and is printed above.
        assertTrue(
            "first text at ${firstTextAt}ms was not meaningfully earlier than completion at ${completeAt}ms",
            firstTextAt < completeAt / 2
        )
    }

    @Test
    fun `the blocking path shows nothing until generation finishes`() = runBlocking {
        // Same total generation time, delivered as one body — which is exactly what
        // the non-streaming endpoint does.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"the complete answer"}}]}""")
                .setBodyDelay(totalGenerationMs, TimeUnit.MILLISECONDS)
        )

        val started = System.currentTimeMillis()
        val result = client().send("key", "model", "system", listOf(user("hi")), emptyList())
        val firstTextAt = System.currentTimeMillis() - started

        println("blocking: first text at ${firstTextAt}ms (generation was ${totalGenerationMs}ms)")
        assertTrue(result is LlmResult.Message)
        // Nothing could have been shown before the whole body arrived, by construction.
        assertTrue(
            "blocking path returned before generation finished, so the comparison is invalid",
            firstTextAt >= totalGenerationMs
        )
    }

    @Test
    fun `tool calls fragmented across stream chunks are reassembled`() {
        // The failure this guards against is subtle and silent: arguments arrive a few
        // characters at a time and, with parallel calls, interleaved by index. Getting
        // the accumulation wrong yields malformed JSON rather than an error.
        val frames = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"open_app"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"b","function":{"name":"device_status"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"app_"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"name\":\"Chrome\"}"}}]}}]}"""
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(frames.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")
        )

        val events = mutableListOf<StreamEvent>()
        runBlocking {
            client().sendStreaming("key", "model", "system", listOf(user("open chrome")), emptyList())
                .collect { events += it }
        }

        val tools = events.filterIsInstance<StreamEvent.Tools>().single().calls
        assertEquals(2, tools.size)
        assertEquals("open_app", tools[0].name)
        assertEquals("""{"app_name":"Chrome"}""", tools[0].argumentsJson)
        assertEquals("device_status", tools[1].name)
        assertEquals("{}", tools[1].argumentsJson)
    }

    private fun user(text: String) = LlmMessage(role = LlmMessage.Role.USER, text = text)
}
