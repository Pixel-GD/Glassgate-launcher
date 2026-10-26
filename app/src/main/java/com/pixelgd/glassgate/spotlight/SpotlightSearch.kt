package com.pixelgd.glassgate.spotlight

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import com.pixelgd.glassgate.apps.AppEntry
import java.text.Normalizer
import java.util.Locale

/** One contact match. [photo] is a thumbnail URI, or null to draw initials. */
class ContactHit(val id: Long, val lookupKey: String, val name: String, val photo: Uri?) {
    fun intent(): Intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(id, lookupKey))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** One system settings page Spotlight can jump to. */
class SettingHit(val title: String, val subtitle: String, val action: String, keywords: String) {
    internal val words: List<String> = (title + " " + keywords).let { SpotlightSearch.normalize(it) }
        .split(' ').filter { it.isNotEmpty() }

    fun intent(): Intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** What a query found. Apps come back best match first; [topHit] is the first of them. */
class SpotlightResults(
    val query: String,
    val apps: List<AppEntry>,
    val settings: List<SettingHit>,
    val calculation: String?,
) {
    val topHit: AppEntry? get() = apps.firstOrNull()
}

/**
 * The matching rules behind Spotlight. Apps and settings are matched on the main thread
 * (a few hundred labels is microseconds of work); contacts go through [contacts], which
 * does disk I/O and must run on a background thread.
 */
object SpotlightSearch {

    fun search(query: String, apps: List<AppEntry>, history: Map<String, Double>): SpotlightResults {
        val q = normalize(query)
        if (q.isEmpty()) return SpotlightResults(query, emptyList(), emptyList(), null)
        val scored = ArrayList<Pair<AppEntry, Double>>()
        for (app in apps) {
            val s = matchScore(q, normalize(app.label), app.packageName.lowercase(Locale.ROOT))
            if (s > 0) scored += app to s + (history[app.key] ?: 0.0).coerceAtMost(10.0) * 2
        }
        scored.sortByDescending { it.second }
        val settings = SETTINGS.mapNotNull { st ->
            val s = st.words.maxOfOrNull { w -> if (w.startsWith(q)) 2 else if (q.length >= 3 && w.contains(q)) 1 else 0 } ?: 0
            val multi = q.contains(' ') && q.split(' ').all { part -> st.words.any { it.startsWith(part) } }
            if (s > 0 || multi) st to (if (multi) 3 else s) else null
        }.sortedByDescending { it.second }.map { it.first }.take(4)
        return SpotlightResults(query, scored.map { it.first }, settings, Calculator.tryEvaluate(query))
    }

    /**
     * 0 for no match. Ranks: whole label, label prefix, word prefix, initials ("gm" for
     * Google Maps), substring, then package name.
     */
    internal fun matchScore(q: String, label: String, pkg: String): Double {
        if (label == q) return 120.0
        if (label.startsWith(q)) return 100.0 - label.length * 0.1
        val words = label.split(' ', '-', '.', '_').filter { it.isNotEmpty() }
        if (words.any { it.startsWith(q) }) return 80.0 - label.length * 0.1
        if (q.length >= 2 && !q.contains(' ')) {
            val initials = words.joinToString("") { it.take(1) }
            if (initials.startsWith(q)) return 70.0
        }
        if (q.length >= 2 && label.contains(q)) return 50.0
        if (q.length >= 3 && pkg.contains(q.replace(" ", ""))) return 20.0
        return 0.0
    }

    /** Lowercase, accents stripped, single spaces: "Café  Maps" -> "cafe maps". */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.getDefault())
            .replace(SPACES, " ")
            .trim()

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")

