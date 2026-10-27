package com.pixelgd.glassgate.icons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * An installed icon pack in the ADW / Nova format that most Play Store packs ship: an
 * appfilter.xml that maps "ComponentInfo{package/activity}" to a drawable in the pack.
 */
class IconPack private constructor(
    val packageName: String,
    private val res: Resources,
    private val map: Map<String, String>,
) {
    private val ids = HashMap<String, Int>()

    /** The pack's icon for [component], or null when the pack doesn't theme that app. */
    fun iconFor(component: ComponentName, density: Int): Drawable? {
        val name = map["${component.packageName}/${component.className}"]
            ?: map[component.packageName]
            ?: return null
        val id = synchronized(ids) {
            ids.getOrPut(name) { res.getIdentifier(name, "drawable", packageName) }
        }
        if (id == 0) return null
        return runCatching { res.getDrawableForDensity(id, density, null) }.getOrNull()
    }

    /** A pack the user can pick in Settings. */
    class Info(val packageName: String, val label: String)

    companion object {
        private const val TAG = "IconPack"
        private val ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")

        /** Installed icon packs, A to Z. */
        fun installed(context: Context): List<Info> {
            val pm = context.packageManager
            val seen = LinkedHashMap<String, Info>()
            for (action in ACTIONS) {
                for (ri in pm.queryIntentActivities(Intent(action), PackageManager.MATCH_ALL)) {
                    val pkg = ri.activityInfo.packageName
                    if (pkg !in seen) seen[pkg] = Info(pkg, ri.activityInfo.applicationInfo.loadLabel(pm).toString())
                }
            }
            return seen.values.sortedBy { it.label.lowercase() }
        }

        /** Parses [pkg]'s appfilter. Slow (reads the pack's XML): call off the main thread. */
        fun load(context: Context, pkg: String): IconPack? = try {
            val res = context.packageManager.getResourcesForApplication(pkg)
            val map = HashMap<String, String>()
            val xmlId = res.getIdentifier("appfilter", "xml", pkg)
            val parser: XmlPullParser = if (xmlId != 0) res.getXml(xmlId) else {
                XmlPullParserFactory.newInstance().newPullParser().apply {
                    setInput(res.assets.open("appfilter.xml"), "UTF-8")
                }
            }
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && parser.name == "item") {
                    val comp = parser.getAttributeValue(null, "component")
                    val drawable = parser.getAttributeValue(null, "drawable")
                    if (comp != null && drawable != null) {
                        // "ComponentInfo{com.app/com.app.Main}"
                        val inner = comp.substringAfter('{', "").substringBefore('}', "")
                        if (inner.isNotEmpty()) {
                            val (p, cls) = inner.split('/', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                            val full = if (cls.startsWith(".")) p + cls else cls
                            map["$p/$full"] = drawable
                            map.putIfAbsent(p, drawable)
                        }
                    }
                }
                ev = parser.next()
            }
            IconPack(pkg, res, map)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read icon pack $pkg", e)
            null
        }
    }
}
