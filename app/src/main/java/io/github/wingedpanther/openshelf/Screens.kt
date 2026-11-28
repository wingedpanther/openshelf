@file:OptIn(ExperimentalFoundationApi::class)

package io.github.wingedpanther.openshelf

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.widthIn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.window.DialogProperties

enum class Tab(val label: Int) { Discover(R.string.tab_discover), Categories(R.string.tab_categories), Search(R.string.tab_search), Mine(R.string.tab_mine), Settings(R.string.tab_settings) }

class FocusHandoff {
    val target = FocusRequester()
    var pending by mutableStateOf(true)
}

@Composable
fun OpenShelf() {
    val activity = androidx.activity.compose.LocalActivity.current
    var tab by remember { mutableStateOf(Tab.Discover) }
    var page by remember { mutableStateOf<String?>(null) }
    var wallId by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var room by remember { mutableStateOf<String?>(null) }
    var lastRoom by remember { mutableStateOf<String?>(null) }
    var lastInRoom by remember { mutableStateOf<String?>(null) }
    val roomGrid = remember(room) { LazyGridState() }
    var roomSort by remember(room) { mutableIntStateOf(0) }
    var tabsFocused by remember { mutableStateOf(false) }
    val tabRequesters = remember { Tab.entries.associateWith { FocusRequester() } }
    val handoff = remember { FocusHandoff() }
    var newsList by remember { mutableStateOf<List<App>?>(null) }
    var limitShown by remember { mutableStateOf(0L) }
    val ctx = LocalContext.current
    LaunchedEffect(Device.tick) {
        Catalog.load(ctx)
        if (Prefs.autoCheckSelf) SelfUpdate.check(ctx)
        CatalogUpdate.check(ctx)
    }
    if (Catalog.apps.isEmpty()) {
        Box(Modifier.fillMaxSize().background(C.Wall), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(C.mark), null, Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                T("Open Shelf", 24.sp, weight = FontWeight.SemiBold, tight = true)
            }
        }
        return
    }

    val updates = remember(Device.tick, SelfUpdate.app) { updateCount(ctx) }
    LaunchedEffect(updates, Catalog.apps) {
        val ready = shelfApps(ctx).filter { (a, i) -> a.pkg != ctx.packageName && i.versionCode < a.versionCode && keyOf(a, i) == KeyStatus.MATCHES }
        val picks = Catalog.trending().take(8).ifEmpty { Catalog.added().take(8) }
        withContext(Dispatchers.IO) { HomeRow.sync(ctx, ready, picks) }
    }
    LaunchedEffect(Links.pending, Catalog.apps) {
        val link = Links.pending ?: return@LaunchedEffect
        when (link.host) {
            "app" -> link.lastPathSegment?.let { id -> if (Catalog.byId(id) != null) { room = null; page = id } }
            "mine" -> { page = null; room = null; tab = Tab.Mine }
        }
        handoff.pending = true
        Links.pending = null
    }
    val openApp: (String) -> Unit = { page = it; handoff.pending = true }
    val openRoom: (String) -> Unit = { room = it; lastRoom = it; lastInRoom = null; handoff.pending = true }
    val closePage = {
        if (page != null) page = null else room = null
        handoff.pending = true
    }

    BackHandler {
        when {
            page != null || room != null -> closePage()
            !tabsFocused -> runCatching { tabRequesters.getValue(tab).requestFocus() }
            else -> activity?.finish()
        }
    }

    Box(Modifier.fillMaxSize().background(C.Wall)) {
        Box(Modifier.fillMaxSize().padding(top = 70.dp)) {
            val app = page?.let { Catalog.byId(it) ?: ManualLinks.byId(it) ?: Direct.byId(it) }
            val r = room
            if (app != null) {
                AppPage(app, openApp, handoff)
            } else if (r != null) {
                RoomPage(r, roomGrid, roomSort, { roomSort = it }, lastInRoom, { lastInRoom = it }, openApp, handoff)
            } else when (tab) {
                Tab.Discover -> Discover(wallId, { wallId = it }, openApp, openRoom, handoff)
                Tab.Categories -> Categories(lastRoom, openRoom, handoff)
                Tab.Search -> SearchScreen(query, { query = it }, openApp, handoff)
                Tab.Mine -> MyApps(openApp, { tab = Tab.Discover; handoff.pending = true }, handoff)
                Tab.Settings -> SettingsScreen(openApp, handoff)
            }
        }
        TopBar(
            tab = tab,
            deep = page != null || room != null,
            backLabel = if (page != null) room?.let(::roomName) ?: str(tab.label) else str(tab.label),
            onBack = closePage,
            requesters = tabRequesters,
            onTab = { page = null; room = null; tab = it },
            onTabsFocus = { tabsFocused = it },
            updates = updates,
        )
        val news = CatalogUpdate.news
        val limitedUntil = GitHub.limitedUntil
        if (news.isNotEmpty()) {
            Notice(newsText(news), Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp), str(R.string.news_see), { newsList = news.take(NEWS_LISTED) }) { hadFocus ->
                CatalogUpdate.news = emptyList()
                if (hadFocus) handoff.pending = true
            }
        } else if (limitedUntil > limitShown) {
            Notice(GitHub.limitMessage(), Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp), null, {}) { limitShown = limitedUntil }
        }
    }

    newsList?.let { list ->
        AppListDialog(str(R.string.news_title), str(R.string.news_body), list, openApp, { newsList = null; handoff.pending = true }, bulk = false)
    }

    val selfApp = SelfUpdate.app
    var selfDismissed by remember(selfApp?.version) { mutableStateOf(false) }
    if (selfApp != null && !selfDismissed && SelfUpdate.shouldPrompt(ctx)) {
        SelfUpdateDialog(
            app = selfApp,
            currentVersion = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty(),
            onUpdate = { selfDismissed = true; Installer.start(ctx, selfApp) },
            onLater = { selfDismissed = true; SelfUpdate.ignore(ctx, selfApp.version) },
        )
    }

    if (Lock.pending.isNotEmpty()) {
        PinDialog(str(R.string.pin_enter), onClose = { Lock.pending.clear() }) { Lock.release() }
    }

    LaunchedEffect(handoff.pending, page, room, tab) {
        if (handoff.pending) {
            delay(60)
            runCatching { handoff.target.requestFocus() }
            handoff.pending = false
        }
    }
}

private const val NOTICE_SHOWN = 10_000L
private const val NEWS_LISTED = 60

private fun newsText(news: List<App>): String =
    if (news.size == 1) str(R.string.news_one, news[0].name)
    else plural(R.plurals.news_many, news.size, news.size, str(R.string.news_and, news[0].name, news[1].name))

