package com.speedmerge.app

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

val Bg = Color(0xFF0E1116)
val CardBg = Color(0xFF171C24)
val Track = Color(0xFF232A35)
val Fg = Color(0xFFE8ECF1)
val Muted = Color(0xFF9AA6B5)
val Teal = Color(0xFF2DD4BF)
val Amber = Color(0xFFF5B942)
val Blue = Color(0xFF8AB4FF)

enum class Range(val label: String, val days: Int) {
    TODAY("Today", 1), WEEK("Week", 7), MONTH("Month", 30)
}

data class AppUse(val name: String, val bytes: Long)
data class UsageData(val mobile: Long, val wifi: Long, val apps: List<AppUse>, val daily: List<Pair<String, Long>>)
data class NetState(val wifi: Boolean, val mobile: Boolean)

fun hasUsageAccess(ctx: Context): Boolean {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode: Int
    if (Build.VERSION.SDK_INT >= 29) {
        mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    } else {
        @Suppress("DEPRECATION")
        mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

fun startOfDay(daysAgo: Int): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    c.add(Calendar.DAY_OF_YEAR, -daysAgo)
    return c.timeInMillis
}

fun perUid(nsm: NetworkStatsManager, type: Int, start: Long, end: Long): Map<Int, Long> {
    val map = HashMap<Int, Long>()
    try {
        val stats = nsm.querySummary(type, null, start, end)
        val b = NetworkStats.Bucket()
        while (stats.hasNextBucket()) {
            stats.getNextBucket(b)
            map[b.uid] = (map[b.uid] ?: 0L) + b.rxBytes + b.txBytes
        }
        stats.close()
    } catch (ex: Exception) {
    }
    return map
}

fun deviceTotal(nsm: NetworkStatsManager, type: Int, start: Long, end: Long): Long {
    return try {
        val b = nsm.querySummaryForDevice(type, null, start, end)
        b.rxBytes + b.txBytes
    } catch (ex: Exception) {
        0L
    }
}

fun appLabel(ctx: Context, uid: Int): String {
    val pm = ctx.packageManager
    val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return "System ($uid)"
    return try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (ex: Exception) {
        pkg
    }
}

fun loadUsage(ctx: Context, range: Range): UsageData {
    val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
    val start = startOfDay(range.days - 1)
    val end = System.currentTimeMillis()
    val mob = perUid(nsm, ConnectivityManager.TYPE_MOBILE, start, end)
    val wif = perUid(nsm, ConnectivityManager.TYPE_WIFI, start, end)
    val merged = HashMap<Int, Long>()
    for ((k, v) in mob) merged[k] = (merged[k] ?: 0L) + v
    for ((k, v) in wif) merged[k] = (merged[k] ?: 0L) + v
    val apps = merged.entries
        .filter { it.key >= 0 && it.value > 0L }
        .sortedByDescending { it.value }
        .take(5)
        .map { AppUse(appLabel(ctx, it.key), it.value) }
    val fmt = SimpleDateFormat("EEE", Locale.getDefault())
    val daily = (6 downTo 0).map { i ->
        val s = startOfDay(i)
        val e = s + 86_400_000L
        fmt.format(Date(s)).take(3) to (deviceTotal(nsm, ConnectivityManager.TYPE_MOBILE, s, e) +
            deviceTotal(nsm, ConnectivityManager.TYPE_WIFI, s, e))
    }
    return UsageData(mob.values.sum(), wif.values.sum(), apps, daily)
}

fun fmtBytes(b: Long): String {
    val mb = b / 1048576.0
    return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else String.format(Locale.US, "%.0f MB", mb)
}

@Suppress("DEPRECATION")
fun netState(ctx: Context): NetState {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    var w = false
    var m = false
    try {
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) w = true
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) m = true
        }
    } catch (ex: Exception) {
    }
    return NetState(w, m)
}

fun openInternetPanel(ctx: Context) {
    try {
        val i = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIRELESS_SETTINGS)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (ex: Exception) {
        try {
            val j = Intent(Settings.ACTION_WIRELESS_SETTINGS)
            j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(j)
        } catch (e2: Exception) {
        }
    }
}

// ---------- Boosted download (Wi-Fi + mobile data together) ----------

