package com.screentranslator.core

import com.screentranslator.search.SearchFiles
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class SelectionGeometryTest {
    @Test fun inverseMappingHandlesInsetsAndLetterboxing() {
        val mapper = CoordinateMapper(100, 200, Box(20f, 60f, 420f, 260f))
        assertEquals(Box(170f, 60f, 270f, 260f), mapper.imageBounds)
        assertEquals(Box(10f, 20f, 80f, 170f), mapper.unmap(Box(180f, 80f, 250f, 230f)))
    }
    @Test fun landscapeScaledSelectionMapsBackToOriginalPixels() {
        val mapper = CoordinateMapper(2400, 1080, Box(0f, 48f, 1200f, 588f))
        assertEquals(PixelCrop(200, 100, 1000, 600), SelectionGeometry.crop(mapper.unmap(Box(100f, 98f, 500f, 348f)), 2400, 1080))
    }
    @Test fun cropClipsAllEdgesAndRoundsOutwards() {
        assertEquals(PixelCrop(0, 0, 100, 200), SelectionGeometry.crop(Box(-10.5f, -20f, 300f, 500f), 100, 200))
        assertEquals(PixelCrop(2, 3, 41, 61), SelectionGeometry.crop(Box(2.8f, 3.2f, 40.1f, 60.4f), 100, 200))
    }
    @Test fun backwardsDragProducesSameCrop() {
        assertEquals(PixelCrop(10, 20, 90, 180), SelectionGeometry.crop(Box(90f, 180f, 10f, 20f), 100, 200))
    }
    @Test fun zeroTinyAndOutsideSelectionsAreRejected() {
        listOf(Box(0f, 0f, 0f, 0f), Box(0f, 0f, 4f, 8f), Box(110f, 10f, 200f, 90f), Box(Float.NaN, 0f, 20f, 20f)).forEach {
            assertNull(SelectionGeometry.crop(it, 100, 100))
        }
    }
    @Test fun moveClampsWithoutChangingCropSize() {
        assertEquals(Box(60f, 60f, 100f, 100f), SelectionGeometry.move(Box(10f, 10f, 50f, 50f), 500f, 400f, Box(0f, 0f, 100f, 100f)))
    }
    @Test fun resizingAtAnEdgeAndCrossingCornerRemainValid() {
        val original = Box(20f, 20f, 80f, 80f); val bounds = Box(0f, 0f, 100f, 100f)
        assertEquals(Box(20f, 20f, 100f, 80f), SelectionGeometry.resize(original, SelectionHandle.E, 300f, 30f, bounds))
        assertEquals(Box(80f, 80f, 100f, 100f), SelectionGeometry.resize(original, SelectionHandle.NW, 120f, 120f, bounds))
        assertEquals(SelectionHandle.NW, SelectionGeometry.hit(original, 19f, 19f, 10f))
        assertEquals(SelectionHandle.MOVE, SelectionGeometry.hit(original, 50f, 50f, 10f))
    }
    @Test fun selectionNeverIncludesLetterboxPixels() {
        val mapper = CoordinateMapper(100, 200, Box(0f, 0f, 400f, 200f))
        assertEquals(PixelCrop(0, 0, 100, 200), SelectionGeometry.crop(mapper.unmap(Box(0f, 0f, 400f, 200f)), 100, 200))
    }
}
class SearchQueryTest {
    @Test fun unicodeAndReservedCharactersRoundTripAsOneQueryParameter() {
        val raw = "Привет мир + & # \"quotes\"\nsecond line? q=other / 你好"
        val uri = URI(SearchQuery.googleUrl(raw)!!)
        assertEquals("https", uri.scheme); assertEquals("www.google.com", uri.host)
        assertNull(uri.fragment)
        assertEquals(1, uri.rawQuery.split('&').size)
        assertEquals(raw, URLDecoder.decode(uri.rawQuery.removePrefix("q="), "UTF-8"))
        assertTrue(uri.rawQuery.contains("%2B")); assertTrue(uri.rawQuery.contains("%26")); assertTrue(uri.rawQuery.contains("%23"))
        assertTrue(uri.rawQuery.contains("%22")); assertTrue(uri.rawQuery.contains("%0A"))
    }
    @Test fun emptyAndWhitespaceQueriesDoNotProduceAnIntentUrl() {
        listOf("", " ", "\n\t\r", "\u2003").forEach { assertNull(SearchQuery.googleUrl(it)) }
    }
}
class SearchCancellationTest {
    @Test fun searchSupersedesTranslationAndReusesTheActiveSession() {
        val c = SessionCoordinator(); c.start(); val translation = c.begin(); c.advance(translation, Phase.RECOGNIZING)
        val search = c.begin(Operation.SEARCH)
        assertFalse(c.advance(translation, Phase.TRANSLATING)); assertTrue(c.advance(search, Phase.SELECTING))
        assertTrue(c.state.value.active); assertEquals(Operation.SEARCH, c.state.value.operation)
    }
    @Test fun delayedUncancellableOcrCannotReplaceNewSelection() = runBlocking {
        val c = SessionCoordinator(); c.start(); val capture = c.begin(Operation.SEARCH); c.advance(capture, Phase.SELECTING)
        val old = c.searchAction(Phase.RECOGNIZING)
        val sdk = CompletableDeferred<String>()
        var query = "new query"
        val waiter = launch { val text = sdk.await(); if (c.advance(old, Phase.EDITING_QUERY)) query = text }
        c.searchAction(Phase.SELECTING)
        sdk.complete("stale OCR"); waiter.join()
        assertEquals("new query", query); assertEquals(Phase.SELECTING, c.state.value.phase)
    }
    @Test fun cancellingAnySearchStepReturnsToUsableTranslation() {
        listOf(Phase.SELECTING, Phase.RECOGNIZING, Phase.EDITING_QUERY, Phase.PREVIEWING_IMAGE, Phase.EXTERNAL).forEach { phase ->
            val c = SessionCoordinator(); c.start(); c.begin(Operation.SEARCH)
            val old = c.searchAction(phase); c.ready()
            assertEquals(Phase.READY, c.state.value.phase); assertFalse(c.accepts(old))
            val next = c.begin(); assertTrue(c.advance(next, Phase.RECOGNIZING))
        }
    }
    @Test fun searchErrorCanBeRetriedButOldResultsCannotBePublished() {
        val c = SessionCoordinator(); c.start(); c.begin(Operation.SEARCH)
        val old = c.searchAction(Phase.RECOGNIZING); c.fail(old, "OCR error")
        val edit = c.searchAction(Phase.EDITING_QUERY)
        assertFalse(c.advance(old, Phase.EDITING_QUERY)); assertTrue(c.accepts(edit))
        c.stop(); assertFalse(c.advance(edit, Phase.EXTERNAL)); assertFalse(c.state.value.active)
    }
}
class ButtonGestureTest {
    @Test fun dragDisarmsLongPressEvenAfterFingerReturns() {
        val g = FloatingButtonGesture(8); g.down(); g.move(20f, 0f); g.move(0f, 0f)
        assertFalse(g.longPress()); assertEquals(ButtonRelease.DRAG, g.release())
    }
    @Test fun longPressDoesNotAlsoTranslateOnRelease() {
        val g = FloatingButtonGesture(8); g.down(); g.move(2f, 1f)
        assertTrue(g.longPress()); assertFalse(g.longPress()); assertEquals(ButtonRelease.NONE, g.release())
    }
    @Test fun cancelAndTapAreDistinct() {
        val g = FloatingButtonGesture(8); g.down(); g.cancel()
        assertFalse(g.longPress()); assertEquals(ButtonRelease.NONE, g.release())
        g.down(); assertEquals(ButtonRelease.TAP, g.release()); assertFalse(g.longPress())
    }
}
class TemporaryImagePolicyTest {
    @Test fun sharedFileIsNotExpiredImmediatelyAndExpiresAfter24Hours() {
        val now = 172_800_000L
        assertFalse(TemporaryImagePolicy.expired(now, now))
        assertFalse(TemporaryImagePolicy.expired(now - TemporaryImagePolicy.MAX_AGE_MS + 1, now))
        assertTrue(TemporaryImagePolicy.expired(now - TemporaryImagePolicy.MAX_AGE_MS, now))
    }
    @Test fun providerNamesRejectTraversalAndNonImageFiles() {
        assertTrue(SearchFiles.isImageName("d418fcb5-45a3-4ff2-b099-ec73d960bd8a.png"))
        listOf("../secret.png", "test.jpg", "../settings", "uuid.part", "a.png/../b").forEach { assertFalse(SearchFiles.isImageName(it)) }
    }
}
