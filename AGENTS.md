# Options Engine — Agent & Coding Rules

## Architecture

This project follows clean architecture with strict dependency direction:

```
adapters/inbound  →  domain  ←  adapters/outbound
```

### Layer rules

- **`domain/`** — zero imports from `adapters`. Contains port interfaces and domain models only.
- **`adapters/outbound/ibkr/`** — the only package allowed to import IBKR (`com.ib.client.*`) types. Nothing outside this package may reference IBKR API classes.
- **`adapters/inbound/`** — may import `IbkrConnectionConfig` (config is adapter-specific) and domain ports. Must not call `IbkrConnectionManager` directly — use the domain port.
- **`OptionsApplication.kt`** — must remain clean of adapter imports. Configuration properties binding belongs in the adapter's `@Configuration` class.

### Port interfaces

| Port | Location | Purpose |
|------|----------|---------|
| `ConnectionPort` | `domain/features/connection/` | connect, disconnect, isConnected |
| `ConnectionStatusPort` | `domain/features/connection/status/` | read-only connection status |

When a new outbound integration is added, define its port interface in `domain/features/<feature>/` first, then implement it under `adapters/outbound/`.

## Package structure

```
cz.solvina.options
├── domain
│   ├── models                 shared domain models (no IBKR types)
│   └── features
│       └── <feature-group>
│           └── <feature>      port interface lives here
├── adapters
│   ├── inbound
│   │   ├── api                REST controllers (implements generated OpenAPI interfaces)
│   │   └── lifecycle          Spring lifecycle hooks (@EventListener, @PreDestroy)
│   └── outbound
│       └── ibkr               ALL com.ib.client.* usage confined here
└── shared                     domain-agnostic utilities only
```

## OpenAPI

- Specs live in `engine/openapi/cz.solvina.options.<domain>.yaml`
- Interfaces are generated at build time; never edit generated code
- Controllers in `adapters/inbound/api/` implement the generated interface
- The `HealthApiImpl` is the reference implementation

## Code style

### Loggers

Declare loggers as **file-level private vals**, not inside the class body:

```kotlin
private val logger = KotlinLogging.logger {}

class MyService { ... }
```

This is the idiomatic KotlinLogging pattern. The `logger {}` lambda captures the enclosing class
automatically, so the logger name is correct. Placing it at file level keeps the class constructor
clean and is consistent with the rest of the codebase.

### Imports and class references

Always import a type and use its simple name. Never use a fully-qualified class path inline in code.

```kotlin
// Wrong
val dto = `cz.solvina.options.spreads`.dto.SpreadDto(...)

// Correct
import cz.solvina.options.spreads.dto.SpreadDto
val dto = SpreadDto(...)
```

The only acceptable exception is when two identically-named types are used in the same file and
neither can be aliased away cleanly — in that case, prefer a `typealias` or `import ... as ...`
over an inline fully-qualified name.

## Market time vs. system time

**Be driven by market data, not the system clock. Use the most suitable timestamp from market data wherever possible.**

System clock (`Instant.now()` / `Clock`) is only appropriate for true wall-clock events: order submission timestamps, audit trails, and scheduler ticks. For everything else — entry blocking, context fields, candle timestamps, replay — derive time from the market data itself so the engine behaves consistently in both live and replay/backtest modes.

## IBKR configuration

- Connection config: `ibkr.connection.*` (see `IbkrConnectionConfig`)
- Heartbeat interval: `ibkr.heartbeat.interval-ms` (default 5000 ms)
- Enable startup connect: `ibkr.connection.enabled=true`
