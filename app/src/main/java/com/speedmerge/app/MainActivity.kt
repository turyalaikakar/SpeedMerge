package com.speedmerge.app

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Process
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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

fun networkName(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Offline"
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi connected"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        else -> "Connected"
    }
}

class MainActivity : ComponentActivity() {
    private var granted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashFile = java.io.File(filesDir, "crash.txt")
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
                if (tab == 0) HomeScreen(granted, onGrant) else UsageScreen(granted, onGrant)
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("Home", "Usage").forEachIndexed { i, t ->
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
fun Ring(m: Long, w: Long) {
    Canvas(Modifier.fillMaxSize()) {
        val sw = 28.dp.toPx()
        val tl = Offset(sw / 2, sw / 2)
        val sz = Size(size.width - sw, size.height - sw)
        val total = m + w
        if (total <= 0L) {
            drawArc(color = Track, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = tl, size = sz, style = Stroke(sw))
        } else {
            val a = 360f * m / total
            drawArc(color = Teal, startAngle = -90f, sweepAngle = a, useCenter = false, topLeft = tl, size = sz, style = Stroke(sw))
            drawArc(color = Blue, startAngle = -90f + a, sweepAngle = 360f - a, useCenter = false, topLeft = tl, size = sz, style = Stroke(sw))
        }
    }
}

@Composable
fun HomeScreen(granted: Boolean, onGrant: () -> Unit) {
    val ctx = LocalContext.current
    var speed by remember { mutableStateOf(0.0) }
    var data by remember { mutableStateOf<UsageData?>(null) }
    LaunchedEffect(Unit) {
        var last = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
        while (true) {
            delay(1000)
            val now = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
            speed = ((now - last) * 8 / 1_000_000.0).coerceAtLeast(0.0)
            last = now
        }
    }
    LaunchedEffect(granted) {
        if (granted) data = withContext(Dispatchers.IO) { runCatching { loadUsage(ctx, Range.TODAY) }.getOrNull() }
    }
    val mobile = data?.mobile ?: 0L
    val wifi = data?.wifi ?: 0L
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("SpeedMerge", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(networkName(ctx), color = Teal, fontSize = 13.sp)
        }
        Box(Modifier.align(Alignment.CenterHorizontally).size(230.dp), contentAlignment = Alignment.Center) {
            Ring(mobile, wifi)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(String.format(Locale.US, "%.1f", speed), fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text("Mbps live", color = Muted, fontSize = 14.sp)
            }
        }
        if (!granted) PermissionCard(onGrant)
        InfoRow(Teal, "Mobile data (SIM 1 + SIM 2)", "Today: " + fmtBytes(mobile))
        InfoRow(Blue, "Wi-Fi", "Today: " + fmtBytes(wifi))
        InfoRow(Amber, "Speed merge", "Coming soon: dono SIM ki speed jorne ke liye server chahiye.")
    }
}

@Composable
fun UsageScreen(granted: Boolean, onGrant: () -> Unit) {
    val ctx = LocalContext.current
    var range by remember { mutableStateOf(Range.TODAY) }
    var data by remember { mutableStateOf<UsageData?>(null) }
    LaunchedEffect(range, granted) {
        if (granted) data = withContext(Dispatchers.IO) { runCatching { loadUsage(ctx, range) }.getOrNull() }
    }
    val d = data
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Data usage", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Range.values().forEach { r ->
                val sel = r == range
                Button(
                    onClick = { range = r },
                    modifier = Modifier.weight(1f).height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sel) Teal else CardBg,
                        contentColor = if (sel) Bg else Muted
                    )
                ) { Text(r.label) }
            }
        }
        if (!granted) PermissionCard(onGrant)
        if (d != null) {
            val total = d.mobile + d.wifi
            Column {
                Text(fmtBytes(total), fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text("used (" + range.label.lowercase() + ")", color = Muted, fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
                Box(Modifier.weight(maxOf(d.mobile.toFloat(), 1f)).fillMaxHeight().background(Teal))
                Box(Modifier.weight(maxOf(d.wifi.toFloat(), 1f)).fillMaxHeight().background(Blue))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Mobile " + fmtBytes(d.mobile), color = Teal, fontSize = 13.sp)
                Text("Wi-Fi " + fmtBytes(d.wifi), color = Blue, fontSize = 13.sp)
            }
            val maxDay = maxOf(d.daily.maxOfOrNull { it.second } ?: 1L, 1L)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(16.dp)) {
                Text("Last 7 days", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    d.daily.forEach { p ->
                        Box(
                            Modifier.weight(1f)
                                .fillMaxHeight((p.second.toFloat() / maxDay).coerceIn(0.03f, 1f))
                                .clip(RoundedCornerShape(4.dp)).background(Teal)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    d.daily.forEach { p -> Text(p.first, modifier = Modifier.weight(1f), color = Muted, fontSize = 11.sp) }
                }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Top apps", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                val top = maxOf(d.apps.firstOrNull()?.bytes ?: 1L, 1L)
                d.apps.forEach { a ->
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(a.name, fontSize = 14.sp)
                            Text(fmtBytes(a.bytes), fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Track)) {
                            Box(Modifier.fillMaxWidth(a.bytes.toFloat() / top).height(6.dp).background(Teal))
                        }
                    }
                }
            }
        }
    }
}
