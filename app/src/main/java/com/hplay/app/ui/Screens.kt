package com.hplay.app.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hplay.app.R
import com.hplay.app.Session
import com.hplay.app.discovery.Cap
import com.hplay.app.discovery.Device

private val Colors: ColorScheme = darkColorScheme(
    primary = Color(0xFF2EC8F7),
    onPrimary = Color(0xFF00131F),
    secondary = Color(0xFF6C7BFF),
    background = Color(0xFF040B26),
    surface = Color(0xFF0B1638),
    surfaceVariant = Color(0xFF14224D),
    onBackground = Color(0xFFE8ECFF),
    onSurface = Color(0xFFE8ECFF),
    error = Color(0xFFFF8A7A),
)

@Composable
fun HPlayTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
}

@Composable
fun MainScreen(vm: MainViewModel, onMirror: () -> Unit, onUpdate: () -> Unit) {
    val screen by vm.screen.collectAsState()
    Box(Modifier.safeDrawingPadding()) {
        when (screen) {
            MainViewModel.Screen.HOME -> HomeScreen(vm, onMirror, onUpdate)
            MainViewModel.Screen.BROWSER -> BrowserScreen(vm)
            MainViewModel.Screen.BATTERY -> BatteryScreen { vm.screen.value = MainViewModel.Screen.HOME }
        }
    }
}

@Composable
private fun HomeScreen(vm: MainViewModel, onMirror: () -> Unit, onUpdate: () -> Unit) {
    val session by vm.session.collectAsState()
    val devices by vm.devices.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val selected by vm.selectedIp.collectAsState()
    val update by vm.update.collectAsState()
    var manual by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HPlay", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.screen.value = MainViewModel.Screen.BATTERY }) { Text(stringResource(R.string.battery_title)) }
            }
        }
        update?.let { m ->
            item {
                Card(Modifier.fillMaxWidth().clickable(onClick = onUpdate), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(com.hplay.updater.R.string.upd_banner, m.versionName), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = onUpdate) { Text(stringResource(com.hplay.updater.R.string.upd_btn)) }
                    }
                }
            }
        }
        if (session.mode != Session.Mode.IDLE) item { SessionCard(vm, session) }
        else if (session.errorRes != null) item { Text(stringResource(session.errorRes!!), color = MaterialTheme.colorScheme.error) }

        item {
            Button(onClick = onMirror, modifier = Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.mirror), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            OutlinedButton(onClick = { vm.openBrowser() }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Text(stringResource(R.string.send_video), fontSize = 18.sp)
            }
        }
        item { Text(stringResource(R.string.drm_notice), fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)) }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.devices), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                TextButton(onClick = vm::refresh, enabled = !scanning) { Text(stringResource(R.string.search)) }
            }
        }
        item {
            DeviceRow(
                title = stringResource(R.string.no_device),
                subtitle = stringResource(R.string.no_device_hint),
                chips = emptyList(),
                selected = selected == null,
                onClick = { vm.select(null) },
            )
        }
        items(devices, key = { it.ip }) { d ->
            DeviceRow(
                title = d.name,
                subtitle = listOf(d.manufacturer, d.model, d.ip).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                chips = d.caps.map { capLabel(it) },
                selected = selected == d.ip,
                onClick = { vm.select(d.ip) },
                onRemove = if (Cap.MANUAL in d.caps) ({ vm.removeManual(d.ip) }) else null,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it.take(64) },
                    label = { Text(stringResource(R.string.manual_ip)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.addManual(manual); manual = "" }),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.addManual(manual); manual = "" }, enabled = manual.isNotBlank()) { Text(stringResource(R.string.add)) }
            }
        }
    }
}

@Composable
private fun capLabel(c: Cap) = when (c) {
    Cap.DLNA -> "DLNA"
    Cap.ROKU -> "Roku"
    Cap.CAST -> stringResource(R.string.cap_cast)
    Cap.AIRPLAY -> stringResource(R.string.cap_airplay)
    Cap.RAOP -> stringResource(R.string.cap_raop)
    Cap.ANDROID_TV -> "Android TV"
    Cap.HPLAY -> stringResource(R.string.cap_hplay)
    Cap.FCAST -> "FCast"
    Cap.KODI -> "Kodi"
    Cap.DIAL -> "DIAL"
    Cap.MANUAL -> stringResource(R.string.cap_manual)
}

