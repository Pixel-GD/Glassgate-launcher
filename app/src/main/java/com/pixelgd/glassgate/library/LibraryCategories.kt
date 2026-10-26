package com.pixelgd.glassgate.library

import android.content.pm.ApplicationInfo
import com.pixelgd.glassgate.R
import com.pixelgd.glassgate.apps.AppEntry

/** The App Library's folders, named and ordered the way iOS names them. */
enum class LibraryCategory(val title: Int) {
    SUGGESTIONS(R.string.library_suggestions),
    RECENT(R.string.library_recent),
    SOCIAL(R.string.library_social),
    PRODUCTIVITY(R.string.library_productivity),
    CREATIVITY(R.string.library_creativity),
    ENTERTAINMENT(R.string.library_entertainment),
    INFO(R.string.library_info),
    SHOPPING(R.string.library_shopping),
    TRAVEL(R.string.library_travel),
    HEALTH(R.string.library_health),
    EDUCATION(R.string.library_education),
    GAMES(R.string.library_games),
    UTILITIES(R.string.library_utilities),
    OTHER(R.string.library_other),
}

class LibraryGroup(val category: LibraryCategory, val apps: List<AppEntry>)

/**
 * Sorts apps into App Library categories. The app's own declared category
 * (ApplicationInfo.category) wins; most apps don't declare one, so package-name rules cover the
 * common ones, and anything else built into the phone lands in Utilities.
 */
object LibraryCategories {

    private const val RECENT_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    private const val RECENT_MAX = 12

    fun build(apps: List<AppEntry>, usage: AppUsage, now: Long = System.currentTimeMillis()): List<LibraryGroup> {
        val recent = apps
            .filter { !it.isSystem && now - it.firstInstallTime in 0..RECENT_WINDOW_MS }
            .sortedByDescending { it.firstInstallTime }
            .take(RECENT_MAX)

        val suggestions = usage.top(apps, 4, now).toMutableList()
        for (a in recent) if (suggestions.size < 4 && a !in suggestions) suggestions += a
        if (suggestions.size < 4) {
            for (a in apps) if (suggestions.size < 4 && !a.isSystem && a !in suggestions) suggestions += a
        }

        val byCategory = apps.groupBy { classify(it) }
        // Within a folder the most-used apps take the big slots; ties keep A to Z order.
        val groups = byCategory.map { (c, list) ->
            LibraryGroup(c, list.sortedByDescending { usage.score(it.key, now) })
        }.sortedWith(
            compareBy<LibraryGroup> { it.category == LibraryCategory.OTHER }
                .thenByDescending { g -> g.apps.sumOf { usage.score(it.key, now).toDouble() } }
                .thenByDescending { it.apps.size },
        )

        val out = ArrayList<LibraryGroup>()
        if (suggestions.isNotEmpty()) out += LibraryGroup(LibraryCategory.SUGGESTIONS, suggestions)
        if (recent.isNotEmpty()) out += LibraryGroup(LibraryCategory.RECENT, recent)
        out += groups
        return out
    }

    fun classify(app: AppEntry): LibraryCategory {
        declared(app.category)?.let { return it }
        val pkg = app.packageName.lowercase()
        val segments = pkg.split('.', '_', '-')
        for ((category, words) in RULES) {
            for (w in words) {
                val hit = if (w.length >= 5) pkg.contains(w) else segments.contains(w)
                if (hit) return category
            }
        }
        return if (app.isSystem) LibraryCategory.UTILITIES else LibraryCategory.OTHER
    }

    private fun declared(c: Int): LibraryCategory? = when (c) {
        ApplicationInfo.CATEGORY_GAME -> LibraryCategory.GAMES
        ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> LibraryCategory.ENTERTAINMENT
        ApplicationInfo.CATEGORY_IMAGE -> LibraryCategory.CREATIVITY
        ApplicationInfo.CATEGORY_SOCIAL -> LibraryCategory.SOCIAL
        ApplicationInfo.CATEGORY_NEWS -> LibraryCategory.INFO
        ApplicationInfo.CATEGORY_MAPS -> LibraryCategory.TRAVEL
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> LibraryCategory.PRODUCTIVITY
        ApplicationInfo.CATEGORY_ACCESSIBILITY -> LibraryCategory.UTILITIES
        else -> null
    }

