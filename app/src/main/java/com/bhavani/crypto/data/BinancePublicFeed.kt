package com.bhavani.crypto.data

import com.bhavani.crypto.model.Asset
import com.bhavani.crypto.model.Book
import com.bhavani.crypto.model.BookLevel
import com.bhavani.crypto.model.DerivativesSnapshot
import com.bhavani.crypto.model.Candle
import com.bhavani.crypto.model.HistoricalSnapshot
import com.bhavani.crypto.model.Tick
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BinancePublicFeed : Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)
    @Volatile private var derivativeTask: ScheduledFuture<*>? = null
    private val started = AtomicBoolean(false)

    @Volatile private var spotSocket: WebSocket? = null
    @Volatile private var futuresSocket: WebSocket? = null
    private var spotRetry = 0
    private var futuresRetry = 0

    private val latestSpot = ConcurrentHashMap<Asset, Double>().apply {
        Asset.entries.forEach { put(it, 0.0) }
    }

    private val _ticks = MutableSharedFlow<Tick>(extraBufferCapacity = 512)
    val ticks: SharedFlow<Tick> = _ticks

    private val _books = MutableSharedFlow<Pair<String, Book>>(extraBufferCapacity = 256)
    val books: SharedFlow<Pair<String, Book>> = _books

    private val _derivatives = MutableSharedFlow<Pair<String, DerivativesSnapshot>>(extraBufferCapacity = 64)
    val derivatives: SharedFlow<Pair<String, DerivativesSnapshot>> = _derivatives

    private val _history = MutableSharedFlow<HistoricalSnapshot>(extraBufferCapacity = 16)
    val history: SharedFlow<HistoricalSnapshot> = _history

    private val _connection = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 2)
    val connection: SharedFlow<Boolean> = _connection

    fun connect() {
        if (!started.compareAndSet(false, true)) return
        spotRetry = 0
        futuresRetry = 0
        scheduler.execute { fetchHistory() }
        connectSpot()
        connectFutures()
        derivativeTask?.cancel(false)
        derivativeTask = scheduler.scheduleAtFixedRate({ fetchDerivatives() }, 0, 15, TimeUnit.SECONDS)
    }

    private fun connectSpot() {
        if (!started.get() || spotSocket != null) return
        val streams = Asset.entries.joinToString("/") {
            "${it.symbol.lowercase()}@trade/${it.symbol.lowercase()}@depth5@100ms"
        }
        val request = Request.Builder()
            .url("wss://stream.binance.com:9443/stream?streams=$streams")
            .build()
        spotSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                spotRetry = 0
                _connection.tryEmit(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { parseSpot(text) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val current = spotSocket === webSocket
                if (current) spotSocket = null
                if (current && started.get()) scheduleSpotReconnect()
                else if (current) _connection.tryEmit(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                val current = spotSocket === webSocket
                if (current) spotSocket = null
                if (current && started.get()) scheduleSpotReconnect()
                else if (current) _connection.tryEmit(false)
            }
        })
    }

    private fun connectFutures() {
        if (!started.get() || futuresSocket != null) return
        val streams = Asset.entries.joinToString("/") { "${it.symbol.lowercase()}@forceOrder" }
        val request = Request.Builder()
            .url("wss://fstream.binance.com/stream?streams=$streams")
            .build()
        futuresSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                futuresRetry = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { parseForceOrder(text) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val current = futuresSocket === webSocket
                if (current) futuresSocket = null
                if (current && started.get()) scheduleFuturesReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                val current = futuresSocket === webSocket
                if (current) futuresSocket = null
                if (current && started.get()) scheduleFuturesReconnect()
            }
        })
    }

    private fun parseSpot(text: String) {
        val root = json.parseToJsonElement(text).jsonObject
        val stream = root["stream"]?.jsonPrimitive?.content ?: return
        val data = root["data"]?.jsonObject ?: return
        val symbol = data["s"]?.jsonPrimitive?.content ?: return
        val now = System.currentTimeMillis()

        when {
            stream.endsWith("@trade") -> {
                val price = data.number("p") ?: return
                val qty = data.number("q") ?: 0.0
                latestSpot[Asset.entries.firstOrNull { it.symbol == symbol } ?: return] = price
                _ticks.tryEmit(
                    Tick(
                        symbol = symbol,
                        price = price,
                        qty = qty,
                        time = now,
                        buyerIsMaker = data["m"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    )
                )
            }

            stream.contains("@depth5") -> {
                val bids = data["bids"]?.jsonArray?.mapNotNull { level(it.jsonArray) } ?: emptyList()
                val asks = data["asks"]?.jsonArray?.mapNotNull { level(it.jsonArray) } ?: emptyList()
                if (bids.isEmpty() || asks.isEmpty()) return
                _books.tryEmit(
                    symbol to Book(
                        bid = bids.first().price,
                        bidQty = bids.first().quantity,
                        ask = asks.first().price,
                        askQty = asks.first().quantity,
                        bids = bids,
                        asks = asks,
                        time = now
                    )
                )
            }
        }
    }

    private fun parseForceOrder(text: String) {
        val root = json.parseToJsonElement(text).jsonObject
        val data = root["data"]?.jsonObject ?: return
        val order = data["o"]?.jsonObject ?: return
        val symbol = order["s"]?.jsonPrimitive?.content ?: return
        val qty = order.number("q") ?: return
        val side = order["S"]?.jsonPrimitive?.content ?: return
        val signed = if (side == "BUY") qty else -qty
        val previous = latestLiquidationBias[symbol] ?: 0.0
        latestLiquidationBias[symbol] = (previous * 0.90 + signed.coerceIn(-100.0, 100.0) / 100.0 * 0.10)
    }

    private val latestLiquidationBias = ConcurrentHashMap<String, Double>()


    private fun fetchHistory() {
        if (!started.get()) return
        Asset.entries.forEach { asset ->
            runCatching {
                val c1 = fetchKlines(asset, "1m", 240)
                val c5 = fetchKlines(asset, "5m", 120)
                val c15 = fetchKlines(asset, "15m", 80)
                val price = c1.lastOrNull()?.close ?: return@runCatching
                _history.tryEmit(
                    HistoricalSnapshot(
                        asset = asset,
                        candles1m = c1,
                        candles5m = c5,
                        candles15m = c15,
                        price = price,
                        fetchedAt = System.currentTimeMillis()
                    )
                )
                latestSpot[asset] = price
            }
        }
    }

    private fun fetchKlines(asset: Asset, interval: String, limit: Int): List<Candle> {
        val request = Request.Builder()
            .url("https://data-api.binance.vision/api/v3/klines?symbol=${asset.symbol}&interval=$interval&limit=$limit")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val array = json.parseToJsonElement(body).jsonArray
            return array.mapNotNull { element ->
                val row = element.jsonArray
                if (row.size < 6) return@mapNotNull null
                val time = row[0].jsonPrimitive.content.toLongOrNull() ?: return@mapNotNull null
                val open = row[1].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
                val high = row[2].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
                val low = row[3].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
                val close = row[4].jsonPrimitive.content.toDoubleOrNull() ?: return@mapNotNull null
                val volume = row[5].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                Candle(time, open, high, low, close, volume)
            }
        }
    }

    private fun fetchDerivatives() {
        if (!started.get()) return
        Asset.entries.forEach { asset ->
            runCatching {
                val premium = getJson("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=${asset.symbol}")
                val oi = getJson("https://fapi.binance.com/fapi/v1/openInterest?symbol=${asset.symbol}")
                val mark = premium?.number("markPrice") ?: 0.0
                val funding = premium?.number("lastFundingRate") ?: 0.0
                val openInterest = oi?.number("openInterest") ?: 0.0
                val spot = latestSpot[asset] ?: 0.0
                val basis = if (spot > 0.0 && mark > 0.0) ((mark - spot) / spot) * 100.0 else 0.0
                _derivatives.tryEmit(
                    asset.symbol to DerivativesSnapshot(
                        openInterest = openInterest,
                        fundingRate = funding,
                        basisPct = basis,
                        liquidationBias = latestLiquidationBias[asset.symbol] ?: 0.0,
                        markPrice = mark,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun getJson(url: String): JsonObject? {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return json.parseToJsonElement(body).jsonObject
        }
    }

    private fun scheduleSpotReconnect() {
        val delay = (1L shl minOf(spotRetry++, 5)).coerceAtMost(30L)
        scheduler.schedule({ connectSpot() }, delay, TimeUnit.SECONDS)
        _connection.tryEmit(false)
    }

    private fun scheduleFuturesReconnect() {
        val delay = (1L shl minOf(futuresRetry++, 5)).coerceAtMost(30L)
        scheduler.schedule({ connectFutures() }, delay, TimeUnit.SECONDS)
    }

    private fun JsonObject.number(key: String): Double? =
        this[key]?.jsonPrimitive?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun level(array: kotlinx.serialization.json.JsonArray): BookLevel? {
        if (array.size < 2) return null
        val p = array[0].jsonPrimitive.content.toDoubleOrNull() ?: return null
        val q = array[1].jsonPrimitive.content.toDoubleOrNull() ?: return null
        if (!p.isFinite() || !q.isFinite() || p <= 0.0 || q < 0.0) return null
        return BookLevel(p, q)
    }

    fun disconnect() {
        if (!started.compareAndSet(true, false)) return
        derivativeTask?.cancel(false)
        derivativeTask = null
        spotSocket?.close(1000, "Monitoring off")
        futuresSocket?.close(1000, "Monitoring off")
        spotSocket = null
        futuresSocket = null
        _connection.tryEmit(false)
    }

    override fun close() {
        disconnect()
        scheduler.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
