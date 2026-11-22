package com.example.spamshield

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

private val Indigo = Color(0xFF3949AB)
private val IndigoLight = Color(0xFF5C6BC0)
private val IndigoTint = Color(0xFFE8EAF6)
private val Bg = Color(0xFFF4F5FA)
private val Ink = Color(0xFF1B1B1F)
private val avatarColors = listOf(
    Color(0xFF5C6BC0), Color(0xFF26A69A), Color(0xFFEF6C00), Color(0xFF8E24AA),
    Color(0xFF00897B), Color(0xFFD81B60), Color(0xFF3949AB), Color(0xFF6D4C41)
)

class MainActivity : ComponentActivity() {
    private val tick = mutableIntStateOf(0)
    private val tab = mutableIntStateOf(0)
    private val openAddr = mutableStateOf<String?>(null)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick.intValue++ }
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick.intValue++ }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Util.channel(this); Util.scheduleDaily(this); handle(intent)
        val need = mutableListOf(
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(need.toTypedArray())
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Indigo, secondaryContainer = IndigoTint)) { App() }
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

    // ---------------------------------------------------------------- App
    @Composable
    fun App() {
        val t = tick.intValue
        val msgs = remember(t) { Util.load(this) }
        val wl = remember(t) { Util.whitelist(this) }
        val threads = remember(msgs, wl) {
            msgs.groupBy { it.addr }.map { (a, l) ->
                val n = Util.contactName(this, a)
                Thread(a, l, Util.key(a) in wl || n != null, n)
            }.sortedByDescending { it.last.date }
        }
        val addr = openAddr.value
        if (addr != null) {
            BackHandler { openAddr.value = null }
            ThreadScreen(threads.firstOrNull { it.addr == addr }, addr, { openAddr.value = null }) { tick.intValue++ }
            return
        }
        val inbox = threads.filter { it.allowed }
        val spam = threads.filter { !it.allowed }
        val spamUnread = spam.sumOf { it.unread }
        val labels = listOf("Inbox", "Spam", "Allowed")
        val icons = listOf(Icons.Default.Email, Icons.Default.Warning, Icons.Default.Star)

        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    labels.forEachIndexed { i, s ->
                        NavigationBarItem(
                            selected = tab.intValue == i, onClick = { tab.intValue = i },
                            icon = {
                                BadgedBox(badge = { if (i == 1 && spamUnread > 0) Badge { Text("$spamUnread") } }) {
                                    Icon(icons[i], contentDescription = s)
                                }
                            },
                            label = { Text(s) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = IndigoTint, selectedIconColor = Indigo, selectedTextColor = Indigo
                            )
                        )
                    }
                }
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                when (tab.intValue) {
                    0 -> Header("Inbox", "${inbox.size} conversation${if (inbox.size == 1) "" else "s"} from people you trust")
                    1 -> Header("Spam Archive", if (spamUnread > 0) "$spamUnread unread · silenced, no notifications" else "All caught up · silenced, no notifications")
                    else -> Header("Allowed Senders", "These senders always reach your inbox")
                }
                if (!isDefault()) DefaultBanner()
                when (tab.intValue) {
                    0 -> ThreadList(inbox, Icons.Default.Email, "No messages yet", "Messages from your contacts and allowed senders will show up here.")
                    1 -> ThreadList(spam, Icons.Default.Warning, "Nothing here", "Messages from unknown senders are quietly collected in this folder.")
                    else -> AllowedScreen(wl)
                }
            }
        }
    }

    // ---------------------------------------------------------------- Pieces
    @Composable
    fun Header(title: String, sub: String) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(Brush.verticalGradient(listOf(Indigo, IndigoLight)))
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(sub, color = Color(0xCCFFFFFF), fontSize = 14.sp)
        }
    }

    @Composable
    fun DefaultBanner() {
        Card(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = Color(0xFFEF6C00))
                    Spacer(Modifier.width(10.dp))
                    Text("Quiet Inbox must be your default SMS app to filter incoming messages.", color = Ink, fontSize = 14.sp)
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { requestDefault() }, shape = RoundedCornerShape(12.dp)) { Text("Set as default") }
            }
        }
    }

    @Composable
    fun EmptyState(icon: ImageVector, title: String, text: String) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(IndigoTint), Alignment.Center) {
                Icon(icon, null, tint = Indigo, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text(text, color = Color.Gray, textAlign = TextAlign.Center)
        }
    }

    @Composable
    fun Avatar(title: String, size: Int = 48) {
        val c = avatarColors[Math.abs(title.hashCode()) % avatarColors.size]
        Box(Modifier.size(size.dp).clip(CircleShape).background(c), Alignment.Center) {
            Text(title.trim().take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.4).sp)
        }
    }

    @Composable
    fun ThreadList(list: List<Thread>, icon: ImageVector, emptyTitle: String, emptyText: String) {
        if (list.isEmpty()) { EmptyState(icon, emptyTitle, emptyText); return }
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.addr }) { th ->
                Card(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { openAddr.value = th.addr },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(th.title)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                th.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, color = Ink,
                                fontWeight = if (th.unread > 0) FontWeight.Bold else FontWeight.Medium
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(th.last.body, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color(0xFF6B6B76), fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fmt(th.last.date), color = Color.Gray, fontSize = 12.sp)
                            if (th.unread > 0) {
                                Spacer(Modifier.height(6.dp))
                                Box(Modifier.clip(CircleShape).background(Indigo).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                    Text("${th.unread}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun AllowedScreen(wl: Set<String>) {
        var text by remember { mutableStateOf("") }
        Column(Modifier.padding(12.dp)) {
            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Add a sender", fontWeight = FontWeight.SemiBold, color = Ink)
                    Text("Phone number or sender name, e.g. your bank.", color = Color.Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            text, { text = it }, Modifier.weight(1f), singleLine = true,
                            shape = RoundedCornerShape(12.dp), placeholder = { Text("3000xxx or MyBank") }
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { if (text.isNotBlank()) { Util.addWl(this@MainActivity, text); text = ""; tick.intValue++ } },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Add") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (wl.isEmpty()) {
                Text("No allowed senders yet. Contacts are always allowed automatically.", color = Color.Gray, modifier = Modifier.padding(8.dp))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(wl.toList().sorted()) { k ->
                        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, null, tint = Color(0xFFFFB300))
                                Spacer(Modifier.width(12.dp))
                                Text(k, Modifier.weight(1f), color = Ink)
                                IconButton(onClick = { Util.removeWl(this@MainActivity, k); tick.intValue++ }) {
                                    Icon(Icons.Default.Delete, "Remove", tint = Color(0xFFB0B0BA))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Conversation
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ThreadScreen(th: Thread?, addr: String, onBack: () -> Unit, onChange: () -> Unit) {
        LaunchedEffect(addr) { Util.markRead(this@MainActivity, addr); onChange() }
        val list = th?.msgs?.sortedBy { it.date } ?: emptyList()
        val listState = rememberLazyListState()
        LaunchedEffect(list.size) { if (list.isNotEmpty()) listState.scrollToItem(list.lastIndex) }
        var text by remember { mutableStateOf("") }

        Scaffold(
            containerColor = Bg,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(th?.title ?: addr, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (th?.name != null) Text(addr, fontSize = 12.sp, color = Color(0xCCFFFFFF))
                        }
                    },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        if (th != null && !th.allowed) {
                            TextButton(onClick = { Util.addWl(this@MainActivity, addr); onChange() }) {
                                Icon(Icons.Default.Star, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Allow sender")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Indigo, titleContentColor = Color.White,
                        navigationIconContentColor = Color.White, actionIconContentColor = Color.White
                    )
                )
            },
            bottomBar = {
                Surface(color = Color.White, shadowElevation = 8.dp) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message") },
                            maxLines = 4, shape = RoundedCornerShape(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(Indigo).clickable {
                                if (text.isBlank()) return@clickable
                                if (!isDefault()) {
                                    Toast.makeText(this@MainActivity, "Set Quiet Inbox as the default SMS app to send messages", Toast.LENGTH_LONG).show()
                                } else {
                                    Util.send(this@MainActivity, addr, text); text = ""; onChange()
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Send, "Send", tint = Color.White) }
                    }
                }
            }
        ) { pad ->
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(), state = listState,
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(list, key = { it.id }) { m ->
                    val mine = m.type != 1
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Column(
                            Modifier.widthIn(max = 290.dp)
                                .clip(RoundedCornerShape(
                                    topStart = if (mine) 18.dp else 4.dp, topEnd = if (mine) 4.dp else 18.dp,
                                    bottomEnd = 18.dp, bottomStart = 18.dp))
                                .background(if (mine) Indigo else Color.White)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(m.body, color = if (mine) Color.White else Ink)
                            Text(
                                fmt(m.date), fontSize = 11.sp, color = if (mine) Color(0xB3FFFFFF) else Color.Gray,
                                modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun fmt(d: Long): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US)
        val pattern = if (day.format(Date(d)) == day.format(Date())) "HH:mm" else "MMM d"
        return SimpleDateFormat(pattern, Locale.US).format(Date(d))
    }
}
