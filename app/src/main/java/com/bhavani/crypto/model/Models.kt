package com.bhavani.crypto.model

enum class Asset(val symbol: String) {
    BTC("BTCUSDT"),
    ETH("ETHUSDT"),
    SOL("SOLUSDT")
}

enum class DecisionState { WAIT, WATCH, LONG, SHORT, MANAGE }

data class Tick(
    val symbol: String,
    val price: Double,
    val qty: Double,
    val time: Long,
    /** true when the buyer was the maker; therefore the trade was seller-aggressive. */
    val buyerIsMaker: Boolean = false
) {
    val signedQty: Double
        get() = if (buyerIsMaker) -qty else qty
}

data class BookLevel(val price: Double, val quantity: Double)

data class Book(
    val bid: Double = 0.0,
    val bidQty: Double = 0.0,
    val ask: Double = 0.0,
    val askQty: Double = 0.0,
    val bids: List<BookLevel> = emptyList(),
    val asks: List<BookLevel> = emptyList(),
    val time: Long = 0L
) {
    val spread: Double
        get() = if (bid > 0.0 && ask > 0.0 && ask >= bid) ask - bid else 0.0

    val mid: Double
        get() = if (bid > 0.0 && ask > 0.0) (bid + ask) / 2.0 else 0.0

    val imbalance: Double
        get() {
            val bidTotal = if (bids.isNotEmpty()) bids.sumOf { it.quantity } else bidQty
            val askTotal = if (asks.isNotEmpty()) asks.sumOf { it.quantity } else askQty
            val total = bidTotal + askTotal
            return if (total > 0.0) ((bidTotal - askTotal) / total).coerceIn(-1.0, 1.0) else 0.0
        }
}

data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val buyVolume: Double = 0.0,
    val sellVolume: Double = 0.0
) {
    val range: Double get() = (high - low).coerceAtLeast(0.0)
    val body: Double get() = kotlin.math.abs(close - open)
    val direction: Double get() = when {
        close > open -> 1.0
        close < open -> -1.0
        else -> 0.0
    }
}


data class HistoricalSnapshot(
    val asset: Asset,
    val candles1m: List<Candle>,
    val candles5m: List<Candle>,
    val candles15m: List<Candle>,
    val price: Double,
    val fetchedAt: Long
)

data class DerivativesSnapshot(
    val openInterest: Double = 0.0,
    val fundingRate: Double = 0.0,
    val basisPct: Double = 0.0,
    val liquidationBias: Double = 0.0,
    val markPrice: Double = 0.0,
    val updatedAt: Long = 0L
)

data class Evidence(
    val trend: Double,
    val momentum: Double,
    val volatility: Double,
    val flow: Double,
    val liquidity: Double,
    val derivatives: Double,
    val alignment: Double,
    val regime: Double,
    val freshness: Double,
    val conflicts: List<String>
)

data class Decision(
    val state: DecisionState,
    val score: Int,
    val reason: String,
    val evidence: Evidence,
    val entry: Double? = null,
    val stop: Double? = null,
    val t1: Double? = null,
    val t2: Double? = null,
    val t3: Double? = null,
    val generatedAt: Long = 0L
)

data class AssetState(
    val asset: Asset,
    val price: Double = 0.0,
    val changePct: Double = 0.0,
    val book: Book = Book(),
    val derivatives: DerivativesSnapshot = DerivativesSnapshot(),
    val decision: Decision = Decision(
        DecisionState.WAIT,
        0,
        "Waiting for fresh market evidence.",
        Evidence(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList())
    ),
    val prices: List<Double> = emptyList(),
    val candles1m: List<Candle> = emptyList(),
    val candles5m: List<Candle> = emptyList(),
    val candles15m: List<Candle> = emptyList(),
    val cvd: Double = 0.0,
    val connected: Boolean = false,
    val lastUpdate: Long = 0L
)