@Composable
private fun DeviceRow(title: String, subtitle: String, chips: List<String>, selected: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (chips.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    chips.forEach { c ->
                        Text(
                            c, fontSize = 12.sp,
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (onRemove != null) TextButton(onClick = onRemove) { Text("✕", modifier = Modifier.semantics { contentDescription = "remove" }) }
        }
    }
}

@Composable
private fun SessionCard(vm: MainViewModel, s: Session.State) {
    val remote by vm.remote.collectAsState()
    val status by vm.remoteStatus.collectAsState()
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (s.mode == Session.Mode.MIRROR) R.string.notif_mirror else R.string.notif_url) +
                    (s.deviceName?.let { " → $it" } ?: ""),
                fontWeight = FontWeight.Bold, fontSize = 18.sp,
            )
            s.routeRes?.let { Text(stringResource(it)) }
            s.errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            s.receiverUrl?.let { url ->
                Text(stringResource(R.string.open_in_browser), fontSize = 13.sp)
                SelectionContainer { Text(url, fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                Qr(url)
            }
            Text(stringResource(R.string.receivers_connected, s.clients), fontSize = 13.sp)

            if (remote != null && s.mode == Session.Mode.URL) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.control { play() } }) { Text(stringResource(R.string.play)) }
                    OutlinedButton(onClick = { vm.control { pause() } }) { Text(stringResource(R.string.pause)) }
                }
                val st = status
                if (st != null && st.durationSec > 0) {
                    var seeking by remember { mutableStateOf(false) }
                    var pos by remember { mutableFloatStateOf(0f) }
                    if (!seeking) pos = st.positionSec.toFloat()
                    Text("${fmt(pos.toLong())} / ${fmt(st.durationSec)}", fontSize = 13.sp)
                    Slider(
                        value = pos.coerceIn(0f, st.durationSec.toFloat()),
                        onValueChange = { seeking = true; pos = it },
                        onValueChangeFinished = { seeking = false; val t = pos.toLong(); vm.control { seek(t) } },
                        valueRange = 0f..st.durationSec.toFloat(),
                        modifier = Modifier.semantics { contentDescription = "seek" },
                    )
                }
                if (st?.volume != null) {
                    var vol by remember { mutableFloatStateOf(st.volume.toFloat()) }
                    Text(stringResource(R.string.volume), fontSize = 13.sp)
                    Slider(
                        value = vol, valueRange = 0f..100f,
                        onValueChange = { vol = it },
                        onValueChangeFinished = { val v = vol.toInt(); vm.control { setVolume(v) } },
                        modifier = Modifier.semantics { contentDescription = "volume" },
                    )
                }
            }
            Button(onClick = vm::stop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stop)) }
        }
    }
}

private fun fmt(s: Long) = "%d:%02d".format(s / 60, s % 60)

