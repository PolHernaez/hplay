# HPlay

Convierte tu **PC o tele en un receptor** al que enviar vídeos desde el móvil, como un Chromecast pero sin comprar nada,
y emite también la **pantalla y el audio** del móvil a cualquier tele u ordenador de la red local.
Gratis, sin anuncios, sin telemetría. Licencia **GPL-3.0**.

## Enviar vídeos desde tus apps (como un Chromecast)

Instala el **receptor** en el PC o en la tele y déjalo abierto. Aparecerá en el botón de enviar a la tele de las apps
de vídeo que usan **DLNA/UPnP** (la mayoría de apps de pelis y series, navegadores tipo Web Video Caster, BubbleUPnP,
galerías, VLC…). La app le pasa el enlace del vídeo y el receptor lo reproduce en calidad original; el móvil queda libre.

| Receptor | Dónde | Cómo |
|---|---|---|
| `HPlay-Receptor.exe` | PC Windows 10/11 | Doble clic (portable, 1,5 MB). Firewall: permitir en **redes privadas** |
| `HPlay-Receptor-TV.apk` | Android TV, Google TV, Fire TV | Instalar y abrir una vez |

Controles desde la app del móvil: reproducir, pausa, avanzar/retroceder, volumen y parar.

> El botón de **Google Cast** (YouTube, Twitch, Netflix…) solo funciona con aparatos certificados por Google: ningún
> receptor libre puede aparecer ahí. Para esas apps usa la app HPlay del móvil (enviar vídeo o duplicar pantalla).

## App del móvil (opcional)

Para las apps sin botón DLNA: **Enviar vídeo** (navegador integrado que detecta el vídeo y lo manda a la tele) y
**Duplicar pantalla**. Elige sola el mejor camino:

| Botón | Camino (de mejor a peor, automático) |
|---|---|
| **Duplicar pantalla** | App HPlay de la tele/PC → DLNA con MPEG-TS en directo → DLNA con HLS → Roku (HLS) → dirección + QR para cualquier navegador |
| **Enviar vídeo** | DLNA directo (calidad original) → DLNA por el proxy local (cabeceras/cookies) → receptor HPlay → Roku → navegador (hls.js) |

## Compilar

Requisitos: JDK 17+ (probado con 25), Android SDK con la plataforma 37.

```bash
./gradlew assembleDebug testDebugUnitTest
```

APKs: `app/build/outputs/apk/debug/app-debug.apk` (móvil) y `receivers/androidtv/build/outputs/apk/debug/androidtv-debug.apk` (tele).
Receptor de Windows (.NET SDK 8+; genera un .exe para .NET Framework 4.8, que ya viene con Windows):

```bash
dotnet publish receivers/windows/HPlayReceiver.csproj -c Release -o out
```

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

Release firmada: exporta `HPLAY_KEYSTORE`, `HPLAY_KEYSTORE_PASSWORD`, `HPLAY_KEY_ALIAS`, `HPLAY_KEY_PASSWORD` y ejecuta
`./gradlew assembleRelease`. GitHub Actions hace lo mismo con los secrets `HPLAY_KEYSTORE_BASE64` + los tres restantes y
publica un release al subir una etiqueta `v*`.

## Estructura

```
app/                      APK del móvil (Kotlin, Compose, minSdk 29, targetSdk 35)
  capture/                Servicio en primer plano, MediaProjection, H.264 (FramePump + MediaCodec), AAC
  stream/                 MediaHub (un codificador → todos los destinos), muxer MPEG-TS, segmentador HLS
  server/                 HTTP + WebSocket propio, proxy de vídeo con reescritura HLS
  discovery/              SSDP, mDNS (NsdManager), descripción UPnP, perfiles cacheados
  cast/                   SOAP DLNA (AVTransport, RenderingControl), Roku ECP, cadena de modos
  assets/receiver/        Receptor web (ES5, jmuxer, hls.js) servido desde el móvil
receivers/windows/        Receptor .exe para Windows: Media Renderer DLNA + receptor HPlay (WebView2)
receivers/androidtv/      Receptor para Android TV / Fire TV: el mismo Media Renderer DLNA
receivers/shared/         Reproductor común (player.html + hls.js)
receivers/webos/          App para LG webOS (.ipk)
receivers/tizen/          App para Samsung Tizen (.wgt)
```

Documentación técnica, compatibilidad, pruebas y limitaciones: [DOCUMENTACIO-HPLAY.md](DOCUMENTACIO-HPLAY.md).

## Licencias de terceros

[jmuxer](https://github.com/samirkumardas/jmuxer) (MIT), [hls.js](https://github.com/video-dev/hls.js) (Apache-2.0),
[ZXing](https://github.com/zxing/zxing) (Apache-2.0). Textos en `app/src/main/assets/licenses/`.

«AirPlay», «Chromecast», «Google Cast», «Miracast», «Roku» y demás son marcas de sus propietarios; se citan solo para
indicar compatibilidad. HPlay no está afiliado a ninguno.
