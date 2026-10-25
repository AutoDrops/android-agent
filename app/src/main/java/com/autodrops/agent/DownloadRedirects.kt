package com.autodrops.agent

/**
 * v3.21 fix: GitHub release assets and Hugging Face files ALWAYS redirect to their CDN host, so the old
 * "same host only" rule blocked every real download. Allow only the known first-party CDNs of the origin;
 * any other cross-host redirect is still blocked (integrity is also enforced by the pinned SHA-256).
 */
object DownloadRedirects {
    private val CDNS = mapOf(
        "github.com" to listOf("release-assets.githubusercontent.com", "objects.githubusercontent.com"),
        "huggingface.co" to listOf("hf.co", "huggingface.co", "xethub.hf.co"),
        "alphacephei.com" to listOf("alphacephei.com"),
    )
    fun allowed(origin: String, final: String): Boolean {
        val o = origin.lowercase(); val f = final.lowercase()
        if (o == f) return true
        val list = CDNS.entries.firstOrNull { o == it.key || o.endsWith("." + it.key) }?.value ?: return false
        return list.any { f == it || f.endsWith(".$it") }
    }
}
