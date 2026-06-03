"""Headless Anki client.

Keeps a local collection synced with AnkiWeb (via the official `anki` package) and
exposes the few operations the Pebble app needs: list decks, fetch the next due card,
and grade a card.

The sync path through the `anki` library has a couple of known rough edges (notably the
first-run full download and the occasionally-blocking `sync_login`). Those spots are
marked; we harden them against the real errors on the first live sync.
"""

from __future__ import annotations

import html
import os
import re
import threading
import time
from dataclasses import dataclass

from anki.collection import Collection


# --- HTML -> plain text the watch can show ---------------------------------------

# <style>/<script> blocks must be removed wholesale (content and all), not just the
# tags — Anki's rendered card HTML prepends the note type's CSS in a <style> block.
_STYLE = re.compile(r"<style[^>]*>.*?</style>", re.I | re.S)
_SCRIPT = re.compile(r"<script[^>]*>.*?</script>", re.I | re.S)
_SOUND = re.compile(r"\[sound:[^\]]+\]")          # raw field form
_PLAY = re.compile(r"\[anki:play:[^\]]*\]")       # rendered form
_BR = re.compile(r"<br\s*/?>", re.I)
_BLOCK_END = re.compile(r"</(p|div|li|tr|h[1-6])>", re.I)
_TAG = re.compile(r"<[^>]+>")


def to_text(rendered_html: str) -> str:
    """Turn Anki's rendered card HTML into plain text for the watch screen."""
    s = _STYLE.sub("", rendered_html)
    s = _SCRIPT.sub("", s)
    s = _SOUND.sub("", s)
    s = _PLAY.sub("", s)
    s = _BR.sub("\n", s)
    s = _BLOCK_END.sub("\n", s)
    s = _TAG.sub("", s)
    s = html.unescape(s)
    s = re.sub(r"[ \t]+", " ", s)
    s = re.sub(r"\n{3,}", "\n\n", s)
    return s.strip()


@dataclass
class DeckInfo:
    id: int
    name: str
    due: int


class AnkiClient:
    def __init__(
        self,
        collection_path: str,
        username: str,
        password: str,
        endpoint: str | None = None,
    ):
        self._username = username
        self._password = password
        self._endpoint = endpoint or None
        self._lock = threading.RLock()
        self._auth = None

        os.makedirs(os.path.dirname(collection_path) or ".", exist_ok=True)
        self.col = Collection(collection_path)
        # v3 scheduler is the default on recent versions; make it explicit.
        try:
            self.col.set_v3_scheduler(True)
        except Exception:
            pass

    # --- sync --------------------------------------------------------------------

    def _login(self):
        if self._auth is None:
            # NOTE: sync_login can block briefly; acceptable for a personal server.
            try:
                self._auth = self.col.sync_login(
                    self._username, self._password, self._endpoint
                )
            except TypeError:
                # Older/newer signatures may not accept an explicit endpoint.
                self._auth = self.col.sync_login(self._username, self._password)
        return self._auth

    def sync(self) -> str:
        """Sync the local collection with AnkiWeb. Returns a short status string."""
        with self._lock:
            auth = self._login()
            # sync_media=False: the watch only shows text, so skip media sync.
            out = self.col.sync_collection(auth, sync_media=False)
            # AnkiWeb load-balances to a shard and returns the host to use for
            # follow-up requests; reusing the login endpoint makes the full download
            # 400 with "missing original size". (auth is cached, so this persists.)
            if out.new_endpoint:
                auth.endpoint = out.new_endpoint
            # ChangesRequired: 0 NO_CHANGES, 1 NORMAL_SYNC, 2 FULL_SYNC,
            #                  3 FULL_DOWNLOAD, 4 FULL_UPLOAD
            req = int(out.required)
            if req <= 1:
                return f"synced ({req})"
            # A full sync is required (first run is FULL_DOWNLOAD). The server is the
            # source of truth here, so anything but FULL_UPLOAD is a download.
            upload = req == 4
            path = self.col.path
            self.col.full_upload_or_download(
                auth=auth, server_usn=out.server_media_usn, upload=upload
            )
            # A full sync replaces the on-disk collection; reopen our handle so we
            # don't keep serving from a stale one.
            try:
                self.col.close()
            except Exception:
                pass
            self.col = Collection(path)
            try:
                self.col.set_v3_scheduler(True)
            except Exception:
                pass
            return f"{'full_upload' if upload else 'full_download'} ({req})"

    # --- reviewing ---------------------------------------------------------------

    def list_decks(self) -> list[DeckInfo]:
        with self._lock:
            tree = self.col.sched.deck_due_tree()
            decks: list[DeckInfo] = []

            def walk(node):
                # The root node has id 0 and an empty name; skip it.
                if getattr(node, "deck_id", 0):
                    due = node.review_count + node.learn_count + node.new_count
                    decks.append(DeckInfo(node.deck_id, node.name, due))
                for child in node.children:
                    walk(child)

            walk(tree)
            return decks

    def next_card(self, deck_id: int) -> dict | None:
        """Return the next card to study in this deck, or None if nothing is due.

        MVP: query due/new cards directly (stable across anki versions) instead of
        driving the full v3 queue. Grading still goes through the real scheduler.
        """
        with self._lock:
            name = self.col.decks.name(deck_id)  # full "Parent::Child" name
            ids = self.col.find_cards(f'deck:"{name}" (is:due OR is:new)')
            if not ids:
                return None
            card = self.col.get_card(ids[0])
            card.timer_started = time.time()
            return {
                "card_id": card.id,
                "front": to_text(card.question()),
                "back": to_text(card.answer()),
            }

    def answer(self, card_id: int, ease: int) -> None:
        """Grade a card. ease: 1=Again (Not OK), 2=Hard, 3=Good (OK), 4=Easy."""
        with self._lock:
            card = self.col.get_card(card_id)
            # The card is re-fetched here, so its review timer isn't set; the v3
            # scheduler reads time_taken() during answerCard and would hit None.
            card.timer_started = time.time()
            self.col.sched.answerCard(card, ease)
