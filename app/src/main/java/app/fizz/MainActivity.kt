@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package app.fizz

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        UI.theme = Store.theme
        setContent {
            val dark = when (UI.theme) { "dark", "amoled" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            val cs = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = if (UI.theme == "amoled") cs.copy(background = Color.Black, surface = Color.Black) else cs) { Surface(Modifier.fillMaxSize()) { App() } }
        }
    }
}

object UI { var theme by mutableStateOf("system") }

sealed interface Scr
data object HomeS : Scr
data class DetailS(val ext: Ext, val item: JSONObject) : Scr
data class ReaderS(val ext: Ext, val item: JSONObject, val chs: JSONArray, val i: Int) : Scr

@Composable
fun App() {
    val stack = remember { mutableStateListOf<Scr>(HomeS) }
    val back = { stack.removeAt(stack.lastIndex); Unit }
    BackHandler(stack.size > 1) { back() }
    when (val s = stack.last()) {
        HomeS -> Home { e, x -> stack.add(DetailS(e, x)) }
        is DetailS -> Detail(s.ext, s.item, { c, i -> stack.add(ReaderS(s.ext, s.item, c, i)) }, back)
        is ReaderS -> Reader(s.ext, s.item, s.chs, s.i, back)
    }
}

@Composable
fun <T> Load(key: Any?, f: suspend () -> T, content: @Composable (T) -> Unit) {
    var st by remember(key) { mutableStateOf<Result<T>?>(null) }
    LaunchedEffect(key) { st = runCatching { f() } }
    val r = st
    when {
        r == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        r.isFailure -> Text(r.exceptionOrNull()?.message ?: "Error", Modifier.padding(16.dp))
        else -> content(r.getOrThrow())
    }
}

@Composable
fun Home(open: (Ext, JSONObject) -> Unit) {
    var tab by remember { mutableStateOf(3) }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("Library" to Icons.Filled.Favorite, "Updates" to Icons.Filled.NewReleases, "History" to Icons.Filled.History, "Browse" to Icons.Filled.Search, "More" to Icons.Filled.MoreHoriz)
                .forEachIndexed { i, (n, ic) -> NavigationBarItem(tab == i, { tab = i }, { Icon(ic, n) }, label = { Text(n) }) }
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) { 0 -> LibraryTab(open); 1 -> Updates(open); 2 -> Entries("hist", open); 3 -> Browse(open); else -> More() }
        }
    }
}

@Composable
fun LibraryTab(open: (Ext, JSONObject) -> Unit) {
    var cat by remember { mutableStateOf<String?>(null) }
    var cats by remember { mutableStateOf(Store.cats()) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("New category") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        confirmButton = { TextButton({ Store.addCat(name); cats = Store.cats(); name = ""; adding = false }) { Text("Add") } })
    Column {
        LazyRow(Modifier.padding(horizontal = 8.dp)) {
            item { FilterChip(cat == null, { cat = null }, { Text("All") }, Modifier.padding(end = 6.dp)) }
            items(cats) { c -> FilterChip(cat == c, { cat = c }, { Text(c) }, Modifier.padding(end = 6.dp)) }
            item { AssistChip({ adding = true }, { Text("+") }) }
        }
        Entries("fav", open, cat)
    }
}

