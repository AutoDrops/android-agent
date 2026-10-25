package com.autodrops.agent

import org.junit.Assert.*
import org.junit.Test

/** v3.18: prompt-length model routing, invalid-tool-JSON self-correction, VAD auto-stop, voice-download prompt. */
class V318Test {

    // ---- ModelRouter ----
    @Test fun routingDisabledAlwaysReturnsDefault() {
        assertEquals("def", ModelRouter.choose(false, "small", "large", 1000, "def", 99999))
    }
    @Test fun routingShortPromptPicksSmall() {
        assertEquals("small", ModelRouter.choose(true, "small", "large", 1000, "def", 500))
    }
    @Test fun routingLongPromptPicksLarge() {
        assertEquals("large", ModelRouter.choose(true, "small", "large", 1000, "def", 1000))
        assertEquals("large", ModelRouter.choose(true, "small", "large", 1000, "def", 5000))
    }
    @Test fun routingBlankSlugFallsBackToDefault() {
        assertEquals("def", ModelRouter.choose(true, "", "large", 1000, "def", 10))
        assertEquals("def", ModelRouter.choose(true, "small", "   ", 1000, "def", 99999))
    }
    @Test fun routingThresholdClamped() {
        // threshold below MIN is clamped up so a tiny prompt still routes to small
        assertEquals("small", ModelRouter.choose(true, "small", "large", 1, "def", 50))
    }
    @Test fun routingExplainEmptyWhenDefault() {
        assertEquals("", ModelRouter.explain("def", "def", 10, 1000))
        assertTrue(ModelRouter.explain("large", "def", 2000, 1000).contains("large"))
    }

    // ---- ToolArgs self-correction ----
    @Test fun validJsonParsesUnrepaired() {
        val r = ToolArgs.parse("ping", """{"host":"1.2.3.4"}""")
        assertTrue(r is ToolArgs.Result.Ok)
        r as ToolArgs.Result.Ok
        assertFalse(r.repaired)
        assertEquals("1.2.3.4", r.args.getString("host"))
    }
    @Test fun emptyArgsAreValidEmptyObject() {
        assertTrue(ToolArgs.parse("x", "") is ToolArgs.Result.Ok)
        assertTrue(ToolArgs.parse("x", "{}") is ToolArgs.Result.Ok)
    }
    @Test fun markdownFencedJsonRepaired() {
        val r = ToolArgs.parse("ping", "```json\n{\"host\":\"10.0.0.1\"}\n```")
        assertTrue(r is ToolArgs.Result.Ok)
        assertEquals("10.0.0.1", (r as ToolArgs.Result.Ok).args.getString("host"))
        assertTrue(r.repaired)
    }
    @Test fun proseWrappedJsonExtracted() {
        val r = ToolArgs.parse("ping", "Sure! {\"host\":\"10.0.0.2\"} done")
        assertTrue(r is ToolArgs.Result.Ok)
        assertEquals("10.0.0.2", (r as ToolArgs.Result.Ok).args.getString("host"))
    }
    @Test fun trulyInvalidReturnsCorrection() {
        val r = ToolArgs.parse("ping", "host=1.2.3.4 not json")
        assertTrue(r is ToolArgs.Result.Invalid)
        val msg = (r as ToolArgs.Result.Invalid).correction
        assertTrue(msg.startsWith("Invalid arguments"))
        assertTrue(msg.contains("ping"))
    }
    @Test fun extractObjectIgnoresBracesInStrings() {
        assertEquals("""{"a":"}{"}""", ToolArgs.extractObject("""noise {"a":"}{"} tail"""))
    }

    // ---- SilenceDetector (VAD) ----
    @Test fun noStopBeforeSpeech() {
        val d = SilenceDetector(silenceMs = 1000, preSpeechTimeoutMs = 8000)
        d.start(0)
        assertFalse(d.shouldStop(500))
    }
    @Test fun preSpeechTimeoutStops() {
        val d = SilenceDetector(preSpeechTimeoutMs = 8000)
        d.start(0)
        assertTrue(d.shouldStop(8000))
    }
    @Test fun stopsAfterSpeechThenSilence() {
        val d = SilenceDetector(speechRms = 2f, silenceMs = 1000, minSpeechMs = 300)
        d.start(0)
        d.onRms(5f, 100); d.onRms(5f, 400) // speech 100..400
        assertFalse(d.shouldStop(900))     // not silent long enough
        assertTrue(d.speechDetected)
        assertTrue(d.shouldStop(1500))     // 1100ms of silence after last speech
    }
    @Test fun ongoingSpeechDoesNotStop() {
        val d = SilenceDetector(speechRms = 2f, silenceMs = 1000)
        d.start(0)
        for (t in 0..3000 step 200) d.onRms(5f, t.toLong())
        assertFalse(d.shouldStop(3100))
    }
    @Test fun voskActivityResetsSilence() {
        val d = SilenceDetector(silenceMs = 1000, minSpeechMs = 100)
        d.start(0)
        d.onActivity(100)
        assertFalse(d.shouldStop(900))
        d.onActivity(1500)                 // more partials arrived
        assertFalse(d.shouldStop(2000))
        assertTrue(d.shouldStop(2600))
    }

    // ---- VoicePrompt ----
    @Test fun offerWhenVoskEnabledButNotInstalled() {
        assertTrue(VoicePrompt.shouldOfferDownload(voskEnabled = true, strictOffline = false, modelInstalled = false))
    }
    @Test fun offerWhenStrictOfflineEvenIfVoskToggleOff() {
        assertTrue(VoicePrompt.shouldOfferDownload(voskEnabled = false, strictOffline = true, modelInstalled = false))
    }
    @Test fun noOfferWhenInstalled() {
        assertFalse(VoicePrompt.shouldOfferDownload(true, true, modelInstalled = true))
    }
    @Test fun noOfferWhenNeitherWanted() {
        assertFalse(VoicePrompt.shouldOfferDownload(false, false, false))
    }
    @Test fun offerMessageMentionsModel() {
        assertTrue(VoicePrompt.offerMessage("uk small").contains("uk small"))
    }
}