class DlState {
    val wifi = AtomicLong(0)
    val mob = AtomicLong(0)
    @Volatile var total = 0L
    @Volatile var status = "Starting..."
    @Volatile var finished = false
    @Volatile var cancelled = false
    @Volatile var error: String? = null
}

class Plan(val url: String, val total: Long, val file: File) {
    val chunk = 512 * 1024L
    val chunks = ((total + chunk - 1) / chunk).toInt()
    val next = AtomicInteger(0)
    val retry = ConcurrentLinkedQueue<Int>()
    val done = AtomicInteger(0)
}

suspend fun requestNet(
    cm: ConnectivityManager,
    transport: Int,
    cbs: MutableList<ConnectivityManager.NetworkCallback>
): Network? = suspendCancellableCoroutine<Network?> { cont ->
    val req = NetworkRequest.Builder()
        .addTransportType(transport)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (cont.isActive) cont.resume(network)
        }

        override fun onUnavailable() {
            if (cont.isActive) cont.resume(null)
        }
    }
    synchronized(cbs) { cbs.add(cb) }
    cm.requestNetwork(req, cb, 8000)
    cont.invokeOnCancellation {
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (ex: Exception) {
        }
    }
}

fun probe(net: Network, url: String): Pair<Long, Boolean> {
    val c = net.openConnection(URL(url)) as HttpURLConnection
    try {
        c.setRequestProperty("Range", "bytes=0-0")
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.instanceFollowRedirects = true
        c.connect()
        val code = c.responseCode
        val cr = c.getHeaderField("Content-Range")
        val len = c.contentLengthLong
        if (code == 206 && cr != null) {
            val t = cr.substringAfter("/").trim().toLongOrNull() ?: -1L
            return Pair(t, true)
        }
        return Pair(len, false)
    } finally {
        c.disconnect()
    }
}

fun fetchChunk(net: Network, url: String, start: Long, end: Long, file: File, counter: AtomicLong) {
    var got = 0L
    val c = net.openConnection(URL(url)) as HttpURLConnection
    try {
        c.setRequestProperty("Range", "bytes=$start-$end")
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.instanceFollowRedirects = true
        c.connect()
        if (c.responseCode != 206) throw IOException("No partial reply")
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(start)
            val buf = ByteArray(32768)
            c.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    got += n
                    counter.addAndGet(n.toLong())
                }
            }
        }
        if (got != end - start + 1) throw IOException("Short chunk")
    } catch (ex: Exception) {
        counter.addAndGet(-got)
        throw ex
    } finally {
        c.disconnect()
    }
}

fun worker(net: Network, plan: Plan, counter: AtomicLong, st: DlState) {
    while (!st.cancelled) {
        val i = plan.retry.poll() ?: plan.next.getAndIncrement()
        if (i >= plan.chunks) break
        val start = i * plan.chunk
        val end = minOf(plan.total - 1, start + plan.chunk - 1)
        var ok = false
        var tries = 0
        while (!ok && tries < 3 && !st.cancelled) {
            try {
                fetchChunk(net, plan.url, start, end, plan.file, counter)
                ok = true
            } catch (ex: Exception) {
                tries++
            }
        }
        if (ok) {
            plan.done.incrementAndGet()
        } else {
            plan.retry.add(i)
            return
        }
    }
}

fun fileNameFrom(url: String): String {
    val p = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
    return if (p.isBlank() || !p.contains('.')) "boost_download.bin" else p.take(80)
}

