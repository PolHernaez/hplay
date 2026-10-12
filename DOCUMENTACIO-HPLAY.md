# HPlay — documentación viva

> Última actualización: 2026-10-12 · versión 1.0 · Investigación previa: [INVESTIGACION-HPLAY.md](INVESTIGACION-HPLAY.md)

## 0. Estado

- `assembleDebug` y 14 tests unitarios pasan (Gradle 9.6, AGP 9.4.1, Kotlin 2.3.21, compileSdk 37, targetSdk 35, minSdk 29).
- **Probado en emulador API 34** (2026-10-12): descubrimiento mDNS real de la red (Nest Audio, Sonos Era 300),
  duplicado → navegador (MSE, vídeo continuo, ~0,2–0,3 s de búfer), rotación (576×1280 → 1280×576 sin cortes),
  `/live.ts` y `/live.m3u8` validados con ffprobe (H.264 Constrained Baseline + AAC-LC, PTS monótonos),
  vídeo por URL (HLS de prueba de Mux) → navegador a 720p por el proxy, mando remoto (pausa, posición 0:07/10:34),
  receptor Android TV descubierto por SSDP y abierto solo con `POST /open`, parada limpia (servicio y servidor cerrados).
- **Sin probar todavía con hardware real**: teles DLNA (LG/Samsung/Sony…), Roku, webOS/Tizen, móviles ColorOS/MIUI.

## 1. Arquitectura

```
MediaProjection ─► VirtualDisplay ─► FramePump (OpenGL, ≥10 fps) ─► MediaCodec H.264 ─┐
AudioPlaybackCapture ─► AudioRecord ─► MediaCodec AAC-LC ─────────────────────────────┤
                                                                                       ▼
                                          MediaHub (un codificador, varios destinos)
                          ┌───────────────────────┬───────────────────────────┐
                          ▼                       ▼                           ▼
             WebSocket /{t}/ws           MPEG-TS /{t}/live.ts        HLS /{t}/live.m3u8
             (receptor web MSE)          (teles DLNA)                (teles DLNA, Roku, Safari)
```

- **Servidor HTTP + WebSocket propio** (`server/HttpServer.kt`): sin dependencias, `TCP_NODELAY`, cola por cliente,
  solo acepta IP privadas/enlace local/loopback, y todas las rutas van bajo un **token aleatorio de 6 caracteres** por sesión
  (también es el «código» que se teclea en la tele). Un token erróneo responde 404 tras 300 ms.
- **Descubrimiento** (`discovery/`): SSDP (MediaRenderer, AVTransport, `roku:ecp`, receptor HPlay, `ssdp:all`) con
  `MulticastLock`; mDNS con `NsdManager` (`_googlecast`, `_airplay`, `_raop`, `_androidtvremote2`); IP manual con
  M-SEARCH unicast. Todo se fusiona por IP en una lista.
- **Perfil por aparato** (`ProfileCache`): ProtocolInfo «Sink» del ConnectionManager + lo que el navegador receptor dice
  al conectar (MSE, Baseline/High, AAC, tamaño de pantalla, User-Agent). Se cachea en SharedPreferences y decide
  resolución/bitrate/perfil la siguiente vez (`EncoderProfile`).

## 2. Decisiones de latencia

| Decisión | Valor | Por qué |
|---|---|---|
| GOP | 1 s (`I_FRAME_INTERVAL=1`) | Un cliente nuevo o un segmento HLS arranca en ≤1 s |
| Fotogramas B | 0 | Orden de decodificación = presentación; menos retardo |
| Perfil | Constrained Baseline (High solo si el navegador lo confirma) | Lo decodifica cualquier tele |
| Resolución | lado largo 1280 (1920 si el receptor dijo tener 1080p) | Calor, batería y Wi-Fi |
| Bitrate | ~0,1 bit/píxel/fotograma, 1,5–8 Mbps, CBR si existe | Tasa estable |
| Vídeo continuo | `FramePump` redibuja el último fotograma cada 100 ms | Android deja de emitir con la pantalla quieta (`REPEAT_PREVIOUS_FRAME` solo repite ~10 veces) y en MSE un hueco congela la imagen y desincroniza el audio. **Detectado en la prueba del emulador** |
| Clave bajo demanda | `REQUEST_SYNC_FRAME` al entrar un cliente o si se atasca (máx. 1 cada 300 ms) | Arranque inmediato |
| Cliente lento | se descartan fotogramas hasta el siguiente clave | Latencia antes que fluidez |
| Navegador | jmuxer `flushingTime:0`, `maxDelay:500` + salto si el búfer >0,8 s | <1 s extremo a extremo |
| Tiempos | audio por recuento de muestras, re-anclado si se desvía >200 ms; duraciones en ms por diferencia de tiempos absolutos | Sin deriva acumulada (test `durationsDoNotDrift`) |
| HLS | segmentos ~1 s, lista de 4, se guardan 10 | 3–6 s en teles (su búfer manda) |
| SPS/PPS | delante de cada clave + PAT/PMT delante de cada clave en TS | Cualquier cliente puede entrar a mitad |

