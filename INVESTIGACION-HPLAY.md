# HPlay: ¿se puede tener «AirPlay» en Android sin Chromecast?

> Informe de investigación técnica. Fecha: **2026-10-12**.
> Etiquetas usadas en todo el documento:
> **[H]** = hecho verificado (documentación oficial, código fuente o especificación) ·
> **[I]** = inferencia razonada a partir de hechos · **[E]** = especulación u opinión, no comprobada.
> Las fuentes están al final (§12). Lo que no he podido verificar en esta sesión lo indico como tal.

---

## 0. Resumen en 30 segundos

- **Veredicto: PARCIALMENTE viable (confianza ~80 %).** Se puede hacer una APK que emita **pantalla + audio** a casi
  cualquier tele que tenga **navegador**, y que envíe **vídeos por URL** a cualquier tele con **DLNA** (casi todas las
  Smart TV). Lo que **no** se puede replicar: emitir contenido con DRM (Netflix, Disney+, Prime…) por duplicado de
  pantalla, ni que «cualquier app» funcione como AirPlay Video sin que la app colabore.
- **Antes de programar nada, prueba esto (gratis):** el «Proyectar pantalla / Smart Cast» de tu propio Android (Oppo/ColorOS
  lo trae) contra el «Screen Mirroring» de la tele, y **Web Video Caster** o **BubbleUPnP** contra el DLNA de la tele.
- **Conclusión honesta:** si solo quieres *usarlo*, un stick de **30–50 €** con Google Cast integrado (Xiaomi TV Stick 4K,
  Fire TV…) gana por goleada a desarrollar la APK. Como **proyecto de DAM** (aprender redes, códecs y Android nativo),
  la arquitectura **D (híbrida)** de §8 es un proyecto excelente y realista en 6–10 semanas para un MVP.

---

## 1. Cómo funciona realmente AirPlay

AirPlay no es «un» protocolo: son varios servicios sobre HTTP/RTSP que comparten descubrimiento y emparejamiento.

| Pieza | Qué hace | Detalle técnico | Etiqueta |
|---|---|---|---|
| **Descubrimiento** | El iPhone «ve» la tele | Bonjour = **mDNS + DNS-SD** (RFC 6762/6763). Servicios `_airplay._tcp` (vídeo/pantalla) y `_raop._tcp` (audio). El registro TXT anuncia capacidades (`features`, `model`, `pk`…) | [H] |
| **AirPlay Audio (AirPlay 1 / RAOP)** | Música a altavoces | RTSP para control + RTP para audio (ALAC/AAC), cifrado AES-128 con una clave que se envía cifrada con RSA | [H] |
| **AirPlay Video (por URL)** | La tele **descarga** el vídeo ella misma | El emisor hace `POST /play` con la URL (normalmente HLS). El móvil solo hace de mando: pausa, posición, volumen. Por eso no gasta batería ni pierde calidad | [H] |
| **AirPlay Mirroring** | Duplicar pantalla | El emisor codifica la pantalla en **H.264** (y AAC/ALAC el audio) y la envía en tiempo real (históricamente TCP 7100). Antes hay un intercambio **FairPlay SAP** (`fp-setup`) que deriva la clave de cifrado del flujo | [H] |
| **AirPlay 2** | Multiroom, buffer, HomeKit | Emparejamiento tipo **HomeKit (HAP)**: SRP-6a + Ed25519 + Curve25519, canal cifrado ChaCha20-Poly1305. Audio con buffer grande y sincronía por **PTP** (IEEE 1588) | [H] |
| **FairPlay** | Dos cosas distintas con el mismo nombre | (1) **FairPlay SAP**: autentica dispositivos y protege la clave del mirroring. (2) **FairPlay Streaming**: el DRM de Apple TV+/iTunes. El (2) no está roto ni hay implementación libre | [H] |

**Explicado fácil:** AirPlay Video es como decirle a la tele «abre este enlace»; Mirroring es como mandarle una
videollamada de tu pantalla. Lo primero tiene calidad perfecta y cero retardo de imagen; lo segundo tiene retardo y
pierde calidad, pero funciona con cualquier app.

### Estado de la ingeniería inversa
- **2004**: Jon Lech Johansen extrae la clave pública RSA del AirPort Express → primeros emisores RAOP libres. [H]
- **2011**: James Laird extrae la clave **privada** → nace **shairport** (receptor de audio). Hoy **shairport-sync** soporta AirPlay 2 audio. [H]
- **~2017–2019**: se reimplementa la parte de FairPlay SAP del **receptor** («playfair») → **RPiPlay** y su fork **UxPlay** (receptores de mirroring en Linux/macOS/Windows). [H]
- **pyatv** (Python): **cliente** para Apple TV/HomePod, con emparejamiento HAP, envío de audio RAOP y `play_url`. [H]
- **OwnTone** (antes forked-daapd): servidor que **emite** audio a altavoces AirPlay 1/2. [H]
- **AirConnect** (philippe44): puente UPnP/Cast → AirPlay (audio). [H]
- **android-airplay-server** (jqssun, sobre UxPlay, GPL-3.0, en F-Droid): **receptor** AirPlay 2 en Android/Android TV. No sirve para tu caso (es al revés), pero confirma que la pila está madura en el lado receptor. [H]