fun runBoost(ctx: Context, url: String, st: DlState, useWifi: Boolean, useMobile: Boolean) {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val cbs = mutableListOf<ConnectivityManager.NetworkCallback>()
    try {
        st.status = "Network dhoondh raha hai..."
        val found = runBlocking {
            val a = async { if (useWifi) requestNet(cm, NetworkCapabilities.TRANSPORT_WIFI, cbs) else null }
            val b = async { if (useMobile) requestNet(cm, NetworkCapabilities.TRANSPORT_CELLULAR, cbs) else null }
            Pair(a.await(), b.await())
        }
        val wifiNet = found.first
        val mobNet = found.second
        if (wifiNet == null && mobNet == null) {
            st.error = "Chune hue network nahi mile. Wi-Fi ya mobile data ON karein."
            return
        }
        val nets = mutableListOf<Pair<Network, AtomicLong>>()
        if (wifiNet != null) nets.add(Pair(wifiNet, st.wifi))
        if (mobNet != null) nets.add(Pair(mobNet, st.mob))
        if (nets.size == 1) {
            st.status = if (wifiNet != null) "Sirf Wi-Fi use ho raha hai" else "Sirf mobile data use ho raha hai"
        } else {
            st.status = "Dono network mil gaye. Probe kar raha hai..."
        }
        val probeResult = probe(nets[0].first, url)
        val total = probeResult.first
        if (!probeResult.second || total <= 0L) {
            st.error = "Is link ka server partial download allow nahi karta, ya link direct file ka nahi hai."
            return
        }
        st.total = total
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        val name = fileNameFrom(url)
        val temp = File(dir, "boost_" + System.currentTimeMillis() + ".part")
        RandomAccessFile(temp, "rw").use { it.setLength(total) }
        val plan = Plan(url, total, temp)
        st.status = "Download ho raha hai..."
        val threads = nets.map { p ->
            val t = Thread { worker(p.first, plan, p.second, st) }
            t.start()
            t
        }
        threads.forEach { it.join() }
        if (plan.done.get() < plan.chunks && !st.cancelled) {
            for (p in nets) {
                if (plan.done.get() >= plan.chunks) break
                worker(p.first, plan, p.second, st)
            }
        }
        if (st.cancelled) {
            temp.delete()
            st.status = "Cancel kar diya"
            return
        }
        if (plan.done.get() < plan.chunks) {
            temp.delete()
            st.error = "Download poora nahi hua (network toot gaya). Dobara try karein."
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues()
            values.put(MediaStore.Downloads.DISPLAY_NAME, name)
            values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Downloads mein save nahi ho saka")
            val out = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Save nahi ho saka")
            out.use { o -> temp.inputStream().use { it.copyTo(o) } }
            temp.delete()
            st.status = "Ho gaya! Downloads folder mein save hua: $name"
        } else {
            val finalFile = File(dir, name)
            temp.renameTo(finalFile)
            st.status = "Ho gaya! Saved: " + finalFile.absolutePath
        }
    } catch (ex: Exception) {
        st.error = "Error: " + (ex.message ?: ex.javaClass.simpleName)
    } finally {
        for (cb in cbs) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (ex: Exception) {
            }
        }
        st.finished = true
    }
}

// ---------- Activity ----------

class MainActivity : ComponentActivity() {
    private var granted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashFile = File(filesDir, "crash.txt")
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                crashFile.writeText(android.util.Log.getStackTraceString(e))
            } catch (ex: Exception) {
            }
            Process.killProcess(Process.myPid())
        }
        val previous = if (crashFile.exists()) crashFile.readText() else null
        if (previous != null) {
            crashFile.delete()
            setContent {
                Surface(Modifier.fillMaxSize(), color = Bg, contentColor = Fg) {
                    Column(Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text("Crash report - please screenshot this", fontWeight = FontWeight.Bold)
                        Text(previous.take(2500), fontSize = 11.sp)
                    }
                }
            }
            return
        }
        granted = hasUsageAccess(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Teal, background = Bg, surface = Bg)) {
                Root(granted) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        granted = hasUsageAccess(this)
    }
}

@Composable
fun Root(granted: Boolean, onGrant: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    Surface(modifier = Modifier.fillMaxSize(), color = Bg, contentColor = Fg) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> HomeScreen(granted, onGrant) { tab = 2 }
                    1 -> UsageScreen(granted, onGrant)
                    else -> BoostScreen()
                }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("Home", "Usage", "Boost").forEachIndexed { i, t ->
                    TextButton(onClick = { tab = i }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(t, color = if (tab == i) Teal else Muted)
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionCard(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Usage access chahiye", fontWeight = FontWeight.SemiBold)
        Text("Data usage dikhane ke liye settings mein SpeedMerge ke liye Usage Access ON karein.", color = Muted, fontSize = 13.sp)
        Button(onClick = onGrant) { Text("Open settings") }
    }
}

@Composable
fun InfoRow(color: Color, title: String, sub: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(color))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
fun ToggleRow(color: Color, title: String, sub: String, on: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(color))
        Column(Modifier.weight(1f)) {
