package com.bhavani.crypto.engine

import com.bhavani.crypto.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CryptoEngine {
    private val resolver = DecisionResolver()
    private val history = Asset.entries.associateWith { ArrayDeque<Double>() }.toMutableMap()
    private val candles1m = Asset.entries.associateWith { ArrayDeque<Candle>() }.toMutableMap()
    private val candles5m = Asset.entries.associateWith { ArrayDeque<Candle>() }.toMutableMap()
    private val candles15m = Asset.entries.associateWith { ArrayDeque<Candle>() }.toMutableMap()
    private val builders1m = Asset.entries.associateWith { CandleBuilder(60_000L) }
    private val builders5m = Asset.entries.associateWith { CandleBuilder(300_000L) }
    private val builders15m = Asset.entries.associateWith { CandleBuilder(900_000L) }
    private val cvd = Asset.entries.associateWith { 0.0 }.toMutableMap()
    private val flowWindows = Asset.entries.associateWith { ArrayDeque<Double>() }.toMutableMap()
    private val books = Asset.entries.associateWith { Book() }.toMutableMap()
    private val derivatives = Asset.entries.associateWith { DerivativesSnapshot() }.toMutableMap()

    private val _states = MutableStateFlow(Asset.entries.associateWith { AssetState(it) })
    val states: StateFlow<Map<Asset, AssetState>> = _states

    @Synchronized
    fun warmup(snapshot: HistoricalSnapshot) {
        val asset = snapshot.asset
        val h = history.getValue(asset)
        h.clear()
        h.addAll(snapshot.candles1m.flatMap { listOf(it.open, it.close) }.takeLast(1000))
        appendAll(candles1m.getValue(asset), snapshot.candles1m, 240)
        appendAll(candles5m.getValue(asset), snapshot.candles5m, 180)
        appendAll(candles15m.getValue(asset), snapshot.candles15m, 120)
        update(asset, price = snapshot.price, lastUpdate = snapshot.fetchedAt)
    }

    @Synchronized
    fun onTick(tick: Tick) {
        val asset = Asset.entries.firstOrNull { it.symbol == tick.symbol } ?: return
        if (!tick.price.isFinite() || tick.price <= 0.0 || !tick.qty.isFinite() || tick.qty < 0.0) return

        val h = history.getValue(asset)
        h.add(tick.price)
        while (h.size > 1_000) h.removeFirst()

        cvd[asset] = (cvd.getValue(asset) + tick.signedQty).coerceIn(-1e12, 1e12)
        val flow = flowWindows.getValue(asset)
        flow.add(tick.signedQty)
        while (flow.size > 200) flow.removeFirst()

        builders1m.getValue(asset).add(tick)?.let { append(candles1m.getValue(asset), it, 240) }
        builders5m.getValue(asset).add(tick)?.let { append(candles5m.getValue(asset), it, 180) }
        builders15m.getValue(asset).add(tick)?.let { append(candles15m.getValue(asset), it, 120) }

        update(asset, price = tick.price, lastUpdate = tick.time)
    }

    @Synchronized
    fun onBook(symbol: String, book: Book) {
        val asset = Asset.entries.firstOrNull { it.symbol == symbol } ?: return
        if (book.bid <= 0.0 || book.ask <= 0.0 || book.ask < book.bid) return
        books[asset] = book
        update(asset, book = book, lastUpdate = book.time)
    }

    @Synchronized
    fun onDerivatives(symbol: String, snapshot: DerivativesSnapshot) {
        val asset = Asset.entries.firstOrNull { it.symbol == symbol } ?: return
        if (snapshot.updatedAt <= 0L) return
        derivatives[asset] = snapshot
        update(asset, derivatives = snapshot, lastUpdate = snapshot.updatedAt)
    }

    @Synchronized
    fun onConnection(connected: Boolean) {
        _states.value = _states.value.mapValues { (_, s) ->
            s.copy(connected = connected)
        }
        if (!connected) {
            _states.value = _states.value.mapValues { (_, s) ->
                s.copy(
                    decision = s.decision.copy(
                        state = DecisionState.WAIT,
                        score = 0,
                        reason = "Live market feed disconnected.",
                        entry = null,
                        stop = null,
                        t1 = null,
                        t2 = null,
                        t3 = null
                    )
                )
            }
        }
    }

    private fun update(
        asset: Asset,
        price: Double? = null,
        book: Book? = null,
        derivatives: DerivativesSnapshot? = null,
        lastUpdate: Long = System.currentTimeMillis()
    ) {
        val old = _states.value.getValue(asset)
        val p = price ?: old.price
        val h = history.getValue(asset).toList()
        val b = book ?: books.getValue(asset)
        val d = derivatives ?: this.derivatives.getValue(asset)
        val currentCvd = cvd.getValue(asset)
        val flow = flowWindows.getValue(asset)
        val grossFlow = flow.sumOf { kotlin.math.abs(it) }
        val flowPressure = if (grossFlow > 0.0) (flow.sum() / grossFlow).coerceIn(-1.0, 1.0) else 0.0

        val previous = if (old.price > 0.0) ((p - old.price) / old.price) * 100.0 else 0.0
        val regime = crossAssetRegime(asset)

        val decision = resolver.resolve(
            price = p,
            history = h,
            candles1m = candles1m.getValue(asset).toList(),
            candles5m = candles5m.getValue(asset).toList(),
            candles15m = candles15m.getValue(asset).toList(),
            book = b,
            derivatives = d,
            cvdDelta = flowPressure,
            crossAssetRegime = regime,
            connected = old.connected,
            now = System.currentTimeMillis()
        )

        _states.value = _states.value + (asset to old.copy(
            price = p,
            changePct = previous,
            book = b,
            derivatives = d,
            decision = decision,
            prices = h.takeLast(240),
            candles1m = candles1m.getValue(asset).takeLast(120),
            candles5m = candles5m.getValue(asset).takeLast(60),
            candles15m = candles15m.getValue(asset).takeLast(40),
            cvd = currentCvd,
            lastUpdate = lastUpdate
        ))
    }

    private fun crossAssetRegime(asset: Asset): Double {
        val signals = Asset.entries.mapNotNull { other ->
            val h = history.getValue(other)
            if (h.size < 30) null else {
                val fast = h.takeLast(10).average()
                val slow = h.takeLast(30).average()
                if (slow > 0.0) (((fast - slow) / slow) * 100.0).coerceIn(-1.0, 1.0) else null
            }
        }
        if (signals.isEmpty()) return 0.0
        val mean = signals.average()
        val btcSignal = history.getValue(Asset.BTC).let { h ->
            if (h.size < 30) 0.0 else {
                val fast = h.takeLast(10).average()
                val slow = h.takeLast(30).average()
                if (slow > 0.0) (((fast - slow) / slow) * 100.0).coerceIn(-1.0, 1.0) else 0.0
            }
        }
        return if (asset == Asset.BTC) mean else (mean * 0.6 + btcSignal * 0.4).coerceIn(-1.0, 1.0)
    }

    private fun appendAll(queue: ArrayDeque<Candle>, values: List<Candle>, max: Int) {
        queue.clear()
        values.takeLast(max).forEach { queue.add(it) }
    }

    private fun append(queue: ArrayDeque<Candle>, candle: Candle, max: Int) {
        queue.add(candle)
        while (queue.size > max) queue.removeFirst()
    }
}