## 3. Tabla de compatibilidad (modo que se usa solo → latencia esperada)

| Marca / sistema | Duplicar pantalla | Enviar vídeo | Latencia espejo |
|---|---|---|---|
| PC (Chrome, Edge, Firefox) | Navegador + QR (MSE) | Navegador (hls.js / MP4) | 0,2–0,6 s |
| Android TV / Google TV / Fire TV con **app HPlay** | App receptora (se abre sola) | App receptora | 0,3–0,8 s |
| Android TV / Fire TV sin app | Navegador de la tele (si tiene) o QR | Navegador | 0,3–1 s |
| LG webOS 4+ | DLNA TS → DLNA HLS; o app/navegador webOS | DLNA directo | DLNA 1–3 s · navegador 0,4–1 s |
| Samsung Tizen 2018+ | DLNA TS → DLNA HLS; o app/navegador Tizen | DLNA directo (con DIDL) | DLNA 1–3 s · navegador 0,4–1 s |
| Sony / Philips / TCL / Hisense (Android TV o propios) | DLNA TS → HLS; o app HPlay | DLNA directo | 1–3 s |
| Xiaomi (PatchWall / Google TV) | DLNA o app HPlay | DLNA directo | 1–3 s |
| Roku | HLS por ECP («Play on Roku») | ECP directo | 3–6 s |
| Tele antigua solo DLNA (sin MSE) | DLNA HLS | DLNA directo | 3–6 s |
| Solo Google Cast / solo AirPlay | Se detecta; se usa navegador/QR | Navegador/QR | — |

Las cifras de latencia son **estimaciones** hasta probar cada marca (ver §4).

## 4. Guía de pruebas (5 casos)

Preparación: móvil y receptor en la **misma Wi-Fi** (mejor 5 GHz). Instalar `app-debug.apk`.

1. **Twitch en el navegador integrado** → «Enviar vídeo», escribir `twitch.tv/<canal>`, reproducir. Aparece una línea
   `HLS usher.ttvnw.net/…` → «Enviar». *Hecho si*: la tele (DLNA) o el PC (QR) reproduce el directo; pausa y volumen
   responden. Si la tele DLNA lo rechaza, la app muestra el aviso y deja el QR.
2. **MP4 por DLNA** → elegir la tele DLNA en la lista, compartir un enlace `.mp4` desde el navegador del móvil a HPlay
   → «Enviar». *Hecho si*: «La tele reproduce el vídeo en calidad original», la barra de posición avanza y «Buscar» funciona.
3. **Duplicado al navegador de la tele** → elegir «Ninguno», «Duplicar pantalla», abrir en el navegador de la tele la
   dirección (o `http://IP:8765` y teclear el código). Pulsar OK en el mando (sonido + pantalla completa).
   *Hecho si*: retardo <1 s (cronómetro en el móvil vs. tele) y suena el audio de un vídeo de YouTube web.
4. **Duplicado a un PC** → igual que 3 en Chrome/Edge/Firefox. Girar el móvil: la imagen cambia de orientación en ~1 s.
   Desconectar el Wi-Fi del PC 10 s y volver: el receptor se reconecta solo.
5. **Reserva HLS** → elegir una tele DLNA que no acepte TS (o forzar abriendo `http://IP:8765/<código>/live.m3u8` en
   Safari/VLC). *Hecho si*: reproduce con 3–6 s de retardo. Comprobar con `ffprobe http://IP:8765/<código>/live.m3u8`.

Extras: pantalla apagada >5 min con la emisión activa (ColorOS: hacer antes la pantalla «Batería»); Netflix → negro y aviso.

## 5. Limitaciones conocidas

