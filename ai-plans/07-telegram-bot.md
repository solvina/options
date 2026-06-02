# Telegram Bot — Component Report

## Purpose

A lightweight Python process that exposes Claude Code as a Telegram chatbot.
Any message sent to the bot is forwarded to the local `claude -p` CLI;
the reply is returned to the user. Session continuity is maintained across
messages so the conversation has persistent context.

---

## Architecture

```
Telegram  ──►  python-telegram-bot (polling)
                    │
                    ▼
               handle_message()
                    │
                    ▼  subprocess
               claude -p --output-format json [--resume <session_id>]
                    │
                    ▼
               JSON response  ──►  reply_text()  ──►  Telegram
```

- **Transport**: long-polling (no webhook needed).
- **Concurrency**: Claude calls run in a thread-pool executor so the async
  event loop is never blocked.
- **Session persistence**: the active `session_id` returned by `claude` is
  written to `.session_id` on disk and passed via `--resume` on subsequent
  calls.

---

## Source files

| File | Description |
|------|-------------|
| `telegram-bot/bot.py` | Entire bot — config, Claude integration, handlers |
| `telegram-bot/requirements.txt` | `python-telegram-bot==21.11.1`, `python-dotenv==1.1.0` |
| `deploy/telegram-bot.service` | systemd unit that keeps the bot running |

---

## Configuration (`.env`)

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `TELEGRAM_BOT_TOKEN` | — | ✅ | BotFather token |
| `TELEGRAM_CHAT_ID` | — | ✅ | The one chat ID allowed to use the bot |
| `CLAUDE_BIN` | `/home/solvina/.local/bin/claude` | — | Path to the `claude` executable |
| `CLAUDE_TIMEOUT` | `120` | — | Seconds before a Claude call is killed |

The `.env` file is loaded from the **parent directory** of the bot
(`/home/solvina/options/.env` in production, `../` relative to `bot.py`).

---

## Commands

| Command | Behaviour |
|---------|-----------|
| `/start` | Greets the user, shows active session ID if one exists |
| `/reset` | Deletes `.session_id` — next message starts a brand-new conversation |
| `/session` | Prints the current session ID |
| _(any text)_ | Forwarded verbatim to Claude Code |

---

## Access control

Only the single chat ID stored in `TELEGRAM_CHAT_ID` is accepted.
All other senders are silently dropped (`is_allowed()` check in every handler).

---

## Message splitting

Telegram enforces a 4 096-character message limit.
`split_message()` naively chunks the response into ≤ 4 000-character pieces
so long Claude outputs are delivered as multiple sequential messages.

---

## Deployment

The bot runs as a **systemd service** (`telegram-bot.service`):

```
WorkingDirectory  /home/solvina/options/telegram-bot
ExecStart         venv/bin/python bot.py
Restart           on-failure (15 s back-off)
EnvironmentFile   /home/solvina/options/.env
```

It is deployed alongside the options engine via `deploy.sh`:
1. `rsync` copies the whole `telegram-bot/` tree to the target.
2. A Python venv is created and `requirements.txt` installed.
3. The systemd unit is enabled and (re)started.

---

## Known limitations / improvement areas

| Area | Note |
|------|------|
| Single-user only | `TELEGRAM_CHAT_ID` is one integer — no multi-user support |
| Naive chunking | `split_message` splits at byte 4 000, not at word/sentence boundaries — can break mid-word or mid-code-block |
| No typing indicator during wait | `send_action("typing")` is fired once but expires after ~5 s; long Claude calls will show the indicator as gone |
| Blocking subprocess | Each message spawns a new `subprocess.run`; concurrent messages from the same chat would queue behind each other |
| Session file race | `.session_id` is written without a file lock — safe for a single-user bot but worth noting |
| `--dangerously-skip-permissions` | The Claude CLI is invoked with full permissions; acceptable on a personal machine, worth auditing before broader use |
