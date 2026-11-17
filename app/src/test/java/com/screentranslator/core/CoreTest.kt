package com.screentranslator.core

import org.junit.Assert.*
import org.junit.Test

class CoordinateMapperTest {
    @Test fun identicalDisplayPreservesBounds() {
        val box = Box(14f, 80f, 500f, 180f)
        assertEquals(box, CoordinateMapper(1080, 2400, Box(0f, 0f, 1080f, 2400f)).map(box))
    }
    @Test fun scalesImageAndLetterboxesInLandscape() {
        val m = CoordinateMapper(100, 200, Box(0f, 0f, 400f, 200f))
        assertEquals(Box(150f, 0f, 250f, 200f), m.map(Box(0f, 0f, 100f, 200f)))
    }
    @Test fun subtractsOverlayOriginIncludingStatusBar() {
        val m = CoordinateMapper(1080, 2400, Box(-30f, -80f, 1050f, 2320f))
        assertEquals(Box(70f, 120f, 170f, 220f), m.map(Box(100f, 200f, 200f, 300f)))
    }
    @Test fun downscalesAndClipsOutsideOcrBounds() {
        val m = CoordinateMapper(200, 400, Box(10f, 20f, 110f, 220f))
        assertEquals(Box(10f, 20f, 110f, 220f), m.map(Box(-20f, -30f, 250f, 450f)))
    }
    @Test fun landscapeFrameDoesNotReceiveExtraRotation() {
        val m = CoordinateMapper(2400, 1080, Box(0f, 0f, 1200f, 540f))
        assertEquals(Box(50f, 100f, 150f, 150f), m.map(Box(100f, 200f, 300f, 300f)))
    }
    @Test(expected = IllegalArgumentException::class) fun emptyFrameRejected() { CoordinateMapper(0, 100, Box(0f, 0f, 10f, 10f)) }
}
class LanguagesTest {
    @Test fun sixLatinSourcesSupportRussianTarget() { Languages.sourceCodes.forEach { assertTrue(Languages.supports(LanguagePair(it, "ru"))) } }
    @Test fun translationSupportDoesNotEnableCyrillicOcr() { assertFalse(Languages.supports(LanguagePair("ru", "en"))) }
    @Test fun rejectsUnknownAndIdenticalLanguages() {
        assertFalse(Languages.supports(LanguagePair("en", "xx")))
        assertFalse(Languages.supports(LanguagePair("en", "en")))
    }
    @Test fun targetCodesAreUniqueAndCompleteForPinnedSdk() {
        assertEquals(com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages().toSet(), Languages.targets.map { it.code }.toSet())
        assertEquals(59, Languages.targets.map { it.code }.toSet().size)
    }
}
class SessionCoordinatorTest {
    @Test fun completePipelineAndHide() {
        val c = SessionCoordinator(); c.start(); val id = c.begin()
        assertTrue(c.advance(id, Phase.RECOGNIZING)); assertTrue(c.advance(id, Phase.TRANSLATING)); assertTrue(c.advance(id, Phase.SHOWING))
        c.ready(); assertEquals(Phase.READY, c.state.value.phase); assertFalse(c.accepts(id))
    }
    @Test fun cancelledRequestCannotPublishAfterAnotherRequest() {
        val c = SessionCoordinator(); c.start(); val old = c.begin(); c.ready(); val fresh = c.begin()
        assertFalse(c.advance(old, Phase.RECOGNIZING)); assertFalse(c.accepts(old)); assertTrue(c.advance(fresh, Phase.RECOGNIZING))
    }
    @Test fun errorAllowsRetryAndInvalidatesLateSuccess() {
        val c = SessionCoordinator(); c.start(); val id = c.begin(); c.fail(id, "timeout")
        assertEquals(Phase.ERROR, c.state.value.phase); assertTrue(c.state.value.active)
        assertFalse(c.accepts(id)); assertFalse(c.advance(id, Phase.SHOWING))
        assertTrue(c.accepts(c.begin()))
    }
    @Test fun stopInvalidatesEveryPhaseAndOldSession() {
        listOf(Phase.CAPTURING, Phase.RECOGNIZING, Phase.TRANSLATING, Phase.SHOWING).forEach { phase ->
            val c = SessionCoordinator(); c.start(); val id = c.begin()
            if (phase != Phase.CAPTURING) c.advance(id, Phase.RECOGNIZING)
            if (phase in listOf(Phase.TRANSLATING, Phase.SHOWING)) c.advance(id, Phase.TRANSLATING)
            if (phase == Phase.SHOWING) c.advance(id, Phase.SHOWING)
            c.stop(); c.stop(); assertFalse(c.state.value.active); assertFalse(c.accepts(id))
            c.start(); assertFalse(c.accepts(id))
        }
    }
    @Test fun lateErrorCannotOverwriteFreshRequestAndInvalidTransitionsAreRejected() {
        val c = SessionCoordinator(); c.start(); val old = c.begin(); val fresh = c.begin()
        c.fail(old, "late error"); assertEquals(Phase.CAPTURING, c.state.value.phase)
        assertFalse(c.advance(fresh, Phase.SHOWING)); assertTrue(c.advance(fresh, Phase.RECOGNIZING))
    }
    @Test fun orientationChangeInvalidatesResult() {
        val c = SessionCoordinator(); c.start(); val id = c.begin(); c.advance(id, Phase.RECOGNIZING)
        c.ready(); assertFalse(c.advance(id, Phase.TRANSLATING)); assertEquals(Phase.READY, c.state.value.phase)
    }
}
class TranslationCacheTest {
    @Test fun boundedLruSeparatesLanguagePairs() {
        val c = TranslationCache(2)
        val a = TranslationKey("Hello", LanguagePair("en", "ru"))
        val b = TranslationKey("Hello", LanguagePair("en", "de"))
        val d = TranslationKey("World", LanguagePair("en", "ru"))
        c.put(a, "Привет"); c.put(b, "Hallo"); assertEquals("Привет", c.get(a))
        c.put(d, "Мир"); assertNull(c.get(b)); assertEquals("Привет", c.get(a)); c.clear(); assertNull(c.get(a))
    }
}
