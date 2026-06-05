// Clay settings schema for the phone-side config page.
// Reached via the Settings gear on the Pebble app (or `pebble emu-app-config`).
module.exports = [
  {
    "type": "heading",
    "defaultValue": "Ishiki Settings"
  },
  {
    "type": "text",
    "defaultValue": "By default, connect to the Ishiki companion app running on this phone. The URL and the token are only needed for the legacy Python backend."
  },
  {
    "type": "section",
    "items": [
      {
        "type": "input",
        "messageKey": "BACKEND_URL",
        "label": "Backend URL",
        "defaultValue": "http://127.0.0.1:8765",
        "attributes": {
          "placeholder": "http://127.0.0.1:8765",
          "type": "url"
        }
      },
      {
        "type": "input",
        "messageKey": "API_TOKEN",
        "label": "API Token (optional)",
        "defaultValue": "",
        "attributes": {
          "placeholder": "only for the legacy Python backend"
        }
      }
    ]
  },
  {
    "type": "submit",
    "defaultValue": "Save"
  }
];
