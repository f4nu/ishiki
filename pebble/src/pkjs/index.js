// PebbleKit JS bridge: runs on the phone, talks to the anki-pebble backend over
// HTTP, and relays results to the watch via AppMessage.
//
// Backend URL + API token are set on the phone via the app's Settings page (Clay).
// In the emulator, open that page with:  pebble emu-app-config

var Clay = require('pebble-clay');
var clayConfig = require('./config');
var clay = new Clay(clayConfig);

var MAX_TEXT = 900; // keep each text field within the watch's AppMessage buffer

// CMD: watch -> js
var CMD_GET_DECKS = 1, CMD_GET_CARD = 2, CMD_ANSWER = 3, CMD_SYNC = 4;
// MSG_TYPE: js -> watch
var MSG_DECKS = 1, MSG_CARD = 2, MSG_DONE = 3, MSG_ERROR = 4;

// --- settings (persisted by Clay under 'clay-settings') ---------------------
function settings() {
  try { return JSON.parse(localStorage.getItem('clay-settings')) || {}; }
  catch (e) { return {}; }
}
function backendUrl() {
  return (settings().BACKEND_URL || 'http://localhost:8000').replace(/\/+$/, '');
}
function apiToken() {
  return settings().API_TOKEN || '';
}

// --- backend calls ----------------------------------------------------------
function api(method, path, body, onok, onerr) {
  var token = apiToken();
  if (!token) { onerr('Set token in phone settings'); return; }
  var xhr = new XMLHttpRequest();
  xhr.open(method, backendUrl() + path);
  xhr.setRequestHeader('Authorization', 'Bearer ' + token);
  if (body) { xhr.setRequestHeader('Content-Type', 'application/json'); }
  xhr.timeout = 15000;
  xhr.onload = function () {
    if (xhr.status >= 200 && xhr.status < 300) {
      try { onok(xhr.responseText ? JSON.parse(xhr.responseText) : null); }
      catch (e) { onerr('bad json'); }
    } else {
      onerr('http ' + xhr.status);
    }
  };
  xhr.onerror = function () { onerr('network'); };
  xhr.ontimeout = function () { onerr('timeout'); };
  xhr.send(body ? JSON.stringify(body) : null);
}

function sendErr(msg) {
  console.log('error: ' + msg);
  Pebble.sendAppMessage({ MSG_TYPE: MSG_ERROR, ERR: String(msg).slice(0, 100) });
}
function trunc(s) { s = s || ''; return s.length > MAX_TEXT ? s.slice(0, MAX_TEXT) : s; }

function fetchDecks() {
  api('GET', '/decks', null, function (decks) {
    var rows = (decks || []).map(function (d) {
      return d.id + '\t' + d.name + '\t' + (d.due || 0);
    }).join('\n');
    Pebble.sendAppMessage({ MSG_TYPE: MSG_DECKS, DECKS: rows });
  }, sendErr);
}

function fetchCard(deckId) {
  api('GET', '/next?deck_id=' + encodeURIComponent(deckId), null, function (card) {
    if (!card || card.done) {
      Pebble.sendAppMessage({ MSG_TYPE: MSG_DONE });
    } else {
      Pebble.sendAppMessage({
        MSG_TYPE: MSG_CARD,
        CARD_ID: String(card.card_id),
        FRONT: trunc(card.front),
        BACK: trunc(card.back)
      });
    }
  }, sendErr);
}

function answerThenNext(cardId, ease, deckId) {
  api('POST', '/answer', { card_id: parseInt(cardId, 10), ease: ease }, function () {
    fetchCard(deckId);
  }, sendErr);
}

// On launch, fetch decks (will prompt for settings if no token yet).
Pebble.addEventListener('ready', function () { fetchDecks(); });

// After the settings page is saved, retry so a new token/URL takes effect.
// (Clay's own webviewclosed listener runs first and persists the values.)
Pebble.addEventListener('webviewclosed', function () { fetchDecks(); });

Pebble.addEventListener('appmessage', function (e) {
  var d = e.payload;
  switch (d.CMD) {
    case CMD_GET_DECKS: fetchDecks(); break;
    case CMD_GET_CARD:  fetchCard(d.DECK_ID); break;
    case CMD_ANSWER:    answerThenNext(d.CARD_ID, d.EASE, d.DECK_ID); break;
    case CMD_SYNC:      api('POST', '/sync', {}, function () {}, sendErr); break;
  }
});
