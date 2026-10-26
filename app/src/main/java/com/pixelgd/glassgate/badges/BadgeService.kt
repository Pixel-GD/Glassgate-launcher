package com.pixelgd.glassgate.badges

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Counts unread notifications per app for [Badges]. Runs once the user allows notification
 * access in system settings. Ongoing notifications (music, navigation, foreground services)
 * and channels with badges turned off don't count, and a group counts its children rather
 * than its summary, which is roughly what iOS shows.
 */
class BadgeService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private val recount = Runnable { recountNow() }
    private val serials = HashMap<UserHandle, Long>()

    override fun onListenerConnected() = recountNow()

    override fun onListenerDisconnected() {
        handler.removeCallbacks(recount)
        Badges.replaceAll(emptyMap())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) = scheduleRecount()
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) = scheduleRecount()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) = scheduleRecount()

    override fun onDestroy() {
        handler.removeCallbacks(recount)
        Badges.replaceAll(emptyMap())
        super.onDestroy()
    }

    /** Apps often post a burst (summary + children); recount once per burst. */
    private fun scheduleRecount() {
        handler.removeCallbacks(recount)
        handler.postDelayed(recount, 80)
    }

    private fun recountNow() {
        val active = try {
            activeNotifications ?: emptyArray()
        } catch (e: Exception) {
            // Thrown when the listener isn't bound (access revoked mid-flight).
            Log.w(TAG, "Couldn't read notifications", e)
            return
        }
        val ranking = currentRanking
        val r = Ranking()
        val counted = ArrayList<StatusBarNotification>(active.size)
        for (sbn in active) {
            if (sbn.packageName == packageName || sbn.isOngoing) continue
            if (ranking != null && ranking.getRanking(sbn.key, r) && !r.canShowBadge()) continue
            counted += sbn
        }
        // Groups whose children are present: count those and skip the summary.
        val groupsWithChildren = counted
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 && it.isGroup }
            .mapTo(HashSet()) { it.groupKey }

        val out = HashMap<String, Int>()
        for (sbn in counted) {
            val n = sbn.notification
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 && sbn.groupKey in groupsWithChildren) continue
            val key = Badges.keyOf(sbn.packageName, serialOf(sbn.user))
            out[key] = (out[key] ?: 0) + if (n.number > 0) n.number else 1
        }
        Badges.replaceAll(out)
    }

    private fun serialOf(user: UserHandle): Long = serials.getOrPut(user) {
        if (user == Process.myUserHandle()) 0L
        else getSystemService(UserManager::class.java).getSerialNumberForUser(user)
    }

    companion object {
        private const val TAG = "BadgeService"
    }
}
