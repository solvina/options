#!/usr/bin/env python3
"""
Telegram bot backed by a single persistent Claude Code process.
One long-running `claude` subprocess handles all messages — no startup overhead.
"""

import os
import json
import asyncio
import subprocess
import logging
import threading
from pathlib import Path
from dotenv import load_dotenv
from telegram import Update
from telegram.ext import ApplicationBuilder, MessageHandler, CommandHandler, filters

# ── Config ────────────────────────────────────────────────────────────────────

load_dotenv(Path(__file__).parent.parent / ".env")

BOT_TOKEN    = os.environ["TELEGRAM_BOT_TOKEN"]
ALLOWED_CHAT = int(os.environ["TELEGRAM_CHAT_ID"])
CLAUDE_BIN   = os.environ.get("CLAUDE_BIN", "/home/solvina/.local/bin/claude")
CLAUDE_MODEL = os.environ.get("CLAUDE_MODEL", "haiku")
WORK_DIR     = Path(__file__).parent

logging.basicConfig(format="%(asctime)s [%(levelname)s] %(message)s", level=logging.INFO)
log = logging.getLogger(__name__)

# ── Persistent Claude session ─────────────────────────────────────────────────

class ClaudeSession:
    """
    Wraps a single long-running `claude -p --input-format stream-json` process.
    Messages are sent via stdin; responses read from stdout until a 'result' event.
    Auto-restarts if the process dies.
    """

    def __init__(self, model: str = "haiku"):
        self.model = model
        self._proc: subprocess.Popen | None = None
        self._lock = asyncio.Lock()

    def _start_process(self):
        log.info("Starting claude process (model=%s)…", self.model)
        self._proc = subprocess.Popen(
            [
                CLAUDE_BIN, "-p",
                "--input-format", "stream-json",
                "--output-format", "stream-json",
                "--verbose",
                "--model", self.model,
                "--dangerously-skip-permissions",
            ],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
            cwd=str(WORK_DIR),
        )
        # Drain stderr silently so it doesn't block
        threading.Thread(target=self._drain_stderr, daemon=True).start()
        log.info("Claude process started (pid=%d)", self._proc.pid)

    def _drain_stderr(self):
        for line in self._proc.stderr:
            stripped = line.rstrip()
            if stripped:
                log.debug("claude stderr: %s", stripped)

    def _is_alive(self) -> bool:
        return self._proc is not None and self._proc.poll() is None

    def _send_recv(self, message: str) -> str:
        """Blocking: send message, read stream until result event."""
        payload = json.dumps({
            "type": "user",
            "message": {"role": "user", "content": message}
        }) + "\n"

        self._proc.stdin.write(payload)
        self._proc.stdin.flush()

        for raw in self._proc.stdout:
            raw = raw.strip()
            if not raw:
                continue
            try:
                ev = json.loads(raw)
                if ev.get("type") == "result":
                    if ev.get("is_error"):
                        return f"⚠️ {ev.get('result', 'Unknown error')}"
                    return ev.get("result", "_(no response)_").strip()
            except json.JSONDecodeError:
                pass  # partial line or non-JSON — ignore

        return "_(process closed without result)_"

    async def chat(self, message: str) -> str:
        async with self._lock:
            if not self._is_alive():
                self._start_process()
            loop = asyncio.get_event_loop()
            return await loop.run_in_executor(None, self._send_recv, message)

    def restart(self):
        if self._proc:
            try:
                self._proc.terminate()
            except Exception:
                pass
        self._proc = None
        log.info("Session reset — process will restart on next message")


# Global session (starts lazily on first message)
session = ClaudeSession(model=CLAUDE_MODEL)

# ── Telegram handlers ─────────────────────────────────────────────────────────

def is_allowed(update: Update) -> bool:
    return update.effective_chat.id == ALLOWED_CHAT

async def keep_typing(chat, stop: asyncio.Event):
    """Re-send 'typing' every 4 s so Telegram shows the indicator."""
    while not stop.is_set():
        try:
            await chat.send_action("typing")
        except Exception:
            pass
        await asyncio.sleep(4)

async def handle_message(update: Update, context):
    if not is_allowed(update):
        log.warning("Ignored message from chat_id=%s", update.effective_chat.id)
        return

    stop = asyncio.Event()
    typing = asyncio.create_task(keep_typing(update.message.chat, stop))
    try:
        response = await session.chat(update.message.text)
    except Exception as e:
        log.exception("chat error")
        response = f"⚠️ Error: {e}"
    finally:
        stop.set()
        typing.cancel()

    for chunk in _split(response):
        await update.message.reply_text(chunk)

async def handle_start(update: Update, context):
    if not is_allowed(update):
        return
    await update.message.reply_text(
        f"👋 Claude bot running ({session.model})\n\n"
        "Commands:\n"
        "  /reset — fresh conversation\n"
        "  /haiku — fast model ⚡\n"
        "  /sonnet — smarter model 🧠\n\n"
        "Just type to chat!"
    )

async def handle_reset(update: Update, context):
    if not is_allowed(update):
        return
    session.restart()
    await update.message.reply_text("🔄 Session reset. Fresh conversation!")

async def handle_haiku(update: Update, context):
    if not is_allowed(update):
        return
    session.model = "haiku"
    session.restart()
    await update.message.reply_text("⚡ Switched to Haiku (fast). Session reset.")

async def handle_sonnet(update: Update, context):
    if not is_allowed(update):
        return
    session.model = "sonnet"
    session.restart()
    await update.message.reply_text("🧠 Switched to Sonnet (smarter). Session reset.")

# ── Helpers ───────────────────────────────────────────────────────────────────

def _split(text: str, limit: int = 4000) -> list[str]:
    if len(text) <= limit:
        return [text]
    chunks = []
    while text:
        chunks.append(text[:limit])
        text = text[limit:]
    return chunks

# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    log.info("Starting Telegram bot (chat=%d, model=%s)", ALLOWED_CHAT, CLAUDE_MODEL)
    app = ApplicationBuilder().token(BOT_TOKEN).build()
    app.add_handler(CommandHandler("start",  handle_start))
    app.add_handler(CommandHandler("reset",  handle_reset))
    app.add_handler(CommandHandler("haiku",  handle_haiku))
    app.add_handler(CommandHandler("sonnet", handle_sonnet))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))
    log.info("Polling…")
    app.run_polling()

if __name__ == "__main__":
    main()
