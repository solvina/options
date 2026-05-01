# Options Engine — Agent Instructions

## Streaming vs. collection returns

Port methods that produce a sequence of live or paginated data must return `Flow<T>`, not `List<T>`.
This keeps consumers memory-efficient and supports backpressure naturally.

Eagerly-loaded finite sets (config, cached lookup results, pre-trade guard checks) remain collections.

Examples:
- `MarketTickPort.streamUnderlyingPrice()` → `Flow<Double>` ✓
- `SpreadPort.findOpen()` → `List<BullPutSpread>` ✓ (finite, used synchronously in pre-trade check)
