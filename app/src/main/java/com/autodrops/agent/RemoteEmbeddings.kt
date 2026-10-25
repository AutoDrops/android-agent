package com.autodrops.agent

import org.json.JSONObject

/**
 * Pure helpers for the OpenAI-compatible /embeddings endpoint (OpenRouter or any compatible host).
 * Request/response shaping is kept side-effect-free so it is fully unit-testable; the actual HTTP
 * call lives in LlmClient.embed. Higher-quality semantic vectors than the on-device hashing fallback.
 */
object RemoteEmbeddings {
    const val DEFAULT_MODEL = "openai/text-embedding-3-small"

    fun buildBody(model: String, input: String): JSONObject =
        JSONObject().put("model", model.trim()).put("input", input)

    /** Parse an OpenAI-style embeddings response: { data: [ { embedding: [...] } ] }. */
    fun parse(json: String): FloatArray {
        val o = JSONObject(json)
        o.optJSONObject("error")?.let { throw RuntimeException(it.optString("message", "embeddings error")) }
        val data = o.optJSONArray("data") ?: throw RuntimeException("no embeddings data")
        if (data.length() == 0) throw RuntimeException("empty embeddings data")
        val arr = data.getJSONObject(0).getJSONArray("embedding")
        return FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
    }

    /** L2-normalize so cosine == dot product, matching the on-device index convention. */
    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += (x * x).toDouble()
        n = Math.sqrt(n)
        if (n > 0) for (i in v.indices) v[i] = (v[i] / n).toFloat()
        return v
    }
}

/**
 * Chooses the embedding backend: remote OpenAI-compatible embeddings when the user turned it on and a
 * key is present, otherwise the deterministic on-device fallback. Remote failures fall back to local,
 * so ingestion/search never hard-fail. Mixed-dimension vectors are safe: cosine() returns 0 on a size
 * mismatch, so stale-dimension docs are simply ignored rather than corrupting results.
 */
object Embedder {
    suspend fun embed(store: SecureStore, llm: LlmClient, text: String): FloatArray {
        if (store.embeddingRemote && store.apiKey.isNotBlank() && store.baseUrlIsSafe()) {
            val remote = runCatching {
                llm.embed(store.baseUrl, store.apiKey, store.embeddingModel, text)
            }.getOrNull()
            if (remote != null && remote.isNotEmpty()) return RemoteEmbeddings.normalize(remote)
        }
        return Embeddings.embed(text)
    }
}