**Lo que falta:** no he encontrado ningún **emisor de mirroring AirPlay** libre y fiable. La razón [I]: la ingeniería
inversa se ha hecho sobre el **receptor** (lo que necesitaba la comunidad); el lado emisor del `fp-setup` requiere
reproducir lo que hace el iPhone, y los receptores certificados (teles Samsung/LG con AirPlay 2) además pueden
exigir emparejamiento HAP con PIN. Existen apps **comerciales** cerradas que afirman emitir AirPlay desde Android
(un blog de 2026 cita *PigeonCast* y *CastOne*); **no he podido verificar** cómo lo hacen ni su fiabilidad. [E]

---

## 2. Android como EMISOR AirPlay

| Modo | ¿Implementable en Android? | Cómo | Dificultad |
|---|---|---|---|
| Audio AirPlay 1 (RAOP) | **Sí** [H] | Protocolo bien documentado; hay código libre (pyatv, OwnTone, libraop de philippe44) portable vía NDK | 6/10 |
| Audio AirPlay 2 | **Sí, con esfuerzo** [H] | OwnTone ya lo hace en Linux; requiere HAP + PTP | 8/10 |
| Vídeo por URL (`/play`) | **Probablemente sí** [I] | pyatv lo hace contra Apple TV; contra teles de terceros con AirPlay 2 la compatibilidad no está documentada públicamente | 6/10 |
| Mirroring | **No con garantías** [I] | Falta un emisor libre del FairPlay SAP; sería ingeniería inversa propia | 9–10/10 |

**Requisito de partida:** la tele tiene que **tener AirPlay 2**. Samsung (2018+), LG (webOS 4+ en muchos modelos de
2018+), Vizio y algunos Sony/Google TV lo traen [H, según listas de Apple y fabricantes; comprueba tu modelo exacto].
Tu padre puede emitir desde el iPhone → **si es la misma tele, tu tele tiene AirPlay 2**, lo cual abre la arquitectura A.
Si tu padre emite a *otra* tele, no.

---

## 3. Qué puede capturar una app Android

| Versión | Cambio relevante | Etiqueta |
|---|---|---|
| Android 5 (API 21) | `MediaProjection`: captura de pantalla con permiso del usuario → `VirtualDisplay` → `Surface` de `MediaCodec` (H.264/HEVC por hardware) | [H] |
| Android 10 (API 29) | `AudioPlaybackCapture`: captura del audio de **otras apps**, solo usos `USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN`. Las apps pueden negarse (`allowAudioPlaybackCapture=false` o `setAllowedCapturePolicy`). Obligatorio un *foreground service* de tipo `mediaProjection` | [H] |
| Android 14 (API 34) | El consentimiento es **por sesión**: el token no se reutiliza; hay que pedir permiso cada vez. Hay que declarar `FOREGROUND_SERVICE_MEDIA_PROJECTION`. Opción de compartir **una sola app** en vez de toda la pantalla (QPR2) | [H] |
| Android 15 (API 35) | Indicador de grabación en la barra de estado; el sistema **oculta notificaciones sensibles** (códigos OTP) durante la captura | [H] |

**DRM y FLAG_SECURE** [H]: las ventanas con `FLAG_SECURE` (Netflix, Disney+, apps bancarias) salen **en negro** en
`MediaProjection`. El vídeo con Widevine L1 se decodifica en una superficie segura que no se puede leer. Muchas de
esas apps además desactivan la captura de audio. **No hay forma legítima de saltarlo** y hacerlo sería elusión de
DRM (ilegal en la UE y EE. UU.). Twitch y YouTube **no** usan DRM en sus streams normales → sí se capturan.

---

## 4. Emitir sin que la app lo soporte (estilo «AirPlay Video»)

**La idea:** en vez de duplicar la pantalla, averiguar la **URL del vídeo** (HLS `.m3u8`, DASH `.mpd` o MP4) y dársela a la tele.

- **Cómo lo hacen Web Video Caster, LocalCast, BubbleUPnP** [H por su funcionamiento público]: traen un **navegador
  integrado** (WebView) que vigila las peticiones de red; cuando detecta un `.m3u8`/`.mp4`, ofrece «enviar». Luego hablan
  con la tele por **DLNA** (UPnP AVTransport `SetAVTransportURI`), Google Cast, Roku o Fire TV. Varios incluyen un
  **proxy local** porque muchas teles DLNA no envían las cabeceras (`Referer`, cookies) que exige el servidor, o no
  entienden HLS.
