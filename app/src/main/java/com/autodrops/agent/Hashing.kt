package com.autodrops.agent

import java.security.MessageDigest

/** SHA-256 utilities for verifying downloaded model files (supply-chain protection). */
object Hashing {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(file: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Constant-time-ish, case-insensitive comparison of a computed hash to an expected one. */
    fun verify(computed: String, expected: String): Boolean {
        val a = computed.trim().lowercase(); val b = expected.trim().lowercase()
        if (a.length != b.length || a.isEmpty()) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
