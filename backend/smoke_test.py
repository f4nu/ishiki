"""Offline smoke test for the backend's Anki logic — no AnkiWeb account needed.

Creates a throwaway collection, adds a card, and exercises list_decks / next_card /
answer plus the HTML->text cleaner. Does NOT test AnkiWeb sync (that needs your
credentials — run the server for that).

Run:
    cd backend && . .venv/bin/activate && python smoke_test.py
"""

import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from anki_client import AnkiClient, to_text


def _anki_version() -> str:
    try:
        from anki.buildinfo import version
        return version
    except Exception:
        return "?"


def main() -> int:
    tmp = tempfile.mkdtemp(prefix="anki-pebble-test-")
    path = os.path.join(tmp, "collection.anki2")

    client = AnkiClient(path, "dummy-user", "dummy-pass")  # sync() is never called
    col = client.col

    deck_id = col.decks.id("Default")
    model = col.models.by_name("Basic")
    note = col.new_note(model)
    note["Front"] = "Capital of <b>France</b>?<br>Think."
    note["Back"] = "Paris [sound:bell.mp3]"
    col.add_note(note, deck_id)

    # 1) HTML / markup cleaning (CSS block, <br>, rendered sound placeholder)
    cleaned = to_text("<style>.card{color:red}</style>Hello<br>World [anki:play:a:0]")
    assert cleaned == "Hello\nWorld", f"to_text gave {cleaned!r}"

    # 2) deck listing with a due count
    decks = client.list_decks()
    assert any(d.name == "Default" and d.due >= 1 for d in decks), decks

    # 3) fetch the card; front/back must be clean text (no leaked CSS braces)
    card = client.next_card(deck_id)
    assert card and card["front"] and card["back"], card
    assert "{" not in card["front"], "CSS leaked into front"

    # 4) grade it (3 = OK / Good) without crashing
    client.answer(card["card_id"], 3)

    print("decks:", [(d.name, d.due) for d in decks])
    print("front:", repr(card["front"]))
    print("back: ", repr(card["back"]))
    print(f"\nOK — backend logic works against anki {_anki_version()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