    fun canReadContacts(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Contacts whose name matches [query]. Background thread only. */
    fun contacts(context: Context, query: String, limit: Int = 4): List<ContactHit> {
        if (!canReadContacts(context) || query.isBlank()) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query.trim()))
        val cols = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        )
        val out = ArrayList<ContactHit>()
        try {
            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(2) ?: continue
                    out += ContactHit(c.getLong(0), c.getString(1) ?: "", name, c.getString(3)?.let(Uri::parse))
                }
            }
        } catch (e: Exception) {
            // Contacts provider missing or permission revoked mid-query: just show no contacts.
        }
        return out
    }

    /** "Search the web" intent: the default browser's search, else a plain web URL. */
    fun webSearch(context: Context, query: String): Intent {
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (search.resolveActivity(context.packageManager) != null) return search
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** The package of the system Settings app, used to borrow its icon for settings rows. */
    fun settingsPackage(context: Context): String? =
        Intent(Settings.ACTION_SETTINGS).resolveActivity(context.packageManager)?.packageName

    val SETTINGS = listOf(
        SettingHit("Wi-Fi", "Connections", Settings.ACTION_WIFI_SETTINGS, "wifi wlan wireless internet network"),
        SettingHit("Bluetooth", "Connections", Settings.ACTION_BLUETOOTH_SETTINGS, "pair headphones earbuds"),
        SettingHit("Mobile data", "Connections", Settings.ACTION_DATA_ROAMING_SETTINGS, "cellular sim roaming 5g lte"),
        SettingHit("Airplane mode", "Connections", Settings.ACTION_AIRPLANE_MODE_SETTINGS, "flight offline"),
        SettingHit("NFC", "Connections", Settings.ACTION_NFC_SETTINGS, "tap pay contactless"),
        SettingHit("Data usage", "Connections", Settings.ACTION_DATA_USAGE_SETTINGS, "cellular mobile limit"),
        SettingHit("VPN", "Connections", Settings.ACTION_VPN_SETTINGS, "private network"),
        SettingHit("Cast", "Connected devices", Settings.ACTION_CAST_SETTINGS, "screen mirror smart view"),
        SettingHit("Display", "Settings", Settings.ACTION_DISPLAY_SETTINGS, "brightness dark mode light screen refresh rate motion smoothness font"),
        SettingHit("Sounds and vibration", "Settings", Settings.ACTION_SOUND_SETTINGS, "volume ringtone silent mute haptics"),
        SettingHit("Notifications", "Settings", Settings.ACTION_NOTIFICATION_SETTINGS, "alerts badges do not disturb"),
        SettingHit("Battery", "Settings", Settings.ACTION_BATTERY_SAVER_SETTINGS, "power saving charge"),
        SettingHit("Storage", "Settings", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "space memory files"),
        SettingHit("Apps", "Settings", Settings.ACTION_APPLICATION_SETTINGS, "applications uninstall permissions"),
        SettingHit("Default apps", "Apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, "browser sms dialer"),
        SettingHit("Home app", "Default apps", Settings.ACTION_HOME_SETTINGS, "launcher home screen glassgate"),
        SettingHit("Location", "Settings", Settings.ACTION_LOCATION_SOURCE_SETTINGS, "gps maps"),
        SettingHit("Security and privacy", "Settings", Settings.ACTION_SECURITY_SETTINGS, "lock screen password pin fingerprint face biometrics"),
        SettingHit("Privacy", "Settings", Settings.ACTION_PRIVACY_SETTINGS, "permissions tracking"),
        SettingHit("Accounts", "Settings", Settings.ACTION_SYNC_SETTINGS, "sync google samsung backup"),
        SettingHit("Accessibility", "Settings", Settings.ACTION_ACCESSIBILITY_SETTINGS, "vision hearing talkback"),
        SettingHit("Date and time", "General management", Settings.ACTION_DATE_SETTINGS, "clock timezone"),
        SettingHit("Language", "General management", Settings.ACTION_LOCALE_SETTINGS, "keyboard region"),
        SettingHit("Keyboard", "General management", Settings.ACTION_INPUT_METHOD_SETTINGS, "typing input ime"),
        SettingHit("Developer options", "Settings", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "adb usb debugging animation scale"),
        SettingHit("About phone", "Settings", Settings.ACTION_DEVICE_INFO_SETTINGS, "software version model build"),
        SettingHit("Settings", "Settings", Settings.ACTION_SETTINGS, "preferences options"),
    )
}

/**
 * A small calculator, so typing "12*7" shows "= 84" like Spotlight does. Handles + - * /
 * (and the × ÷ symbols), %, ^, brackets and decimals. Returns null for anything else.
 */
internal object Calculator {

    fun tryEvaluate(input: String): String? {
        val s = input.replace('×', '*').replace('÷', '/').replace(',', '.').replace(" ", "")
        if (s.length < 3 || s.none { it.isDigit() } || s.none { it in "+-*/^%" }) return null
        if (s.any { !(it.isDigit() || it in "+-*/^%().") }) return null
        val v = try { Parser(s).parse() } catch (e: Exception) { return null }
        if (v.isNaN() || v.isInfinite()) return null
        return format(v)
    }

    fun format(v: Double): String {
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
        return java.math.BigDecimal(v).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Double {
            val v = expr()
            if (i != s.length) throw IllegalArgumentException("trailing input")
            return v
        }

        private fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                v = if (op == '+') v + term() else v - term()
            }
            return v
        }

        private fun term(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) {
                val op = s[i++]
                v = if (op == '*') v * power() else v / power()
            }
            return v
        }

        private fun power(): Double {
            val base = unary()
            if (i < s.length && s[i] == '^') { i++; return Math.pow(base, power()) }
            return base
        }

        private fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            var v = atom()
            while (i < s.length && s[i] == '%') { i++; v /= 100.0 }
            return v
        }

        private fun atom(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                if (i >= s.length || s[i] != ')') throw IllegalArgumentException("missing )")
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException("number expected")
            return s.substring(start, i).toDouble()
        }
    }
}
