package com.bhavani.crypto.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bhavani.crypto.BhavaniCryptoApp
import com.bhavani.crypto.model.Asset
import com.bhavani.crypto.model.AssetState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app
    private val graph = (app as BhavaniCryptoApp).graph
    private val prefs = app.getSharedPreferences("monitoring", Application.MODE_PRIVATE)

    val states: StateFlow<Map<Asset, AssetState>> = graph.engine.states
    private val _monitoring = MutableStateFlow(prefs.getBoolean("enabled", false))
    val monitoring: StateFlow<Boolean> = _monitoring
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    init {
        viewModelScope.launch {
            graph.feed.ticks.collect { graph.engine.onTick(it) }
        }
        viewModelScope.launch {
            graph.feed.books.collect { (symbol, book) -> graph.engine.onBook(symbol, book) }
        }
        viewModelScope.launch {
            graph.feed.derivatives.collect { (symbol, snapshot) ->
                graph.engine.onDerivatives(symbol, snapshot)
            }
        }
        viewModelScope.launch {
            graph.feed.history.collect { snapshot ->
                graph.engine.warmup(snapshot)
            }
        }
        viewModelScope.launch {
            graph.feed.connection.collect {
                _connected.value = it
                graph.engine.onConnection(it)
            }
        }
    }

    fun setMonitoring(on: Boolean) {
        _monitoring.value = on
        prefs.edit().putBoolean("enabled", on).apply()
        val intent = Intent(application, com.bhavani.crypto.service.CryptoMonitorService::class.java)
        if (on) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(application, intent)
            } else {
                application.startService(intent)
            }
        } else {
            application.stopService(intent)
            graph.feed.disconnect()
        }
    }
}
