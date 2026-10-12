# HPlay

Emite la **pantalla, el audio y vídeos por URL** desde un móvil Android a cualquier tele u ordenador de la red local,
sin Chromecast ni hardware extra. Gratis, sin anuncios, sin telemetría. Licencia **GPL-3.0**.

La app detecta qué sabe hacer cada aparato y elige sola el mejor camino:

| Botón | Camino (de mejor a peor, automático) |
|---|---|
| **Duplicar pantalla** | App HPlay de la tele → DLNA con MPEG-TS en directo → DLNA con HLS en directo → Roku (HLS) → dirección + QR para cualquier navegador |
| **Enviar vídeo** | DLNA directo (calidad original) → DLNA por el proxy local (cabeceras/cookies) → Roku → navegador receptor (hls.js) |

Compatible con: televisores DLNA/UPnP (LG, Samsung, Sony, Philips, TCL, Hisense, Xiaomi…), Roku, Android TV / Google TV /
Fire TV (con la app receptora o su navegador) y cualquier PC con Chrome, Edge o Firefox. Los aparatos con Google Cast o
AirPlay se detectan y se muestran, pero HPlay no usa esos protocolos propietarios (ver [limitaciones](DOCUMENTACIO-HPLAY.md#5-limitaciones-conocidas)).

## Compilar

Requisitos: JDK 17+ (probado con 25), Android SDK con la plataforma 37.

```bash
./gradlew assembleDebug testDebugUnitTest
```

APKs: `app/build/outputs/apk/debug/app-debug.apk` (móvil) y `receivers/androidtv/build/outputs/apk/debug/androidtv-debug.apk` (tele).

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
receivers/androidtv/      APK receptora para Android TV / Fire TV (SSDP + WebView a pantalla completa)
receivers/webos/          App para LG webOS (.ipk)
receivers/tizen/          App para Samsung Tizen (.wgt)
```

Documentación técnica, compatibilidad, pruebas y limitaciones: [DOCUMENTACIO-HPLAY.md](DOCUMENTACIO-HPLAY.md).

## Licencias de terceros

[jmuxer](https://github.com/samirkumardas/jmuxer) (MIT), [hls.js](https://github.com/video-dev/hls.js) (Apache-2.0),
[ZXing](https://github.com/zxing/zxing) (Apache-2.0). Textos en `app/src/main/assets/licenses/`.

«AirPlay», «Chromecast», «Google Cast», «Miracast», «Roku» y demás son marcas de sus propietarios; se citan solo para
indicar compatibilidad. HPlay no está afiliado a ninguno.
