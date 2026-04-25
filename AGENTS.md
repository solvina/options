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

## IBKR configuration

- Connection config: `ibkr.connection.*` (see `IbkrConnectionConfig`)
- Heartbeat interval: `ibkr.heartbeat.interval-ms` (default 5000 ms)
- Enable startup connect: `ibkr.connection.enabled=true`
