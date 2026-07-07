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
from pathlib import Path
from dotenv import load_dotenv
from telegram import Update
from telegram.ext import ApplicationBuilder, MessageHandler, CommandHandler, filters

# ── Config ────────────────────────────────────────────────────────────────────

load_dotenv(Path(__file__).parent.parent / ".env")

BOT_TOKEN     = os.environ["TELEGRAM_BOT_TOKEN"]
ALLOWED_CHAT  = int(os.environ["TELEGRAM_CHAT_ID"])
# Forum topic the bot chats in. When set, the bot only engages inside this topic, leaving the
# separate engine-alerts topic (TELEGRAM_ALERTS_TOPIC_ID, used by the engine) untouched. 0 = any thread.
CHAT_TOPIC_ID = int(os.environ.get("TELEGRAM_CHAT_TOPIC_ID", "0"))
CLAUDE_BIN    = os.environ.get("CLAUDE_BIN", "/home/solvina/.local/bin/claude")
CLAUDE_MODEL  = os.environ.get("CLAUDE_MODEL", "opus")
CLAUDE_TIMEOUT = int(os.environ.get("CLAUDE_TIMEOUT", "120"))
WORK_DIR      = Path(__file__).parent

BOT_SYSTEM_PROMPT = (
    "You are a Telegram chat assistant. Reply with a single, concise final "
    "message. Do not send progress updates, status notes, or 'let me check' "
    "preambles — only the finished answer. Keep formatting plain-text friendly."
)

logging.basicConfig(format="%(asctime)s [%(levelname)s] %(message)s", level=logging.INFO)
log = logging.getLogger(__name__)

# ── Claude session (one `claude -p` invocation per turn) ───────────────────────

class ClaudeSession:
    """
    Drives Claude Code the way it's designed for headless use: a fresh, short-lived
    `claude -p` process per message. Conversation continuity is kept by capturing the
    `session_id` from each reply and passing it back via `--resume` on the next turn.

    A short-lived process reads its whole stdout to EOF, so no buffered output can
    leak into a later turn — replies can never drift onto an older question.
    """

    def __init__(self, model: str = "haiku"):
        self.model = model
        self._session_id: str | None = None
        self._lock = asyncio.Lock()

    def _run(self, message: str) -> str:
        """Blocking: spawn one claude process, return its result text."""
        argv = [
            CLAUDE_BIN, "-p", message,
            "--output-format", "json",
            "--model", self.model,
            "--append-system-prompt", BOT_SYSTEM_PROMPT,
            "--dangerously-skip-permissions",
        ]
        if self._session_id:
            argv += ["--resume", self._session_id]

        try:
            proc = subprocess.run(
                argv,
                capture_output=True,
                text=True,
                timeout=CLAUDE_TIMEOUT,
                cwd=str(WORK_DIR),
            )
        except subprocess.TimeoutExpired:
            return f"⚠️ Claude timed out after {CLAUDE_TIMEOUT}s."

        if proc.returncode != 0:
            err = (proc.stderr or "").strip() or f"exit code {proc.returncode}"
            log.error("claude failed: %s", err)
            return f"⚠️ Claude error: {err[:500]}"

        try:
            obj = json.loads(proc.stdout)
        except json.JSONDecodeError:
            log.error("claude output not JSON: %s", proc.stdout[:500])
            return "_(no response)_"

        # Carry the session forward so the next turn continues this conversation.
        sid = obj.get("session_id")
        if sid:
            self._session_id = sid

        if obj.get("is_error"):
            return f"⚠️ {obj.get('result', 'Unknown error')}"
        return (obj.get("result") or "_(no response)_").strip()

    async def chat(self, message: str) -> str:
        async with self._lock:
            loop = asyncio.get_event_loop()
            return await loop.run_in_executor(None, self._run, message)

    def reset(self):
        self._session_id = None
        log.info("Session reset — next message starts a fresh conversation")


# Global session (starts lazily on first message)
session = ClaudeSession(model=CLAUDE_MODEL)

# ── Telegram handlers ─────────────────────────────────────────────────────────

def is_allowed(update: Update) -> bool:
    if update.effective_chat is None or update.effective_chat.id != ALLOWED_CHAT:
        return False
    # Scope to the chat topic when configured, so the bot never answers in the engine-alerts topic
    # or the group's General thread. message_thread_id is None outside a forum topic.
    if CHAT_TOPIC_ID:
        msg = update.effective_message
        return msg is not None and msg.message_thread_id == CHAT_TOPIC_ID
    return True

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
        "  /sonnet — smarter model 🧠\n"
        "  /opus — most capable model 🎩\n\n"
        "Just type to chat!"
    )

async def handle_reset(update: Update, context):
    if not is_allowed(update):
        return
    session.reset()
    await update.message.reply_text("🔄 Session reset. Fresh conversation!")

async def handle_haiku(update: Update, context):
    if not is_allowed(update):
        return
    session.model = "haiku"
    await update.message.reply_text("⚡ Switched to Haiku (fast). Context kept.")

async def handle_sonnet(update: Update, context):
    if not is_allowed(update):
        return
    session.model = "sonnet"
    await update.message.reply_text("🧠 Switched to Sonnet (smarter). Context kept.")

async def handle_opus(update: Update, context):
    if not is_allowed(update):
        return
    session.model = "opus"
    await update.message.reply_text("🎩 Switched to Opus (most capable). Context kept.")

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
    log.info(
        "Starting Telegram bot (chat=%d, chat_topic=%s, model=%s)",
        ALLOWED_CHAT, CHAT_TOPIC_ID or "any", CLAUDE_MODEL,
    )
    app = ApplicationBuilder().token(BOT_TOKEN).build()
    app.add_handler(CommandHandler("start",  handle_start))
    app.add_handler(CommandHandler("reset",  handle_reset))
    app.add_handler(CommandHandler("haiku",  handle_haiku))
    app.add_handler(CommandHandler("sonnet", handle_sonnet))
    app.add_handler(CommandHandler("opus",   handle_opus))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))
    log.info("Polling…")
    app.run_polling()

if __name__ == "__main__":
    main()