@Composable
private fun Notice(text: String, modifier: Modifier, action: String?, onAction: () -> Unit, onDone: (Boolean) -> Unit) {
    val button = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(text) {
        if (action != null) {
            delay(60)
            runCatching { button.requestFocus() }
        }
        delay(NOTICE_SHOWN)
        onDone(focused)
    }
    Row(
        modifier.widthIn(max = 760.dp).shadow(16.dp, RoundedCornerShape(18.dp))
            .background(C.Mat, RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.hasFocus }
            .padding(start = 22.dp, end = if (action != null) 10.dp else 24.dp, top = if (action != null) 8.dp else 15.dp, bottom = if (action != null) 8.dp else 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(C.mark), null, Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        T(text, 14.5.sp, Modifier.weight(1f, fill = false), maxLines = 2)
        if (action != null) {
            Spacer(Modifier.width(14.dp))
            Pill(action, onAction, solid = true, requester = button)
        }
    }
}

@Composable
private fun SelfUpdateDialog(app: App, currentVersion: String, onUpdate: () -> Unit, onLater: () -> Unit) {
    val updateRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onLater) {
        Column(
            Modifier.width(440.dp).shadow(16.dp, RoundedCornerShape(18.dp))
                .background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp),
        ) {
            Image(painterResource(C.mark), null, Modifier.size(40.dp))
            T(str(R.string.self_update_title), 22.sp, Modifier.padding(top = 16.dp),
                weight = FontWeight.SemiBold, tight = true)
            T(str(R.string.self_update_body, currentVersion, app.versionName ?: app.version),
                14.5.sp, Modifier.padding(top = 8.dp), color = C.Ink2)
            Row(
                Modifier.padding(top = 22.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(str(R.string.self_update_later), onLater)
                Spacer(Modifier.width(10.dp))
                Pill(str(R.string.action_update), onUpdate, solid = true,
                    icon = Icons.Download, requester = updateRequester)
            }
            LaunchedEffect(Unit) { runCatching { updateRequester.requestFocus() } }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TopBar(
    tab: Tab,
    deep: Boolean,
    backLabel: String,
    onBack: () -> Unit,
    requesters: Map<Tab, FocusRequester>,
    onTab: (Tab) -> Unit,
    onTabsFocus: (Boolean) -> Unit,
    updates: Int,
) {
    Box(Modifier.fillMaxWidth().height(64.dp)) {
        if (deep) {
            Focusable(onBack, Modifier.padding(start = 44.dp, top = 18.dp)) { f ->
                Row(
                    Modifier
                        .lift(f, 1.06f, CircleShape, rest = 0.dp)
                        .background(if (f) C.accent else Color.Transparent, CircleShape)
                        .height(28.dp)
                        .padding(start = 8.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ico(Icons.ChevLeft, 15.dp, if (f) C.OnAccent else C.Stone)
                    Spacer(Modifier.width(5.dp))
                    T(backLabel, 13.5.sp, color = if (f) C.OnAccent else C.Stone, weight = FontWeight.Medium)
                }
            }
        } else {
            Row(Modifier.padding(start = 48.dp, top = 21.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(C.mark), null, Modifier.size(21.dp))
                Spacer(Modifier.width(7.dp))
                T("Open Shelf", 15.5.sp, weight = FontWeight.SemiBold, tight = true)
            }
        }
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 17.dp)
                .background(C.Bar, CircleShape)
                .border(1.dp, C.Hair, CircleShape)
                .padding(3.dp)
                .onFocusChanged { onTabsFocus(it.hasFocus) }
                .focusProperties { enter = { requesters.getValue(tab) } }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Tab.entries.forEach { t ->
                Focusable(
                    onClick = { onTab(t) },
                    requester = requesters.getValue(t),
                    onFocus = { if (!deep) onTab(t) },
                ) { f ->
                    val selected = t == tab
                    Row(
                        Modifier
                            .lift(f, 1.06f, CircleShape, rest = 0.dp)
                            .background(if (f) C.accent else if (selected) C.Selected else Color.Transparent, CircleShape)
                            .height(28.dp)
                            .padding(horizontal = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T(str(t.label), 13.5.sp, color = if (f) C.OnAccent else if (selected) C.Ink else C.Stone, weight = FontWeight.Medium)
                        if (t == Tab.Mine && updates > 0) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.height(17.dp).widthIn(min = 17.dp).background(if (f) C.OnAccent else C.accent, CircleShape).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                                T(if (updates > 9) "9+" else "$updates", 10.5.sp, color = if (f) C.accent else C.OnAccent, weight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        Clock(Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = 23.dp))
    }
}

@Composable
private fun Clock(modifier: Modifier) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(15_000) } }
    T(SimpleDateFormat("HH:mm", Locale.ROOT).format(now), 13.5.sp, modifier, color = C.Stone, weight = FontWeight.Medium)
}

@Composable
private fun Discover(wallId: String, onWall: (String) -> Unit, openApp: (String) -> Unit, openRoom: (String) -> Unit, handoff: FocusHandoff) {
    val ctx = LocalContext.current
    val tick = Device.tick
    val first = remember(Prefs.hideStreams) { Catalog.visible.firstOrNull { it.art.banner != null && it.art.wall != null && it.runsHere } ?: Catalog.apps.first() }
    val wall = Catalog.byId(wallId) ?: first
    var browsing by remember { mutableStateOf(false) }
    val heroH by animateDpAsState(if (browsing) 150.dp else 268.dp, tween(320), label = "hero")
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 48.dp).height(heroH)) {
            AnimatedContent(wall, transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(200)) }, label = "wall") { a ->
                Box(Modifier.size(heroH * 16f / 9f, heroH).shadow(8.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))) {
                    ArtImage(a.art.wall, a, 960)
                }
            }
            Spacer(Modifier.width(36.dp))
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                AnimatedContent(wall, transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(160)) }, label = "label") { a ->
                    Column {
                        T(a.name, 32.sp, weight = FontWeight.SemiBold, tight = true, maxLines = 1)
                        T(str(R.string.by_maker, a.maker), 13.5.sp, Modifier.padding(top = 4.dp), color = C.Stone)
                        AnimatedVisibility(!browsing, enter = expandVertically(tween(320)) + fadeIn(tween(320)), exit = shrinkVertically(tween(320)) + fadeOut(tween(160))) {
                            Column {
                                T(a.note, 15.5.sp, Modifier.padding(top = 11.dp).width(330.dp), color = C.Ink2, maxLines = 2)
                                Box(Modifier.padding(top = 15.dp).fillMaxWidth().height(1.dp).background(C.Hair))
                                Column(Modifier.padding(top = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Fact(str(R.string.fact_version), "${L.wrap(a.version)}, ${L.wrap(Fmt.date(a.date))}")
                                    Fact(str(R.string.fact_licence), a.licence ?: str(R.string.licence_unknown))
                                    Fact(str(R.string.fact_stars), Fmt.count(a.stars))
                                }
                            }
                        }
                    }
                }
                Row(Modifier.padding(top = 13.dp).offset(x = (-4).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Pill(str(R.string.view_app), { openApp(wall.id) }, solid = true, requester = handoff.target)
                    InstallButton(wall, solid = false)
                }
            }
        }
        val tv = remember(Prefs.hideStreams) { Catalog.visible.filter { it.art.banner != null && it.runsHere }.take(40) }
        val recent = remember(Prefs.hideStreams) { Catalog.visible.sortedByDescending { it.date }.take(40) }
        val starred = remember(Prefs.hideStreams) { Catalog.visible.take(40) }
        val fresh = remember(Prefs.hideStreams) { Catalog.added().take(40) }
        val trending = remember(Prefs.hideStreams) { Catalog.trending().take(40) }
        val packs = Catalog.packs
        var pack by remember { mutableStateOf<Pack?>(null) }
        val rooms = remember(Prefs.hideStreams) { roomsOf() }
        fun meta(a: App) = status(ctx, a)
        @Suppress("UNUSED_VARIABLE") val watch = tick
        val rowsState = rememberLazyListState()
        val spec = LocalBringIntoViewSpec.current
        fun tiles(list: List<App>, label: (App) -> String): androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            items(list, key = { it.id }) { a -> Tile(a, meta(a).ifEmpty { label(a) }, meta(a).isNotEmpty(), { onWall(a.id) }) { openApp(a.id) } }
        }
        val shelves = buildList {
            add(Shelf(str(R.string.row_tv), str(R.string.row_tv_sub), tiles(tv) { "★ ${Fmt.stars(it.stars)}" }))
            if (packs.isNotEmpty()) add(Shelf(str(R.string.row_packs), str(R.string.row_packs_sub)) {
                items(packs, key = { it.id }) { p -> PackCard(p) { pack = p } }
            })
            if (fresh.isNotEmpty()) add(Shelf(str(R.string.row_new), str(R.string.row_new_sub), tiles(fresh) { "★ ${Fmt.stars(it.stars)}" }))
            if (trending.isNotEmpty()) add(Shelf(str(R.string.row_trending), Catalog.trendSince?.let { str(R.string.row_trending_sub, Fmt.shortDate(it)) }, tiles(trending) { "★ +${Fmt.count(it.trend)}" }))
            add(Shelf(str(R.string.row_recent), str(R.string.row_recent_sub), tiles(recent) { Fmt.shortDate(it.date) }))
            add(Shelf(str(R.string.row_starred), null, tiles(starred) { "★ ${Fmt.stars(it.stars)}" }))
            add(Shelf(str(R.string.row_categories), str(R.string.row_categories_sub)) {
                items(rooms, key = { it.first }) { (room, apps) -> RoomCard(room, apps.size) { openRoom(room) } }
            })
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides Still) {
        LazyColumn(
            Modifier.padding(top = 12.dp).fillMaxWidth().weight(1f).onFocusChanged { browsing = it.hasFocus },
            state = rowsState,
        ) {
            shelves.forEachIndexed { i, shelf ->
                item { ShelfRow(i, rowsState, spec, shelf.title, shelf.sub, shelves.getOrNull(i + 1)?.title, shelf.content) }
            }
        }
        }
        pack?.let { p -> PackDialog(p, openApp) { pack = null } }
    }
}

private class Shelf(val title: String, val sub: String?, val content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit)

@Composable
private fun PackCard(pack: Pack, onClick: () -> Unit) {
    val apps = remember(pack.id, Catalog.apps) { pack.apps }
    Focusable(onClick) { f ->
        Column(
            Modifier.size(230.dp, 83.dp).lift(f, ring = 10.dp).clip(RoundedCornerShape(10.dp)).background(C.Mat)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                apps.take(5).forEach { a -> AppIcon(a, 24.dp) }
            }
            Spacer(Modifier.weight(1f))
            T(packName(pack), 16.sp, weight = FontWeight.SemiBold, tight = true, maxLines = 1)
            T(plural(R.plurals.apps, apps.size, apps.size), 11.5.sp, color = C.Stone)
        }
    }
}

private fun packName(p: Pack): String = when (p.id) {
    "new_tv" -> str(R.string.pack_new_tv)
    "media" -> str(R.string.pack_media)
    "games" -> str(R.string.pack_games)
    "tools" -> str(R.string.pack_tools)
    else -> p.title
}

@Composable
private fun PackDialog(pack: Pack, openApp: (String) -> Unit, onClose: () -> Unit) {
    AppListDialog(packName(pack), str(R.string.pack_body), pack.apps, openApp, onClose)
}

@Composable
fun AppListDialog(title: String, body: String, apps: List<App>, openApp: (String) -> Unit, onClose: () -> Unit, bulk: Boolean = true) {
    val ctx = LocalContext.current
    val tick = Device.tick
    val first = remember { FocusRequester() }
    val missing = remember(apps, tick) { apps.filter { Device.installedAs(ctx, it) == null && it.runsHere } }
    val size = missing.sumOf { pickApk(it).size }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(640.dp).heightIn(max = 600.dp).shadow(16.dp, RoundedCornerShape(18.dp))
                .background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp),
        ) {
            T(title, 22.sp, weight = FontWeight.SemiBold, tight = true)
            T(body, 14.sp, Modifier.padding(top = 6.dp), color = C.Ink2)
            Column(Modifier.padding(top = 12.dp).weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                apps.forEach { a ->
                    val installed = remember(a.id, tick) { Device.installedAs(ctx, a) != null }
                    val job = Installer.jobs[a.id]
                    AppRow(
                        focusRequester = null,
                        onClick = { onClose(); openApp(a.id) },
                        icon = { AppIcon(a, 34.dp) },
                        title = a.name,
                        sub = a.note,
                    ) { f ->
                        val label = when {
                            job is Job.Downloading -> str(R.string.job_downloading, (job.fraction * 100).toInt())
                            job == Job.Checking || job == Job.Installing -> str(R.string.job_installing)
                            job is Job.Failed -> str(R.string.pack_failed)
                            job is Job.Held -> str(R.string.pack_held)
                            installed -> str(R.string.status_installed)
                            !a.runsHere -> str(R.string.unsupported_short)
                            else -> Fmt.mb(pickApk(a).size)
                        }
                        T("  $label", 12.5.sp, color = C.Stone, maxLines = 1)
                        MiniPill(str(R.string.details), f)
                    }
                }
            }
            val install = bulk && missing.isNotEmpty()
            Row(Modifier.padding(top = 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Pill(str(R.string.action_close), onClose, requester = if (install) null else first)
                if (install) {
                    Spacer(Modifier.width(10.dp))
                    Pill(str(R.string.pack_install, missing.size, Fmt.mb(size)), { missing.forEach { Installer.start(ctx, it) } },
                        solid = true, icon = Icons.Download, requester = first)
                }
            }
            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        }
    }
}

private val Still = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

@Composable
private fun Fact(label: String, value: String, labelWidth: Dp = 64.dp) {
    Row {
        T(label, 12.5.sp, Modifier.width(labelWidth), color = C.Stone)
        T(value, 12.5.sp, weight = FontWeight.Medium, maxLines = 1)
    }
}

private fun toneOf(b: ImageBitmap): Color =
    Color(Bitmap.createScaledBitmap(b.asAndroidBitmap(), 1, 1, true).getPixel(0, 0)).copy(alpha = 1f)

private fun groundOf(b: ImageBitmap): Color? {
    val bm = b.asAndroidBitmap()
    val w = bm.width
    val h = bm.height
    if (w < 8 || h < 8) return null
    val x0 = (w * 0.04f).toInt(); val x1 = w - 1 - x0
    val y0 = (h * 0.06f).toInt(); val y1 = h - 1 - y0
    val samples = buildList {
        for (i in 0..10) {
            val x = x0 + (x1 - x0) * i / 10
            add(bm.getPixel(x, y0)); add(bm.getPixel(x, y1))
        }
        for (i in 1..5) {
            val y = y0 + (y1 - y0) * i / 6
            add(bm.getPixel(x0, y)); add(bm.getPixel(x1, y))
        }
    }.map { px -> if (android.graphics.Color.alpha(px) < 128) android.graphics.Color.WHITE else px }
    fun ch(px: Int, shift: Int) = (px shr shift) and 0xFF
    val mean = intArrayOf(16, 8, 0).map { sh -> samples.sumOf { ch(it, sh) } / samples.size }
    val close = samples.count { px -> intArrayOf(16, 8, 0).withIndex().all { (i, sh) -> kotlin.math.abs(ch(px, sh) - mean[i]) <= 22 } }
    if (close < samples.size * 0.85f) return null
    return Color(mean[0], mean[1], mean[2])
}

