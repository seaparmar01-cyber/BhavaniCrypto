package com.bhavani.crypto.engine

import com.bhavani.crypto.model.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

class DecisionResolver {
    companion object {
        private const val MIN_HISTORY = 30
        private const val LONG_THRESHOLD = 70.0
        private const val SHORT_THRESHOLD = -70.0
        private const val WATCH_THRESHOLD = 40.0
        private const val MAX_DATA_AGE_MS = 5_000L
    }

    fun resolve(
        price: Double,
        history: List<Double>,
        candles1m: List<Candle>,
        candles5m: List<Candle>,
        candles15m: List<Candle>,
        book: Book,
        derivatives: DerivativesSnapshot,
        cvdDelta: Double,
        crossAssetRegime: Double,
        connected: Boolean,
        marketPriceUpdatedAt: Long = 0L,
        now: Long = System.currentTimeMillis()
    ): Decision {
        if (!connected || !price.isFinite() || price <= 0.0 || history.size < MIN_HISTORY) {
            return wait("Waiting for enough fresh market evidence.", 0.0, now)
        }

        // Candle timestamps represent the start of a completed bucket, so a healthy
        // live feed can legitimately have a candle timestamp older than 5 seconds.
        // Core freshness must therefore use the live price/tick timestamp plus the
        // live order-book timestamp, not the age of the last completed candle.
        val requiredAges = listOf(
            marketPriceUpdatedAt,
            book.time
        ).map { timestamp ->
            if (timestamp > 0L) (now - timestamp).coerceAtLeast(0L) else Long.MAX_VALUE
        }
        val optionalDerivativeAge = if (derivatives.updatedAt > 0L) {
            (now - derivatives.updatedAt).coerceAtLeast(0L)
        } else null
        val requiredAge = requiredAges.maxOrNull() ?: Long.MAX_VALUE
        val freshness = (1.0 - requiredAge.toDouble() / MAX_DATA_AGE_MS).coerceIn(0.0, 1.0)
        val derivativeFreshness = optionalDerivativeAge?.let {
            (1.0 - it.toDouble() / (MAX_DATA_AGE_MS * 4.0)).coerceIn(0.0, 1.0)
        } ?: 0.0

        if (requiredAge > MAX_DATA_AGE_MS) {
            return wait("Market evidence is stale.", freshness, now)
        }

        val trend = multiTimeframeTrend(candles1m, candles5m, candles15m, history)
        val momentum = momentum(history)
        val volatility = volatility(history)
        val flow = (cvdDelta.coerceIn(-1.0, 1.0) * 0.65 + book.imbalance * 0.35).coerceIn(-1.0, 1.0)
        val liquidity = liquidity(book, price)
        val derivativesScore = (derivativesScore(derivatives) * derivativeFreshness).coerceIn(-1.0, 1.0)
        val alignment = listOf(trend, momentum, flow).average().coerceIn(-1.0, 1.0)
        val regime = (crossAssetRegime * 0.7 + volatility * 0.3 * signOrZero(trend)).coerceIn(-1.0, 1.0)

        val conflicts = buildList {
            if (trend > 0.30 && flow < -0.20) add("Trend vs order-flow conflict")
            if (trend < -0.30 && flow > 0.20) add("Trend vs order-flow conflict")
            if (trend > 0.25 && derivativesScore < -0.35) add("Trend vs derivatives conflict")
            if (trend < -0.25 && derivativesScore > 0.35) add("Trend vs derivatives conflict")
            if (abs(crossAssetRegime) > 0.45 && signOrZero(trend) != 0.0 &&
                signOrZero(trend) != signOrZero(crossAssetRegime)) {
                add("Cross-asset regime conflict")
            }
            if (liquidity < 0.35) add("Thin order-book liquidity")
            if (freshness < 0.50) add("Core market data freshness degraded")
            if (derivatives.updatedAt > 0L && derivativeFreshness < 0.50) add("Derivatives data freshness degraded")
        }

        val raw = (
            trend * 20.0 +
            momentum * 18.0 +
            flow * 18.0 +
            liquiditySigned(liquidity, trend) * 10.0 +
            derivativesScore * 10.0 +
            alignment * 10.0 +
            regime * 8.0 +
            signOrZero(trend) * freshness * 6.0
        ).coerceIn(-100.0, 100.0)

        val score = abs(raw).roundToInt()
        val clean = conflicts.isEmpty() && freshness >= 0.75
        val state = when {
            clean && raw >= LONG_THRESHOLD -> DecisionState.LONG
            clean && raw <= SHORT_THRESHOLD -> DecisionState.SHORT
            abs(raw) >= WATCH_THRESHOLD -> DecisionState.WATCH
            else -> DecisionState.WAIT
        }

        val direction = signOrZero(raw)
        val atr = atrProxy(candles1m, price).coerceAtLeast(price * 0.002)
        val entry = if (state == DecisionState.LONG || state == DecisionState.SHORT) price else null
        val stop = entry?.let { it - direction * atr * 1.15 }
        val t1 = entry?.let { it + direction * atr * 1.0 }
        val t2 = entry?.let { it + direction * atr * 1.8 }
        val t3 = entry?.let { it + direction * atr * 2.7 }

        val reason = when (state) {
            DecisionState.LONG -> "Trend, flow, liquidity, derivatives and regime evidence align for LONG."
            DecisionState.SHORT -> "Trend, flow, liquidity, derivatives and regime evidence align for SHORT."
            DecisionState.WATCH -> if (conflicts.isEmpty())
                "Directional pressure exists, but confirmation is below the entry threshold."
            else conflicts.joinToString("; ")
            DecisionState.WAIT -> if (conflicts.isEmpty())
                "Independent evidence stack is not strong enough."
            else conflicts.joinToString("; ")
            DecisionState.MANAGE -> "Existing trade management state."
        }

        return Decision(
            state = state,
            score = score,
            reason = reason,
            evidence = Evidence(
                trend = trend,
                momentum = momentum,
                volatility = volatility,
                flow = flow,
                liquidity = liquidity,
                derivatives = derivativesScore,
                alignment = alignment,
                regime = regime,
                freshness = freshness,
                conflicts = conflicts
            ),
            entry = entry,
            stop = stop,
            t1 = t1,
            t2 = t2,
            t3 = t3,
            generatedAt = now
        )
    }

