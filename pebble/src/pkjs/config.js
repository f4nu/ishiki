// Clay settings schema for the phone-side config page.
// Reached via the Settings gear on the Pebble app (or `pebble emu-app-config`
// in the emulator). Values are persisted to localStorage and read back in index.js.
module.exports = [
  {
    "type": "heading",
    "defaultValue": "Anki Settings"
  },
  {
    "type": "text",
    "defaultValue": "Point the watch at your anki-pebble backend."
  },
  {
    "type": "section",
    "items": [
      {
        "type": "input",
        "messageKey": "BACKEND_URL",
        "label": "Backend URL",
        "defaultValue": "http://localhost:8000",
        "attributes": {
          "placeholder": "https://your-server:8000",
          "type": "url"
        }
      },
      {
        "type": "input",
        "messageKey": "API_TOKEN",
        "label": "API Token",
        "defaultValue": "",
        "attributes": {
          "placeholder": "API_TOKEN from backend/.env"
        }
      }
    ]
  },
  {
    "type": "submit",
    "defaultValue": "Save"
  }
];
