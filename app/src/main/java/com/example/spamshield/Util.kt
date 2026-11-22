package com.example.spamshield

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract.PhoneLookup
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

data class Msg(val id: Long, val addr: String, val body: String, val date: Long, val type: Int, val read: Boolean)
data class Thread(val addr: String, val msgs: List<Msg>, val allowed: Boolean, val name: String? = null) {
    val title get() = name ?: addr
    val last get() = msgs.first()
    val unread get() = msgs.count { it.type == 1 && !it.read }
}

object Util {
    private const val CH = "main"
    private const val PREF = "prefs"

    // ---------- لیست سفید ----------
    fun key(a: String): String {
        val d = a.filter { it.isDigit() }
        return if (d.length >= 7 && a.none { it.isLetter() }) d.takeLast(9) else a.trim().lowercase()
    }
    fun whitelist(c: Context): Set<String> =
        c.getSharedPreferences(PREF, 0).getStringSet("wl", emptySet()) ?: emptySet()
    fun addWl(c: Context, a: String) {
        val s = whitelist(c).toMutableSet().apply { add(key(a)) }
        c.getSharedPreferences(PREF, 0).edit().putStringSet("wl", s).apply()
    }
    fun removeWl(c: Context, k: String) {
        val s = whitelist(c).toMutableSet().apply { remove(k) }
        c.getSharedPreferences(PREF, 0).edit().putStringSet("wl", s).apply()
    }

    // ---------- تشخیص مجاز بودن ----------
    fun contactName(c: Context, a: String): String? {
        if (a.any { it.isLetter() }) return null
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            c.contentResolver.query(
                Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(a)),
                arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) { null }
    }
    fun allowed(c: Context, a: String, wl: Set<String> = whitelist(c)) = key(a) in wl || contactName(c, a) != null

    // ---------- پیام‌ها ----------
    fun load(c: Context, selection: String? = null, args: Array<String>? = null): List<Msg> {
        if (c.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val out = ArrayList<Msg>()
        try {
            c.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("_id", "address", "body", "date", "type", "read"),
                selection, args, "date DESC"
            )?.use {
                while (it.moveToNext() && out.size < 3000) {
                    val a = it.getString(1) ?: continue
                    out += Msg(it.getLong(0), a, it.getString(2) ?: "", it.getLong(3), it.getInt(4), it.getInt(5) == 1)
                }
            }
        } catch (e: Exception) { }
        return out
    }
    fun unseenSpamToday(c: Context): Int {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val wl = whitelist(c)
        return load(c, "type=1 AND read=0 AND date>=?", arrayOf(cal.timeInMillis.toString()))
            .count { !allowed(c, it.addr, wl) }
    }
    fun markRead(c: Context, addr: String) {
        try {
            c.contentResolver.update(Telephony.Sms.CONTENT_URI, ContentValues().apply { put("read", 1); put("seen", 1) },
                "address=? AND read=0", arrayOf(addr))
        } catch (e: Exception) { }
    }
    fun send(c: Context, addr: String, text: String) {
        val sm = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        sm.sendMultipartTextMessage(addr, null, sm.divideMessage(text), null, null)
        try {
            c.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, ContentValues().apply {
                put("address", addr); put("body", text); put("date", System.currentTimeMillis()); put("read", 1)
            })
        } catch (e: Exception) { }
    }

    // ---------- اعلان ----------
    fun channel(c: Context) {
        if (Build.VERSION.SDK_INT >= 26)
            c.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH, "Notifications", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun notify(c: Context, id: Int, title: String, text: String, tab: Int, addr: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        channel(c)
        val i = Intent(c, MainActivity::class.java).putExtra("tab", tab).putExtra("addr", addr)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CH).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title).setContentText(text).setContentIntent(pi).setAutoCancel(true).build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    // ---------- گزارش شبانه (ساعت ۲۱) ----------
    fun scheduleDaily(c: Context) {
        val now = Calendar.getInstance()
        val t = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 21); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        if (t.before(now)) t.add(Calendar.DAY_OF_YEAR, 1)
        val req = PeriodicWorkRequestBuilder<DailyWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(t.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("daily", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class DailyWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        val n = Util.unseenSpamToday(applicationContext)
        val text = if (n > 0) "You got $n message${if (n == 1) "" else "s"} today that you haven't seen. Tap to review."
        else "No unseen messages today 🎉"
        Util.notify(applicationContext, 777, "Daily summary", text, 1)
        return Result.success()
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(i) ?: return
        if (parts.isEmpty()) return
        val addr = parts[0].originatingAddress ?: return
        val body = parts.joinToString("") { it.messageBody ?: "" }
        try {
            c.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
                put("address", addr); put("body", body); put("date", System.currentTimeMillis())
                put("read", 0); put("seen", 0)
            })
        } catch (e: Exception) { }
        // فقط پیام مخاطب‌ها و لیست سفید اعلان می‌گیرن؛ بقیه بی‌صدا تو آرشیو می‌رن
        if (Util.allowed(c, addr)) Util.notify(c, addr.hashCode(), Util.contactName(c, addr) ?: addr, body, 0, addr)
    }
}

class MmsReceiver : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) {} }
class RespondService : Service() { override fun onBind(i: Intent?) = null }
class ComposeActivity : Activity() {
    override fun onCreate(s: android.os.Bundle?) {
        super.onCreate(s)
        startActivity(Intent(this, MainActivity::class.java)); finish()
    }
}