    private fun wait(reason: String, freshness: Double, now: Long) = Decision(
        DecisionState.WAIT, 0, reason,
        Evidence(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, freshness, emptyList()),
        generatedAt = now
    )

    private fun momentum(h: List<Double>): Double {
        val a = h.takeLast(8).firstOrNull() ?: return 0.0
        val b = h.lastOrNull() ?: return 0.0
        return (((b - a) / a) * 100.0).coerceIn(-1.0, 1.0)
    }

    private fun volatility(h: List<Double>): Double {
        val r = h.takeLast(30).zipWithNext().mapNotNull { (a, b) ->
            if (a > 0.0 && b > 0.0) (b - a) / a else null
        }
        if (r.size < 5) return 0.0
        val mean = r.average()
        val variance = r.map { (it - mean) * (it - mean) }.average()
        return (sqrt(variance) * 100.0).coerceIn(0.0, 1.0)
    }

    private fun multiTimeframeTrend(
        one: List<Candle>, five: List<Candle>, fifteen: List<Candle>, history: List<Double>
    ): Double {
        fun tf(c: List<Candle>, fallback: List<Double>): Double {
            if (c.size >= 6) {
                val recent = c.takeLast(3).map { it.close }.average()
                val prior = c.takeLast(6).dropLast(3).map { it.close }.average()
                if (prior > 0.0) return (((recent - prior) / prior) * 100.0).coerceIn(-1.0, 1.0)
            }
            if (fallback.size >= 20) {
                val fast = fallback.takeLast(8).average()
                val slow = fallback.takeLast(20).average()
                return if (slow > 0.0) (((fast - slow) / slow) * 100.0).coerceIn(-1.0, 1.0) else 0.0
            }
            return 0.0
        }
        return (tf(one, history) * 0.50 + tf(five, history) * 0.30 + tf(fifteen, history) * 0.20)
            .coerceIn(-1.0, 1.0)
    }

    private fun liquidity(book: Book, price: Double): Double {
        if (book.bid <= 0.0 || book.ask <= 0.0 || price <= 0.0 || book.ask < book.bid) return 0.0
        val spreadBps = (book.spread / price) * 10_000.0
        return (1.0 - spreadBps / 20.0).coerceIn(0.0, 1.0)
    }

    private fun liquiditySigned(liquidity: Double, trend: Double): Double =
        (liquidity * signOrZero(trend)).coerceIn(-1.0, 1.0)

    private fun derivativesScore(d: DerivativesSnapshot): Double {
        if (d.updatedAt <= 0L) return 0.0
        val funding = (-d.fundingRate * 1000.0).coerceIn(-1.0, 1.0)
        val basis = (-d.basisPct / 0.30).coerceIn(-1.0, 1.0)
        val liquidation = d.liquidationBias.coerceIn(-1.0, 1.0)
        return (funding * 0.35 + basis * 0.25 + liquidation * 0.40).coerceIn(-1.0, 1.0)
    }

    private fun atrProxy(c: List<Candle>, price: Double): Double {
        val ranges = c.takeLast(14).map { it.range }.filter { it.isFinite() && it > 0.0 }
        return if (ranges.isNotEmpty()) ranges.average() else price * 0.003
    }

    private fun signOrZero(v: Double): Double = when {
        v > 0.000001 -> 1.0
        v < -0.000001 -> -1.0
        else -> 0.0
    }
}
