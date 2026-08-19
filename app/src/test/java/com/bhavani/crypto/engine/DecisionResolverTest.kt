package com.bhavani.crypto.engine

import com.bhavani.crypto.model.Asset
import com.bhavani.crypto.model.Book
import com.bhavani.crypto.model.BookLevel
import com.bhavani.crypto.model.Candle
import com.bhavani.crypto.model.DerivativesSnapshot
import com.bhavani.crypto.model.DecisionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionResolverTest {
    private val resolver = DecisionResolver()

    @Test
    fun insufficientDataWaits() {
        val d = resolver.resolve(
            Asset.BTC, 100.0, (1..20).map { it.toDouble() },
            emptyList(), emptyList(), emptyList(), Book(), DerivativesSnapshot(),
            0.0, 0.0, true, System.currentTimeMillis()
        )
        assertEquals(DecisionState.WAIT, d.state)
    }

    @Test
    fun disconnectedWaits() {
        val d = resolver.resolve(
            Asset.BTC, 100.0, (1..40).map { it.toDouble() },
            emptyList(), emptyList(), emptyList(), Book(), DerivativesSnapshot(),
            0.0, 0.0, false, System.currentTimeMillis()
        )
        assertEquals(DecisionState.WAIT, d.state)
    }

    @Test
    fun strongAlignedEvidenceCanProduceLong() {
        val now = System.currentTimeMillis()
        val history = (1..80).map { it.toDouble() }
        val candles = (1..20).map {
            Candle(now - (20L - it) * 60_000L, it.toDouble(), it + 1.0, it - 0.2, it + 0.9, 10.0, 7.0, 3.0)
        }
        val book = Book(
            bid = 180.0, bidQty = 100.0, ask = 180.01, askQty = 1.0,
            bids = listOf(BookLevel(180.0, 100.0)),
            asks = listOf(BookLevel(180.01, 1.0)),
            time = now
        )
        val d = resolver.resolve(
            Asset.BTC, 180.0, history, candles, candles, candles, book,
            DerivativesSnapshot(updatedAt = now), 10.0, 0.8, true, now
        )
        assertTrue(d.score >= 70)
        assertEquals(DecisionState.LONG, d.state)
    }

    @Test
    fun staleCoreDataWaits() {
        val old = System.currentTimeMillis() - 20_000L
        val history = (1..60).map { it.toDouble() }
        val candles = listOf(Candle(old, 1.0, 2.0, 0.5, 1.8, 10.0))
        val book = Book(bid = 1.79, ask = 1.80, bidQty = 10.0, askQty = 10.0, time = old)
        val d = resolver.resolve(
            Asset.BTC, 1.8, history, candles, candles, candles, book,
            DerivativesSnapshot(), 0.0, 0.0, true, System.currentTimeMillis()
        )
        assertEquals(DecisionState.WAIT, d.state)
        assertTrue(d.reason.contains("stale", ignoreCase = true))
    }

    @Test
    fun conflictingEvidenceCannotEnter() {
        val now = System.currentTimeMillis()
        val history = (1..80).map { it.toDouble() }
        val candles = (1..20).map {
            Candle(now - (20L - it) * 60_000L, it.toDouble(), it + 1.0, it - 0.2, it + 0.9, 10.0, 7.0, 3.0)
        }
        val book = Book(
            bid = 180.0, bidQty = 1.0, ask = 180.01, askQty = 100.0,
            bids = listOf(BookLevel(180.0, 1.0)),
            asks = listOf(BookLevel(180.01, 100.0)),
            time = now
        )
        val d = resolver.resolve(
            Asset.BTC, 180.0, history, candles, candles, candles, book,
            DerivativesSnapshot(updatedAt = now), -10.0, 0.8, true, now
        )
        assertTrue(d.state != DecisionState.LONG)
    }
}
