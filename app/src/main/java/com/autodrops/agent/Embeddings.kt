package com.autodrops.agent

import kotlin.math.sqrt

/**
 * Lightweight, fully on-device embedding: hashing-trick bag-of-words with L2 normalization.
 * No network, no model download, deterministic — powers the local RAG index. Pure & testable.
 */
object Embeddings {
    const val DIM = 256

    fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.length in 2..40 }

    fun embed(text: String): FloatArray {
        val v = FloatArray(DIM)
        val toks = tokenize(text)
        if (toks.isEmpty()) return v
        for (t in toks) {
            val h = (t.hashCode() and 0x7fffffff) % DIM
            val sign = if ((t.hashCode() ushr 31) == 0) 1f else -1f
            v[h] += sign
        }
        var norm = 0.0
        for (x in v) norm += (x * x).toDouble()
        norm = sqrt(norm)
        if (norm > 0) for (i in v.indices) v[i] = (v[i] / norm).toFloat()
        return v
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot // both are L2-normalized
    }

    fun encode(v: FloatArray): String = v.joinToString(",") { it.toString() }
    fun decode(s: String): FloatArray =
        if (s.isBlank()) FloatArray(DIM) else s.split(",").map { it.toFloatOrNull() ?: 0f }.toFloatArray()

    data class Scored(val id: Long, val title: String, val content: String, val score: Float)

    /** Rank documents by cosine similarity to the query embedding. */
    fun search(queryVec: FloatArray, docs: List<RagDoc>, topK: Int): List<Scored> =
        docs.map { Scored(it.id, it.title, it.content, cosine(queryVec, decode(it.embedding))) }
            .sortedByDescending { it.score }
            .take(topK)
}