@Composable
private fun ArtImage(url: String?, app: App, maxWidth: Int, fill: Boolean = false) {
    var bmp by remember(url, maxWidth) { mutableStateOf(url?.let { Images.cached(it, maxWidth) }) }
    var failed by remember(url) { mutableStateOf(url == null) }
    LaunchedEffect(url, maxWidth) {
        if (bmp == null && url != null) {
            bmp = Images.load(url, maxWidth)
            failed = bmp == null
        }
    }
    val b = bmp
    val wide = b != null && (fill || b.width >= 900 || kotlin.math.abs(b.width.toFloat() / b.height - 16f / 9f) < 0.12f)
    val ground = remember(b, fill) { if (b != null && !fill && b.width < 900) groundOf(b) else null }
    val tone = remember(b, wide, ground) { if (b != null && !wide && ground == null) toneOf(b) else null }
    Box(Modifier.fillMaxSize().background(ground ?: tone ?: C.WallDeep), contentAlignment = Alignment.Center) {
        when {
            b != null && ground != null -> Image(b, null, Modifier.fillMaxSize(0.82f), contentScale = ContentScale.Fit, filterQuality = FilterQuality.High)
            b != null && wide -> Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
            b != null -> Image(
                b, null,
                Modifier.fillMaxHeight(0.7f).aspectRatio(b.width.toFloat() / b.height, matchHeightConstraintsFirst = true)
                    .shadow(10.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
            failed -> NameCard(app)
        }
    }
}

@Composable
private fun NameCard(app: App) {
    BoxWithConstraints(Modifier.fillMaxSize().background(C.Mat), contentAlignment = Alignment.Center) {
        val big = maxWidth > 300.dp
        val type = (maxWidth.value * 0.062f).coerceIn(13f, 30f).sp
        val icon = (maxWidth * 0.14f).coerceIn(30.dp, 68.dp)
        Column(Modifier.padding(horizontal = if (big) 36.dp else 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (app.art.icon != null) {
                AppIcon(app, icon)
                Spacer(Modifier.height(if (big) 16.dp else 8.dp))
            }
            T(app.name, type, weight = FontWeight.SemiBold, tight = true, maxLines = 2)
            if (big) T(str(R.string.by_maker, app.maker), 13.5.sp, Modifier.padding(top = 6.dp), color = C.Stone, maxLines = 1)
        }
    }
}

@Composable
fun AppIcon(app: App, size: Dp, modifier: Modifier = Modifier) {
    val url = app.art.icon
    var bmp by remember(url) { mutableStateOf(url?.let { Images.cached(it, 192) }) }
    LaunchedEffect(url) { if (bmp == null && url != null) bmp = Images.load(url, 192) }
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(C.WallDeep), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else T(app.name.take(1).uppercase(), (size.value * 0.45f).sp, color = C.Stone, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun ShelfRow(
    index: Int,
    rows: androidx.compose.foundation.lazy.LazyListState,
    spec: BringIntoViewSpec,
    title: String,
    sub: String?,
    next: String?,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.height(168.dp).onFocusChanged { if (it.hasFocus) scope.launch { rows.animateScrollToItem(index) } }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            T(title, 15.5.sp, weight = FontWeight.SemiBold, tight = true)
            if (sub != null) T(sub, 12.5.sp, Modifier.padding(start = 7.dp), color = C.Stone)
            Spacer(Modifier.weight(1f))
            if (next != null) {
                T(next, 11.5.sp, color = C.Stone)
                Ico(Icons.ChevDown, 12.dp, C.Stone, Modifier.padding(start = 3.dp))
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun Tile(a: App, meta: String, dot: Boolean, onFocus: () -> Unit, requester: FocusRequester? = null, onClick: () -> Unit) {
    Focusable(onClick, requester = requester, onFocus = onFocus) { f ->
        Column(Modifier.width(148.dp)) {
            Box(Modifier.size(148.dp, 83.dp).lift(f, ring = 10.dp).clip(RoundedCornerShape(10.dp))) {
                ArtImage(a.art.tile, a, 320, fill = true)
            }
            T(a.name, 13.sp, Modifier.padding(top = 10.dp), color = if (f) C.Ink else C.Ink2, weight = FontWeight.SemiBold, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dot) { Dot(5.5.dp); Spacer(Modifier.width(5.dp)) }
                T(meta, 11.5.sp, color = C.Stone, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RoomCard(room: String, n: Int, onClick: () -> Unit) {
    Focusable(onClick) { f ->
        Column(
            Modifier.size(180.dp, 83.dp).lift(f, ring = 10.dp).clip(RoundedCornerShape(10.dp)).background(C.Mat)
                .padding(horizontal = 15.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            T(roomName(room), 20.sp, weight = FontWeight.SemiBold, tight = true, maxLines = 1)
            T(plural(R.plurals.apps, n, n), 11.5.sp, color = C.Stone)
        }
    }
}

private fun status(ctx: Context, a: App): String {
    val inst = Device.installedAs(ctx, a)
    return when {
        inst != null && inst.versionCode < a.versionCode -> str(R.string.status_update_ready)
        inst != null -> str(R.string.status_installed)
        else -> ""
    }
}

private fun roomsOf(): List<Pair<String, List<App>>> =
    Catalog.visible.groupBy { it.room }.toList()
        .sortedWith(compareBy<Pair<String, List<App>>> { it.first == "Other" }.thenByDescending { it.second.size })

@Composable
private fun Categories(last: String?, openRoom: (String) -> Unit, handoff: FocusHandoff) {
    val rooms = remember(Prefs.hideStreams) { roomsOf() }
    val target = last?.takeIf { l -> rooms.any { it.first == l } } ?: rooms.first().first
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            T(str(R.string.categories_summary, rooms.size, Fmt.count(Catalog.visible.size)), 13.5.sp, color = C.Stone, weight = FontWeight.Medium)
        }
        items(rooms, key = { it.first }) { (room, apps) ->
            CategoryCard(room, apps, if (room == target) handoff.target else null) { openRoom(room) }
        }
    }
}

@Composable
private fun CategoryCard(room: String, apps: List<App>, requester: FocusRequester?, onClick: () -> Unit) {
    val faces = remember(room) { apps.filter { it.art.tile != null }.take(3) }
    Focusable(onClick, requester = requester) { f ->
        Column(
            Modifier.fillMaxWidth().height(116.dp)
                .lift(f, 1.05f, RoundedCornerShape(12.dp), ring = 12.dp)
                .clip(RoundedCornerShape(12.dp)).background(C.Mat)
                .padding(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                faces.forEach { a -> MiniArt(a) }
            }
            Spacer(Modifier.weight(1f))
            T(roomName(room), 19.sp, weight = FontWeight.SemiBold, tight = true, maxLines = 1)
            T(plural(R.plurals.apps, apps.size, apps.size), 11.5.sp, color = if (f) C.accent else C.Stone, weight = FontWeight.Medium)
        }
    }
}

@Composable
private fun MiniArt(a: App) {
    val url = a.art.tile
    var bmp by remember(url) { mutableStateOf(url?.let { Images.cached(it, 160) }) }
    LaunchedEffect(url) { if (bmp == null && url != null) bmp = Images.load(url, 160) }
    Box(Modifier.size(52.dp, 29.dp).clip(RoundedCornerShape(6.dp)).background(C.WallDeep)) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun RoomPage(
    room: String,
    grid: LazyGridState,
    sort: Int,
    onSort: (Int) -> Unit,
    last: String?,
    onFocusApp: (String) -> Unit,
    openApp: (String) -> Unit,
    handoff: FocusHandoff,
) {
    val ctx = LocalContext.current
    val tick = Device.tick
    val apps = remember(room, sort, Prefs.hideStreams) {
        Catalog.visible.filter { it.room == room }.let { list ->
            when (sort) {
                1 -> list.sortedByDescending { it.date }
                2 -> list.sortedByDescending { it.downloads }
                else -> list
            }
        }
    }
    val target = last?.takeIf { l -> apps.any { it.id == l } } ?: apps.firstOrNull()?.id
    @Suppress("UNUSED_VARIABLE") val watch = tick
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        state = grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 4.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                Column {
                    T(roomName(room), 32.sp, weight = FontWeight.SemiBold, tight = true, maxLines = 1)
                    T(plural(R.plurals.apps, apps.size, apps.size), 13.5.sp, Modifier.padding(top = 2.dp), color = C.Stone)
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(str(R.string.row_starred), sort == 0) { onSort(0) }
                    Chip(str(R.string.row_recent), sort == 1) { onSort(1) }
                    Chip(str(R.string.row_downloads), sort == 2) { onSort(2) }
                }
            }
        }
        items(apps, key = { it.id }) { a ->
            val s = status(ctx, a)
            Tile(
                a, s.ifEmpty { when (sort) { 1 -> Fmt.shortDate(a.date); 2 -> Fmt.downloads(a.downloads); else -> "★ ${Fmt.stars(a.stars)}" } }, s.isNotEmpty(),
                onFocus = { onFocusApp(a.id) },
                requester = if (a.id == target) handoff.target else null,
            ) { openApp(a.id) }
        }
    }
}

@Composable
private fun AppPage(app: App, openApp: (String) -> Unit, handoff: FocusHandoff) {
    val ctx = LocalContext.current
    val tick = Device.tick
    val installed = remember(app.id, tick) { Device.installedAs(ctx, app) }
    val other = remember(app.id, tick) { Device.installed(ctx, app.pkg)?.let { Catalog.sibling(app, it) } }
    var readme by remember(app.id) { mutableStateOf(emptyList<String>()) }
    var notes by remember(app.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(app.id) {
        notes = GitHub.release(app.repo, app.version)?.notes?.takeIf { it.isNotBlank() }
    }
    LaunchedEffect(app.id) {
        readme = coroutineScope {
            GitHub.screenshots(app.repo).map { url -> async { url.takeIf { Images.load(url, 480)?.let { it.width >= 480 && it.height >= 270 } == true } } }
                .awaitAll().filterNotNull()
        }
    }
    val gallery = remember(app.id, readme) { (app.art.gallery + readme).distinct() }
    var shot by remember(app.id) { mutableStateOf(app.art.gallery.firstOrNull()) }
    val apk = pickApk(app)
    val browser = remember { hasBrowser(ctx) }
    val releases = remember(app.id) { app.releases.sortedByDescending { it.tag == app.version } }
    var older by remember(app.id) { mutableStateOf<Release?>(null) }
    var showNotes by remember(app.id) { mutableStateOf(false) }
    var showPermissions by remember(app.id) { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showScan by remember(app.id) { mutableStateOf(false) }
    val permissions = remember(app.id, installed) {
        if (installed != null) Device.permissions(ctx, app.pkg) else app.perms
    }
    val knownPermissions = remember(permissions) { permissions.mapNotNull(::permissionLabel) }
    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
        Column(Modifier.width(476.dp)) {
            AnimatedContent(shot, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) }, label = "shot") { s ->
                Box(Modifier.size(476.dp, 268.dp).shadow(8.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))) {
                    ArtImage(s, app, 960)
                }
            }
            if (gallery.size > 1) {
                LazyRow(
                    Modifier.padding(top = 14.dp).requiredWidth(500.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(gallery, key = { it }) { s ->
                        Focusable({ shot = s }, onFocus = { shot = s }) { f ->
                            Box(Modifier.size(108.dp, 61.dp).lift(f, ring = 8.dp, shape = RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
                                ArtImage(s, app, 240, fill = true)
                            }
                        }
                    }
                }
            }
            if (releases.isNotEmpty()) {
                T(str(R.string.releases), 12.5.sp, Modifier.padding(top = if (gallery.size > 1) 16.dp else 28.dp), color = C.Stone, weight = FontWeight.Medium)
                Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    releases.take(4).forEachIndexed { i, r ->
                        Focusable({ older = r }, Modifier.weight(1f).focusProperties { canFocus = i > 0 }) { f ->
                            Column(
                                Modifier.fillMaxWidth().lift(f, 1.04f, RoundedCornerShape(8.dp), rest = 0.dp, ring = 8.dp)
                                    .background(C.Mat, RoundedCornerShape(8.dp))
                                    .border(if (i == 0) 1.5.dp else 1.dp, if (i == 0) C.accent else C.Hair, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                T(r.tag, 12.5.sp, weight = FontWeight.SemiBold, maxLines = 1)
                                T(if (i == 0) str(R.string.latest_month, Fmt.monthYear(r.date)) else Fmt.monthYear(r.date), 10.5.sp, color = C.Stone, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(36.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, 52.dp, Modifier.shadow(3.dp, RoundedCornerShape(11.dp)))
                Spacer(Modifier.width(13.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(app.name, 24.sp, weight = FontWeight.SemiBold, tight = true, maxLines = 1)
                        if (installed != null) { Spacer(Modifier.width(8.dp)); Dot() }
                    }
                    val byLine = when {
                        app.forkOf != null -> str(R.string.by_maker_fork, app.maker, Catalog.byRepo(app.forkOf)?.name ?: app.forkOf.substringAfter('/'))
                        app.basedOn != null -> str(R.string.by_maker_based, app.maker, Catalog.byRepo(app.basedOn)?.name ?: app.basedOn.substringAfter('/'))
                        else -> str(R.string.by_maker, app.maker)
                    }
                    T(listOfNotNull(byLine, app.code?.let { str(R.string.code_short, it.toString()) }).joinToString(" · "), 12.5.sp, color = C.Stone, maxLines = 1)
                }
            }
            T(app.note, 15.5.sp, Modifier.padding(top = 12.dp), color = C.Ink2, maxLines = 2)
            val stale = remember(app.id) { runCatching { java.time.LocalDate.parse(app.date).isBefore(java.time.LocalDate.now().minusYears(1)) }.getOrDefault(false) }
            if (app.id.startsWith("direct-")) T(str(R.string.direct_warning), 11.5.sp, Modifier.padding(top = 4.dp), color = C.Stone)
            else if (app.archived) T(str(R.string.archived_note), 11.5.sp, Modifier.padding(top = 4.dp), color = C.Stone)
            else if (stale) T(str(R.string.stale_note, Fmt.monthYear(app.date)), 11.5.sp, Modifier.padding(top = 4.dp), color = C.Stone)
            val ks = installed?.let { keyOf(app, it) } ?: KeyStatus.MATCHES
            val acceptable = (installed?.takeIf { other == null && ks == KeyStatus.DIFFERS }?.certs?.firstOrNull()) ?: (Installer.jobs[app.id] as? Job.Held)?.cert
            Row(Modifier.padding(top = 10.dp).offset(x = (-4).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                InstallButton(app, requester = handoff.target)
                if (installed != null && !Installer.busy(app.id)) Pill(if (installed.versionCode < app.versionCode) "" else str(R.string.uninstall), { uninstall(ctx, app.pkg) }, icon = Icons.Trash)
                if ((Installer.jobs[app.id] as? Job.Failed)?.message?.let(Help::applies) == true) Pill(str(R.string.help_action), { showHelp = true }, icon = Icons.Help)
            }
            JobNote(app, Modifier.padding(top = 6.dp), helpButton = false)
            StorageLine(app, installed)
            val fork = remember(app.id) { Catalog.forksOf(app).maxByOrNull { it.date } }
            if (fork != null) ForkLine(fork) { openApp(fork.id) }
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(1.dp).background(C.Hair))
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row {
                    Prov(Icons.Calendar, str(R.string.prov_released), "${L.wrap(app.version)}, ${L.wrap(Fmt.date(app.date))}", Modifier.weight(1f))
                    Prov(Icons.Star, str(R.string.fact_stars), Fmt.count(app.stars), Modifier.weight(1f))
                }
                Row {
                    Prov(Icons.Scale, str(R.string.fact_licence), app.licence ?: str(R.string.licence_unknown), Modifier.weight(1f))
                    Prov(Icons.Box, str(R.string.prov_download), if (app.downloads > 0) "${Fmt.mb(apk.size)} · ${L.wrap(Fmt.downloads(app.downloads))}" else Fmt.mb(apk.size), Modifier.weight(1f))
                }
                val cert = app.certSha256
                val mismatch = ks == KeyStatus.DIFFERS
                val sig = when {
                    other != null -> str(R.string.sig_sibling, other.repo)
                    cert == null -> str(R.string.sig_unrecorded)
                    installed == null -> str(R.string.sig_checked)
                    mismatch -> str(R.string.sig_mismatch)
                    ks == KeyStatus.IGNORED -> str(R.string.sig_ignored)
                    else -> str(R.string.sig_matches)
                }
                Prov(if (mismatch || other != null) Icons.ShieldAlert else Icons.Shield, str(R.string.prov_signing), sig)
                val picked = if (apk.abi in listOf("any", "universal")) str(R.string.file_picked_any) else str(R.string.file_picked_abi, abiLabel(apk.abi))
                Prov(Icons.Chip, picked, apk.name)
                apk.scan?.let { s ->
                    Prov(
                        if (s.flagged > 0) Icons.ShieldAlert else Icons.Shield, str(R.string.prov_virustotal),
                        if (s.flagged > 0) str(R.string.vt_flagged, s.flagged, s.engines) else str(R.string.vt_clean, s.engines),
                    )
                }
            }
            FlowRow(
                Modifier.padding(top = 14.dp).offset(x = (-4).dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val ignoring = acceptable != null && !Installer.busy(app.id)
                if (ignoring || Prefs.acceptedKeys[app.pkg] != null) Pill(
                    str(if (ignoring) R.string.key_ignore else R.string.key_check_on),
                    { if (ignoring) { Prefs.acceptKey(app.pkg, acceptable!!); Installer.jobs.remove(app.id) } else Prefs.forgetKey(app.pkg); Device.tick++ },
                    icon = if (ignoring) Icons.Shield else Icons.ShieldAlert,
                )
                if (browser) Pill("GitHub", { openRepo(ctx, app.repo) }, icon = Icons.Link)
                if (notes != null) Pill(str(R.string.whats_new_short), { showNotes = true })
                if (permissions.isNotEmpty()) Pill(str(R.string.permissions_action), { showPermissions = true })
                if (apk.report != null) Pill(str(R.string.prov_virustotal), { showScan = true }, icon = Icons.Shield)
            }
        }
    }
    older?.let { r -> OlderDialog(app, r, installed) { older = null } }
    notes?.takeIf { showNotes }?.let { InfoDialog(str(R.string.whats_new, app.version), it) { showNotes = false } }
    if (showPermissions) PermissionsDialog(knownPermissions, permissions.size - knownPermissions.size) { showPermissions = false }
    if (showHelp) HelpDialog { showHelp = false }
    if (showScan) ScanDialog(apk) { showScan = false }
}

@Composable
private fun ScanDialog(apk: Apk, onClose: () -> Unit) {
    val closeRequester = remember { FocusRequester() }
    val report = apk.report ?: return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier.width(680.dp).shadow(16.dp, RoundedCornerShape(18.dp))
                .background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp),
        ) {
            Column(Modifier.weight(1f)) {
                T(str(R.string.vt_title), 22.sp, weight = FontWeight.SemiBold, tight = true)
                val s = apk.scan
                val body = if (s == null) str(R.string.vt_body_unscanned, apk.name)
                    else str(R.string.vt_body, apk.name, Fmt.date(s.date), if (s.flagged > 0) str(R.string.vt_flagged, s.flagged, s.engines) else str(R.string.vt_clean, s.engines))
                T(body, 14.sp, Modifier.padding(top = 12.dp), color = C.Ink2)
                Row(Modifier.padding(top = 22.dp)) {
                    Pill(str(R.string.action_close), onClose, solid = true, requester = closeRequester)
                }
            }
            Spacer(Modifier.width(24.dp))
            Qr(report, 200.dp)
            LaunchedEffect(Unit) { runCatching { closeRequester.requestFocus() } }
        }
    }
}

@Composable
fun InfoDialog(title: String, notes: String, width: Dp = 620.dp, onClose: () -> Unit) {
    val closeRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(width).shadow(16.dp, RoundedCornerShape(18.dp))
                .background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp),
        ) {
            T(title, 22.sp, weight = FontWeight.SemiBold, tight = true)
            T(notes, 14.sp, Modifier.padding(top = 12.dp), color = C.Ink2, maxLines = 14)
            Row(Modifier.padding(top = 22.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Pill(str(R.string.action_close), onClose, solid = true, requester = closeRequester)
            }
            LaunchedEffect(Unit) { runCatching { closeRequester.requestFocus() } }
        }
    }
}

@Composable
private fun PermissionsDialog(known: List<String>, others: Int, onClose: () -> Unit) {
    val closeRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(520.dp).heightIn(max = 560.dp).shadow(16.dp, RoundedCornerShape(18.dp))
                .background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp),
        ) {
            T(str(R.string.permissions_title), 22.sp, weight = FontWeight.SemiBold, tight = true)
            Column(
                Modifier.padding(top = 12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                known.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).background(C.Stone, CircleShape))
                        Spacer(Modifier.width(11.dp))
                        T(p, 14.sp, color = C.Ink2)
                    }
                }
                if (others > 0) {
                    T(plural(R.plurals.permissions_more, others, others), 14.sp, Modifier.padding(top = 3.dp), color = C.Stone)
                }
            }
            Row(Modifier.padding(top = 22.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Pill(str(R.string.action_close), onClose, solid = true, requester = closeRequester)
            }
            LaunchedEffect(Unit) { runCatching { closeRequester.requestFocus() } }
        }
    }
}

@Composable
private fun OlderDialog(app: App, release: Release, installed: Installed?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val goRequester = remember { FocusRequester() }
    var info by remember { mutableStateOf<ReleaseInfo?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(release.tag) {
        info = GitHub.release(app.repo, release.tag)
        failed = info == null
    }
    val name = release.tag.removePrefix("v")
    val backwards = installed != null && (installed.versionName == null || !SelfUpdate.newer(name, installed.versionName.removePrefix("v")))
    val target = info?.takeIf { it.apks.isNotEmpty() }?.let { app.copy(version = release.tag, versionName = name, date = release.date, apks = it.apks) }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.width(460.dp).shadow(16.dp, RoundedCornerShape(18.dp))
                .background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp),
        ) {
            T(str(R.string.older_title, release.tag), 22.sp, weight = FontWeight.SemiBold, tight = true)
            val body = when {
                backwards -> str(R.string.older_uninstall_body, app.name, installed?.versionName ?: "?", release.tag)
                failed -> str(R.string.older_failed)
                info == null -> str(R.string.older_checking)
                target == null -> str(R.string.older_no_apk)
                else -> str(R.string.older_install_body, release.tag)
            }
            T(body, 14.5.sp, Modifier.padding(top = 8.dp), color = C.Ink2)
            Row(
                Modifier.padding(top = 22.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(str(R.string.action_cancel), onClose, requester = if (backwards || target == null) goRequester else null)
                if (backwards) {
                    Spacer(Modifier.width(10.dp))
                    Pill(str(R.string.older_uninstall), { uninstall(ctx, app.pkg); onClose() }, icon = Icons.Trash)
                } else if (target != null) {
                    Spacer(Modifier.width(10.dp))
                    Pill(str(R.string.action_install_size, Fmt.mb(pickApk(target).size)), { Installer.start(ctx, target); onClose() },
                        solid = true, icon = Icons.Download, requester = goRequester)
                }
            }
            LaunchedEffect(target != null) { runCatching { goRequester.requestFocus() } }
        }
    }
}

@Composable
private fun ForkLine(fork: App, onClick: () -> Unit) {
    Row(Modifier.padding(top = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Ico(Icons.Up, 15.dp, C.accent)
        Spacer(Modifier.width(7.dp))
        T(str(R.string.fork_line, fork.maker, Fmt.monthYear(fork.date)), 12.sp, Modifier.weight(1f), color = C.Ink2, weight = FontWeight.Medium, maxLines = 2)
        Spacer(Modifier.width(10.dp))
        Pill(str(R.string.view_fork), onClick, solid = false, icon = Icons.ChevRight)
    }
}

@Composable
private fun Prov(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Ico(icon, 14.dp, C.Stone, Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(7.dp))
        Column {
            T(label, 10.5.sp, color = C.Stone, maxLines = 1)
            T(value, 12.sp, weight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun StorageLine(app: App, installed: Installed?) {
    val ctx = LocalContext.current
    val free = remember { Device.freeBytes(ctx) }
    val total = remember { Device.totalBytes(ctx) }
    val need = pickApk(app).size
    Column(Modifier.padding(top = 8.dp)) {
        if (installed != null) {
            T(str(R.string.installed_version, installed.versionName ?: "?"), 11.5.sp, color = C.Stone, maxLines = 1)
        } else {
            T(str(R.string.needs_space, Fmt.mb(need), Fmt.size(free)), 11.5.sp, color = C.Stone, maxLines = 1)
            Row(Modifier.padding(top = 5.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(C.WallDeep)) {
                val used = if (total > 0) (total - free).toFloat() / total else 0f
                Box(Modifier.fillMaxHeight().weight(used.coerceIn(0.01f, 0.98f)).background(C.Stone2))
                Box(Modifier.fillMaxHeight().width(3.dp).background(C.accent))
                Box(Modifier.fillMaxHeight().weight((1f - used).coerceIn(0.01f, 0.98f)))
            }
        }
    }
}

@Composable
private fun SearchScreen(query: String, onQuery: (String) -> Unit, openApp: (String) -> Unit, handoff: FocusHandoff) {
    var editing by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) {
        if (editing) {
            delay(40)
            runCatching { field.requestFocus() }
            keyboard?.show()
        }
    }
    val finish = {
        keyboard?.hide()
        editing = false
        runCatching { handoff.target.requestFocus() }
    }
    val focus = LocalFocusManager.current
    val side = Modifier.padding(horizontal = 48.dp)
    val ctx = LocalContext.current
    val voice = remember {
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, str(R.string.voice_prompt))
            .takeIf { ctx.packageManager.queryIntentActivities(it, 0).isNotEmpty() }
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { onQuery(it) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(side, verticalAlignment = Alignment.CenterVertically) {
        Focusable(onClick = { editing = true }, modifier = Modifier.weight(1f), requester = handoff.target) { f ->
            val active = f || editing
            Row(
                Modifier.padding(top = 4.dp).fillMaxWidth().height(58.dp)
                    .lift(active, 1.015f, RoundedCornerShape(16.dp), ring = 16.dp)
                    .background(C.Mat, RoundedCornerShape(16.dp)).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ico(Icons.Search, 22.dp, if (active) C.accent else C.Stone)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = Geist, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = C.Ink),
                        cursorBrush = SolidColor(C.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { finish() }, onDone = { finish() }),
                        modifier = Modifier.fillMaxWidth()
                            .focusRequester(field)
                            .focusProperties { canFocus = editing }
                            .onFocusChanged { if (!it.isFocused && editing) editing = false }
                            .onPreviewKeyEvent { e ->
                                if (!editing || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.Back -> { finish(); true }
                                    Key.DirectionDown, Key.DirectionUp -> {
                                        finish()
                                        focus.moveFocus(if (e.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up)
                                        true
                                    }
                                    else -> false
                                }
                            },
                    )
                    if (query.isEmpty()) {
                        T(str(if (editing) R.string.search_hint_editing else R.string.search_hint), 22.sp, color = C.Stone2, tight = true, maxLines = 1)
                    }
                }
                if (f && !editing) {
                    Box(Modifier.background(C.accent, CircleShape).padding(horizontal = 11.dp, vertical = 4.dp)) {
                        T(str(if (query.isEmpty()) R.string.search_press_ok else R.string.search_ok_edit), 11.5.sp, color = C.OnAccent, weight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (voice != null) {
            Spacer(Modifier.width(10.dp))
            Pill(str(R.string.voice_action), { runCatching { speech.launch(voice) } }, icon = Icons.Mic)
        }
        }
        Row(side.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            T(str(R.string.search_try), 12.5.sp, Modifier.padding(end = 4.dp), color = C.Stone)
            val chips = listOf("video player", "game streaming", "launcher", "web browser", "music", "screensaver")
            chips.forEach { s -> Chip(s, s == query.trim()) { onQuery(s) } }
            if (query.isNotEmpty()) Chip(str(R.string.search_clear), false) { onQuery("") }
        }
        val q = query.trim().lowercase()
        val results = remember(q, Catalog.apps, Prefs.hideStreams) { search(q) }
        val shown = if (q.isEmpty()) Catalog.visible.take(20).map { it to true } else results
        T(
            when {
                q.isEmpty() -> str(R.string.row_starred)
                results.isEmpty() -> str(R.string.search_none, query.trim())
                else -> plural(R.plurals.search_results, results.size, results.size, query.trim())
            },
            13.5.sp, side.padding(top = 20.dp), color = C.Stone, weight = FontWeight.Medium,
        )
        val repo = remember(query) { Direct.repoOf(query) }
        if (repo != null) {
            val scope = rememberCoroutineScope()
            var looking by remember(repo) { mutableStateOf(false) }
            var failed by remember(repo) { mutableStateOf(false) }
            Row(side.padding(top = 10.dp).offset(x = (-4).dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(str(if (looking) R.string.direct_looking else R.string.direct_lookup, repo), {
                    if (!looking) {
                        looking = true
                        failed = false
                        scope.launch {
                            val app = Direct.lookup(repo)
                            looking = false
                            if (app != null) openApp(app.id) else failed = true
                        }
                    }
                }, solid = true, icon = Icons.Link)
                if (failed) T(if (GitHub.limited) GitHub.limitMessage() else str(R.string.direct_failed), 13.5.sp, Modifier.padding(start = 10.dp), color = C.Stone)
            }
        } else if (q.isNotEmpty() && results.isEmpty()) {
            T(str(R.string.search_tip), 15.5.sp, side.padding(top = 10.dp), color = C.Stone)
        }
        LazyRow(
            Modifier.padding(top = 2.dp),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(shown.take(40), key = { it.first.id }) { (a, byName) -> ResultCard(a, q, byName) { openApp(a.id) } }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Focusable(onClick) { f ->
        Box(
            Modifier.lift(f, 1.08f, CircleShape, rest = 0.dp).height(28.dp)
                .background(if (f) C.accent else if (selected) C.Ink else C.Wall, CircleShape)
                .border(1.dp, if (f || selected) Color.Transparent else C.Hair2, CircleShape).padding(horizontal = 13.dp),
            contentAlignment = Alignment.Center,
        ) { T(text, 12.sp, color = if (f) C.OnAccent else if (selected) C.OnInk else C.Ink2, weight = FontWeight.Medium) }
    }
}

private fun search(q: String): List<Pair<App, Boolean>> {
    if (q.isEmpty()) return emptyList()
    if (q.matches(Regex("(\\d{5}[\\s,.]*)+"))) return Regex("\\d{5}").findAll(q).mapNotNull { Catalog.byCode(it.value) }.distinct().map { it to true }.toList()
    val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return Catalog.apps.filter { Catalog.shown(it) }.mapNotNull { a ->
        val hay = "${a.name} ${a.note} ${a.desc} ${a.room} ${a.repo}".lowercase()
        val byName = a.name.lowercase().contains(q)
        val all = words.all { w -> hay.contains(w) || (w.endsWith("s") && hay.contains(w.dropLast(1))) }
        when {
            byName && (a.stars >= Prefs.minStars || a.name.lowercase() == q) -> a to true
            all && a.stars >= Prefs.minStars -> a to false
            else -> null
        }
    }.sortedWith(compareByDescending<Pair<App, Boolean>> { it.second }.thenByDescending { it.first.stars })
}

@Composable
private fun ResultCard(a: App, q: String, byName: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val tick = Device.tick
    val installed = remember(a.id, tick) { Device.installedAs(ctx, a) != null }
    Focusable(onClick) { f ->
        Column(Modifier.width(224.dp)) {
            Box(Modifier.size(224.dp, 126.dp).lift(f, ring = 12.dp, shape = RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))) {
                ArtImage(a.art.wall ?: a.art.tile, a, 480)
            }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                T(a.name, 15.5.sp, weight = FontWeight.SemiBold, tight = true, maxLines = 1)
                if (installed) { Spacer(Modifier.width(6.dp)); Dot() }
            }
            val field = listOf(a.note, a.desc).firstOrNull { q.isNotEmpty() && it.lowercase().contains(q) }
            val why = if (byName || field == null) buildAnnotatedString { append(a.note) } else buildAnnotatedString {
                val i = field.lowercase().indexOf(q)
                append(str(R.string.search_matches) + " ")
                append(field.substring(0, i))
                withStyle(SpanStyle(background = C.accent.copy(alpha = 0.18f), color = C.Ink)) { append(field.substring(i, i + q.length)) }
                append(field.substring(i + q.length))
            }
            T(why, 11.5.sp, Modifier.padding(top = 3.dp), color = C.Stone, maxLines = 2)
        }
    }
}

private enum class State { Busy, Held, Ignored, Update, Ok }

private data class LocalApp(val pkg: String, val label: String, val version: String?, val icon: ImageBitmap?)

private fun otherApps(ctx: Context, exclude: Set<String>): List<LocalApp> {
    val pm = ctx.packageManager
    return pm.getInstalledApplications(0)
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != ctx.packageName && it.packageName !in exclude }
        .filter { pm.getLeanbackLaunchIntentForPackage(it.packageName) != null || pm.getLaunchIntentForPackage(it.packageName) != null }
        .map { ai ->
            val icon = runCatching {
                val d = pm.getApplicationIcon(ai)
                val bm = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 96, 96)
                d.draw(android.graphics.Canvas(bm))
                bm.asImageBitmap()
            }.getOrNull()
            LocalApp(ai.packageName, pm.getApplicationLabel(ai).toString(), runCatching { pm.getPackageInfo(ai.packageName, 0).versionName }.getOrNull(), icon)
        }
        .sortedBy { it.label.lowercase() }
}

private fun plainName(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

private fun vendor(pkg: String): String {
    val parts = pkg.split('.')
    return parts.take(if (parts.size > 2 && parts[1] in setOf("github", "gitlab", "codeberg")) 3 else 2).joinToString(".")
}

internal fun listedAs(label: String, pkg: String, apps: List<App>): App? =
    plainName(label).takeIf { it.length >= 3 }?.let { name ->
        apps.filter { plainName(it.name) == name && vendor(it.pkg) == vendor(pkg) }.maxByOrNull { it.stars }
    }

internal enum class AppSort { Default, Name, Date }

internal fun sortApps(mine: List<Pair<App, Installed>>, sort: AppSort): List<Pair<App, Installed>> = when (sort) {
    AppSort.Default -> mine
    AppSort.Name -> mine.sortedBy { it.first.name.lowercase() }
    AppSort.Date -> mine.sortedByDescending { it.first.date }
}

private fun shelfApps(ctx: Context): List<Pair<App, Installed>> {
    val own = SelfUpdate.app?.takeIf { SelfUpdate.ready(ctx) }?.let { listOf(it to Device.installed(ctx, it.pkg)!!) }.orEmpty()
    return own + Catalog.apps.groupBy { it.pkg }.mapNotNull { (pkg, list) ->
        Device.installed(ctx, pkg)?.let { i -> (list.firstOrNull { it.certSha256 != null && it.certSha256 in i.certs } ?: list.firstOrNull { it.forkOf == null } ?: list.first()) to i }
            ?.takeIf { (a, i) -> !(i.system && a.certSha256 != null && a.certSha256 !in i.certs) }
    }
}

private fun keyOf(a: App, i: Installed) = keyStatus(a.certSha256, i.certs, Prefs.acceptedKeys[a.pkg])

private fun updateCount(ctx: Context): Int = shelfApps(ctx).count { (a, i) ->
    i.versionCode < a.versionCode && keyOf(a, i) == KeyStatus.MATCHES
}

@Composable
private fun MyApps(openApp: (String) -> Unit, goDiscover: () -> Unit, handoff: FocusHandoff) {
    val ctx = LocalContext.current
    val tick = Device.tick
    val self = SelfUpdate.app
    var manualApps by remember { mutableStateOf<List<App>>(emptyList()) }
    LaunchedEffect(tick, ManualLinks.links) {
        manualApps = if (ManualLinks.links.isEmpty()) emptyList()
        else withContext(Dispatchers.IO) { ManualLinks.apps(ctx) }
    }
    val linked = ManualLinks.links.map { it.pkg }.toSet()
    val mine = remember(tick, self, manualApps, ManualLinks.links) {
        val manual = manualApps.mapNotNull { a -> Device.installed(ctx, a.pkg)?.let { a to it } }
        manual + shelfApps(ctx).filterNot { it.first.pkg in linked }
    }
    val sortedMine = remember(mine, Prefs.appSort) {
        sortApps(mine, AppSort.entries[Prefs.appSort])
    }
    var others by remember { mutableStateOf<List<LocalApp>?>(null) }
    LaunchedEffect(tick, mine) { others = withContext(Dispatchers.IO) { otherApps(ctx, mine.map { it.first.pkg }.toSet()) } }
    var linking by remember { mutableStateOf<Pair<String, String>?>(null) }
    var digest by remember { mutableStateOf<List<App>?>(null) }
    fun state(a: App, i: Installed): State = when {
        Installer.busy(a.id) -> State.Busy
        Installer.jobs[a.id] is Job.Held || keyOf(a, i) == KeyStatus.DIFFERS -> State.Held
        keyOf(a, i) == KeyStatus.IGNORED -> State.Ignored
        i.versionCode < a.versionCode -> State.Update
        else -> State.Ok
    }
    val updatable = mine.filter { (a, i) -> state(a, i) == State.Update }
    val held = mine.count { (a, i) -> state(a, i) == State.Held }
    val otherList = others.orEmpty()
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                T(str(R.string.mine_title), 32.sp, weight = FontWeight.SemiBold, tight = true)
                val parts = buildList {
                    if (updatable.isNotEmpty()) add(plural(R.plurals.updates_ready, updatable.size, updatable.size))
                    if (held > 0) add(str(R.string.held_count, held))
                    add(str(R.string.from_shelf_count, mine.count { it.first != self }))
                    if (others != null) add(plural(R.plurals.other_apps, otherList.size, otherList.size))
                }
                T(parts.joinToString(" · ", transform = L::wrap), 13.5.sp, Modifier.padding(top = 4.dp), color = C.Stone)
            }
            val sortText = when (AppSort.entries[Prefs.appSort]) {
                AppSort.Default -> str(R.string.sort_default)
                AppSort.Name -> str(R.string.sort_name)
                AppSort.Date -> str(R.string.sort_date)
            }
            val week = remember(mine) { mine.map { it.first }.filter { a -> runCatching { !java.time.LocalDate.parse(a.date).isBefore(java.time.LocalDate.now().minusDays(7)) }.getOrDefault(false) } }
            if (week.isNotEmpty()) {
                Pill(str(R.string.digest_action, week.size), { digest = week })
                Spacer(Modifier.width(12.dp))
            }
            Pill(sortText, { Prefs.nextAppSort() })
            if (updatable.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Pill(str(R.string.update_all), { updatable.filter { it.first != self }.ifEmpty { updatable }.forEach { (a, _) -> Installer.start(ctx, a) } }, solid = true, icon = Icons.Download, requester = handoff.target)
            }
        }
        LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)) {
            item { Section(str(R.string.section_from_shelf)) }
            if (mine.isEmpty()) {
                item {
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(str(R.string.mine_empty), 13.5.sp, Modifier.padding(end = 14.dp), color = C.Ink2)
                        Pill(str(R.string.find_app), goDiscover, requester = if (updatable.isEmpty()) handoff.target else null)
                    }
                }
            }
            items(sortedMine, key = { it.first.id }) { (a, i) ->
                val st = state(a, i)
                val first = updatable.isEmpty() && a == sortedMine.first().first
                AppRow(
                    focusRequester = if (first) handoff.target else null,
                    onClick = {
                        if (st == State.Update) Installer.start(ctx, a)
                        else openApp(a.id)
                    },
                    icon = { AppIcon(a, 38.dp) },
                    title = a.name,
                    sub = when (st) {
                        State.Update -> "\u2066${i.versionName} → ${a.version}\u2069 · ${Fmt.mb(pickApk(a).size)}"
                        State.Held -> str(R.string.held_sub, i.versionName ?: "?")
                        else -> i.versionName ?: ""
                    },
                    action = if (ManualLinks.has(a.pkg)) {
                        { ActionPill(str(R.string.link_unlink)) { ManualLinks.remove(ctx, a.pkg) } }
                    } else null,
                ) { f ->
                    when (st) {
                        State.Busy -> {
                            val job = Installer.jobs[a.id]
                            val frac = (job as? Job.Downloading)?.fraction ?: 0.9f
                            Ring(frac, C.accent, C.Hair2)
                            T("  " + if (job is Job.Downloading) str(R.string.job_downloading, (frac * 100).toInt()) else str(R.string.job_installing), 12.5.sp, color = C.Ink2, weight = FontWeight.Medium)
                        }
                        State.Update -> { Ico(Icons.Up, 17.dp, C.accent); T("  " + str(R.string.status_update_ready), 12.5.sp, color = C.Ink2, weight = FontWeight.Medium); MiniPill(str(R.string.action_update), f) }
                        State.Held -> { Ico(Icons.ShieldAlert, 17.dp, C.Ink); T("  " + str(R.string.update_held), 12.5.sp, weight = FontWeight.SemiBold); MiniPill(str(R.string.review), f) }
                        State.Ignored -> { Ico(Icons.Shield, 17.dp, C.Stone); T("  " + str(R.string.status_key_ignored), 12.5.sp, color = C.Stone); MiniPill(str(R.string.details), f) }
                        State.Ok -> {
                            Ico(Icons.Check, 17.dp, C.Stone); T("  " + str(R.string.up_to_date), 12.5.sp, color = C.Stone)
                            MiniPill(str(R.string.details), f)
                        }
                    }
                }
            }
            if (otherList.isNotEmpty()) {
                item { Section(str(R.string.section_other)) }
                items(otherList, key = { it.pkg }) { o ->
                    val listed = remember(o.pkg, Catalog.apps) { listedAs(o.label, o.pkg, Catalog.apps) }
                    AppRow(
                        focusRequester = null,
                        onClick = { launch(ctx, o.pkg) },
                        icon = {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(C.WallDeep), contentAlignment = Alignment.Center) {
                                if (o.icon != null) Image(o.icon, null, Modifier.fillMaxSize()) else T(o.label.take(1), 16.sp, color = C.Stone)
                            }
                        },
                        title = o.label,
                        sub = listOfNotNull(o.version, str(if (listed != null) R.string.other_listed else R.string.not_in_list)).joinToString(" · ", transform = L::wrap),
                        action = {
                            if (listed != null) ActionPill(str(R.string.other_view)) { openApp(listed.id) }
                            else ActionPill(str(R.string.link_action)) { linking = o.pkg to o.label }
                            ActionPill(str(R.string.uninstall)) { uninstall(ctx, o.pkg) }
                        },
                    ) { f ->
                        MiniPill(str(R.string.action_open), f)
                    }
                }
            }
        }
    }
    digest?.let { DigestDialog(it, openApp) { digest = null } }
    linking?.let { (pkg, label) ->
        LinkDialog(
            pkg = pkg,
            label = label,
            onDismiss = { linking = null },
            onLinked = { linking = null },
        )
    }
}

@Composable
private fun DigestDialog(apps: List<App>, openApp: (String) -> Unit, onClose: () -> Unit) {
    val closeRequester = remember { FocusRequester() }
    val notes = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    LaunchedEffect(apps) {
        apps.forEach { a -> GitHub.release(a.repo, a.version)?.notes?.takeIf { it.isNotBlank() }?.let { notes[a.id] = it } }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(680.dp).heightIn(max = 600.dp).shadow(16.dp, RoundedCornerShape(18.dp))
                .background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp),
        ) {
            T(str(R.string.digest_title), 22.sp, weight = FontWeight.SemiBold, tight = true)
            Column(Modifier.padding(top = 12.dp).weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                apps.sortedByDescending { it.date }.forEach { a ->
                    Focusable({ onClose(); openApp(a.id) }) { f ->
                        Row(
                            Modifier.fillMaxWidth().lift(f, 1.01f, RoundedCornerShape(12.dp), rest = 0.dp, ring = 12.dp)
                                .background(if (f) C.WallDeep else Color.Transparent, RoundedCornerShape(12.dp)).padding(12.dp),
                        ) {
                            AppIcon(a, 34.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                T("${a.name}  ${L.wrap(a.version)}", 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
                                T(Fmt.date(a.date), 11.5.sp, color = C.Stone)
                                T(notes[a.id] ?: str(R.string.digest_no_notes), 13.sp, Modifier.padding(top = 4.dp), color = C.Ink2, maxLines = 4)
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Pill(str(R.string.action_close), onClose, solid = true, requester = closeRequester)
            }
            LaunchedEffect(Unit) { runCatching { closeRequester.requestFocus() } }
        }
    }
}

@Composable
private fun LinkDialog(pkg: String, label: String, onDismiss: () -> Unit, onLinked: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var repoInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fieldRequester = remember { FocusRequester() }
    val confirmRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier.width(480.dp).shadow(16.dp, RoundedCornerShape(18.dp))
                .background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp),
        ) {
            T(str(R.string.link_title), 22.sp, weight = FontWeight.SemiBold, tight = true)
            T(label, 13.5.sp, Modifier.padding(top = 4.dp), color = C.Stone)
            Box(
                Modifier.padding(top = 16.dp).fillMaxWidth().height(50.dp)
                    .clip(RoundedCornerShape(12.dp)).background(C.WallDeep)
                    .border(1.dp, C.Hair2, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = repoInput,
                    onValueChange = { error = null; repoInput = it },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Geist, fontSize = 16.sp, color = C.Ink),
                    cursorBrush = SolidColor(C.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { runCatching { confirmRequester.requestFocus() } }),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldRequester).leaveDown(confirmRequester),
                )
                if (repoInput.isEmpty()) T(str(R.string.link_hint), 16.sp, color = C.Stone2)
            }
            error?.let { T(it, 12.5.sp, Modifier.padding(top = 8.dp), color = Color(0xFFC23B2E)) }
            Row(
                Modifier.padding(top = 20.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(str(R.string.link_cancel), { if (!busy) onDismiss() })
                Spacer(Modifier.width(10.dp))
                Pill(
                    if (busy) str(R.string.link_working) else str(R.string.link_confirm),
                    {
                        if (!busy && repoInput.isNotBlank()) {
                            busy = true; error = null
                            scope.launch {
                                when (val result = ManualLinks.link(ctx, repoInput, pkg)) {
                                    is LinkResult.Linked -> onLinked()
                                    is LinkResult.Error -> { error = result.message; busy = false }
                                }
                            }
                        }
                    },
                    solid = true, icon = if (busy) null else Icons.Download, requester = confirmRequester,
                )
            }
            LaunchedEffect(Unit) { runCatching { fieldRequester.requestFocus() } }
        }
    }
}

@Composable
private fun Section(title: String) {
    T(title, 12.5.sp, Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp), color = C.Stone, weight = FontWeight.SemiBold)
}