@Composable
private fun Qr(text: String) {
    val bmp = remember(text) {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
        val scale = 8
        val b = Bitmap.createBitmap(m.width * scale, m.height * scale, Bitmap.Config.ARGB_8888)
        for (x in 0 until m.width) for (y in 0 until m.height) {
            val c = if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            for (dx in 0 until scale) for (dy in 0 until scale) b.setPixel(x * scale + dx, y * scale + dy, c)
        }
        b.asImageBitmap()
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(bmp, contentDescription = text, modifier = Modifier.size(200.dp).background(Color.White).padding(8.dp))
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserScreen(vm: MainViewModel) {
    val target by vm.browserUrl.collectAsState()
    val detected by vm.detected.collectAsState()
    val notice by vm.notice.collectAsState()
    val devices by vm.devices.collectAsState()
    val selected by vm.selectedIp.collectAsState()
    var input by remember { mutableStateOf(target) }
    var web by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else vm.screen.value = MainViewModel.Screen.HOME
    }
    fun go() {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = if (t.startsWith("http://") || t.startsWith("https://")) t
        else if (' ' in t || '.' !in t) "https://duckduckgo.com/?q=" + Uri.encode(t)
        else "https://$t"
        vm.detected.value = emptyList()
        vm.browserUrl.value = url
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.screen.value = MainViewModel.Screen.HOME }) { Text("←", fontSize = 20.sp, modifier = Modifier.semantics { contentDescription = "back" }) }
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.browser_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = ::go) { Text(stringResource(R.string.go)) }
        }
        // Sin web abierta, el WebView vacío se pinta negro encima de toda la pantalla (tapaba la barra de dirección):
        // hasta que hay una URL se muestra una explicación.
        if (target.isBlank()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.browser_empty_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.browser_empty_web))
                Text(stringResource(R.string.browser_empty_apps), color = MaterialTheme.colorScheme.primary)
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        addJavascriptInterface(object {
                            @JavascriptInterface fun media(url: String, mime: String, page: String) = vm.onScriptMedia(url, mime, page)
                        }, "HPlayDetect")
                        // Antes que los scripts de la página: así se ven también los fetch que hace al cargar.
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            WebViewCompat.addDocumentStartJavaScript(this, DETECT_JS, setOf("*"))
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = view.evaluateJavascript(DETECT_JS, null)

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val url = request.url.toString()
                                val headers = HashMap(request.requestHeaders)
                                CookieManager.getInstance().getCookie(url)?.let { headers["Cookie"] = it }
                                vm.onRequest(url, headers)
                                return null // solo se observa: el WebView carga normal
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                vm.pageTitle = view.title.orEmpty()
                                input = url
                                vm.onPage(url)
                                view.evaluateJavascript(DETECT_JS, null) // por si la página reemplazó el de onPageStarted
                            }
                        }
                        web = this
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        LaunchedEffect(target, web) { if (target.isNotBlank()) web?.loadUrl(target) }

        val dest = devices.firstOrNull { it.ip == selected }?.name ?: stringResource(R.string.no_device)
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
            Text(stringResource(R.string.send_to, dest), fontSize = 13.sp)
            if (detected.isEmpty() && target.isNotBlank()) Text(stringResource(R.string.no_media_yet), fontSize = 14.sp)
            notice?.let { Text(stringResource(it), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary) }
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(detected, key = { it.url }) { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.kind, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                        Text(m.url.substringBefore('?').substringAfter("://"), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Button(onClick = { vm.send(m) }) { Text(stringResource(if (m.app != null) R.string.open_on_tv else R.string.send)) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Script inyectado en cada página: avisa de los vídeos que la URL no delata (manifiestos sin extensión, <video> con
 * src directo) mirando el Content-Type de fetch/XHR y los elementos <video>/<audio>. Los blob: (MSE) no se pueden
 * enviar; su manifiesto real aparece por fetch/XHR.
 */
private const val DETECT_JS = """
(function () {
  if (window.__hplay) return; window.__hplay = 1;
  var seen = {};
  function report(u, t) {
    try { u = new URL(u, location.href).href; } catch (e) { return; }
    if (seen[u] || !/^https?:/.test(u)) return; seen[u] = 1;
    HPlayDetect.media(u, t || '', location.href);
  }
  function check(u, t) { if (/mpegurl|dash\+xml|^video\/|^audio\/(mp4|mpeg|aac)/i.test(t || '')) report(u, t.split(';')[0]); }
  var f = window.fetch;
  if (f) window.fetch = function () {
    return f.apply(this, arguments).then(function (r) { try { check(r.url, r.headers.get('content-type')); } catch (e) {} return r; });
  };
  var open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (m, u) {
    this.addEventListener('load', function () { try { check(this.responseURL || u, this.getResponseHeader('content-type')); } catch (e) {} });
    return open.apply(this, arguments);
  };
  setInterval(function () {
    document.querySelectorAll('video,audio,video source,audio source').forEach(function (v) {
      var s = v.currentSrc || v.src;
      if (s && !/^blob:/.test(s)) report(s, v.type || '');
    });
  }, 1500);
})();
"""

/** Pantalla guiada para ColorOS, MIUI, One UI y similares, que matan servicios en segundo plano. */
@Composable
private fun BatteryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    val pm = ctx.getSystemService(PowerManager::class.java)
    val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    val maker = Build.MANUFACTURER.lowercase()
    val hint = when {
        maker in setOf("oppo", "realme", "oneplus") -> R.string.battery_coloros
        maker in setOf("xiaomi", "redmi", "poco") -> R.string.battery_miui
        maker == "samsung" -> R.string.battery_oneui
        else -> R.string.battery_generic
    }
    LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←", fontSize = 20.sp) }
                Text(stringResource(R.string.battery_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
        item { Text(stringResource(R.string.battery_intro)) }
        item { Text(stringResource(hint)) }
        item {
            Text(stringResource(R.string.battery_step1) + if (ignoring) "  ✓" else "", fontWeight = FontWeight.SemiBold)
            @SuppressLint("BatteryLife") // app de emisión continua: caso de uso permitido fuera de Play
            Button(enabled = !ignoring, onClick = {
                tryStart(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }) { Text(stringResource(if (ignoring) R.string.battery_done else R.string.battery_allow)) }
        }
        item {
            Text(stringResource(R.string.battery_step2), fontWeight = FontWeight.SemiBold)
            Button(onClick = { openAutostart(ctx) }) { Text(stringResource(R.string.battery_autostart)) }
        }
        item {
            Text(stringResource(R.string.battery_step3), fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = {
                tryStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) { Text(stringResource(R.string.battery_app_settings)) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun tryStart(ctx: Context, i: Intent): Boolean =
    runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

/** Pantallas de autoarranque de cada capa; si ninguna existe, ajustes de la app. */
private fun openAutostart(ctx: Context) {
    val candidates = listOf(
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
    )
    for ((pkg, cls) in candidates) if (tryStart(ctx, Intent().setComponent(ComponentName(pkg, cls)))) return
    tryStart(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
}