- **DRM / FLAG_SECURE**: Netflix, Disney+, Prime, HBO, apps de banco salen en negro y sin audio. Es intencionado y legal; la app lo avisa.
- **Audio interno**: solo apps que permiten la captura (`USAGE_MEDIA/GAME/UNKNOWN`); llamadas, WhatsApp y apps que lo
  prohíben no se oyen en la tele. Sin permiso de micrófono → espejo sin audio (aviso en pantalla).
- **Google Cast y AirPlay**: no se emite a ellos (protocolos propietarios con autenticación; no se hace ingeniería inversa).
  Solo se detectan para que el usuario sepa qué tiene.
- **RAOP / AirPlay 1 audio**: módulo **no incluido**. Sería viable con libraop (MIT) o código de shairport/pyatv vía NDK,
  pero casi todos los receptores actuales (AirPlay 2) exigen emparejamiento HAP. Queda como módulo opcional documentado.
- **DASH (.mpd)**: se detecta y se envía por DLNA, pero pocas teles lo reproducen y el receptor web no incluye dash.js.
- **Contenido con cookies cortas o IP ligada** (algunos CDN): el proxy lo resuelve casi siempre; si el token caduca, hay que reenviar.
- **Cambio de Wi-Fi**: el espejo se reenvía solo a la tele; un vídeo por URL que pase por el proxy hay que reenviarlo.
- **Navegadores de tele sin MSE** (webOS <3, Tizen <3, Opera TV antiguo): pasan a HLS nativo si lo tienen; si no, solo DLNA.
- **Latencia DLNA**: la marca el búfer del reproductor de la tele (1–6 s); no se puede bajar desde el móvil.
- **Roku**: el canal 15985 («Play on Roku») debe estar disponible; sin búsqueda ni volumen por ECP.
- **webOS/Tizen**: hay que instalar en modo desarrollador (abajo); el código del móvil se teclea cada sesión (seguridad).
- **Batería/calor**: 720p30 por hardware es moderado, pero sesiones largas calientan; recomendable cargador.
- Probado solo en emulador; ver §0.

## 6. Apps receptoras opcionales

- **Android TV / Fire TV** (`receivers/androidtv`): instalar `androidtv-debug.apk` (Fire TV: Ajustes → Mi Fire TV →
  Opciones de desarrollador → Apps de origen desconocido; luego `adb connect IP_TV` y `adb install`). Ábrela una vez:
  queda escuchando (puerto 8766, SSDP). En el móvil aparece como «App HPlay» y el espejo se abre sin tocar el mando.
- **LG webOS** (`receivers/webos`): activar Developer Mode (app «Developer Mode» de LG Content Store),
  `npm i -g @webos-tools/cli`, `ares-setup-device`, `ares-package receivers/webos`, `ares-install com.hplay.receiver_1.0.0_all.ipk`.
- **Samsung Tizen** (`receivers/tizen`): Tizen Studio + certificado Samsung, modo desarrollador en la tele (Apps → 12345),
  `tizen package -t wgt -s <perfil> -- receivers/tizen`, `tizen install -n HPlay.wgt -t <tele>`.
- Las apps webOS/Tizen recuerdan la IP del móvil y solo piden el código de 6 letras.

## 7. Seguridad y privacidad

Servidor solo en red local (filtro por IP de origen) y token por sesión; el proxy rechaza destinos loopback y solo
reenvía Referer/User-Agent/Cookie/Origin/Authorization; el receptor de Android TV solo abre URLs `http` de IP privadas.
Sin telemetría, sin analíticas, sin peticiones a internet salvo las que hace el usuario en el navegador integrado o los
vídeos que envía. Permisos: red, multicast, micrófono (audio interno), notificaciones, servicio en primer plano
(mediaProjection/mediaPlayback) y exclusión de batería.

## 8. Supuestos tomados

- Puerto 8765 (si está ocupado, uno libre) y 8766 para la app de TV.
- 720p30 por defecto; 1080p solo con receptor 1080p confirmado.
- Interfaz en inglés por defecto y español (`values-es`); el receptor web y las apps de tele se traducen según el idioma del navegador.
- Distribución fuera de Google Play (GitHub Releases).

## 9. Registro de cambios

- **2026-10-12 · 1.0** — Primera versión completa: espejo (navegador, DLNA TS/HLS, Roku, app TV), vídeo por URL con
  proxy, descubrimiento SSDP/mDNS/manual, receptores Android TV/webOS/Tizen, CI. Tras la prueba en emulador: `FramePump`
  (vídeo continuo con pantalla quieta), rutas relativas en el proxy, hls.js preferido a HLS «nativo», audio sin pulsar en la app TV.
