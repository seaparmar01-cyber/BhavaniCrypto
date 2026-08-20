package com.bhavani.crypto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bhavani.crypto.model.*
import kotlin.math.max
import kotlin.math.min

@Composable
fun CryptoRoot(vm: MainViewModel = viewModel()) {
    val states by vm.states.collectAsState()
    val monitoring by vm.monitoring.collectAsState()
    val connected by vm.connected.collectAsState()
    var selected by remember { mutableStateOf(Asset.BTC) }
    val state = states.getValue(selected)

    Surface(color=Color(0xFF080A0E), modifier=Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=18.dp, vertical=14.dp),
            verticalArrangement=Arrangement.spacedBy(14.dp)
        ) {
            Header(monitoring, connected, vm::setMonitoring)
            AssetSelector(selected) { selected = it }
            DecisionHero(state)
            IntelligenceChart(state)
            EvidenceStrip(state)
            TradeMap(state)
            Spacer(Modifier.height(4.dp))
            Text(
                "Public market data • local decision engine • no application server",
                color=Color(0xFF657184), fontSize=11.sp,
                modifier=Modifier.padding(horizontal=2.dp)
            )
        }
    }
}

@Composable
private fun Header(monitoring: Boolean, connected: Boolean, onToggle: (Boolean)->Unit) {
    Row(verticalAlignment=Alignment.CenterVertically, modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("BHAVANI", color=Color(0xFFF4F7FA), fontSize=20.sp, fontWeight=FontWeight.Bold, letterSpacing=2.sp)
            Text("CRYPTO INTELLIGENCE", color=Color(0xFF7CFFB2), fontSize=10.sp, fontWeight=FontWeight.Medium, letterSpacing=1.6.sp)
        }
        Surface(
            shape=RoundedCornerShape(18.dp),
            color=if(monitoring) Color(0xFF10291D) else Color(0xFF151922),
            modifier=Modifier.clickable { onToggle(!monitoring) }
        ) {
            Row(Modifier.padding(horizontal=12.dp, vertical=8.dp), verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.PowerSettingsNew, null, tint=if(monitoring) Color(0xFF7CFFB2) else Color(0xFF8C97A8), modifier=Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text(if(monitoring) "MONITORING ON" else "MONITORING OFF", color=if(monitoring) Color(0xFF7CFFB2) else Color(0xFF8C97A8), fontSize=10.sp, fontWeight=FontWeight.Bold)
            }
        }
    }
    Row(verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(if(connected) Color(0xFF7CFFB2) else Color(0xFFFF6B7A), RoundedCornerShape(50)))
        Spacer(Modifier.width(7.dp))
        Text(if(connected) "LIVE FEED CONNECTED" else "FEED PAUSED", color=Color(0xFF8C97A8), fontSize=10.sp)
    }
}

@Composable
private fun AssetSelector(selected: Asset, onSelect:(Asset)->Unit) {
    LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        items(Asset.entries) { a ->
            val active=a==selected
            Surface(shape=RoundedCornerShape(12.dp), color=if(active) Color(0xFFE9FFF1) else Color(0xFF10141B),
                modifier=Modifier.clickable { onSelect(a) }) {
                Text(a.name, color=if(active) Color.Black else Color(0xFFD5DBE4), fontSize=13.sp, fontWeight=FontWeight.Bold,
                    modifier=Modifier.padding(horizontal=17.dp, vertical=10.dp))
            }
        }
    }
}

@Composable
private fun DecisionHero(s: AssetState) {
    val d=s.decision
    val accent=when(d.state) {
        DecisionState.LONG -> Color(0xFF7CFFB2)
        DecisionState.SHORT -> Color(0xFFFF6B7A)
        DecisionState.WATCH -> Color(0xFFFFC857)
        else -> Color(0xFF8C97A8)
    }
    Surface(shape=RoundedCornerShape(22.dp), color=Color(0xFF10141B), tonalElevation=0.dp) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.Bottom) {
                Text(s.asset.name, color=Color(0xFF8C97A8), fontSize=12.sp, fontWeight=FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(if(s.price>0) String.format("%,.2f",s.price) else "—", color=Color(0xFFF4F7FA), fontSize=30.sp, fontWeight=FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(d.state.name, color=accent, fontSize=16.sp, fontWeight=FontWeight.Bold)
            }
            Text(d.reason, color=Color(0xFFB4BDCA), fontSize=12.sp)
            Text(
                "Funding ${String.format("%.4f", s.derivatives.fundingRate * 100)}%  •  OI ${String.format("%,.0f", s.derivatives.openInterest)}  •  Basis ${String.format("%.3f", s.derivatives.basisPct)}%",
                color=Color(0xFF657184), fontSize=9.sp
            )
            LinearProgressIndicator(
                progress={d.score/100f},
                modifier=Modifier.fillMaxWidth().height(5.dp),
                color=accent, trackColor=Color(0xFF222936)
            )
            Text("DECISION CONFIDENCE  ${d.score} / 100", color=Color(0xFF657184), fontSize=9.sp, letterSpacing=1.1.sp)
        }
    }
}

