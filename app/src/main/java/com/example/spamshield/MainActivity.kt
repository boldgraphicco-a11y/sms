package com.example.spamshield

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val tick = mutableIntStateOf(0)
    private val tab = mutableIntStateOf(0)
    private val openAddr = mutableStateOf<String?>(null)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick.intValue++ }
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick.intValue++ }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Util.channel(this); Util.scheduleDaily(this); handle(intent)
        val need = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(need.toTypedArray())
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF3949AB), secondaryContainer = Color(0xFFE3E7FF))) { App() }
            }
        }
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }
    private fun handle(i: Intent) {
        tab.intValue = i.getIntExtra("tab", tab.intValue)
        i.getStringExtra("addr")?.let { openAddr.value = it }
    }
    override fun onResume() { super.onResume(); tick.intValue++ }

    private fun isDefault() = Telephony.Sms.getDefaultSmsPackage(this) == packageName
    private fun requestDefault() {
        val i = if (Build.VERSION.SDK_INT >= 29)
            getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
        else Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        roleLauncher.launch(i)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun App() {
        val t = tick.intValue
        val msgs = remember(t) { Util.load(this) }
        val wl = remember(t) { Util.whitelist(this) }
        val threads = remember(msgs, wl) {
            msgs.groupBy { it.addr }.map { (a, l) -> Thread(a, l, Util.allowed(this, a, wl)) }.sortedByDescending { it.last.date }
        }
        val addr = openAddr.value
        if (addr != null) {
            BackHandler { openAddr.value = null }
            ThreadScreen(threads.firstOrNull { it.addr == addr }, addr, { openAddr.value = null }) { tick.intValue++ }
            return
        }
        val titles = listOf("صندوق ورودی", "آرشیو اسپم", "لیست سفید")
        Scaffold(
            topBar = { CenterAlignedTopAppBar(title = { Text(titles[tab.intValue], fontWeight = FontWeight.Bold) }) },
            bottomBar = {
                NavigationBar {
                    titles.forEachIndexed { i, s ->
                        val badge = if (i == 1) threads.filter { !it.allowed }.sumOf { it.unread } else 0
                        NavigationBarItem(selected = tab.intValue == i, onClick = { tab.intValue = i },
                            icon = { Text(if (badge > 0) "$badge" else listOf("✉", "🛡", "★")[i]) }, label = { Text(s, maxLines = 1) })
                    }
                }
            }
        ) { pad ->
            Column(Modifier.padding(pad)) {
                if (!isDefault()) DefaultBanner()
                when (tab.intValue) {
                    0 -> ThreadList(threads.filter { it.allowed }, "هنوز پیامی از مخاطب‌ها نداری.")
                    1 -> ThreadList(threads.filter { !it.allowed }, "آرشیو اسپم خالیه 🎉")
                    else -> WhitelistScreen(wl)
                }
            }
        }
    }

    @Composable
    fun DefaultBanner() {
        Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
            Column(Modifier.padding(16.dp)) {
                Text("برای فیلتر کردن پیام‌ها، این برنامه باید «برنامه پیش‌فرض پیامک» باشه.", fontSize = androidx.compose.ui.unit.TextUnit.Unspecified)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { requestDefault() }) { Text("انتخاب به‌عنوان پیش‌فرض") }
            }
        }
    }

    @Composable
    fun ThreadList(list: List<Thread>, empty: String) {
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { Text(empty, color = Color.Gray) }
            return
        }
        LazyColumn {
            items(list, key = { it.addr }) { th ->
                Row(Modifier.fillMaxWidth().clickable { openAddr.value = th.addr }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), Alignment.Center) {
                        Text(th.addr.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(th.addr, fontWeight = if (th.unread > 0) FontWeight.Bold else FontWeight.Normal)
                        Text(th.last.body, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.Gray)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmt(th.last.date), color = Color.Gray)
                        if (th.unread > 0) Badge { Text("${th.unread}") }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }

    @Composable
    fun WhitelistScreen(wl: Set<String>) {
        var text by remember { mutableStateOf("") }
        Column(Modifier.padding(16.dp)) {
            Text("شماره یا نام فرستنده (مثلاً بانک) که همیشه باید اعلان بده:", color = Color.Gray)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("مثلاً 3000xxx یا Bank") })
                Spacer(Modifier.width(8.dp))
                Button(onClick = { if (text.isNotBlank()) { Util.addWl(this@MainActivity, text); text = ""; tick.intValue++ } }) { Text("افزودن") }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn {
                items(wl.toList().sorted()) { k ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(k, Modifier.weight(1f))
                        IconButton(onClick = { Util.removeWl(this@MainActivity, k); tick.intValue++ }) { Icon(Icons.Default.Delete, "حذف") }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ThreadScreen(th: Thread?, addr: String, onBack: () -> Unit, onChange: () -> Unit) {
        LaunchedEffect(addr) { Util.markRead(this@MainActivity, addr); onChange() }
        val list = th?.msgs?.sortedBy { it.date } ?: emptyList()
        var text by remember { mutableStateOf("") }
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(addr) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") } },
                    actions = {
                        if (th != null && !th.allowed)
                            TextButton(onClick = { Util.addWl(this@MainActivity, addr); onChange() }) { Text("★ لیست سفید") }
                    })
            },
            bottomBar = {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("پیام...") }, maxLines = 4,
                        shape = RoundedCornerShape(24.dp))
                    IconButton(onClick = {
                        if (text.isNotBlank() && isDefault()) { Util.send(this@MainActivity, addr, text); text = ""; onChange() }
                    }) { Icon(Icons.Default.Send, "ارسال", tint = MaterialTheme.colorScheme.primary) }
                }
            }
        ) { pad ->
            LazyColumn(Modifier.padding(pad).padding(horizontal = 12.dp), reverseLayout = false) {
                items(list, key = { it.id }) { m ->
                    val mine = m.type != 1
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (mine) Arrangement.Start else Arrangement.End) {
                        Column(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (mine) MaterialTheme.colorScheme.primary else Color(0xFFEDEDF2)).padding(10.dp)) {
                            Text(m.body, color = if (mine) Color.White else Color.Black)
                            Text(fmt(m.date), color = if (mine) Color(0xCCFFFFFF) else Color.Gray)
                        }
                    }
                }
            }
        }
    }

    private fun fmt(d: Long): String {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US)
        return SimpleDateFormat(if (today.format(Date(d)) == today.format(Date())) "HH:mm" else "MM/dd", Locale.US).format(Date(d))
    }
}