@Composable
fun AppRow(
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    title: String,
    sub: String,
    action: (@Composable () -> Unit)? = null,
    trailing: @Composable (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Focusable(onClick = onClick, modifier = Modifier.weight(1f), requester = focusRequester) { f ->
            Row(
                Modifier.fillMaxWidth().height(56.dp).lift(f, 1.015f, RoundedCornerShape(12.dp), rest = 0.dp, ring = 12.dp)
                    .background(if (f) C.Mat else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon()
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    T(title, 15.5.sp, weight = FontWeight.SemiBold, tight = true, maxLines = 1)
                    T(sub, 11.5.sp, color = C.Stone, maxLines = 1)
                }
                trailing(f)
            }
        }
        action?.invoke()
    }
}

@Composable
fun MiniPill(text: String, focused: Boolean) {
    Spacer(Modifier.width(14.dp))
    Box(
        Modifier.height(28.dp).background(if (focused) C.accent else Color.Transparent, CircleShape)
            .border(1.dp, if (focused) Color.Transparent else C.Hair2, CircleShape).padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) { T(text, 12.5.sp, color = if (focused) C.OnAccent else C.Ink, weight = FontWeight.SemiBold) }
}

@Composable
private fun ActionPill(text: String, onClick: () -> Unit) {
    Spacer(Modifier.width(14.dp))
    Focusable(onClick = onClick) { focused ->
        Box(
            Modifier.height(28.dp).background(if (focused) C.accent else Color.Transparent, CircleShape)
                .border(1.dp, if (focused) Color.Transparent else C.Hair2, CircleShape).padding(horizontal = 13.dp),
            contentAlignment = Alignment.Center,
        ) { T(text, 12.5.sp, color = if (focused) C.OnAccent else C.Ink, weight = FontWeight.SemiBold) }
    }
}

