# BhavaniCrypto V1.0.0 — Institutional Fusion

A separate, on-device crypto decision-intelligence Android application for BTC, ETH and SOL.

## Production-oriented architecture

- No application server.
- Binance public Spot WebSocket for live trades and 5-level order-book depth.
- Binance USD-M Futures public data for funding, open interest, mark-price/basis and liquidation-flow hooks.
- Automatic bounded WebSocket reconnect while Monitoring is ON.
- Monitoring is explicitly user-controlled. OFF stops sockets and the background service.
- Local 1m / 5m / 15m candle construction from live trades.
- Evidence fusion across trend, momentum, volatility, order flow/CVD proxy, liquidity, derivatives, multi-timeframe alignment, cross-asset regime and data freshness.
- Conflicts downgrade the decision instead of forcing a trade.
- Trade-ready states: WAIT, WATCH, LONG, SHORT, MANAGE (MANAGE remains reserved for a future paper-position/trade-management layer).
- Visual intelligence chart and evidence dashboard.
- Defensive parsing, stale-data protection and bounded reconnection.
- Unit tests for decision safety and edge cases.

## Safety / scope

This application is decision support. It does not place live orders and does not claim guaranteed profit or zero bugs. Exchange availability, API limits, network behaviour, device lifecycle behaviour and market conditions must be validated in GitHub CI and controlled market sessions before commercial release.

Derivatives data is treated as supplemental evidence: if it is unavailable or stale, it is not silently presented as fresh live evidence.

## Build

The project targets Android API 36, Java 17 and Kotlin 2.1.x. The repository intentionally contains source only; generate/retain the Gradle wrapper in the development environment used for CI if your repository policy requires it.
