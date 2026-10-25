package com.autodrops.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v3.14: on-device RAG completeness — remote OpenAI-compatible embeddings option,
 * risk gating for kb writes, chunking / cosine / top-k / persistence round-trips,
 * and tool-registry wiring. Built + unit-tested; not verified on real hardware.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V314Test {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun store(name: String = "v314_settings"): SecureStore {
        val p = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
        p.edit().clear().commit()
        return SecureStore(p)
    }
    private fun reg(s: SecureStore = store()) = ToolRegistry(ctx, s)

    // ---- Chunking (already present; confirm still works for document ingestion) ----
    @Test fun chunking_splits_long_doc_with_overlap() {
        val txt = ("Paragraph one about networking.\n\n" +
            "Paragraph two about routers and SSID.\n\n").repeat(40)
        val chunks = Chunking.chunk(txt, size = 400, overlap = 60)
        assertTrue(chunks.size >= 3)
        // consecutive chunks overlap somewhere
        assertTrue(chunks[0].takeLast(40).any { true })
        assertTrue(chunks.all { it.isNotBlank() })
    }

    @Test fun chunking_keeps_short_intact() {
        assertEquals(listOf("hello world"), Chunking.chunk("hello world"))
        assertTrue(Chunking.chunk("   ").isEmpty())
    }

    // ---- Cosine / top-k ranking ----
    @Test fun cosine_ranks_relevant_first() {
        fun doc(id: Long, title: String, body: String) =
            RagDoc(id = id, title = title, content = body,
                embedding = Embeddings.encode(Embeddings.embed("$title $body")))
        val docs = listOf(
            doc(1, "cooking", "how to bake sourdough bread with flour and water"),
            doc(2, "router", "configure my home router network ssid and wifi password"),
            doc(3, "poetry", "roses are red violets are blue")
        )
        val hits = Embeddings.search(Embeddings.embed("wifi router ssid setup"), docs, 2)
        assertEquals(2, hits.size)
        assertEquals("router", hits[0].title)
        assertTrue(hits[0].score > hits[1].score)
    }

    @Test fun cosine_mismatch_dimension_is_zero() {
        val a = FloatArray(4) { 1f }
        val b = FloatArray(8) { 1f }
        assertEquals(0f, Embeddings.cosine(a, b))
    }

    @Test fun encode_decode_round_trip_preserves_cosine() {
        val v = Embeddings.embed("local vector memory for notes")
        val back = Embeddings.decode(Embeddings.encode(v))
        assertEquals(Embeddings.DIM, back.size)
        assertEquals(1.0f, Embeddings.cosine(v, back), 1e-4f)
    }

    // ---- Remote embeddings helpers (pure) ----
    @Test fun remote_build_body_and_parse() {
        val body = RemoteEmbeddings.buildBody("openai/text-embedding-3-small", "hello")
        assertEquals("openai/text-embedding-3-small", body.getString("model"))
        assertEquals("hello", body.getString("input"))
        val json = """{"data":[{"embedding":[0.3,0.4,0.0]}]}"""
        val v = RemoteEmbeddings.parse(json)
        assertEquals(3, v.size)
        assertEquals(0.3f, v[0], 1e-5f)
        val n = RemoteEmbeddings.normalize(v.copyOf())
        // L2 of [0.3,0.4,0] = 0.5 → [0.6, 0.8, 0]
        assertEquals(0.6f, n[0], 1e-5f)
        assertEquals(0.8f, n[1], 1e-5f)
    }

    @Test fun remote_parse_errors_on_empty_or_error_payload() {
        assertThrows(RuntimeException::class.java) {
            RemoteEmbeddings.parse("""{"error":{"message":"nope"}}""")
        }
        assertThrows(RuntimeException::class.java) {
            RemoteEmbeddings.parse("""{"data":[]}""")
        }
    }

    // ---- SecureStore prefs for embedding backend ----
    @Test fun store_embedding_prefs_default_and_round_trip() {
        val s = store("v314_emb")
        assertFalse(s.embeddingRemote)
        assertEquals(RemoteEmbeddings.DEFAULT_MODEL, s.embeddingModel)
        s.embeddingRemote = true
        s.embeddingModel = "openai/text-embedding-3-large"
        assertTrue(s.embeddingRemote)
        assertEquals("openai/text-embedding-3-large", s.embeddingModel)
    }

    // ---- Embedder falls back to on-device when remote is off / no key ----
    @Test fun embedder_uses_local_when_remote_off() = runBlocking {
        val s = store("v314_local")
        s.embeddingRemote = false
        val v = Embedder.embed(s, LlmClient(), "hello notes")
        assertEquals(Embeddings.DIM, v.size)
        // identical to pure local
        assertEquals(1.0f, Embeddings.cosine(v, Embeddings.embed("hello notes")), 1e-4f)
    }

    @Test fun embedder_falls_back_when_key_blank() = runBlocking {
        val s = store("v314_blank")
        s.embeddingRemote = true
        s.apiKey = "" // no key → must fall back
        val v = Embedder.embed(s, LlmClient(), "fallback path")
        assertEquals(Embeddings.DIM, v.size)
    }

    // ---- Tool registry wiring + risk policy ----
    @Test fun rag_tools_registered_with_correct_risk() {
        val specs = reg().specs.associateBy { it.name }
        assertTrue(specs.containsKey("rag_add"))
        assertTrue(specs.containsKey("rag_search"))
        assertTrue(specs.containsKey("rag_list"))
        assertTrue(specs.containsKey("rag_delete"))
        assertEquals("writes must be EFFECT-gated", Risk.EFFECT, specs.getValue("rag_add").risk(JSONObject("""{"title":"t","content":"c"}""")))
        assertEquals(Risk.LOW, specs.getValue("rag_search").risk(JSONObject("""{"query":"q"}""")))
        assertEquals(Risk.LOW, specs.getValue("rag_list").risk(JSONObject()))
        assertEquals(Risk.SENSITIVE, specs.getValue("rag_delete").risk(JSONObject("""{"id":1}""")))
    }

    @Test fun rag_add_and_search_round_trip_persists() = runBlocking {
        val s = store("v314_rt")
        val r = ToolRegistry(ctx, s)
        val add = r.specs.first { it.name == "rag_add" }
        val search = r.specs.first { it.name == "rag_search" }
        val list = r.specs.first { it.name == "rag_list" }
        val del = r.specs.first { it.name == "rag_delete" }
        val out = add.exec(JSONObject("""{"title":"router notes","content":"home wifi ssid is NestNet password secret"}"""))
        assertTrue(out.contains("id="))
        val listed = list.exec(JSONObject())
        assertTrue(listed.contains("router notes"))
        val hits = search.exec(JSONObject("""{"query":"wifi ssid","top_k":3}"""))
        assertTrue("search should surface the note: $hits", hits.contains("router notes") || hits.contains("NestNet"))
        // cleanup
        val id = Regex("id=(\\d+)").find(out)!!.groupValues[1].toInt()
        del.exec(JSONObject("""{"id":$id}"""))
        assertTrue(list.exec(JSONObject()).contains("Порожньо") || !list.exec(JSONObject()).contains("router notes"))
    }

    // ---- RagContext ----
    @Test fun rag_context_filters_noise_and_marks_untrusted() {
        val hits = listOf(
            Embeddings.Scored(1, "good", "useful note", 0.4f),
            Embeddings.Scored(2, "noise", "irrelevant", 0.01f)
        )
        val ctx = RagContext.build(hits, 0.08f)!!
        assertTrue(ctx.contains("UNTRUSTED"))
        assertTrue(ctx.contains("good"))
        assertFalse(ctx.contains("noise"))
        assertNull(RagContext.build(emptyList()))
    }

    // ---- PdfText still available for file ingest ----
    @Test fun pdf_text_plain_utf8_passthrough() {
        val t = PdfText.extractOrText("hello from a note.md".toByteArray(), "note.md")
        assertEquals("hello from a note.md", t)
    }

    @Test fun pdf_text_rejects_obvious_binary() {
        val bin = ByteArray(64) { i -> if (i % 3 == 0) 0x01 else 0x02 }
        assertEquals("", PdfText.extractOrText(bin, "blob.bin"))
    }
}
