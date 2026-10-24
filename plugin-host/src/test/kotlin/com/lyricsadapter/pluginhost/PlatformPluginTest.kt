package com.lyricsadapter.pluginhost

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Real time (runBlocking): the runtime's timers and the transport run on real threads. */
class PlatformPluginTest {
    private val server = MockWebServer()
    private val store = InMemoryPluginDataStore()
    private val environment = PluginEnvironment(logger = { _, _, _ -> }, store = store)

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun manifest(extra: String = "") = PlatformManifest.parse(
        Json.parseToJsonElement(
            """
            {"manifestVersion":1,"id":"echo-translation","name":"Echo","version":"1.0.0","main":"index.cjs",
             "coreApi":"^1.1.0","extensionApis":{"lyrics.translation":"^1.1.0"},
             "permissions":{"secrets":"own"},
             "contributes":{"providers":[{"type":"lyrics.translation","id":"default","name":"Echo"$extra}],
               "configuration":{
                 "endpoint":{"title":"Base URL","type":"string","default":"","format":"endpoint"},
                 "api-key":{"title":"Key","type":"string","default":"","format":"secret"},
                 "prefix":{"title":"Prefix","type":"string","default":"T:"}}}}
            """,
        ).jsonObject,
    )

    /** Posts each line to the configured endpoint and prefixes the reply; remembers results in its cache. */
    private val code = """
        exports.activate = (context) => {
          context.extensions.register('lyrics.translation', 'default', {
            async translate(request, { signal }) {
              const cached = await context.cache.get(request.document.revision);
              if (cached) return cached;
              const reply = await context.network.fetchText(context.configuration.get('endpoint') + '/echo', {
                method: 'POST',
                headers: { authorization: 'Bearer ' + context.secrets.get('api-key') },
                body: JSON.stringify(request.document.lines.map((line) => line.text)),
                signal,
              });
              const texts = JSON.parse(reply);
              const result = {
                documentId: request.document.id,
                documentRevision: request.document.revision,
                targetLanguage: request.targetLanguage,
                lines: request.document.lines.map((line, index) => ({ lineId: line.id, text: context.configuration.get('prefix') + texts[index] })),
              };
              await context.cache.set(request.document.revision, result);
              return result;
            },
          });
        };
    """.trimIndent()

    private fun request(revision: String = "r1") = Json.parseToJsonElement(
        """{"document":{"id":"lyrics-1","revision":"$revision","trackId":"t1","lines":[{"id":"line-0","text":"hello"},{"id":"line-1","text":"world"}]},"targetLanguage":"zh"}""",
    ).jsonObject

    private fun texts(result: JsonObject) = result["lines"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }

    @Test
    fun `translates through the configured endpoint with the plugin secret, then from its cache`() = runBlocking<Unit> {
        val manifest = manifest()
        server.enqueue(MockResponse.Builder().body("""["你好","世界"]""").build())
        val plugin = PlatformPlugin.start(manifest, code, environment, cacheDirectory = null)
        plugin.use {
            it.settings.set("endpoint", JsonPrimitive(server.url("/v1").toString().trimEnd('/')))
            it.settings.set("api-key", JsonPrimitive("sk-test"))

            assertEquals(listOf("T:你好", "T:世界"), texts(it.translate("default", request())))
            val sent = server.takeRequest()
            assertEquals("Bearer sk-test", sent.headers["authorization"])
            assertEquals("""["hello","world"]""", sent.body?.utf8())

            // Cached: the server has nothing more queued, so a second request would fail.
            assertEquals(listOf("T:你好", "T:世界"), texts(it.translate("default", request())))
        }
        assertEquals("sk-test", store.readSecret("echo-translation", "api-key"), "secret fields live with the plugin's secrets")
    }

    @Test
    fun `refuses origins the plugin did not declare or configure`() = runBlocking<Unit> {
        PlatformPlugin.start(manifest(), code, environment, cacheDirectory = null).use { plugin ->
            val error = assertFailsWith<PluginCallException> { plugin.translate("default", request()) }
            assertTrue("not declared" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `rejects a result for another document`() = runBlocking<Unit> {
        val wrong = code.replace("documentRevision: request.document.revision", "documentRevision: 'other'")
        server.enqueue(MockResponse.Builder().body("""["a","b"]""").build())
        PlatformPlugin.start(manifest(), wrong, environment, cacheDirectory = null).use { plugin ->
            plugin.settings.set("endpoint", JsonPrimitive(server.url("/").toString().trimEnd('/')))
            assertFailsWith<IllegalStateException> { plugin.translate("default", request()) }
        }
    }

    @Test
    fun `validates manifests as desktop does`() {
        assertEquals(30_000L, manifest(""","timeoutSeconds":30""").translationTimeoutMillis("default"))
        assertFailsWith<IllegalArgumentException> { manifest(""","timeoutSeconds":5""") }
        assertTrue(PlatformManifest.supportsApi("1.1.0", "^1.0.0"))
        assertTrue(!PlatformManifest.supportsApi("1.1.0", "^1.2.0"))
        assertEquals("http://127.0.0.1:8080", PlatformManifest.networkOrigin("http://127.0.0.1:8080/v1"))
        assertEquals(null, PlatformManifest.networkOrigin("http://example.com/v1"))
        assertEquals("https://api.openai.com", PlatformManifest.networkOrigin("https://api.openai.com/v1"))
    }
}