@Composable
fun Updates(open: (Ext, JSONObject) -> Unit) {
    val exts = remember { Store.exts() }
    val favs = remember { Store.list("fav") }
    var res by remember { mutableStateOf<List<Triple<Ext, JSONObject, Int>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column {
        Button({
            scope.launch {
                busy = true
                val out = mutableListOf<Triple<Ext, JSONObject, Int>>()
                for (a in favs) {
                    val e = exts.firstOrNull { it.name == a.getString("ext") } ?: continue
                    val item = a.getJSONObject("item")
                    try {
                        val n = (e.call("detail", item.getString("url")) as JSONObject).optJSONArray("chapters")?.length() ?: 0
                        val s = Store.seen(e, item)
                        if (s in 0 until n) out.add(Triple(e, item, n - s))
                    } catch (x: Exception) { }
                }
                res = out; busy = false
            }
        }, Modifier.padding(12.dp), enabled = !busy && favs.isNotEmpty()) { Text(if (busy) "Checking…" else "Check library for new chapters") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val r = res
        if (r != null && r.isEmpty()) Text("Everything is up to date.", Modifier.padding(12.dp))
        LazyColumn {
            items(r ?: emptyList()) { (e, item, n) ->
                ListItem(headlineContent = { Text(item.optString("title")) }, supportingContent = { Text("+$n new  ·  ${e.name}") },
                    leadingContent = { AsyncImage(item.optString("cover"), null, Modifier.size(48.dp, 68.dp), contentScale = ContentScale.Crop) },
                    modifier = Modifier.clickable { open(e, item) })
            }
        }
    }
}

@Composable
fun More() {
    var sub by remember { mutableStateOf(0) }
    var theme by remember { mutableStateOf(UI.theme) }
    Column {
        TabRow(sub) { Tab(sub == 0, { sub = 0 }, text = { Text("Settings") }); Tab(sub == 1, { sub = 1 }, text = { Text("Extensions") }) }
        if (sub == 1) Extensions() else Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            listOf("system" to "System", "light" to "Light", "dark" to "Dark", "amoled" to "AMOLED black").forEach { (k, n) ->
                val pick = { theme = k; UI.theme = k; Store.theme = k }
                Row(Modifier.fillMaxWidth().clickable { pick() }, verticalAlignment = Alignment.CenterVertically) { RadioButton(theme == k, { pick() }); Text(n) }
            }
            Spacer(Modifier.height(24.dp))
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("Fizz 0.2.0\nThe developers of this app have no affiliation with any content provider, and the app hosts no content.")
        }
    }
}

@Composable
fun Entries(kind: String, open: (Ext, JSONObject) -> Unit, cat: String? = null) {
    val exts = remember { Store.exts() }
    val l = remember(cat) { Store.list(kind).filter { cat == null || it.optString("cat") == cat } }
    if (l.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text(if (kind == "hist") "Nothing read or watched yet." else "No favorites yet. Tap the heart on any title.") }
        return
    }
    LazyColumn {
        items(l) { a ->
            val e = exts.firstOrNull { it.name == a.getString("ext") }
            val item = a.getJSONObject("item")
            ListItem(
                headlineContent = { Text(item.optString("title")) },
                supportingContent = { Text(if (kind == "hist") "Continue: " + a.getJSONObject("ch").optString("name") else a.getString("ext")) },
                leadingContent = { AsyncImage(item.optString("cover"), null, Modifier.size(48.dp, 68.dp), contentScale = ContentScale.Crop) },
                modifier = Modifier.clickable(enabled = e != null) { open(e!!, item) },
            )
        }
    }
}

