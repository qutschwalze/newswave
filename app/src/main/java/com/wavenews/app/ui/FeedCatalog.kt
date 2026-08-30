package com.wavenews.app.ui

/**
 * Kuratierter Feed-Katalog für das "Feed entdecken"-Sheet.
 * Jede URL wurde vor Aufnahme per HTTP-Check verifiziert (RSS/Atom/RDF-Erkennung).
 * Kategorie = Ziellabel in FreshRSS (wird beim Import automatisch angelegt).
 */
data class CuratedFeed(val title: String, val url: String, val category: String)

/** Alle Kategorien des Katalogs in definierter Reihenfolge. */
val catalogCategories: List<String>
    get() = curatedCatalog.map { it.category }.distinct()

val curatedCatalog: List<CuratedFeed> = listOf(
    // --- Nachrichten DE ---
    CuratedFeed("tagesschau", "https://www.tagesschau.de/xml/rss2/", "Nachrichten DE"),
    CuratedFeed("ZDF Nachrichten", "https://www.zdf.de/rss/zdf/nachrichten", "Nachrichten DE"),
    CuratedFeed("DIE ZEIT", "https://newsfeed.zeit.de/index", "Nachrichten DE"),
    CuratedFeed("DW Deutsch", "https://rss.dw.com/rdf/rss-de-all", "Nachrichten DE"),
    CuratedFeed("Deutschlandfunk", "https://www.deutschlandfunk.de/nachrichten-100.rss", "Nachrichten DE"),

    // --- Politik ---
    CuratedFeed("ZEIT Politik", "https://newsfeed.zeit.de/politik/index", "Politik"),
    CuratedFeed("FAZ Politik", "https://www.faz.net/rss/aktuell/politik/", "Politik"),
    CuratedFeed("SPIEGEL Politik", "https://www.spiegel.de/politik/index.rss", "Politik"),
    CuratedFeed("Süddeutsche Zeitung", "https://rss.sueddeutsche.de/rss/Topthemen", "Politik"),
    CuratedFeed("DW Wirtschaft & Politik", "https://rss.dw.com/rdf/rss-de-wirtschaft", "Politik"),

    // --- Wirtschaft ---
    CuratedFeed("SPIEGEL Wirtschaft", "https://www.spiegel.de/wirtschaft/index.rss", "Wirtschaft"),
    CuratedFeed("SZ Wirtschaft", "https://rss.sueddeutsche.de/rss/Wirtschaft", "Wirtschaft"),
    CuratedFeed("Handelsblatt", "https://www.handelsblatt.com/contentexport/feed/top-themen", "Wirtschaft"),

    // --- Sport ---
    CuratedFeed("DW Sport", "https://rss.dw.com/rdf/rss-de-sport", "Sport"),
    CuratedFeed("FAZ Sport", "https://www.faz.net/rss/aktuell/sport/", "Sport"),
    CuratedFeed("ZEIT Sport", "https://newsfeed.zeit.de/sport/index", "Sport"),

    // --- Kultur ---
    CuratedFeed("Monopol Magazin", "https://www.monopol-magazin.de/rss.xml", "Kultur"),
    CuratedFeed("SPIEGEL Kultur", "https://www.spiegel.de/kultur/index.rss", "Kultur"),

    // --- Wissenschaft ---
    CuratedFeed("Spektrum der Wissenschaft", "https://www.spektrum.de/rss.xml", "Wissenschaft"),
    CuratedFeed("Quanta Magazine", "https://api.quantamagazine.org/feed/", "Wissenschaft"),
    CuratedFeed("scinexx", "https://www.scinexx.de/feed/", "Wissenschaft"),

    // --- Tech DE ---
    CuratedFeed("heise top", "https://www.heise.de/rss/heise-top-atom.xml", "Tech DE"),
    CuratedFeed("Golem", "https://rss.golem.de/rss.php?r=de&feed=RSS2.0", "Tech DE"),
    CuratedFeed("ComputerBase", "https://www.computerbase.de/rss/news", "Tech DE"),
    CuratedFeed("t3n", "https://t3n.de/rss.xml", "Tech DE"),
    CuratedFeed("c't Magazin", "https://www.heise.de/ct/rss/artikel-atom.xml", "Tech DE"),
    CuratedFeed("iX Magazin", "https://www.heise.de/ix/rss/news-atom.xml", "Tech DE"),

    // --- Tech EN ---
    CuratedFeed("Hacker News", "https://hnrss.org/frontpage", "Tech EN"),
    CuratedFeed("Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", "Tech EN"),
    CuratedFeed("The Verge", "https://www.theverge.com/rss/index.xml", "Tech EN"),
    CuratedFeed("Wired", "https://www.wired.com/feed/rss", "Tech EN"),
    CuratedFeed("Smashing Magazine", "https://www.smashingmagazine.com/feed/", "Tech EN"),
    CuratedFeed("CSS-Tricks", "https://css-tricks.com/feed/", "Tech EN"),
    CuratedFeed("Changelog", "https://changelog.com/feed", "Tech EN"),

    // --- Security ---
    CuratedFeed("Krebs on Security", "https://krebsonsecurity.com/feed/", "Security"),
    CuratedFeed("BleepingComputer", "https://www.bleepingcomputer.com/feed/", "Security"),
    CuratedFeed("The Hacker News", "https://feeds.feedburner.com/TheHackersNews", "Security"),
    CuratedFeed("Golem Security", "https://rss.golem.de/rss.php?r=de&feed=RSS2.0&ms=security", "Security"),

    // --- KI ---
    CuratedFeed("heise KI", "https://www.heise.de/rss/heise-ki-atom.xml", "Künstliche Intelligenz"),
    CuratedFeed("THE DECODER", "https://the-decoder.de/feed/", "Künstliche Intelligenz"),
    CuratedFeed("MIT Technology Review", "https://www.technologyreview.com/feed/", "Künstliche Intelligenz"),
    CuratedFeed("Golem KI", "https://rss.golem.de/rss.php?r=de&feed=RSS2.0&ms=ki", "Künstliche Intelligenz"),
    CuratedFeed("MIXED KI-News", "https://mixed.de/feed/", "Künstliche Intelligenz"),
    CuratedFeed("VentureBeat AI", "https://venturebeat.com/category/ai/feed/", "Künstliche Intelligenz"),

    // --- Android ---
    CuratedFeed("9to5Google", "https://9to5google.com/feed/", "Android"),
    CuratedFeed("r/Android", "https://www.reddit.com/r/Android/.rss", "Android"),

    // --- Selfhosted ---
    CuratedFeed("Lobste.rs", "https://lobste.rs/rss", "Selfhosted"),
    CuratedFeed("selfh.st", "https://selfh.st/feed/", "Selfhosted"),
    CuratedFeed("r/selfhosted", "https://www.reddit.com/r/selfhosted/.rss", "Selfhosted"),

    // --- Schule & Bildung ---
    CuratedFeed("Deutsches Schulportal", "https://deutsches-schulportal.de/feed/", "Schule & Bildung"),

    // --- Netzpolitik & Privacy ---
    CuratedFeed("netzpolitik.org", "https://netzpolitik.org/feed/", "Netzpolitik & Privacy"),
    CuratedFeed("Kuketz-Blog", "https://www.kuketz-blog.de/feed/", "Netzpolitik & Privacy"),
)
