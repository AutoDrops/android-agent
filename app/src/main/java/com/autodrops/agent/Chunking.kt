package com.autodrops.agent

/** Pure text utilities for RAG file ingestion: chunk large documents into overlapping windows. */
object Chunking {
    /** Split text into ~size-char chunks with overlap, preferring paragraph/sentence boundaries. */
    fun chunk(text: String, size: Int = 1200, overlap: Int = 150): List<String> {
        val clean = text.replace("\r\n", "\n").replace("\r", "\n").trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= size) return listOf(clean)
        val out = ArrayList<String>()
        var start = 0
        while (start < clean.length) {
            var end = minOf(start + size, clean.length)
            if (end < clean.length) {
                val window = clean.substring(start, end)
                val brk = window.lastIndexOfAny(charArrayOf('\n', '.', '!', '?'))
                if (brk > size / 2) end = start + brk + 1
            }
            val piece = clean.substring(start, end).trim()
            if (piece.isNotEmpty()) out.add(piece)
            if (end >= clean.length) break
            start = maxOf(end - overlap, start + 1)
        }
        return out
    }
}
