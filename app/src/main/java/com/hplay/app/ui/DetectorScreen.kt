package com.hplay.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hplay.app.R
import com.hplay.app.detect.DetectorService
import com.hplay.app.detect.DnsVpnService
import com.hplay.app.detect.MediaDetector
import com.hplay.app.detect.MediaDetector.Availability
import com.hplay.app.detect.MediaDetector.DomainClass
import com.hplay.app.detect.MediaDetector.Evidence
import com.hplay.app.detect.MediaDetector.Source
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Detector multimedia»: estado de las dos sondas (receptor DLNA de diagnóstico y monitor DNS), las direcciones
 * recibidas por vías legítimas, los dominios vistos (marcados como lo que son: dominios) y el registro técnico.
 */
@Composable
fun DetectorScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val items by MediaDetector.items.collectAsState()
    val domains by MediaDetector.domains.collectAsState()
    val log by MediaDetector.log.collectAsState()
    val dlnaOn by MediaDetector.dlnaOn.collectAsState()
    val dnsOn by MediaDetector.dnsOn.collectAsState()
    val selected by vm.selectedIp.collectAsState()
    val devices by vm.devices.collectAsState()
    var allDomains by remember { mutableStateOf(false) }
    val back = { vm.screen.value = MainViewModel.Screen.HOME }
    BackHandler(onBack = back)

    val vpnDenied = stringResource(R.string.det_vpn_denied)
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) DnsVpnService.start(ctx) else Toast.makeText(ctx, vpnDenied, Toast.LENGTH_LONG).show()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back) { Text("←", fontSize = 20.sp) }
                Text(stringResource(R.string.detector_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = MediaDetector::clear) { Text(stringResource(R.string.det_clear)) }
            }
        }
        item { Text(stringResource(R.string.det_intro), fontSize = 13.sp) }

        item {
            ProbeCard(
                title = stringResource(R.string.det_dlna), hint = stringResource(R.string.det_dlna_hint), on = dlnaOn,
                onToggle = { if (dlnaOn) DetectorService.stop(ctx) else DetectorService.start(ctx) },
            )
        }
        item {
            ProbeCard(
                title = stringResource(R.string.det_dns), hint = stringResource(R.string.det_dns_hint), on = dnsOn,
                onToggle = {
                    if (dnsOn) DnsVpnService.stop(ctx)
                    else VpnService.prepare(ctx)?.let { vpnConsent.launch(it) } ?: DnsVpnService.start(ctx)
                },
            )
        }

        item {
            val dest = devices.firstOrNull { it.ip == selected }?.name ?: stringResource(R.string.no_device)
            Column {
                Text(stringResource(R.string.det_items), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.send_to, dest), fontSize = 12.sp)
            }
        }
        if (items.isEmpty()) item { Text(stringResource(R.string.det_none), fontSize = 13.sp) }
        items(items, key = { it.id }) { ItemCard(vm, it) }

        item { Text(stringResource(R.string.det_domains), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        val relevant = domains.filter { it.cls != DomainClass.OTHER }
        val shown = if (allDomains) domains else relevant
        if (domains.isEmpty()) item { Text(stringResource(if (dnsOn) R.string.det_domains_none else R.string.det_domains_off), fontSize = 13.sp) }
        items(shown, key = { it.name + "|" + it.app }) { d ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row {
                    Text(d.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("×${d.count}", fontSize = 12.sp)
                }
                val cls = when (d.cls) {
                    DomainClass.VIDEO_SERVICE -> stringResource(R.string.det_cls_video)
                    DomainClass.CDN -> stringResource(R.string.det_cls_cdn)
                    DomainClass.OTHER -> ""
                }
                Text(listOf(cls, d.app).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (domains.size > relevant.size) item {
            TextButton(onClick = { allDomains = !allDomains }) {
                Text(stringResource(if (allDomains) R.string.det_show_relevant else R.string.det_show_all, domains.size))
            }
        }

        item { Text(stringResource(R.string.det_log), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        items(log.take(150)) { l ->
            Text("${fmt.format(Date(l.time))}  ${l.text}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ProbeCard(title: String, hint: String, on: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(if (on) R.string.det_on else R.string.det_off), fontSize = 12.sp,
                        color = if (on) Color(0xFF6BE38A) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                if (on) OutlinedButton(onClick = onToggle) { Text(stringResource(R.string.stop)) }
                else Button(onClick = onToggle) { Text(stringResource(R.string.det_start)) }
            }
            Text(hint, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ItemCard(vm: MainViewModel, m: MediaDetector.Item) {
    val ctx = LocalContext.current
    val copied = stringResource(R.string.det_copied)
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.kind.name, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                Text(sourceLabel(m.source), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Text(DateUtils.getRelativeTimeSpanString(m.time).toString(), fontSize = 11.sp)
            }
            if (m.title.isNotBlank()) Text(m.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(stringResource(R.string.det_origin, m.origin), fontSize = 12.sp)
            if (m.client.isNotBlank()) Text(m.client, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2)
            Text(stringResource(R.string.det_domain, m.domain), fontSize = 12.sp)
            Text(stringResource(R.string.det_type, listOfNotNull(m.kind.name, m.mime).joinToString(" · "), evidenceLabel(m.evidence)), fontSize = 12.sp)
            Text(availabilityLabel(m.availability) + (if (m.status.isNotBlank()) " — ${m.status}" else ""), fontSize = 12.sp,
                color = when (m.availability) {
                    Availability.OK -> Color(0xFF6BE38A)
                    Availability.NEEDS_HEADERS, Availability.CHECKING, Availability.UNCHECKED -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.error
                })
            if (m.source == Source.DLNA && m.availability == Availability.DENIED) Text(stringResource(R.string.det_dlna_headers), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            m.expiresAt?.let { t ->
                val rel = DateUtils.getRelativeTimeSpanString(t).toString()
                Text(stringResource(if (t < System.currentTimeMillis()) R.string.det_expired else R.string.det_expires, rel), fontSize = 12.sp)
            }
            if (m.headers.isNotEmpty()) Text(stringResource(R.string.det_headers, m.headers.keys.joinToString(", ")), fontSize = 12.sp)
            val meta = m.meta.filterKeys { it in setOf("size", "duration", "resolution", "bitrate", "class") }
            if (meta.isNotEmpty()) Text(meta.entries.joinToString(" · ") { "${it.key}=${it.value}" }, fontSize = 11.sp)
            Text(stringResource(R.string.det_url_label), fontSize = 11.sp)
            SelectionContainer { Text(m.url, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 6) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("URL", m.url))
                    Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.det_copy)) }
                TextButton(onClick = { openWith(ctx, m) }) { Text(stringResource(R.string.det_open)) }
                TextButton(onClick = { vm.probe(m) }, enabled = m.availability != Availability.CHECKING) { Text(stringResource(R.string.det_check)) }
                Button(onClick = { vm.sendDetected(m) }) { Text(stringResource(R.string.send)) }
            }
        }
    }
}

/** «Abrir con» otro reproductor (VLC, MX Player, navegador…), pasando las cabeceras con la convención de MX Player. */
private fun openWith(ctx: Context, m: MediaDetector.Item) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(m.url), m.mime ?: MediaDetector.mimeFor(m.kind))
    if (m.headers.isNotEmpty()) view.putExtra("headers", m.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
    if (m.title.isNotBlank()) view.putExtra("title", m.title)
    val chooser = Intent.createChooser(view, null)
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(ctx, com.hplay.app.MainActivity::class.java)))
    runCatching { ctx.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun sourceLabel(s: Source) = stringResource(
    when (s) {
        Source.DLNA -> R.string.det_src_dlna
        Source.BROWSER -> R.string.det_src_browser
        Source.SHARE -> R.string.det_src_share
        Source.PLAYER -> R.string.det_src_player
    }
)

@Composable
private fun evidenceLabel(e: Evidence) = stringResource(
    when (e) {
        Evidence.CONTENT -> R.string.det_ev_content
        Evidence.MIME -> R.string.det_ev_mime
        Evidence.URL -> R.string.det_ev_url
        Evidence.NONE -> R.string.det_ev_none
    }
)

@Composable
private fun availabilityLabel(a: Availability) = stringResource(
    when (a) {
        Availability.UNCHECKED -> R.string.det_av_unchecked
        Availability.CHECKING -> R.string.det_av_checking
        Availability.OK -> R.string.det_av_ok
        Availability.NEEDS_HEADERS -> R.string.det_av_headers
        Availability.DENIED -> R.string.det_av_denied
        Availability.EXPIRED -> R.string.det_av_expired
        Availability.UNREACHABLE -> R.string.det_av_unreachable
    }
)