- **Twitch** [H en cuanto a mecanismo, ver Streamlink]: el directo es HLS servido desde `usher.ttvnw.net`, con un token de
  acceso que se pide a su API. Streamlink lo implementa. Riesgos: Twitch cambia la API a menudo, inserta anuncios en
  el propio HLS, y sus condiciones de uso no permiten clientes no autorizados [I].
- **Límites:** no funciona con DRM, ni con apps nativas que no comparten URL (salvo con su botón «Compartir enlace» y
  luego resolver el enlace), ni con teles DLNA antiguas que no reproducen HLS.

---

## 5. Alternativas de protocolo

| Protocolo | Qué es | Latencia típica | Calidad | ¿La tele lo trae? | Dificultad para ti | Etiqueta |
|---|---|---|---|---|---|---|
| **Miracast** (Wi-Fi Display) | Duplicado por Wi-Fi Direct, H.264 | 100–300 ms | Media; cortes con interferencias | Muchas teles («Screen Mirroring»), pero **Android de stock lo quitó en 6.0**; algunos fabricantes (Oppo, Xiaomi, Samsung…) lo mantienen en su capa | No implementable por una app normal (necesita permisos de sistema) | [H]/[I] |
| **Google Cast** | La tele/stick carga la URL o una «app receptora» web | Por URL: ~0; mirroring: 300–800 ms | Muy buena | Solo con Chromecast/Google TV integrado | Fácil como emisor (SDK oficial), pero **no tienes receptor** | [H] |
| **DLNA/UPnP AV** | «Reproduce esta URL» | ~0 (la tele descarga) | Original | **Casi todas las Smart TV** | 4/10 (SSDP + SOAP) | [H] |
| **Navegador + WebSocket + MSE** | La tele abre una web del móvil | 300 ms–1 s | Buena | Si el navegador de la tele soporta MSE (Tizen/webOS modernos sí [I]) | 6/10 | [I] |
| **WebRTC** | Videollamada móvil→tele | 100–300 ms | Buena, adaptativa | Navegadores de tele: soporte **irregular** [I] | 7/10 | [I] |
| **HLS local** | El móvil sirve un `.m3u8` en directo | 3–10 s (LL-HLS 1–3 s) | Buena | DLNA/navegadores sí, en general | 5/10 | [H]/[I] |
| **RTSP/RTP** | Streaming clásico | 200–500 ms | Buena | Pocas teles; VLC/Kodi sí | 6/10 | [I] |
| **SRT** | Transporte robusto para directos | 120 ms+ | Buena | Teles no; OBS/VLC/ffmpeg sí | 6/10 | [H] |
| **NDI** | Vídeo por red profesional | <100 ms | Muy alta | No; PC con NDI Tools | SDK con licencia propia | [H] |
| **scrcpy** (USB/Wi-Fi a PC) | Duplicado al ordenador vía ADB | 35–70 ms | Muy buena, audio desde Android 11 | Necesita PC conectado a la tele | 0 (ya existe) | [H] |

---

## 6. El receptor: qué hace falta en la tele u ordenador

| Receptor | ¿Qué trae ya? | ¿Qué instalar? | Nota |
|---|---|---|---|
| **Samsung Tizen** 2018+ | AirPlay 2 (muchos modelos), Smart View (acepta duplicado tipo Miracast), DLNA, navegador | Nada | [H]/[I] Hay informes no verificados de que Samsung restringe Miracast a móviles de terceros |
| **LG webOS** 4+ | AirPlay 2 (muchos), Screen Share (Miracast), DLNA, navegador | Nada | [H]/[I] |
| **Sony / Philips / TCL con Android TV / Google TV** | **Chromecast integrado** | Nada | Si la tuya es Android TV, *ya* tienes Cast: revisa el menú [H] |
| **Tele «tonta» o vieja** | HDMI | Stick o PC | — |
| **Ordenador** | — | **scrcpy** (gratis), o un receptor web (arquitectura B), VLC (RTSP/HLS) | UxPlay solo sirve para emisores Apple |
| **Raspberry Pi / mini PC** | — | Kodi (DLNA), navegador en kiosco (arq. B), UxPlay (para tu padre) | 40–80 € |
| **Stick con Cast** | Google Cast | Nada | Chromecast con Google TV **descatalogado** (agosto de 2024) → Google TV Streamer (~110 €). Alternativas con Cast integrado: Xiaomi TV Stick 4K y similares (~40–50 €) [H/E precio] |

**¿Solo con APK y sin hardware?** **Sí**, si tu tele tiene al menos uno de: **navegador** (arq. B), **DLNA** (arq. C) o
**AirPlay 2** (arq. A). Casi todas las Smart TV de 2016+ cumplen los dos primeros [I].

