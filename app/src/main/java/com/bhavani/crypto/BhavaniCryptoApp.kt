package com.bhavani.crypto
import android.app.Application
import com.bhavani.crypto.core.CryptoGraph

class BhavaniCryptoApp : Application() {
    lateinit var graph: CryptoGraph
    override fun onCreate() {
        super.onCreate()
        graph = CryptoGraph(this)
    }
}