private class Setting(val title: String, val value: String, val help: String, val action: (() -> Unit)? = null)

@Composable
private fun SettingsScreen(openApp: (String) -> Unit, handoff: FocusHandoff) {
    val ctx = LocalContext.current
    val tick = Device.tick
    var cacheTick by remember { mutableIntStateOf(0) }
    val canInstall = remember(tick) { Installer.canInstall(ctx) }
    val cache = remember(cacheTick, tick) { Device.cacheBytes(ctx) }
    val version = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
    var showDiscord by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var showPhone by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    val backup = remember(tick) { backupApps(ctx) }
    val rows = listOf(
        Setting(str(R.string.set_accent), str(Prefs.accents[Prefs.accent].first),
            str(R.string.set_accent_help)) { Prefs.nextAccent() },
        Setting(str(R.string.set_appearance), str(Prefs.themes[Prefs.theme]),
            str(R.string.set_appearance_help)) { Prefs.nextTheme() },
        Setting(str(R.string.set_language), L.name(Prefs.languages[Prefs.language]),
            str(R.string.set_language_help)) { Prefs.nextLanguage() },
        Setting(str(R.string.set_install), str(if (canInstall) R.string.allowed else R.string.not_allowed),
            str(R.string.set_install_help)) {
            Installer.openInstallPermission(ctx)
        },
        Setting(str(R.string.set_apk), str(R.string.apk_auto, abiLabel(Build.SUPPORTED_ABIS.first())),
            str(R.string.set_apk_help, Build.SUPPORTED_ABIS.joinToString(", "))),
        Setting(str(R.string.set_key), str(R.string.set_key_value),
            str(R.string.set_key_help)),
        Setting(str(R.string.set_pin), str(if (Lock.on) R.string.on else R.string.off),
            str(R.string.set_pin_help)) { showPin = true },
        Setting(str(R.string.set_self_update), str(if (Prefs.autoCheckSelf) R.string.on else R.string.off),
            str(R.string.set_self_update_help)) { Prefs.toggleAutoCheck() },
        Setting(str(R.string.set_catalog), str(R.string.catalog_value, Fmt.count(Catalog.apps.size), Fmt.shortDate(Catalog.generated)),
            str(R.string.catalog_help, Fmt.date(Catalog.generated))),
        Setting(str(R.string.set_stars), if (Prefs.minStars > 0) "${Prefs.minStars}" else str(R.string.off),
            str(R.string.set_stars_help, Fmt.count(Catalog.apps.count { it.stars < Prefs.minStars }))) { Prefs.nextStars() },
        Setting(str(R.string.set_streams), if (Prefs.hideStreams) str(R.string.streams_on, Fmt.count(Catalog.apps.count { it.streams })) else str(R.string.streams_off),
            str(R.string.set_streams_help)) { Prefs.toggleStreams() },
        Setting(str(R.string.set_cache), str(R.string.cache_value, Fmt.mb(cache)),
            str(R.string.set_cache_help)) {
            Device.clearCache(ctx); cacheTick++
        },
        Setting(str(R.string.set_phone), str(R.string.phone_value),
            str(R.string.phone_help)) { showPhone = true },
        Setting(str(R.string.set_github), str(if (Prefs.githubToken.isNotEmpty()) R.string.github_on else R.string.github_off),
            str(R.string.set_github_help)) { if (Prefs.githubToken.isNotEmpty()) Prefs.saveGithubToken("") else showPhone = true },
        Setting(str(R.string.set_backup), plural(R.plurals.apps, backup.size, backup.size),
            str(R.string.backup_help)) { showBackup = true },
        Setting(str(R.string.set_restore), str(R.string.restore_value),
            str(R.string.restore_help)) { showRestore = true },
        Setting(str(R.string.set_discord), str(R.string.discord_value),
            str(R.string.discord_help)) { showDiscord = true },
        Setting(str(R.string.set_report), Device.model,
            str(R.string.report_help)) { showReport = true },
        Setting(str(R.string.set_about), str(R.string.about_value, version ?: ""),
            str(R.string.about_help)),
    )
    var focusedIndex by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp)) {
        Column(Modifier.width(320.dp)) {
            val s = rows[focusedIndex]
            Column(
                Modifier.size(320.dp, 340.dp).shadow(6.dp, RoundedCornerShape(16.dp)).background(C.Mat, RoundedCornerShape(16.dp)).padding(28.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Image(painterResource(C.mark), null, Modifier.size(44.dp))
                Column {
                    T(s.title, 22.sp, weight = FontWeight.SemiBold, tight = true)
                    T(s.help, 14.5.sp, Modifier.padding(top = 10.dp), color = C.Ink2)
                }
            }
            T(str(R.string.settings_footer, version ?: "", Fmt.count(Catalog.apps.size)), 12.5.sp, Modifier.padding(top = 14.dp), color = C.Stone)
        }
        Spacer(Modifier.width(52.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEachIndexed { i, s ->
                Focusable(s.action ?: {}, requester = if (i == 0) handoff.target else null, onFocus = { focusedIndex = i }) { f ->
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).lift(f, 1.02f, RoundedCornerShape(12.dp), rest = 0.dp, ring = 12.dp)
                            .background(if (f) C.Mat else Color.Transparent, RoundedCornerShape(12.dp)).padding(horizontal = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        T(s.title, 14.5.sp, Modifier.weight(1f), weight = FontWeight.Medium, maxLines = 1)
                        if (i == 0) { Dot(9.dp); Spacer(Modifier.width(7.dp)) }
                        T(s.value, 13.5.sp, color = if (f) C.Ink2 else C.Stone, maxLines = 1)
                        if (s.action != null) Ico(Icons.ChevRight, 13.dp, if (f) C.accent else C.Stone, Modifier.padding(start = 6.dp))
                    }
                }
                if (i == 7 || i == 10) Box(Modifier.padding(horizontal = 15.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(C.Hair))
            }
        }
    }
    if (showDiscord) DiscordDialog { showDiscord = false }
    if (showReport) ReportDialog { showReport = false }
    if (showPin) PinSetup { showPin = false }
    if (showBackup) BackupDialog(backup) { showBackup = false }
    if (showPhone) PhoneDialog { showPhone = false }
    if (showRestore) RestoreDialog(openApp) { showRestore = false }
}

@Composable
private fun PhoneDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val closeRequester = remember { FocusRequester() }
    val url = remember { Companion.start(ctx) }
    val close = { Companion.stop(); onClose() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Companion.stop() } }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier.width(760.dp).shadow(16.dp, RoundedCornerShape(18.dp))
                .background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp),
        ) {
            Column(Modifier.weight(1f)) {
                T(str(R.string.phone_title), 22.sp, weight = FontWeight.SemiBold, tight = true)
                T(str(if (url != null) R.string.phone_body else R.string.phone_offline), 14.5.sp, Modifier.padding(top = 8.dp), color = C.Ink2)
                if (url != null) T(url.substringBefore("?"), 16.sp, Modifier.padding(top = 12.dp), weight = FontWeight.SemiBold)
                val asked = Companion.asked.distinct().mapNotNull { Companion.byId(it) }.takeLast(4)
                if (asked.isNotEmpty()) {
                    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        asked.forEach { a ->
                            val job = Installer.jobs[a.id]
                            val line = when (job) {
                                is Job.Downloading -> str(R.string.job_downloading, (job.fraction * 100).toInt())
                                Job.Checking, Job.Installing -> str(R.string.job_installing)
                                is Job.Failed -> job.message
                                else -> if (Device.installedAs(ctx, a) != null) str(R.string.status_installed) else ""
                            }
                            T(if (line.isEmpty()) a.name else "${a.name}  ·  $line", 13.sp, color = C.Stone, maxLines = 1)
                        }
                    }
                }
                Row(Modifier.padding(top = 22.dp)) {
                    Pill(str(R.string.action_close), close, solid = true, requester = closeRequester)
                }
            }
            if (url != null) {
                Spacer(Modifier.width(24.dp))
                Qr(url, 220.dp)
            }
            LaunchedEffect(Unit) { runCatching { closeRequester.requestFocus() } }
        }
    }
}