---

## 7. Barreras legales y de plataforma

| Tema | Situación | Etiqueta |
|---|---|---|
| Marca «AirPlay» | No puedes usar el nombre ni el logo en la app; sí decir «compatible con dispositivos AirPlay» con cautela. Las licencias oficiales (MFi / AirPlay para fabricantes) no están abiertas a apps Android | [H] |
| Ingeniería inversa | UE: Directiva 2009/24/CE art. 6 permite descompilar **para interoperabilidad**. EE. UU.: DMCA §1201(f), excepción similar. Usar el código libre existente (GPL) es más seguro que extraer claves tú mismo | [H] texto legal / [I] aplicación |
| Licencias de código | UxPlay, RPiPlay, shairport-sync, android-airplay-server: **GPL** → si las usas, tu APK debe ser GPL y publicar el código | [H] |
| DRM | Saltarse Widevine/FLAG_SECURE: **ilegal** (art. 6 Directiva 2001/29/CE; DMCA §1201) y fuera de cualquier tienda | [H] |
| Google Play | Exige declarar el tipo de *foreground service* `mediaProjection` y justificar su uso; prohíbe apps que faciliten descargar contenido de YouTube/terceros violando sus condiciones. Un extractor de URLs de Twitch tiene **alto riesgo de rechazo** | [H] política / [I] riesgo |
| Fuera de Play | GitHub Releases + actualizador propio (tu modelo ParkH) o F-Droid: sin problema para uso personal | [H] |

---

## 8. Parte creativa: 4 arquitecturas

### A) Cliente AirPlay nativo («hablar AirPlay como un iPhone»)
```
[App] --mDNS _airplay._tcp--> descubre tele
  ├─ Vídeo por URL: POST /play {url}  ──► tele descarga el HLS
  └─ Audio: RTSP+RTP (RAOP, AES)       ──► tele/altavoz
  (Mirroring: FairPlay SAP emisor — NO resuelto)
```
- **Tecnologías:** Kotlin + NDK (C portado de libraop/pyatv), `NsdManager` para mDNS, SRP/Ed25519 (BouncyCastle).
- **Ventajas:** experiencia idéntica a tu padre para URL y audio; cero hardware.
- **Inconvenientes:** solo teles con AirPlay 2; mirroring inviable; emparejamiento HAP con teles de terceros poco documentado; riesgo de que una actualización de la tele lo rompa.
- **Dificultad:** 8/10 (URL+audio) · 10/10 con mirroring · **Latencia:** URL ~0 / audio 2 s (buffer AirPlay 2) · **Compatibilidad esperada:** 25–35 % de teles [E].

### B) Servidor en el móvil + receptor web en la tele («la tele abre una web»)
```
MediaProjection ─► VirtualDisplay ─► MediaCodec (H.264) ─┐
AudioPlaybackCapture ─► AudioRecord ─► MediaCodec (AAC) ─┤
                                                         ▼
                                  Servidor HTTP+WebSocket en el móvil (puerto 8080)
                                                         │
Tele: navegador → http://192.168.1.X:8080 ─► página con jmuxer (H.264→fMP4) ─► <video> MSE
```
- **Tecnologías:** Kotlin, `MediaProjection`, `MediaCodec`, Ktor o NanoHTTPD+Java-WebSocket; en la web, **jmuxer** (librería JS que convierte H.264/AAC crudo en fragmentos MP4 para MSE).
- **Ventajas:** funciona con **cualquier app sin DRM** (Twitch app, juegos, navegador); cero hardware si la tele tiene navegador; sirve también a un PC.
- **Inconvenientes:** hay que escribir la URL en la tele (mitigable con QR o DLNA, ver D); navegadores de tele lentos; batería y calor del móvil; sin DRM.
- **Dificultad:** 6/10 · **Latencia:** 300 ms–1 s [E] · **Compatibilidad:** 60–75 % [E] (depende de MSE en el navegador de la tele).

### C) Extractor de URL + «cast» por DLNA
```
WebView integrado ─intercepta .m3u8/.mp4─► lista de vídeos detectados
Botón «Compartir» de otra app ─► enlace ─► resolvedor (p. ej. Twitch) ─► URL HLS
                     │
SSDP (M-SEARCH) ─► tele DLNA ─► SOAP SetAVTransportURI(url | url del proxy local) ─► Play
                     ▲
       Proxy local opcional (añade cabeceras, reescribe HLS)
```
- **Tecnologías:** Kotlin, `WebViewClient.shouldInterceptRequest`, UDP multicast 239.255.255.250:1900, SOAP sobre HTTP.
- **Ventajas:** calidad original, la batería apenas se usa, el móvil queda libre.
- **Inconvenientes:** es lo que ya hacen Web Video Caster/BubbleUPnP (reinventas la rueda); resolvedores que se rompen; DLNA de teles con soporte HLS desigual.
- **Dificultad:** 5/10 · **Latencia:** ~0 (arranque 2–5 s) · **Compatibilidad:** 70–85 % de teles para MP4; menos para HLS [E].

