package com.axel.homeboard

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val FR = Locale.FRENCH
private val CLOCK_FMT = DateTimeFormatter.ofPattern("HH:mm", FR)
private val DATE_FMT = DateTimeFormatter.ofPattern("EEEE d MMMM", FR)
private val DAY_FMT = DateTimeFormatter.ofPattern("EEE", FR)

private val TextMain = Color(0xFFF5F3FF)
private val TextDim = Color(0xB3E9E5FF)
private val Glass = Color(0x1AFFFFFF)
private val GlassBorder = Color(0x26FFFFFF)
private val Accent = Color(0xFFFF9F43)

private val PAD_H = 56.dp
private val GAP = 20.dp

@Composable
fun HomeScreen(resumeTick: Int, lastInput: MutableLongState, saverOn: MutableState<Boolean>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    var settings by remember { mutableStateOf(SettingsStore.load(ctx)) }
    fun update(s: HomeSettings) { settings = s; SettingsStore.save(ctx, s) }
    val cols = settings.columns
    val city = CITIES[settings.city]
    var showSettings by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf(Profiles.name(ctx)) }

    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1000L * (60 - value.second).coerceAtLeast(1))
        }
    }
    val weather by produceState<Weather?>(null, settings.city) {
        value = null
        while (true) {
            WeatherRepo.fetch(city.lat, city.lon)?.let { value = it }
            delay(if (value == null) 60_000L else 30 * 60_000L)
        }
    }

    // Programme TV de ce soir (téléchargé une fois par jour, puis lu depuis le cache)
    var evening by remember { mutableStateOf<TvEvening?>(null) }
    LaunchedEffect(resumeTick, settings.showGuide) {
        if (settings.showGuide) evening = TvGuide.evening(ctx)
    }

    // Écran de veille : on surveille l'inactivité
    val saverMinutes = settings.saverMinutes
    LaunchedEffect(saverMinutes) {
        while (true) {
            delay(5_000)
            val idle = SystemClock.uptimeMillis() - lastInput.longValue
            if (saverMinutes > 0 && !saverOn.value && idle > saverMinutes * 60_000L) saverOn.value = true
        }
    }

    val allApps by produceState(initialValue = emptyList<AppEntry>(), resumeTick) {
        value = withContext(Dispatchers.IO) { AppRepo.load(ctx) }
    }
    var order by remember { mutableStateOf(Prefs.order(ctx)) }
    var hidden by remember { mutableStateOf(Prefs.hidden(ctx)) }
    var recents by remember { mutableStateOf(Prefs.recents(ctx)) }
    LaunchedEffect(resumeTick) { recents = Prefs.recents(ctx) }

    var showHidden by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }

    // L'en-tête (heure + météo) se replie quand on navigue dans les apps
    var focusInApps by remember { mutableStateOf(false) }

    // Tri : ordre perso d'abord, puis le reste dans l'ordre par défaut
    val ordered = remember(allApps, order) {
        val byPkg = allApps.associateBy { it.pkg }
        order.mapNotNull { byPkg[it] } + allApps.filter { it.pkg !in order }
    }
    val visible = ordered.filter { showHidden || it.pkg !in hidden }
    val recentApps = remember(allApps, recents, hidden, cols, settings.showRecents) {
        if (!settings.showRecents) emptyList()
        else {
            val byPkg = allApps.associateBy { it.pkg }
            recents.mapNotNull { byPkg[it] }.filter { it.pkg !in hidden }.take(cols)
        }
    }

    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(key: String) = requesters.getOrPut(key) { FocusRequester() }

    // Focus initial sur la première tuile
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(allApps) {
        if (allApps.isNotEmpty() && !focusedOnce) {
            delay(150)
            val first = recentApps.firstOrNull()?.let { "r_" + it.pkg } ?: visible.firstOrNull()?.pkg
            first?.let { k -> runCatching { requesterFor(k).requestFocus() } }
            focusedOnce = true
        }
    }

    /** Passe au profil suivant et recharge tout ce qui lui est propre. */
    fun switchProfile() {
        Profiles.setActive(ctx, (Profiles.active(ctx) + 1) % Profiles.count(ctx))
        profileName = Profiles.name(ctx)
        settings = SettingsStore.load(ctx)
        order = Prefs.order(ctx)
        hidden = Prefs.hidden(ctx)
        recents = Prefs.recents(ctx)
        showHidden = false
        Toast.makeText(ctx, "Profil : $profileName", Toast.LENGTH_SHORT).show()
    }

    fun refocus(key: String) = scope.launch {
        delay(80)
        runCatching { requesterFor(key).requestFocus() }
    }

    /** Échange l'app déplacée avec sa voisine (delta = ±1 ou ±COLUMNS). */
    fun moveBy(delta: Int) {
        val pkg = moving ?: return
        val i = visible.indexOfFirst { it.pkg == pkg }
        val j = i + delta
        if (i < 0 || j !in visible.indices) return
        val full = ordered.map { it.pkg }.toMutableList()
        val a = full.indexOf(pkg)
        val b = full.indexOf(visible[j].pkg)
        full[a] = full[b].also { full[b] = full[a] }
        order = full
        Prefs.setOrder(ctx, full)
        // garder la tuile visible
        val guideItems = if (!settings.showGuide) 0 else 1 + (evening?.shows?.size?.takeIf { it > 0 } ?: 1)
        val gridIndex = (if (recentApps.isEmpty()) 0 else recentApps.size + 1) + guideItems + 1 + j
        scope.launch {
            val shown = gridState.layoutInfo.visibleItemsInfo.any { it.key == pkg }
            if (!shown) gridState.animateScrollToItem(gridIndex)
        }
    }

    BackHandler(enabled = moving != null) { moving?.let { refocus(it) }; moving = null }

    Box(Modifier.fillMaxSize()) {
        AnimatedBackground(settings.theme, running = !saverOn.value)

        Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
            AnimatedVisibility(
                visible = !focusInApps,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Box(Modifier.padding(horizontal = PAD_H).padding(bottom = 16.dp)) { Header(now, weather, city.name, profileName) }
            }

            if (moving != null) {
                Box(Modifier.padding(horizontal = PAD_H).padding(bottom = 8.dp)) {
                    Text(
                        "Déplacement : ◀ ▶ ▲ ▼ pour déplacer · OK pour valider",
                        color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Box(Modifier.padding(horizontal = PAD_H)) { SectionTitle("Raccourcis") }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAD_H)
                    .onFocusChanged { if (it.hasFocus) focusInApps = false },
                horizontalArrangement = Arrangement.spacedBy(GAP),
            ) {
                SHORTCUTS.forEach { s ->
                    FocusCard(
                        modifier = Modifier.weight(1f).aspectRatio(2.2f),
                        onClick = { s.action(ctx) },
                    ) { focused ->
                        Row(
                            Modifier.fillMaxSize().padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(s.emoji, fontSize = 22.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                s.label,
                                color = if (focused) Color.White else TextMain,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                state = gridState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onFocusChanged { focusInApps = it.hasFocus }
                    .onPreviewKeyEvent { e ->
                        // Mode déplacement : les flèches déplacent l'app au lieu du focus
                        if (moving == null || e.key == Key.Back) return@onPreviewKeyEvent false
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                        when (e.key) {
                            Key.DirectionLeft -> moveBy(-1)
                            Key.DirectionRight -> moveBy(1)
                            Key.DirectionUp -> moveBy(-cols)
                            Key.DirectionDown -> moveBy(cols)
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                moving?.let { refocus(it) }; moving = null
                            }
                            else -> {}
                        }
                        true
                    },
                // marge pour que la tuile agrandie ne soit pas coupée
                contentPadding = PaddingValues(start = PAD_H, end = PAD_H, top = 12.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(GAP),
                verticalArrangement = Arrangement.spacedBy(GAP),
            ) {
                if (recentApps.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "t_recents") { SectionTitle("Reprendre") }
                    items(recentApps, key = { "r_" + it.pkg }) { app ->
                        AppTile(
                            app = app,
                            modifier = Modifier.focusRequester(requesterFor("r_" + app.pkg)),
                            onClick = { ctx.launchFirst(app.intent) },
                            onMenu = { menuFor = app },
                        )
                    }
                }

                if (settings.showGuide) {
                    val ev = evening
                    item(span = { GridItemSpan(maxLineSpan) }, key = "t_guide") {
                        SectionTitle(if (ev == null || ev.isToday) "Ce soir à la télé" else "Demain soir à la télé")
                    }
                    when {
                        ev == null -> item(span = { GridItemSpan(maxLineSpan) }, key = "guide_loading") {
                            Text("Chargement du programme…", color = TextDim, fontSize = 14.sp)
                        }
                        ev.shows.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "guide_empty") {
                            Text(
                                "Programme indisponible pour le moment" +
                                    (TvGuide.lastError?.let { "\n($it)" } ?: ""),
                                color = TextDim, fontSize = 13.sp,
                            )
                        }
                        else -> items(ev.shows, key = { "tv_" + it.channel }) { show ->
                            ShowTile(show) {
                                val keyword = GUIDE_CHANNELS.first { it.name == show.channel }.appKeyword
                                val app = allApps.firstOrNull { normalizeName(it.label).contains(keyword) }
                                if (app != null) ctx.launchFirst(app.intent)
                                else Toast.makeText(ctx, "Aucune app installée pour ${show.channel}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }, key = "t_apps") {
                    SectionTitle("Applications · ${visible.size}")
                }
                items(visible, key = { it.pkg }) { app ->
                    AppTile(
                        app = app,
                        modifier = Modifier.focusRequester(requesterFor(app.pkg)),
                        dimmed = app.pkg in hidden,
                        moving = moving == app.pkg,
                        onClick = { if (moving == null) ctx.launchFirst(app.intent) },
                        onMenu = { if (moving == null) menuFor = app },
                    )
                }

                if (hidden.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "toggle_hidden") {
                        Row {
                            FocusCard(onClick = { showHidden = !showHidden }) {
                                Text(
                                    if (showHidden) "🙈  Cacher les apps masquées"
                                    else "👁️  Afficher les apps masquées (${hidden.size})",
                                    color = TextMain, fontSize = 14.sp,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }, key = "settings") {
                    Row {
                        FocusCard(
                            modifier = Modifier.focusRequester(requesterFor("settings_btn")),
                            onClick = { showSettings = true },
                        ) {
                            Text(
                                "🛠️  Réglages de FireHome",
                                color = TextMain, fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            )
                        }
                        Spacer(Modifier.width(GAP))
                        FocusCard(onClick = { switchProfile() }) {
                            Text(
                                "👤  Profil : $profileName  ·  changer",
                                color = TextMain, fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            )
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }, key = "tip") {
                    Text(
                        "Astuce : bouton ☰ sur une app pour la déplacer, la masquer ou voir ses infos",
                        color = TextDim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        menuFor?.let { app ->
            AppMenu(
                app = app,
                isHidden = app.pkg in hidden,
                onMove = {
                    menuFor = null
                    showHidden = showHidden || app.pkg in hidden
                    moving = app.pkg
                    refocus(app.pkg)
                },
                onToggleHide = {
                    val hide = app.pkg !in hidden
                    Prefs.setHidden(ctx, app.pkg, hide)
                    hidden = Prefs.hidden(ctx)
                    menuFor = null
                    val next = visible.firstOrNull { it.pkg != app.pkg }?.pkg
                    (if (hide && !showHidden) next else app.pkg)?.let { refocus(it) }
                },
                onInfo = { menuFor = null; ctx.openAppDetails(app.pkg) },
                onDismiss = { menuFor = null; refocus(app.pkg) },
            )
        }

        if (showSettings) {
            val rows = listOf(
                SettingRow("Ville de la météo", city.name,
                    { update(settings.copy(city = (settings.city - 1 + CITIES.size) % CITIES.size)) },
                    { update(settings.copy(city = (settings.city + 1) % CITIES.size)) }),
                SettingRow("Couleurs du fond", settings.theme.label,
                    { update(settings.copy(theme = BgTheme.entries.toList().cycle(settings.theme, -1))) },
                    { update(settings.copy(theme = BgTheme.entries.toList().cycle(settings.theme, 1))) }),
                SettingRow("Apps par ligne", "${settings.columns}",
                    { update(settings.copy(columns = COLUMN_OPTIONS.cycle(settings.columns, -1))) },
                    { update(settings.copy(columns = COLUMN_OPTIONS.cycle(settings.columns, 1))) }),
                SettingRow("Section « Reprendre »", if (settings.showRecents) "Affichée" else "Masquée",
                    { update(settings.copy(showRecents = !settings.showRecents)) },
                    { update(settings.copy(showRecents = !settings.showRecents)) }),
                SettingRow("Ce soir à la télé", if (settings.showGuide) "Affiché" else "Masqué",
                    { update(settings.copy(showGuide = !settings.showGuide)) },
                    { update(settings.copy(showGuide = !settings.showGuide)) }),
                SettingRow("Écran de veille",
                    if (settings.saverMinutes == 0) "Désactivé" else "Après ${settings.saverMinutes} min",
                    { update(settings.copy(saverMinutes = SAVER_OPTIONS.cycle(settings.saverMinutes, -1))) },
                    { update(settings.copy(saverMinutes = SAVER_OPTIONS.cycle(settings.saverMinutes, 1))) }),
            )
            SettingsPanel(
                rows = rows,
                onPreviewSaver = { showSettings = false; saverOn.value = true },
                onDismiss = { showSettings = false; refocus("settings_btn") },
            )
        }

        AnimatedVisibility(
            visible = saverOn.value,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(1200)),
            exit = fadeOut(tween(300)),
        ) {
            Screensaver(now, weather, city.name, settings.theme)
        }
    }
}

/** Petit menu par-dessus l'accueil (bouton ☰ sur une app). */
@Composable
private fun AppMenu(
    app: AppEntry,
    isHidden: Boolean,
    onMove: () -> Unit,
    onToggleHide: () -> Unit,
    onInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = listOf(
        "↔️  Déplacer" to onMove,
        (if (isHidden) "👁️  Afficher" else "🙈  Masquer") to onToggleHide,
        "ℹ️  Infos de l'app" to onInfo,
        "✖️  Annuler" to onDismiss,
    )
    val requesters = remember { List(entries.size) { FocusRequester() } }
    var index by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { requesters[0].requestFocus() }
    }
    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            // On garde le focus dans le menu : flèches gérées à la main
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> { index = (index - 1).coerceAtLeast(0); requesters[index].requestFocus(); true }
                    Key.DirectionDown -> { index = (index + 1).coerceAtMost(entries.lastIndex); requesters[index].requestFocus(); true }
                    Key.DirectionLeft, Key.DirectionRight -> true
                    Key.Menu -> { onDismiss(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .width(360.dp)
                .clip(shape)
                .background(Color(0xF0181528))
                .border(1.dp, GlassBorder, shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(app.label, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            entries.forEachIndexed { i, (label, action) ->
                FocusCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(requesters[i])
                        .onFocusChanged { if (it.isFocused) index = i },
                    scaleFocused = 1.03f,
                    onClick = action,
                ) {
                    Text(label, color = TextMain, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- En-tête */

@Composable
private fun Header(now: LocalDateTime, weather: Weather?, city: String, name: String) {
    val greeting = when (now.hour) {
        in 5..11 -> "Bonjour"
        in 12..17 -> "Bon après-midi"
        else -> "Bonsoir"
    }

    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("$greeting $name", color = TextDim, fontSize = 18.sp)
            Text(
                now.format(CLOCK_FMT),
                color = TextMain,
                fontSize = 88.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 92.sp,
            )
            Text(
                now.format(DATE_FMT).replaceFirstChar { it.titlecase(FR) },
                color = TextMain,
                fontSize = 22.sp,
            )
        }
        WeatherCard(weather, city)
    }
}

@Composable
private fun WeatherCard(w: Weather?, city: String) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .width(360.dp)
            .clip(shape)
            .background(Glass)
            .border(1.dp, GlassBorder, shape)
            .padding(20.dp),
    ) {
        if (w == null) {
            Text(city, color = TextDim, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text("Météo en cours de chargement…", color = TextMain, fontSize = 16.sp)
            return@Column
        }
        val (emoji, label) = WeatherRepo.describe(w.code)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 44.sp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("$city · $label", color = TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${w.temp}°", color = TextMain, fontSize = 44.sp, fontWeight = FontWeight.Light, lineHeight = 46.sp)
            }
        }
        Text("Ressenti ${w.feels}° · Vent ${w.wind} km/h", color = TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            w.days.forEach { d ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        d.date.format(DAY_FMT).trimEnd('.').replaceFirstChar { it.titlecase(FR) },
                        color = TextDim, fontSize = 13.sp,
                    )
                    Text(WeatherRepo.describe(d.code).first, fontSize = 20.sp)
                    Text("${d.min}° / ${d.max}°", color = TextMain, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(FR),
        color = TextDim,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/* ---------------------------------------------------------------- Tuiles */

@Composable
private fun FocusCard(
    modifier: Modifier = Modifier,
    highlight: Color? = null,
    scaleFocused: Float = 1.08f,
    onClick: () -> Unit,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) scaleFocused else 1f, label = "scale")
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused) Color(0x40FFFFFF) else Glass)
            .border(
                width = if (focused || highlight != null) 3.dp else 1.dp,
                color = highlight ?: if (focused) Color.White else GlassBorder,
                shape = shape,
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) { content(focused) }
}

@Composable
private fun AppTile(
    app: AppEntry,
    modifier: Modifier,
    dimmed: Boolean = false,
    moving: Boolean = false,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    FocusCard(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .graphicsLayer { alpha = if (dimmed) 0.4f else 1f }
            .onPreviewKeyEvent {
                if (it.key == Key.Menu && it.type == KeyEventType.KeyUp) { onMenu(); true } else false
            },
        highlight = if (moving) Accent else null,
        onClick = onClick,
    ) { focused ->
        if (app.banner != null) {
            Image(
                bitmap = app.banner,
                contentDescription = app.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (focused) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(app.label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                app.icon?.let {
                    Image(bitmap = it, contentDescription = null, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(6.dp))
                }
                Text(app.label, color = TextMain, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Tuile d'une émission : affiche en fond, chaîne + heure + titre. */
@Composable
private fun ShowTile(show: TvShow, onClick: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, show.image) {
        show.image?.let { value = ImageCache.load(it) }
    }
    FocusCard(modifier = Modifier.aspectRatio(16f / 9f), onClick = onClick) { focused ->
        image?.let {
            Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0xE6000000)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                "${show.channel} · ${show.start.atZoneSameInstant(java.time.ZoneId.of("Europe/Paris")).format(CLOCK_FMT)}",
                color = Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            Text(
                show.title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 15.sp,
            )
        }
    }
}

/* ---------------------------------------------------------------- Fond animé */

private class Blob(val x: Float, val y: Float, val r: Float, val speed: Int, val phase: Float)

private val BLOBS = listOf(
    Blob(0.22f, 0.28f, 0.55f, 1, 0.0f),
    Blob(0.78f, 0.30f, 0.50f, -1, 2.1f),
    Blob(0.60f, 0.85f, 0.45f, 2, 4.2f),
    Blob(0.12f, 0.88f, 0.42f, -2, 1.0f),
)

@Composable
private fun AnimatedBackground(theme: BgTheme, running: Boolean = true) {
    // Le mouvement est très lent : 15 images/s suffisent et soulagent le processeur du stick
    var t by remember { mutableFloatStateOf(0f) }
    val start = remember { System.nanoTime() }
    LaunchedEffect(running) {
        while (running) {
            val elapsed = (System.nanoTime() - start) / 1_000_000_000f
            t = (elapsed / 60f * 2f * PI.toFloat()) % (2f * PI.toFloat())
            delay(66)
        }
    }
    val colors = theme.colors.map { Color(it) }

    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(0xFF07060F))
        BLOBS.forEachIndexed { i, b ->
            val color = colors[i % colors.size]
            val a = t * b.speed + b.phase
            val center = Offset(
                (b.x + 0.14f * cos(a)) * size.width,
                (b.y + 0.12f * sin(a)) * size.height,
            )
            val radius = b.r * size.maxDimension
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.18f), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
        // Voile sombre pour garder le texte lisible
        drawRect(Brush.verticalGradient(listOf(Color(0x33000000), Color(0x8C000000))))
    }
}

/* ---------------------------------------------------------------- Réglages */

class SettingRow(val label: String, val value: String, val prev: () -> Unit, val next: () -> Unit)

@Composable
private fun SettingsPanel(
    rows: List<SettingRow>,
    onPreviewSaver: () -> Unit,
    onDismiss: () -> Unit,
) {
    val total = rows.size + 2 // + « Tester l'écran de veille » + « Fermer »
    val requesters = remember { List(total) { FocusRequester() } }
    var index by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        delay(50)
        runCatching { requesters[0].requestFocus() }
    }
    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> { index = (index - 1).coerceAtLeast(0); requesters[index].requestFocus(); true }
                    Key.DirectionDown -> { index = (index + 1).coerceAtMost(total - 1); requesters[index].requestFocus(); true }
                    Key.DirectionLeft -> { rows.getOrNull(index)?.prev?.invoke(); true }
                    Key.DirectionRight -> { rows.getOrNull(index)?.next?.invoke(); true }
                    Key.Menu -> { onDismiss(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .width(560.dp)
                .clip(shape)
                .background(Color(0xF0181528))
                .border(1.dp, GlassBorder, shape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Réglages de FireHome", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("▲ ▼ pour choisir · ◀ ▶ pour changer", color = TextDim, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))

            rows.forEachIndexed { i, row ->
                FocusCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(requesters[i])
                        .onFocusChanged { if (it.isFocused) index = i },
                    scaleFocused = 1.02f,
                    onClick = row.next,
                ) { focused ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.label, color = TextMain, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(
                            if (focused) "◀  ${row.value}  ▶" else row.value,
                            color = if (focused) Accent else TextDim,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusCard(
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(requesters[rows.size])
                        .onFocusChanged { if (it.isFocused) index = rows.size },
                    scaleFocused = 1.03f,
                    onClick = onPreviewSaver,
                ) {
                    Text("🌙  Tester l'écran de veille", color = TextMain, fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
                FocusCard(
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(requesters[rows.size + 1])
                        .onFocusChanged { if (it.isFocused) index = rows.size + 1 },
                    scaleFocused = 1.03f,
                    onClick = onDismiss,
                ) {
                    Text("✔️  Fermer", color = TextMain, fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- Écran de veille */

@Composable
private fun Screensaver(now: LocalDateTime, weather: Weather?, city: String, theme: BgTheme) {
    // L'horloge se décale doucement chaque minute (évite de marquer l'écran)
    val target = remember(now.minute) { Offset(Random.nextInt(-30, 31).toFloat(), Random.nextInt(-20, 21).toFloat()) }
    val ox by animateFloatAsState(target.x, tween(6000), label = "ox")
    val oy by animateFloatAsState(target.y, tween(6000), label = "oy")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.45f }) { AnimatedBackground(theme) }

        Column(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(ox.dp.roundToPx(), oy.dp.roundToPx()) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                now.format(CLOCK_FMT),
                color = TextMain,
                fontSize = 150.sp,
                fontWeight = FontWeight.Thin,
                lineHeight = 150.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                now.format(DATE_FMT).replaceFirstChar { it.titlecase(FR) },
                color = TextMain,
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            weather?.let { w ->
                val (emoji, label) = WeatherRepo.describe(w.code)
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(emoji, fontSize = 34.sp)
                    Spacer(Modifier.width(12.dp))
                    Text("${w.temp}°  ·  $label  ·  $city", color = TextDim, fontSize = 22.sp)
                }
            }
        }

        Text(
            "Appuie sur une touche pour revenir",
            color = Color(0x66FFFFFF),
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        )
    }
}