@Composable
fun Browse(open: (Ext, JSONObject) -> Unit) {
    val exts = remember { Store.exts() }
    if (exts.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No extensions yet. Add one in the Extensions tab.") }; return }
    var sel by remember { mutableStateOf(0) }
    var q by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun search() = scope.launch {
        busy = true; err = null
        try { val r = exts[sel].call("search", q, 1) as JSONArray; items = List(r.length()) { r.getJSONObject(it) } }
        catch (e: Exception) { err = e.message }
        busy = false
    }
    LaunchedEffect(sel) { search() }
    Column {
        LazyRow(Modifier.padding(horizontal = 8.dp)) { itemsIndexed(exts) { i, e -> FilterChip(sel == i, { sel = i; items = emptyList() }, { Text(e.name) }, Modifier.padding(end = 6.dp)) } }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(8.dp), placeholder = { Text("Search…") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        err?.let { Text(it, Modifier.padding(8.dp)) }
        LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            gridItems(items) { x ->
                Column(Modifier.clickable { open(exts[sel], x) }) {
                    AsyncImage(x.optString("cover"), null, Modifier.fillMaxWidth().aspectRatio(.7f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Text(x.optString("title"), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun Detail(ext: Ext, item: JSONObject, onRead: (JSONArray, Int) -> Unit, back: () -> Unit) {
    var fav by remember { mutableStateOf(Store.isFav(ext, item)) }
    val read = Store.read()
    var showCat by remember { mutableStateOf(false) }
    if (showCat) AlertDialog(onDismissRequest = { showCat = false }, confirmButton = {}, title = { Text("Category") }, text = {
        Column {
            if (Store.cats().isEmpty()) Text("Create categories from the Library tab (+).")
            (listOf<String?>(null) + Store.cats()).forEach { c ->
                ListItem(headlineContent = { Text(c ?: "None") }, modifier = Modifier.clickable { Store.setCat(ext, item, c); fav = Store.isFav(ext, item); showCat = false })
            }
        }
    })
    Scaffold(topBar = {
        TopAppBar(title = { Text(item.optString("title"), maxLines = 1) },
            navigationIcon = { IconButton(back) { Icon(Icons.Filled.ArrowBack, "Back") } },
            actions = { IconButton({ showCat = true }) { Icon(Icons.Filled.Folder, "Category") }; IconButton({ fav = Store.toggleFav(ext, item) }) { Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favorite") } })
    }) { pad ->
        Box(Modifier.padding(pad)) {
            Load(item.getString("url"), { ext.call("detail", item.getString("url")) as JSONObject }) { d ->
                val ch = d.optJSONArray("chapters") ?: JSONArray()
                LaunchedEffect(ch.length()) { Store.setSeen(ext, item, ch.length()) }
                LazyColumn {
                    item { if (d.optString("description").isNotEmpty()) Text(d.optString("description"), Modifier.padding(12.dp)) }
                    items(ch.length()) { i ->
                        val c = ch.getJSONObject(i)
                        val done = (ext.name + "|" + c.getString("url")) in read
                        ListItem(headlineContent = { Text(c.optString("name"), color = if (done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface) },
                            trailingContent = { if (done) Icon(Icons.Filled.Check, null) },
                            modifier = Modifier.clickable { onRead(ch, i) })
                    }
                }
            }
        }
    }
}

@Composable
fun Reader(ext: Ext, item: JSONObject, chs: JSONArray, start: Int, back: () -> Unit) {
    var i by remember { mutableStateOf(start) }
    var mode by remember { mutableStateOf(Store.mode) }
    var menu by remember { mutableStateOf(false) }
    val ch = chs.getJSONObject(i)
    LaunchedEffect(i) { Store.markRead(ext.name + "|" + ch.getString("url")); Store.addHist(ext, item, ch) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(ch.optString("name"), maxLines = 1) },
            navigationIcon = { IconButton(back) { Icon(Icons.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton({ i-- }, enabled = i > 0) { Icon(Icons.Filled.SkipPrevious, "Previous") }
                IconButton({ i++ }, enabled = i < chs.length() - 1) { Icon(Icons.Filled.SkipNext, "Next") }
                IconButton({ menu = true }) { Icon(Icons.Filled.ChromeReaderMode, "Mode") }
                DropdownMenu(menu, { menu = false }) {
                    listOf("webtoon" to "Webtoon (scroll)", "ltr" to "Paged left-to-right", "rtl" to "Paged right-to-left (manga)").forEach { (k, n) ->
                        DropdownMenuItem({ Text(n) }, { mode = k; Store.mode = k; menu = false })
                    }
                }
            })
    }) { pad ->
        Box(Modifier.padding(pad)) {
            Load(i, { ext.call("content", ch.getString("url")) as JSONObject }) { d -> Content(d, mode, i < chs.length() - 1) { i++ } }
        }
    }
}

@Composable
fun Content(d: JSONObject, mode: String, hasNext: Boolean, next: () -> Unit) {
    val pgs = d.optJSONArray("items") ?: JSONArray()
    when (d.optString("type")) {
        "video" -> VideoView(pgs.getJSONObject(0).getString("url"))
        "images" -> {
            val end: @Composable () -> Unit = { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { if (hasNext) Button(next) { Text("Next chapter") } else Text("No more chapters") } }
            if (mode == "webtoon") {
                LazyColumn {
                    items(pgs.length()) { k -> AsyncImage(pgs.getString(k), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                    item { end() }
                }
            } else {
                val ps = rememberPagerState { pgs.length() + 1 }
                HorizontalPager(ps, reverseLayout = mode == "rtl") { p ->
                    if (p < pgs.length()) AsyncImage(pgs.getString(p), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Box(Modifier.fillMaxSize(), Alignment.Center) { end() }
                }
            }
        }
        else -> Text(d.optString("text"), Modifier.verticalScroll(rememberScrollState()).padding(16.dp), fontSize = 18.sp, lineHeight = 30.sp)
    }
}

@Composable
fun VideoView(url: String) {
    val c = LocalContext.current
    val player = remember { ExoPlayer.Builder(c).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    AndroidView({ PlayerView(it).apply { this.player = player } }, Modifier.fillMaxSize())
}

@Composable
fun Extensions() {
    val ctx = LocalContext.current
    var exts by remember { mutableStateOf(Store.exts()) }
    var url by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var repo by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun install(code: String) {
        val e = Ext(code)
        when {
            !e.valid -> msg = "Not a Fizz extension. It needs // @name, // @type and the functions search(), detail() and content()."
            exts.any { it.name == e.name } -> msg = "${e.name} is already installed."
            else -> { Store.addExt(code); exts = Store.exts(); msg = "Installed ${e.name}." }
        }
    }
    fun add() = scope.launch {
        busy = true; msg = null; repo = emptyList()
        try {
            val body = withContext(Dispatchers.IO) { Net.get(url.trim()) }
            val t = body.trimStart()
            if (t.startsWith("[") || t.startsWith("{")) {
                val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("extensions") ?: JSONArray()
                val l = List(arr.length()) { arr.getJSONObject(it) }
                when {
                    l.any { it.has("pkg") || it.has("apk") } -> msg = "This is an Aniyomi/Mihon repo (Android APK extensions). Fizz can't load those. It uses .js extensions."
                    l.isEmpty() -> msg = "No extensions found in this repo."
                    else -> repo = l
                }
            } else install(body)
        } catch (e: Exception) { msg = "Could not load: ${e.message}" }
        busy = false
    }

    Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(url, { url = it }, Modifier.weight(1f), placeholder = { Text("Repo or extension URL") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { add() }))
            IconButton({ add() }, enabled = !busy && url.isNotBlank()) { Icon(Icons.Filled.Add, "Add") }
        }
        TextButton({ install(ctx.assets.open("sample_mangadex.js").bufferedReader().readText()) }, enabled = exts.none { it.name.startsWith("MangaDex") }) { Text("Add built-in sample (MangaDex)") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        msg?.let { Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary) }
        LazyColumn {
            if (repo.isNotEmpty()) {
                item { Text("Repository", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(repo) { r ->
                    ListItem(headlineContent = { Text(r.optString("name")) }, supportingContent = { Text(r.optString("type")) },
                        trailingContent = {
                            TextButton({
                                scope.launch {
                                    try { install(withContext(Dispatchers.IO) { Net.get(URL(URL(url.trim()), r.getString("url")).toString()) }) }
                                    catch (e: Exception) { msg = "Could not install: ${e.message}" }
                                }
                            }) { Text("Install") }
                        })
                }
            }
            item { Text("Installed", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            if (exts.isEmpty()) item { Text("Nothing installed yet.", Modifier.padding(vertical = 8.dp)) }
            itemsIndexed(exts) { i, e ->
                ListItem(headlineContent = { Text(e.name) }, supportingContent = { Text(e.type) },
                    leadingContent = { Icon(Icons.Filled.Extension, null) },
                    trailingContent = { IconButton({ Store.delExt(i); exts = Store.exts() }) { Icon(Icons.Filled.Delete, "Delete") } })
            }
        }
    }
}