    /**
     * Checked in order, first match wins. Words of 5+ letters match anywhere in the package name;
     * shorter ones must be a whole segment ("tv" matches com.foo.tv, not com.tvista).
     * Food comes before Travel so com.ubercab.eats isn't filed with com.ubercab.
     */
    private val RULES: List<Pair<LibraryCategory, List<String>>> = listOf(
        LibraryCategory.SHOPPING to listOf(
            "eats", "food", "deliveroo", "justeat", "doordash", "grubhub", "starbucks", "mcdonalds",
            "dominos", "amazon.mshop", "amazon.shopping", "ebay", "aliexpress", "temu", "shein", "vinted",
            "depop", "etsy", "zalando", "asos", "lidl", "tesco", "aldi", "ikea", "shop", "store.app",
        ),
        LibraryCategory.SOCIAL to listOf(
            "whatsapp", "instagram", "facebook.katana", "facebook.orca", "messenger", "telegram",
            "thoughtcrime", "signal", "discord", "snapchat", "twitter", "reddit", "tiktok", "musically",
            "zhiliaoapp", "linkedin", "barcelona", "bereal", "pinterest", "tumblr", "mastodon", "bluesky",
            "viber", "wechat", "line", "skype", "messaging", "mms", "dialer", "contacts", "incallui",
            "facetime", "duo", "meet", "talk",
        ),
        LibraryCategory.PRODUCTIVITY to listOf(
            "gm", "email", "outlook", "office", "docs", "sheets", "slides", "drive", "dropbox", "onedrive",
            "notion", "slack", "teams", "zoom", "calendar", "keep", "notes", "todo", "trello", "asana",
            "evernote", "bank", "banking", "paypal", "revolut", "wallet", "finance", "invest", "crypto",
            "coinbase", "binance", "monzo", "wise", "stripe", "pay", "authenticator", "acrobat", "adobe.reader",
        ),
        LibraryCategory.CREATIVITY to listOf(
            "camera", "gallery", "photos", "photo", "lightroom", "photoshop", "snapseed", "canva",
            "capcut", "vsco", "picsart", "videoeditor", "procreate", "sketch", "draw", "paint", "studio",
        ),
        LibraryCategory.ENTERTAINMENT to listOf(
            "youtube", "netflix", "spotify", "disney", "primevideo", "avod", "hulu", "twitch", "music",
            "soundcloud", "deezer", "tidal", "shazam", "podcast", "audible", "crunchyroll", "plex",
            "video", "player", "tv", "radio", "hbo", "max",
        ),
        LibraryCategory.INFO to listOf(
            "news", "weather", "kindle", "reader", "books", "wikipedia", "medium", "feedly", "flipboard",
            "bbc", "cnn", "rte", "guardian", "nytimes", "magzter",
        ),
        LibraryCategory.TRAVEL to listOf(
            "maps", "waze", "ubercab", "lyft", "bolt", "freenow", "airbnb", "booking", "expedia",
            "tripadvisor", "skyscanner", "ryanair", "aerlingus", "airline", "flight", "hotel", "transit",
            "citymapper", "trainline", "moovit", "navigation", "translate",
        ),
        LibraryCategory.HEALTH to listOf(
            "health", "shealth", "fitness", "strava", "workout", "myfitnesspal", "calm", "headspace",
            "sleep", "garmin", "fitbit", "runtastic", "nike.plusgps", "flo", "clue", "fit",
        ),
        LibraryCategory.EDUCATION to listOf(
            "duolingo", "classroom", "quizlet", "photomath", "khanacademy", "coursera", "udemy",
            "brainly", "moodle", "canvas", "learn", "school", "edu",
        ),
        LibraryCategory.GAMES to listOf("game", "games", "supercell", "king", "roblox", "mojang", "epicgames"),
        LibraryCategory.UTILITIES to listOf(
            "settings", "calculator", "calc", "clock", "deskclock", "alarm", "myfiles", "files",
            "filemanager", "compass", "recorder", "voicerecorder", "chrome", "browser", "sbrowser",
            "firefox", "vending", "galaxyapps", "flashlight", "scanner", "smartthings", "bixby",
            "assistant", "googlequicksearchbox", "magnifier", "watchmanager", "wearable",
        ),
    )
}