private fun backupApps(ctx: Context): List<App> =
    shelfApps(ctx).map { it.first }.filter { it.code != null && it.pkg != ctx.packageName }.sortedBy { it.name.lowercase() }

@Composable
private fun BackupDialog(apps: List<App>, onClose: () -> Unit) {
    val closeRequester = remember { FocusRequester() }
    val codes = apps.mapNotNull { it.code }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier.width(760.dp).shadow(16.dp, RoundedCornerShape(18.dp))
                .background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp),
        ) {
            Column(Modifier.weight(1f)) {
                T(str(R.string.backup_title), 22.sp, weight = FontWeight.SemiBold, tight = true)
                if (codes.isEmpty()) {
                    T(str(R.string.backup_empty), 14.5.sp, Modifier.padding(top = 8.dp), color = C.Ink2)
                } else {
                    T(str(R.string.backup_body), 14.5.sp, Modifier.padding(top = 8.dp), color = C.Ink2)
                    T(codes.joinToString("  "), 20.sp, Modifier.padding(top = 14.dp), weight = FontWeight.SemiBold, maxLines = 6)
                    T(apps.joinToString(", ") { it.name }, 12.5.sp, Modifier.padding(top = 10.dp), color = C.Stone, maxLines = 3)
                }
                Row(Modifier.padding(top = 22.dp)) {
                    Pill(str(R.string.action_close), onClose, solid = true, requester = closeRequester)
                }
            }
            if (codes.isNotEmpty()) {
                Spacer(Modifier.width(24.dp))
                Qr("https://wingedpanther.github.io/openshelf/#restore=" + codes.joinToString("."), 220.dp)
            }
            LaunchedEffect(Unit) { runCatching { closeRequester.requestFocus() } }
        }
    }
}