### D) Híbrida original: **«HPlay»** — elige solo el mejor camino
La idea: el usuario no elige protocolo. La app **descubre qué sabe hacer la tele** y escoge:
```
            ┌─ ¿hay URL de vídeo? (WebView / Compartir) ── sí ─► DLNA (C) o AirPlay /play (A) ─► calidad original
Usuario ──► │
            └─ no (app nativa, juego, pantalla) ─► Mirroring (B)
                                                    │
                                      ¿cómo abre la tele la web del receptor?
                                      1. DLNA: le mando la URL de un HLS de baja latencia generado en el móvil
                                      2. Si no: QR/URL corta para el navegador de la tele
                                      3. Si es un PC: misma web, o scrcpy si hay cable
```
**Lo original:** usar **DLNA como «mando a distancia»** para que la tele abra el stream de mirroring sin teclear
nada (la tele DLNA reproduce un HLS servido por el móvil), y reservar el modo WebSocket de baja latencia para cuando
el usuario abra la web. Así cubres teles sin navegador decente (vía DLNA+HLS, más retardo) y con navegador (baja latencia).

- **Dificultad:** 7/10 · **Latencia:** URL ~0 · mirroring web 0,3–1 s · mirroring DLNA/HLS 3–6 s · **Compatibilidad:** 80–90 % de Smart TV [E].

### Diseño de HPlay

**Módulos**
| Módulo | Responsabilidad |
|---|---|
| `discovery` | SSDP (DLNA), mDNS (`_airplay._tcp`, `_googlecast._tcp`) con `NsdManager` |
| `capture` | Foreground service: `MediaProjection` + `AudioPlaybackCapture` → `MediaCodec` |
| `server` | HTTP (página receptora, HLS) + WebSocket (H.264/AAC crudo) |
| `urlcast` | WebView detector, resolvedor de enlaces compartidos, control DLNA (Play/Pause/Seek/Volumen) |
| `receiver-web` | `index.html` + jmuxer, se sirve desde los *assets* de la APK |

**Flujo de usuario**
1. Abre HPlay → lista de teles detectadas (icono según DLNA/AirPlay/navegador).
2. Elige «Enviar vídeo» (navegador integrado o enlace compartido) o «Duplicar pantalla».
3. Duplicar → diálogo del sistema de Android → la tele recibe la orden por DLNA, o se muestra la URL/QR.
4. Notificación persistente con «Parar».

**Esqueleto Kotlin de la parte clave (captura + servidor)**

```kotlin
// AndroidManifest: <uses-permission FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION, RECORD_AUDIO, INTERNET>
// <service android:name=".CaptureService" android:foregroundServiceType="mediaProjection" />

class CaptureService : Service() {
    private lateinit var projection: MediaProjection
    private lateinit var videoCodec: MediaCodec
    private val clients = CopyOnWriteArrayList<WebSocket>() // receptores conectados
    @Volatile private var running = true

    override fun onStartCommand(i: Intent, f: Int, id: Int): Int {
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14+: el resultado del diálogo solo vale para UNA sesión
        projection = mpm.getMediaProjection(i.getIntExtra("code", 0), i.getParcelableExtra("data")!!)
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, null)
        startVideo(1280, 720)
        startAudio()
        startServer(8080)
        return START_NOT_STICKY
    }

    private fun startVideo(w: Int, h: Int) {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // un keyframe por segundo: el receptor entra rápido
        }
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        videoCodec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = videoCodec.createInputSurface()
        projection.createVirtualDisplay("hplay", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        videoCodec.start()
        thread(name = "video-out") { drain(videoCodec, tag = 0x01) }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO se pide en la Activity antes de arrancar
    private fun startAudio() {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val rec = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(cfg)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48_000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
            .build()
        // shortcut: PCM → AAC con un segundo MediaCodec ("audio/mp4a-latm") y tag 0x02; omitido en el esqueleto
        rec.startRecording()
    }

    private fun drain(codec: MediaCodec, tag: Int) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = codec.dequeueOutputBuffer(info, 10_000)
            if (idx < 0) continue
            val buf = codec.getOutputBuffer(idx)!!
            val packet = ByteArray(info.size + 1).also { it[0] = tag.toByte(); buf.get(it, 1, info.size) }
            // Incluye SPS/PPS (BUFFER_FLAG_CODEC_CONFIG): guardarlos y reenviarlos a cada cliente nuevo
            clients.forEach { ws -> runCatching { ws.send(packet) }.onFailure { clients.remove(ws) } }
            codec.releaseOutputBuffer(idx, false)
        }
    }

    private fun startServer(port: Int) {
        // Ktor (embeddedServer(CIO)) o NanoHTTPD + Java-WebSocket:
        //  GET /        → assets/receiver/index.html (jmuxer + <video autoplay muted>)
        //  WS  /stream  → añade el socket a `clients`
    }

    override fun onDestroy() { running = false; videoCodec.stop(); projection.stop() }
    override fun onBind(i: Intent?) = null
}
```

