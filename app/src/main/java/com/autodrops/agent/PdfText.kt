package com.autodrops.agent

/** Extracts text from an ingested file. PDFs via PdfBox-Android; everything else decoded as UTF-8 text. */
object PdfText {
    fun extractOrText(bytes: ByteArray, name: String): String {
        val lower = name.lowercase()
        val looksPdf = lower.endsWith(".pdf") || (bytes.size > 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte())
        if (looksPdf) {
            return runCatching {
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes).use { doc ->
                    com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc)
                }
            }.getOrElse { "" }.let { Ocr.cleanup(it) }
        }
        // Plain text (txt/md/csv/log/code). Reject obvious binary.
        val text = runCatching { bytes.decodeToString() }.getOrDefault("")
        val controlRatio = if (text.isEmpty()) 1.0 else text.count { it.code < 9 || (it.code in 14..31) }.toDouble() / text.length
        return if (controlRatio > 0.1) "" else text
    }
}
