package com.bhavani.crypto.engine

import com.bhavani.crypto.model.Candle
import com.bhavani.crypto.model.Tick

class CandleBuilder(private val timeframeMs: Long) {
    private var current: Candle? = null

    fun add(tick: Tick): Candle? {
        val bucket = (tick.time / timeframeMs) * timeframeMs
        val c = current
        if (c == null) {
            current = Candle(
                time = bucket,
                open = tick.price,
                high = tick.price,
                low = tick.price,
                close = tick.price,
                volume = tick.qty,
                buyVolume = if (tick.signedQty > 0) tick.qty else 0.0,
                sellVolume = if (tick.signedQty < 0) tick.qty else 0.0
            )
            return null
        }
        if (bucket == c.time) {
            current = c.copy(
                high = maxOf(c.high, tick.price),
                low = minOf(c.low, tick.price),
                close = tick.price,
                volume = c.volume + tick.qty,
                buyVolume = c.buyVolume + if (tick.signedQty > 0) tick.qty else 0.0,
                sellVolume = c.sellVolume + if (tick.signedQty < 0) tick.qty else 0.0
            )
            return null
        }
        val completed = c
        current = Candle(
            time = bucket,
            open = tick.price,
            high = tick.price,
            low = tick.price,
            close = tick.price,
            volume = tick.qty,
            buyVolume = if (tick.signedQty > 0) tick.qty else 0.0,
            sellVolume = if (tick.signedQty < 0) tick.qty else 0.0
        )
        return completed
    }
}
