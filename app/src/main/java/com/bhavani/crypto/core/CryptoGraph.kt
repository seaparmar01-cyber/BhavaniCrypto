package com.bhavani.crypto.core

import android.content.Context
import com.bhavani.crypto.data.BinancePublicFeed
import com.bhavani.crypto.engine.CryptoEngine

class CryptoGraph(@Suppress("UNUSED_PARAMETER") context: Context) {
    val feed = BinancePublicFeed()
    val engine = CryptoEngine()
}
