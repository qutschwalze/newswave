package com.wavenews.app.ui

import androidx.compose.ui.graphics.Color

/**
 * Themen-Erkennung für generische Vorschaubilder:
 * Erkennt bekannte Tech-Themen im Artikeltext und liefert das passende Brand-Logo
 * (verifizierte URLs: simple-icons-CDN + offizielle Quellen). Ohne Treffer → Monogramm.
 */
object TopicMatcher {

    // Reihenfolge = Priorität (spezifisch vor generisch)
    private val topics: List<Pair<List<String>, String>> = listOf(
        listOf("jellyfin") to "https://cdn.simpleicons.org/jellyfin",
        listOf("plex") to "https://www.plex.tv/wp-content/themes/plex/assets/img/plex-logo.svg",
        listOf("pi-hole", "pihole") to "https://cdn.simpleicons.org/pihole",
        listOf("home assistant", "homeassistant", "home-assistant") to "https://cdn.simpleicons.org/homeassistant",
        listOf("nextcloud") to "https://cdn.simpleicons.org/nextcloud",
        listOf("wireguard") to "https://cdn.simpleicons.org/wireguard",
        listOf("tailscale") to "https://cdn.simpleicons.org/tailscale",
        listOf("proxmox") to "https://cdn.simpleicons.org/proxmox",
        listOf("docker") to "https://cdn.simpleicons.org/docker",
        listOf("kodi") to "https://cdn.simpleicons.org/kodi",
        listOf("grafana") to "https://cdn.simpleicons.org/grafana",
        listOf("caddy") to "https://cdn.simpleicons.org/caddy",
        listOf("wordpress") to "https://s.w.org/style/images/about/WordPress-logotype-simplified.png",
        listOf("github") to "https://cdn.simpleicons.org/github",
        listOf("youtube") to "https://cdn.simpleicons.org/youtube",
        listOf("samsung", "galaxy s", "galaxy tab") to "https://cdn.simpleicons.org/samsung",
        listOf("android") to "https://cdn.simpleicons.org/android",
        listOf("linux", "ubuntu", "debian", "fedora", "opensuse", "arch linux", "kernel") to
            "https://www.kernel.org/theme/images/logos/tux.png",
        listOf("google", "chrome", "chromium") to "https://cdn.simpleicons.org/google",
    )

    /** Liefert die Logo-URL für das Thema oder null, wenn kein bekanntes Thema erkannt wurde. */
    fun match(text: String): String? {
        val lower = text.lowercase()
        return topics.firstOrNull { (keywords, _) -> keywords.any { lower.contains(it) } }?.second
    }

    private val monogramPalette = listOf(
        Color(0xFF3949AB), Color(0xFF00897B), Color(0xFFD81B60), Color(0xFFF4511E),
        Color(0xFF6D4C41), Color(0xFF5E35B1), Color(0xFF039BE5), Color(0xFF7CB342),
        Color(0xFFF9A825), Color(0xFF455A64),
    )

    /** Stabile Farbe pro Quelle (Monogramm-Hintergrund). */
    fun monogramColor(seed: String): Color =
        monogramPalette[Math.floorMod(seed.hashCode(), monogramPalette.size)]
}