@Composable
private fun IntelligenceChart(s: AssetState) {
    Surface(shape=RoundedCornerShape(22.dp), color=Color(0xFF0D1117)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("VISUAL INTELLIGENCE", color=Color(0xFFF4F7FA), fontSize=12.sp, fontWeight=FontWeight.Bold, letterSpacing=1.sp)
                Spacer(Modifier.weight(1f))
                Text("PRICE • FLOW • LIQUIDITY", color=Color(0xFF657184), fontSize=9.sp)
            }
            Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                val pts=s.prices
                if(pts.size>1) {
                    val lo=pts.minOrNull() ?: 0.0; val hi=pts.maxOrNull() ?: 1.0
                    val range=max(hi-lo, hi*0.000001)
                    val path=Path()
                    pts.forEachIndexed { i,v ->
                        val x=i.toFloat()/(pts.size-1)*size.width
                        val y=size.height-((v-lo)/range).toFloat()*size.height
                        if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
                    }
                    drawPath(path, color=Color(0xFF7CFFB2), style=Stroke(width=3f))
                    val d=s.decision
                    d.entry?.let { entry ->
                        val y=size.height-((entry-lo)/range).toFloat()*size.height
                        drawLine(Color(0xFF7CFFB2).copy(alpha=.45f),Offset(0f,y),Offset(size.width,y),2f)
                    }
                    d.stop?.let { stop ->
                        if(stop in lo..hi) {
                            val y=size.height-((stop-lo)/range).toFloat()*size.height
                            drawLine(Color(0xFFFF6B7A).copy(alpha=.45f),Offset(0f,y),Offset(size.width,y),2f)
                        }
                    }
                    d.t1?.let { t ->
                        if(t in lo..hi) {
                            val y=size.height-((t-lo)/range).toFloat()*size.height
                            drawLine(Color(0xFFFFC857).copy(alpha=.35f),Offset(0f,y),Offset(size.width,y),1.5f)
                        }
                    }
                } else {
                    drawLine(Color(0xFF202733), Offset(0f,size.height/2), Offset(size.width,size.height/2), 2f)
                }
            }
        }
    }
}

@Composable
private fun EvidenceStrip(s: AssetState) {
    val e=s.decision.evidence
    val items=listOf(
        "TREND" to e.trend,
        "MOMENTUM" to e.momentum,
        "FLOW" to e.flow,
        "LIQUIDITY" to (e.liquidity*2-1),
        "DERIVATIVES" to e.derivatives,
        "ALIGNMENT" to e.alignment,
        "REGIME" to e.regime,
        "FRESHNESS" to e.freshness
    )
    LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        items(items) { (name,v) ->
            Surface(shape=RoundedCornerShape(14.dp), color=Color(0xFF10141B)) {
                Column(Modifier.padding(horizontal=12.dp,vertical=10.dp)) {
                    Text(name,color=Color(0xFF657184),fontSize=8.sp,fontWeight=FontWeight.Bold)
                    Text(if(v>=0) "＋${String.format("%.2f",v)}" else String.format("%.2f",v),
                        color=if(v>=0) Color(0xFF7CFFB2) else Color(0xFFFF6B7A),fontSize=12.sp,fontWeight=FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TradeMap(s: AssetState) {
    val d=s.decision
    Surface(shape=RoundedCornerShape(22.dp), color=Color(0xFF10141B)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("TRADE MAP", color=Color(0xFFF4F7FA), fontSize=12.sp, fontWeight=FontWeight.Bold, letterSpacing=1.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceEvenly) {
                Metric("ENTRY",d.entry); Metric("SL",d.stop); Metric("T1",d.t1); Metric("T2",d.t2); Metric("T3",d.t3)
            }
            if(d.evidence.conflicts.isNotEmpty()) {
                Text("CONFLICT • ${d.evidence.conflicts.joinToString(" · ")}", color=Color(0xFFFFC857), fontSize=10.sp)
            }
        }
    }
}

@Composable
private fun Metric(label:String, value:Double?) {
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        Text(label,color=Color(0xFF657184),fontSize=8.sp)
        Text(value?.let{String.format("%,.2f",it)} ?: "—",color=Color(0xFFE7EBF0),fontSize=11.sp,fontWeight=FontWeight.SemiBold)
    }
}
