# News Wave

[![Release](https://img.shields.io/github/v/release/qutschwalze/newswave)](https://github.com/qutschwalze/newswave/releases)
[![License](https://img.shields.io/github/license/qutschwalze/newswave)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android%2013%2B-green)

**Deutsch** · [English](#english)

News Wave ist ein freier (FOSS) News-Reader für Android mit eigenem **FreshRSS**-Backend.
Kein Algorithmus, kein Clickbait-Feed, kein Tracking — deine Quellen, deine Kontrolle.

## Features

- **FreshRSS-Sync** über die Google-Reader-kompatible API (`/api/greader.php`) — FreshRSS bleibt 100 % Standard
- **Offline-Cache** (Room): Artikel sind auch ohne Netz lesbar
- **Karten-Ansicht** mit Artikelbildern, relativen Zeiten („5 Min.") und drei konfigurierbaren Größen (Standard / Mittel / Kompakt)
- **Themen-Bilder**: Artikel ohne Bild bekommen Brand-Logos (Jellyfin, Docker, Nextcloud, Home Assistant, Linux/Tux, …) oder ein Farb-Monogramm der Quelle
- **Dark Mode** mit Material-You-Dynamikfarben (Android 12+)
- **Detail-Ansicht** mit internem Browser (JavaScript + Cookies, consent-basierte Seiten wie zeit.de funktionieren)
- **Swipe-Gesten** im Artikel: nach unten ziehen → interner Viewer (später: KI-Zusammenfassung), nach oben ziehen → externer Browser — mit Ziel-Anzeige während des Ziehens
- **Back-Navigationskette** (konfigurierbar): Übersicht → Menü → Beenden
- **Home-Screen-Widget** (3 neueste ungelesene Artikel) & **Benachrichtigungen** bei neuen Artikeln
- **Share-Buttons** (Titel + Link) an Karte und Artikel
- **Deep-Link** `newswave://open` / `newswave://article/<id>` — bereit für die Wave-Launcher-Integration (Phase 6)

## Download

Aktuelle APK unter [Releases](https://github.com/qutschwalze/newswave/releases).
Die APKs sind debug-signierte Test-Builds — einfach über die vorherige Version installieren.

## Einrichtung

1. FreshRSS-Server bereithalten (self-hosted)
2. In der App anmelden: **Server-URL**, **Benutzername** und **API-Passwort**
   (FreshRSS → Einstellungen → Konto → API-Verwaltung; das *Web*-Passwort funktioniert nicht)
3. „Aktualisieren" tippen — Feeds und Artikel werden geladen

## Selbst bauen

```bash
git clone https://github.com/qutschwalze/newswave.git
cd newswave
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Voraussetzungen: JDK 17, Android SDK (API 35). Release-Publishing: `scripts/release.sh <version>`.

## Datenschutz

- Keine Analysen, kein Tracking, keine Drittanbieter-APIs
- Die App verbindet sich ausschließlich mit deinem FreshRSS-Server
- Das Passwort wird **nicht** gespeichert — nur das Session-Token
- Zusammenfassungen (Phase 5) laufen lokal per ONNX, ohne Cloud

## Roadmap

- [ ] **Phase 5:** Lokale KI-Artikel-Zusammenfassung (ONNX, DE/EN) — der Einstieg („↓ Interner Viewer") ist bereits als Hook vorbereitet
- [ ] **Phase 6:** Wave-Launcher-Integration (Linkswisch auf -1 via Deep-Link)

## Lizenz

[Apache 2.0](LICENSE)

---

# English

**News Wave** is a free and open-source (FOSS) news reader for Android powered by your own **FreshRSS** server.
No algorithm, no clickbait feed, no tracking — your sources, your control.

## Features

- **FreshRSS sync** via the Google-Reader-compatible API — FreshRSS stays 100 % stock
- **Offline cache** (Room): read articles without network
- **Card layout** with article images, relative timestamps, three configurable sizes (standard / medium / compact)
- **Topic images**: articles without an image get brand logos (Jellyfin, Docker, Nextcloud, Home Assistant, Linux/Tux, …) or a colored monogram of the source
- **Dark mode** with Material You dynamic colors (Android 12+)
- **Article detail view** with an internal browser (JS + cookies, consent-gated sites like zeit.de work)
- **Swipe gestures** on the article: drag down → internal viewer (future: AI summary), drag up → external browser, with an on-screen target indicator while dragging
- **Back-navigation chain** (configurable): main list → drawer → exit
- **Home-screen widget** (3 latest unread) & **notifications** for new articles
- **Share buttons** (title + link) on cards and in the article view
- **Deep links** `newswave://open` / `newswave://article/<id>` — ready for the Wave Launcher integration (phase 6)

## Download

Grab the latest APK from [Releases](https://github.com/qutschwalze/newswave/releases).
APKs are debug-signed test builds — just install over the previous version.

## Setup

1. Have a FreshRSS server ready (self-hosted)
2. Sign in with **server URL**, **username** and the **API password**
   (FreshRSS → Settings → Account → API management; the *web* password won't work)
3. Tap refresh — feeds and articles load

## Build from source

```bash
git clone https://github.com/qutschwalze/newswave.git
cd newswave
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK (API 35). Release publishing: `scripts/release.sh <version>`.

## Privacy

- No analytics, no tracking, no third-party APIs
- The app only talks to your own FreshRSS server
- The password is **never** stored — only the session token
- Summaries (phase 5) will run locally via ONNX, no cloud

## License

[Apache 2.0](LICENSE)