private fun Modifier.leaveDown(target: FocusRequester) = onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { runCatching { target.requestFocus() }; true } else false
}

@Composable
private fun RestoreDialog(openApp: (String) -> Unit, onClose: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<App>?>(null) }
    val field = remember { FocusRequester() }
    val go = remember { FocusRequester() }
    val list = found
    if (list != null) {
        AppListDialog(str(R.string.restore_title), plural(R.plurals.restore_found, list.size, list.size), list, openApp, onClose)
        return
    }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.width(520.dp).shadow(16.dp, RoundedCornerShape(18.dp))
                .background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp),
        ) {
            T(str(R.string.restore_title), 22.sp, weight = FontWeight.SemiBold, tight = true)
            T(str(R.string.restore_body), 14.sp, Modifier.padding(top = 6.dp), color = C.Ink2)
            Box(
                Modifier.padding(top = 16.dp).fillMaxWidth().height(50.dp)
                    .clip(RoundedCornerShape(12.dp)).background(C.WallDeep)
                    .border(1.dp, C.Hair2, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it.filter { c -> c.isDigit() || c == ' ' } },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Geist, fontSize = 18.sp, color = C.Ink),
                    cursorBrush = SolidColor(C.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { runCatching { go.requestFocus() } }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field).leaveDown(go),
                )
                if (input.isEmpty()) T("48213 99120", 18.sp, color = C.Stone2)
            }
            val codes = Regex("\\d{5}").findAll(input).map { it.value }.toList()
            Row(Modifier.padding(top = 20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Pill(str(R.string.action_cancel), onClose)
                Spacer(Modifier.width(10.dp))
                Pill(str(R.string.restore_show, codes.size), { found = codes.mapNotNull { Catalog.byCode(it) }.distinct() }, solid = true, requester = go)
            }
            LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
        }
    }
}