Receptor web (lo que abre la tele), mínimo:
```html
<video id="v" autoplay muted playsinline style="width:100vw;height:100vh;background:#000"></video>
<script src="jmuxer.min.js"></script>
<script>
  const jm = new JMuxer({ node: 'v', mode: 'video', flushingTime: 0, fps: 30 });
  const ws = new WebSocket(`ws://${location.host}/stream`);
  ws.binaryType = 'arraybuffer';
  ws.onmessage = e => { const d = new Uint8Array(e.data); if (d[0] === 1) jm.feed({ video: d.subarray(1) }); };
</script>
```

---

## 9. Plan de MVP por fases

| Fase | Qué | Tiempo [E] | Herramientas | Criterio de «hecho» |
|---|---|---|---|---|
| 0 | **Investigar tu tele**: modelo exacto, ¿AirPlay 2? ¿DLNA? ¿navegador con MSE? (abrir en la tele una web de test de MSE) | 1 tarde | Manual de la tele, BubbleUPnP | Sabes qué arquitectura toca |
| 1 | Mirroring solo vídeo → navegador del **PC** | 1–2 semanas | Android Studio, Kotlin, Ktor, jmuxer | Ves la pantalla en Chrome del PC con <1 s |
| 2 | Lo mismo en el **navegador de la tele** + audio AAC | 1–2 semanas | `AudioPlaybackCapture`, segundo `MediaCodec` | Twitch app se ve y se oye en la tele |
| 3 | Descubrimiento DLNA + «Enviar URL» (MP4/HLS) | 1–2 semanas | SSDP, SOAP AVTransport | Un MP4 del navegador integrado se reproduce en la tele |
| 4 | Modo D: mirroring vía DLNA+HLS de baja latencia (sin teclear URL) | 2 semanas | `MediaMuxer`/segmentador HLS propio | La tele abre el mirroring sin tocar su mando |
| 5 | Pulido: QR, notificación, reconexión, ajustes de batería ColorOS, actualizador modelo ParkH | 1 semana | — | APK en GitHub Releases |
| (opc.) 6 | AirPlay `/play` y audio RAOP si tu tele tiene AirPlay 2 | 3–4 semanas | NDK, código de pyatv/libraop como referencia | Funciona con tu tele concreta |

**Total MVP útil (fases 0–3): ~4–6 semanas a tiempo parcial. Con fase 4–5: 6–10 semanas.** [E]

---

## 10. Entregables

### 10.1 Veredicto
**Parcialmente viable — confianza ~80 %.**
- ✅ Duplicar pantalla + audio de apps **sin DRM** a una tele con navegador o DLNA: **sí** (todas las piezas existen y están documentadas).
- ✅ Enviar vídeos web por URL a DLNA: **sí** (de hecho ya existe como app).
- ⚠️ Hablar AirPlay real desde Android: audio y URL **sí, con mucho trabajo**; mirroring AirPlay **no** con garantías.
- ❌ Netflix/Disney+/Prime por duplicado: **no**, ni lo hace nadie legalmente (ni siquiera Google Cast mirroring).
- ❌ Igualar la integración de AirPlay en iOS (botón en todas las apps): **no**, porque eso depende del sistema operativo, no de una app.

### 10.2 Tabla comparativa global

| Solución | Latencia | Calidad | DRM | Hardware extra | Dificultad (desarrollar) | Coste |
|---|---|---|---|---|---|---|
| Miracast del propio móvil (si lo trae) | 100–300 ms | Media | ❌ (negro) | No | 0 (ya existe) | 0 € |
| Cable USB-C→HDMI (si el móvil tiene DP Alt Mode) | ~0 | Máxima | ✅ (HDCP) | Cable | 0 | 10–20 € |
| Web Video Caster / BubbleUPnP → DLNA | ~0 (arranque 2–5 s) | Original | ❌ | No | 0 | 0–5 € |
| scrcpy → PC → HDMI | 35–70 ms | Muy buena | ❌ | PC cerca | 0 | 0 € |
| Stick con Google Cast | URL ~0 / mirror 0,3–0,8 s | Muy buena | ✅ (apps con Cast) | Stick | 0 | 40–110 € |
| A) Cliente AirPlay propio | URL ~0 / audio ~2 s | Original | ❌ | No (tele AirPlay 2) | 8–10 | Tu tiempo |
| B) Servidor + web | 0,3–1 s | Buena | ❌ | No (tele con navegador) | 6 | Tu tiempo |
| C) URL + DLNA propio | ~0 | Original | ❌ | No | 5 | Tu tiempo |
| D) HPlay híbrida | 0 / 0,3–1 s / 3–6 s | Buena–original | ❌ | No | 7 | Tu tiempo |
| Raspberry Pi/mini PC + Kodi/navegador | según modo | Buena | ❌ | Sí | 3 | 40–80 € |

### 10.3 Tu caso real: 5 soluciones para hoy (de más fácil a más difícil)

1. **Mirar si tu Android ya duplica a la tele (0 €, 5 min).** En ColorOS/Oppo: Ajustes → Conexión y uso compartido → **Proyección de pantalla / Screencast**. En la tele: activar «Screen Mirroring / Smart View / Screen Share». Si la tele es Samsung y no aparece, prueba también la app **SmartThings**. [I: depende de tu modelo de móvil y tele]
2. **Web Video Caster o BubbleUPnP + DLNA de la tele (0 € o unos pocos € la versión sin anuncios).** Para Twitch: abre twitch.tv en el navegador interno de Web Video Caster, espera a que detecte el vídeo y envíalo. Calidad original y el móvil queda libre. LocalCast es alternativa. [H funcionamiento / E precios]
3. **Cable USB-C → HDMI (10–20 €).** Solo si tu móvil soporta *DisplayPort Alt Mode* (muchos gama media de Oppo/Xiaomi **no**; compruébalo en las especificaciones antes de comprar). Retardo cero y funciona hasta con Netflix. [H]
4. **PC/portátil conectado a la tele + scrcpy (0 €).** Instala scrcpy (Genymobile, código abierto), activa la depuración USB en el móvil, conecta por cable o Wi-Fi. Audio incluido desde Android 11. Es el mejor duplicado de pantalla gratuito que existe. [H]
5. **Stick con Google Cast integrado (≈40–50 € Xiaomi TV Stick 4K o similares; ~110 € Google TV Streamer).** Solución definitiva: botón Cast en Twitch, YouTube, Netflix, Disney+… con DRM y calidad máxima. Un Fire TV Stick (≈30–40 € en oferta) también vale con apps como AirScreen/la propia de Amazon, pero sin Cast nativo. [H producto / E precios, varían mucho]

**Extra para tu padre y tú a la vez:** si la tele tiene AirPlay 2 y la solución 5, cada uno usa lo suyo sin conflicto.

### 10.4 Hoja de ruta si lo desarrollas tú

**Stack recomendado**
- Kotlin nativo (no Capacitor: necesitas `MediaProjection`, `MediaCodec` y servicios en primer plano a bajo nivel).
- minSdk **29** (Android 10, por `AudioPlaybackCapture`), targetSdk 35+.
- Servidor: **Ktor (CIO)** con WebSockets, o NanoHTTPD + Java-WebSocket si quieres algo más ligero.
- Receptor: HTML + **jmuxer**, empaquetado en `assets/`.
- DLNA: SSDP y SOAP escritos a mano (son pocas peticiones; evita librerías UPnP pesadas y viejas).
- AirPlay (opcional): NDK + código de referencia de pyatv / libraop. Ojo con licencias GPL.
- Distribución: GitHub Releases + actualizador modelo ParkH (fuera de Play Store).

**Riesgos principales**
| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| El navegador de tu tele no soporta MSE o va a tirones | Media | Fase 0: probarlo antes de escribir código; plan B DLNA+HLS |
| Calor/batería del móvil en sesiones largas | Alta | 720p/30 fps, bitrate adaptativo, cargador |
| ColorOS mata el servicio en segundo plano | Alta (ya te pasó en ParkH) | Pantalla de ajustes de batería, como en tus otras apps |
| Apps que bloquean captura de audio/pantalla | Segura en apps con DRM | Avisar en la interfaz; nunca prometer lo que no se da |
| Resolvedores de URL (Twitch) que se rompen | Alta | Mantenerlos fuera del núcleo; preferir WebView + detección |
| Wi-Fi saturado / 2,4 GHz | Media | Recomendar 5 GHz; indicador de calidad |
| Reinventar Web Video Caster | Segura | Centrar el valor en el modo híbrido D, que no existe tal cual [I] |

### 10.5 Conclusión honesta
- **Para ver Twitch en la tele mañana:** no desarrolles nada. Prueba las soluciones 1, 2 y 4 (gratis). Si ninguna te
  convence, **compra un stick de 40–50 € con Google Cast**. Desarrollar la APK no compensa como «producto para usar»:
  semanas de trabajo para acabar con algo peor que un stick (sin DRM y con más retardo).
- **Como proyecto de aprendizaje o de portfolio DAM:** **sí compensa.** HPlay (arquitectura D) toca redes (SSDP, mDNS,
  WebSocket), multimedia (H.264, AAC, MSE, HLS) y Android avanzado (MediaProjection, servicios en primer plano,
  permisos de Android 14/15). Es demostrable en clase con cualquier tele o un portátil.
- **Lo que no haría:** intentar implementar mirroring AirPlay desde cero. Es un pozo sin fondo de ingeniería inversa
  para cubrir solo las teles que ya tienen AirPlay.

---

## 11. Glosario rápido
- **mDNS/Bonjour:** «gritar» en la red local «¿quién es una tele?» sin servidor central.
- **SSDP:** lo mismo, pero en el mundo DLNA/UPnP.
- **HLS / DASH:** vídeo partido en trocitos de pocos segundos con una lista (`.m3u8` / `.mpd`).
- **MSE (Media Source Extensions):** permite que una página web meta trocitos de vídeo en un `<video>` a mano.
- **Codec por hardware (MediaCodec):** el chip del móvil comprime vídeo sin gastar CPU.
- **DRM / Widevine / FLAG_SECURE:** candados que impiden capturar o copiar el vídeo protegido.

---

## 12. Fuentes
Protocolo AirPlay y proyectos:
- Especificación no oficial de AirPlay (comunidad): https://openairplay.github.io/airplay-spec/
- Análisis de AirPlay 2 (Emanuele Cozzi): https://emanuelecozzi.net/docs/airplay2
- UxPlay: https://github.com/FDH2/UxPlay · RPiPlay: https://github.com/FD-/RPiPlay
- shairport-sync: https://github.com/mikebrady/shairport-sync
- pyatv: https://pyatv.dev/ · https://github.com/postlund/pyatv
- OwnTone: https://github.com/owntone/owntone-server · AirConnect: https://github.com/philippe44/AirConnect
- [android-airplay-server (jqssun)](https://github.com/jqssun/android-airplay-server) · [F-Droid](https://f-droid.org/packages/io.github.jqssun.airplay/)
- [AirPlay on Android: what works (blog, fuente secundaria)](https://bestforandroid.com/airplay-available-on-android.md)
- RFC 6762 (mDNS) y RFC 6763 (DNS-SD): https://www.rfc-editor.org/rfc/rfc6762 · https://www.rfc-editor.org/rfc/rfc6763

Android:
- MediaProjection: https://developer.android.com/media/grow/media-projection
- AudioPlaybackCapture: https://developer.android.com/media/platform/av-capture
- Cambios de Android 14 (MediaProjection, foreground services): https://developer.android.com/about/versions/14/behavior-changes-14
- Tipos de foreground service: https://developer.android.com/develop/background-work/services/fg-service-types
- `FLAG_SECURE`: https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE

Emisión y receptores:
- UPnP AV / DLNA (AVTransport): https://openconnectivity.org/developer/specifications/upnp-resources/upnp/
- Google Cast: https://developers.google.com/cast
- scrcpy: https://github.com/Genymobile/scrcpy
- jmuxer: https://github.com/samirkumardas/jmuxer
- Streamlink (plugin de Twitch, para entender el HLS): https://github.com/streamlink/streamlink
- [Teles con AirPlay 2 en 2026 (iGeeksBlog)](https://www.igeeksblog.com/airplay-2-supported-tvs/) · [Lista histórica (Wccftech)](https://wccftech.com/list-all-the-tvs-that-will-receive-airplay-2-constantly-updated/amp/) · [LG ThinQ y AirPlay 2 (PhoneArena)](https://www.phonearena.com/news/Apple-AirPlay-2-support-LG-ThinQ-AI-TVs_id117691)
- [Miracast (Wikipedia)](https://en.wikipedia.com/wiki/Miracast) · [Foro: Samsung y Smart View](https://forums.androidcentral.com/threads/smartview.1019472)

Legal:
- Directiva 2009/24/CE (programas de ordenador), art. 6: https://eur-lex.europa.eu/eli/dir/2009/24/oj
- Directiva 2001/29/CE, art. 6 (medidas tecnológicas): https://eur-lex.europa.eu/eli/dir/2001/29/oj
- 17 U.S.C. §1201 (DMCA): https://www.law.cornell.edu/uscode/text/17/1201
- Políticas de Google Play (foreground services): https://support.google.com/googleplay/android-developer/answer/13392821

> **No verificado en esta sesión:** los enlaces de fuentes «clásicas» (los que no tienen texto entre corchetes) los cito
> de memoria y no los he abierto hoy; los precios son orientativos de octubre de 2026; las apps comerciales emisoras de
> AirPlay (PigeonCast, CastOne) no las he probado.