@Composable
private fun PinSetup(onDone: () -> Unit) {
    val ctx = LocalContext.current
    if (Lock.on) {
        PinDialog(str(R.string.pin_turn_off), onClose = onDone) { Lock.clearPin(ctx); onDone() }
        return
    }
    var first by remember { mutableStateOf<String?>(null) }
    PinDialog(
        str(if (first == null) R.string.pin_new else R.string.pin_again),
        onEntered = { pin ->
            when (val f = first) {
                null -> { first = pin; null }
                pin -> { Lock.setPin(ctx, pin); onDone(); null }
                else -> { first = null; str(R.string.pin_mismatch) }
            }
        },
        onClose = onDone,
    ) {}
}

@Composable
fun HelpDialog(onClose: () -> Unit) {
    val closeRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier.width(760.dp).shadow(16.dp, RoundedCornerShape(18.dp))
                .background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp),
        ) {
            Column(Modifier.weight(1f)) {
                T(str(R.string.help_title), 22.sp, weight = FontWeight.SemiBold, tight = true)
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(R.string.help_step1, R.string.help_step2, R.string.help_step3).forEachIndexed { i, step ->
                        Row {
                            T("${i + 1}", 14.sp, Modifier.width(20.dp), color = C.accent, weight = FontWeight.SemiBold)
                            T(str(step), 14.sp, color = C.Ink2)
                        }
                    }
                    if (Help.brand == "amazon") T(str(R.string.help_fire), 14.sp, color = C.Ink2)
                    if (Help.operator) T(str(R.string.help_operator), 14.sp, color = C.Ink2)
                }
                Row(Modifier.padding(top = 22.dp)) {
                    Pill(str(R.string.action_close), onClose, solid = true, requester = closeRequester)
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Qr(Help.url, 200.dp)
                T(str(R.string.help_more), 12.sp, Modifier.padding(top = 8.dp).width(200.dp), color = C.Stone)
            }
            LaunchedEffect(Unit) { runCatching { closeRequester.requestFocus() } }
        }
    }
}

@Composable
private fun ReportDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val closeRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier.width(680.dp).shadow(16.dp, RoundedCornerShape(18.dp))
                .background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp),
        ) {
            Column(Modifier.weight(1f)) {
                T(str(R.string.report_title), 22.sp, weight = FontWeight.SemiBold, tight = true)
                T(str(R.string.report_body), 14.5.sp, Modifier.padding(top = 8.dp), color = C.Ink2)
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Fact(str(R.string.report_tv), Device.model, 96.dp)
                    Fact("Android", Device.android, 96.dp)
                    Fact(str(R.string.report_cpu), Device.cpu, 96.dp)
                    Fact("Open Shelf", Device.ownVersion(ctx), 96.dp)
                }
                Row(Modifier.padding(top = 22.dp)) {
                    Pill(str(R.string.action_close), onClose, solid = true, requester = closeRequester)
                }
            }
            Spacer(Modifier.width(24.dp))
            Qr(Device.reportUrl(ctx), 240.dp)
            LaunchedEffect(Unit) { runCatching { closeRequester.requestFocus() } }
        }
    }
}

@Composable
private fun DiscordDialog(onClose: () -> Unit) {
    val closeRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.width(440.dp).shadow(16.dp, RoundedCornerShape(18.dp))
                .background(C.Mat, RoundedCornerShape(18.dp)).padding(28.dp),
        ) {
            T(str(R.string.discord_title), 22.sp, weight = FontWeight.SemiBold, tight = true)
            T(str(R.string.discord_body), 14.5.sp, Modifier.padding(top = 8.dp), color = C.Ink2)
            Image(painterResource(R.drawable.discord_qr), null,
                Modifier.padding(top = 20.dp).size(220.dp).clip(RoundedCornerShape(12.dp)).align(Alignment.CenterHorizontally))
            T(str(R.string.discord_link), 18.sp, Modifier.padding(top = 14.dp).align(Alignment.CenterHorizontally), weight = FontWeight.SemiBold)
            Row(Modifier.padding(top = 22.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Pill(str(R.string.action_close), onClose, solid = true, requester = closeRequester)
            }
            LaunchedEffect(Unit) { runCatching { closeRequester.requestFocus() } }
        }
    }
}

private fun permissionLabel(permission: String): String? {
    val short = permission.removePrefix("android.permission.").removePrefix("android.")
    val mapped = when (short) {
        "INTERNET" -> R.string.perm_internet
        "ACCESS_NETWORK_STATE" -> R.string.perm_network_state
        "ACCESS_WIFI_STATE" -> R.string.perm_wifi_state
        "WAKE_LOCK" -> R.string.perm_wake_lock
        "FOREGROUND_SERVICE" -> R.string.perm_foreground_service
        "RECEIVE_BOOT_COMPLETED" -> R.string.perm_boot
        "REQUEST_INSTALL_PACKAGES" -> R.string.perm_install_packages
        "READ_EXTERNAL_STORAGE" -> R.string.perm_storage_read
        "WRITE_EXTERNAL_STORAGE" -> R.string.perm_storage_write
        "RECORD_AUDIO" -> R.string.perm_record_audio
        "SYSTEM_ALERT_WINDOW" -> R.string.perm_overlay
        "QUERY_ALL_PACKAGES" -> R.string.perm_query_packages
        else -> null
    }
    return mapped?.let(::str)
}
