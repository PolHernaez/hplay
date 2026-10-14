# HPlay — Recerca de compatibilitat (fase 2)

Data: 2026-10-14. Completa `INVESTIGACION-HPLAY.md` (AirPlay, protocols, idea de l'extractor d'URL) sense repetir-lo.
Els repositoris s'han comprovat amb l'API de GitHub el mateix dia: existeixen, i se n'ha mirat la llicència i l'última activitat.

**Etiquetes de fiabilitat**
- **[D]** documentat oficialment o visible al codi.
- **[C]** demostrat per la comunitat (eines que funcionen, però sense document oficial).
- **[P]** cal provar-ho nosaltres.

**Punt de partida.** HPlay té llicència **GPL-3.0**. Per això pot fer servir codi MIT, BSD, Apache-2.0, MPL-2.0, LGPL i GPL-3.
**No** pot incloure codi CDDL/EPL (jUPnP) ni codi sense llicència (DLNA-Cast). La regla 5 del `CLAUDE.md` continua vigent:
res de DRM, de FLAG_SECURE, ni de Cast/AirPlay copiats per enginyeria inversa.

---

## 1. Les troballes més importants

1. **L'equivalent legal del «Chromecast amb el mòbil apagat» per a Netflix, YouTube, Disney+ i els altres no és fer-se
   passar per Chromecast. És demanar a la tele que obri la seva pròpia app oficial al vídeo concret.** L'app de la tele
   reprodueix el contingut amb els seus permisos i el seu DRM, i el mòbil ja no fa falta. Hi ha vies documentades o provades:
   - Intents d'Android a Android TV i Fire TV: l'app receptora de HPlay ja hi és instal·lada, així que no cal cap protocol nou.
   - Roku ECP `launch/<canal>?contentId=…`.
   - LG webOS `system.launcher/launch` amb `contentId`.
   - DIAL, a les teles que el tenen.
   **És la millora que més serveis cobreix.**
2. **FCast** ([futo-org/fcast](https://github.com/futo-org/fcast), MIT, actiu) és un protocol **obert** que fa el mateix
   que Chromecast: envia una URL i controla la reproducció. Funciona per TCP al port 46899 amb missatges JSON, i ja admet
   **capçaleres HTTP** dins l'ordre de reproduir.
   - L'app **Grayjay** (YouTube, Twitch, PeerTube… amb connectors) ja envia per FCast.
   - Si els receptors de HPlay parlen FCast, Grayjay i les eines FCast hi podran enviar.
   - Si l'app de HPlay en parla, podrà enviar als receptors FCast.
   - El protocol és molt petit: unes 200 línies per banda [D].
3. **Google Cast com a emissor sí que és possible**, amb el SDK oficial i el *Default Media Receiver*, que no cal registrar [D].
   No és enginyeria inversa: HPlay podria enviar URLs i el duplicat HLS a qualsevol Chromecast o Google TV.
   **Inconvenient:** el SDK és propietari i no és compatible amb la GPL-3 tal com està (vegeu §10).
4. **Kodi** té una API oficial (JSON-RPC `Player.Open`) i accepta capçaleres dins la URL
   (`url|Referer=…&User-Agent=…`) [D/C]. Afegir-lo com a destinació és barat i resol webs que demanen `Referer` sense el proxy.
5. **Play Shots** fa servir la biblioteca de codi obert [devin1014/DLNA-Cast](https://github.com/devin1014/DLNA-Cast)
   (Cling). Ho hem comprovat comparant el codi descompilat amb el repositori (`DLNACastManager.kt`, `DeviceRegistryImpl.kt`) [D].
   Es pot compilar l'app d'exemple d'aquesta biblioteca a l'emulador i **reproduir el comportament de Play Shots sense el mòbil**.
   Ens serveix per provar, però no la podem integrar perquè no té llicència.
6. La detecció de vídeos del navegador integrat de HPlay només mira **l'extensió** de la URL (`.m3u8`, `.mpd`, `.mp4`…,
   a `ui/MainViewModel.kt`). Moltes webs serveixen els manifests sense extensió.
   Si hi afegim un script injectat que vigili `<video>`, `MediaSource`, `fetch` i `XHR` i el tipus MIME de la resposta,
   la detecció millora molt amb poc codi [P].
7. Les biblioteques que barregen molts protocols ([Connect SDK](https://github.com/ConnectSDK/Connect-SDK-Android),
   Apache-2.0, últim commit el 2024-03-19) estan **aturades**. HPlay ja té la interfície `Remote` (`cast/Remote.kt`),
   així que val més afegir **adaptadors petits** que importar una biblioteca gran sense manteniment.

---

## 2. Els millors projectes de codi obert

| # | Projecte | Què fa de veritat | Manteniment | Llengua | Llicència | Encaix amb HPlay | Recomanació |
|---|---|---|---|---|---|---|---|
| 1 | [futo-org/fcast](https://github.com/futo-org/fcast) | Protocol obert d'enviament; receptors Android i escriptori; emissors d'escriptori i de terminal; SDK [D] | Molt actiu (oct. 2026) | Rust, TS, Kotlin | MIT | Alt: receptor i emissor | Implementar FCast v2/v3 als receptors i com a `Remote` |
| 2 | [androidx/media](https://github.com/androidx/media) (Media3/ExoPlayer) | Reproductor amb HLS, DASH, SmoothStreaming, capçaleres i ClearKey; analitzadors de manifests [D] | Molt actiu | Java | Apache-2.0 | Alt: receptor d'Android TV; analitzar manifests a l'app | Reproductor del receptor de tele; `HlsPlaylistParser` per saber resolució i codecs |
| 3 | [shaka-project/shaka-player](https://github.com/shaka-project/shaka-player) | Reproductor web amb HLS i DASH [D] | Molt actiu | JS | Apache-2.0 | Mitjà: `player.html` del .exe i del receptor web | Afegir-lo per a DASH (avui només hi ha hls.js) |
| 4 | [Dash-Industry-Forum/dash.js](https://github.com/Dash-Industry-Forum/dash.js) | Reproductor DASH de referència [D] | Actiu | JS | BSD-3 | Mitjà | Alternativa a Shaka; Shaka cobreix HLS i DASH alhora |
| 5 | [yt-dlp/yt-dlp](https://github.com/yt-dlp/yt-dlp) | Extreu les URL de vídeo de més de 1.000 webs [D] | Molt actiu | Python | Unlicense | Mitjà: «comparteix una pàgina → obtenir el vídeo» | Amb Chaquopy; només per a webs sense app oficial de tele |
| 6 | [chaquo/chaquopy](https://github.com/chaquo/chaquopy) | Python dins d'apps Android [D] | Actiu | Python/Java | MIT | Mitjà (afegeix uns MB a l'APK) | Necessari per a yt-dlp o streamlink |
| 7 | [TeamNewPipe/NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) | Extractor en Java: YouTube, SoundCloud, PeerTube, Bandcamp, media.ccc [D] | Molt actiu | Java | GPL-3.0 | Mitjà; incompatible amb el SDK de Cast (§10) | Només si no s'afegeix Cast; per a YouTube és millor obrir l'app oficial |
| 8 | [streamlink/streamlink](https://github.com/streamlink/streamlink) | Extreu directes de Twitch i d'altres plataformes [D] | Molt actiu | Python | BSD-2 | Baix-mitjà (Python) | El navegador integrat ja troba el HLS de Twitch; com a reserva |
| 9 | [home-assistant-libs/aiowebostv](https://github.com/home-assistant-libs/aiowebostv) | Control de LG webOS per SSAP: obrir apps, `contentId`, volum [D, al codi] | Molt actiu | Python | Apache-2.0 | Referència per a un adaptador webOS en Kotlin | Copiar el format dels missatges, no el codi Python |
| 10 | [ConnectSDK/Connect-SDK-Android](https://github.com/ConnectSDK/Connect-SDK-Android) | Una sola API per a Cast, DLNA, DIAL, Roku, webOS, Netcast i Fire TV [D] | Aturat (2024-03) | Java | Apache-2.0 | Mitjà | **No integrar-lo sencer**; consultar-lo per fer els adaptadors webOS i DIAL |
| 11 | [MarcoLucidi01/ytcast](https://github.com/MarcoLucidi01/ytcast) | Envia vídeos de YouTube a l'app de la tele amb DIAL i la Lounge API, que no està documentada [C] | Actiu | Go | MIT | Baix | Fer servir només la part DIAL (obrir l'app). La Lounge API queda exclosa (regla 5, i «pot deixar de funcionar») |
| 12 | [alexballas/go2tv](https://github.com/alexballas/go2tv) | Emissor DLNA i Chromecast amb subtítols i transcodificació [D] | Molt actiu | Go | MIT | Referència | Comparar com fa el DIDL-Lite i els subtítols per a DLNA |
| 13 | [pedroSG94/RootEncoder](https://github.com/pedroSG94/RootEncoder) | RTMP, RTSP i SRT des d'Android, també de la pantalla [D] | Molt actiu | Kotlin | Apache-2.0 | Experimental | Sortida RTSP/SRT de baixa latència cap a VLC, Kodi o OBS |
| 14 | [GetStream/webrtc-android](https://github.com/GetStream/webrtc-android) | libwebrtc ja compilada per a Android [D] | Actiu | Kotlin | Apache-2.0 | Experimental | Duplicar la pantalla per WebRTC (<0,3 s) als navegadors de PC |
| 15 | [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) | Duplica Android cap al PC amb 35–70 ms de retard [D] | Molt actiu | C/Java | Apache-2.0 | Referència | Copiar com configura MediaCodec per a baixa latència |
| 16 | [jmdns/jmdns](https://github.com/jmdns/jmdns) | mDNS en Java [D] | Actiu | Java | Apache-2.0 | Baix | Només si `NsdManager` falla en algun mòbil |
| 17 | [project-chip/connectedhomeip](https://github.com/project-chip/connectedhomeip) `examples/tv-casting-app` | «Matter Casting» (Fire TV i Echo) [D] | Molt actiu | C++/Java | Apache-2.0 | Experimental | Només envia a apps que ho admeten; poc útil avui |
| 18 | [devin1014/DLNA-Cast](https://github.com/devin1014/DLNA-Cast) | La biblioteca DLNA de Play Shots [D] | Actiu | Kotlin | **Cap** | Només per provar | Compilar-ne la demo per provar els receptors sense el mòbil |
| 19 | [54ac/stream-detector](https://github.com/54ac/stream-detector) | Extensió que detecta m3u8, mpd, f4m, ism i subtítols per tipus MIME i URL [D] | **Arxivat** (2023) | JS | MPL-2.0 | Referència | Copiar-ne la llista de tipus MIME i patrons |

**Descartats**, amb el motiu:
- **jUPnP**: CDDL, incompatible amb GPL.
- **ffmpeg-kit**: arxivat el 2026-07.
- **UxPlay** (receptor AirPlay) i **librespot** (receptor de Spotify Connect): fets per enginyeria inversa de protocols tancats (regla 5).
- **chromecast-java-api-v2 / node-castv2-client / pychromecast** com a emissors: fan Cast V2 per enginyeria inversa. El SDK oficial fa el mateix legalment.
- **androidtvremote2**: protocol de Google fet per enginyeria inversa. A més, a Android TV ja tenim la nostra pròpia app.

---

## 3. Estratègia per servei

**Hi ha quatre maneres d'enviar:**
- **A** — ordre a l'app oficial de la tele (obrir el contingut concret).
- **B** — URL que el receptor reprodueix sol.
- **C** — duplicar la pantalla.
- **D** — reproduir directament un flux que el mòbil serveix.

| Servei | DRM | Millor via | Com, en concret | Què s'aconsegueix | Límits |
|---|---|---|---|---|---|
| **YouTube** | No (en general) | **A** | Android TV: intent `https://www.youtube.com/watch?v=ID` cap a `com.google.android.youtube.tv` [C]. Roku: canal 837 amb `contentId` [P]. webOS: `youtube.leanback.v4` amb `contentId` [C]. Altres teles: DIAL `POST /apps/YouTube` amb `v=ID` [C] | Qualitat original, el compte de la tele, mòbil apagat | Sense control de posició des de HPlay. La via B (extractor) va contra les condicions de YouTube i el vídeo HD ve en pistes separades |
| **Netflix** | **Sí** | **A** | Android TV: `https://www.netflix.com/title/ID` cap a `com.netflix.ninja` [C, Home Assistant]. Roku: canal 12 [P]. DIAL «Netflix» [C] | Obre el títol | **B i C impossibles** (DRM; el duplicat surt en negre) |
| **Disney+ / Prime Video / Max / Apple TV** | **Sí** | **A** | Enllaços d'app a Android TV (`disneyplus.com/…`, `app.primevideo.com`, `tv.apple.com/…`) [C/P]. Roku i webOS amb l'identificador de l'app [P] | Obre l'app, i el títol si l'enllaç el porta | Igual que Netflix |
| **Twitch** | No | **B** (ja fet) i **A** | El navegador integrat ja troba el HLS `usher.ttvnw.net`. A: `twitch://…` a Android TV [C] | B: tots els receptors de HPlay | Els anuncis van dins del HLS; la API canvia sovint |
| **Spotify** | Sí (àudio) | **A** | Obrir l'app de Spotify de la tele i usar **Spotify Connect** des del mòbil, que és l'oficial | Mòbil apagat | HPlay no pot ser un receptor Connect (librespot exclòs) |
| **Crunchyroll** | Probablement | **A** | Enllaç a l'app de tele [P] | — | Cal comprovar-ho |
| **Plex** | No (contingut propi) | **A** o **B** | `plex://` a Android TV [C]. El servidor Plex també és servidor DLNA: des de BubbleUPnP es pot enviar als receptors de HPlay [D] | — | — |
| **Kodi** (com a destinació) | — | **B** | JSON-RPC `Player.Open {"item":{"file":URL}}` amb `URL\|Referer=…` [D/C] | URL amb capçaleres, sense proxy | Cal activar «Permetre control remot per HTTP» a Kodi |
| **Vimeo, Dailymotion, PeerTube, SoundCloud, webs amb `<video>`** | En general no | **B** | Navegador integrat i, si no n'hi ha prou, yt-dlp o NewPipeExtractor | Qualitat original | Enllaços que caduquen i lligats a capçaleres o a la IP |
| **Apps sense botó i sense enllaç** | — | **C** (ja fet) | Duplicar la pantalla | Qualsevol cosa sense DRM | El mòbil ha d'estar encès |

**Peça que falta a HPlay:** un «resolutor d'enllaços» petit, una taula de regles, que passi d'un enllaç compartit a
(servei, ID). Exemples: `youtu.be/ID`, `youtube.com/watch?v=ID`, `netflix.com/title/ID`, `twitch.tv/canal`.
A partir d'aquí tria la via A si la tele té l'app, o la via B en cas contrari.

---

## 4. Eines per a apps sense botó d'enviar

Ordenades de més a menys útils:

1. **Compartir → HPlay** (ja fet: `SEND text/plain` i `VIEW http(s)` / `video/*`), afegint-hi el **resolutor**:
   - Un enllaç de servei conegut va per la via A (app oficial).
   - Un enllaç a un fitxer o manifest va per la via B.
   - Qualsevol altra pàgina s'obre al navegador integrat amb la detecció millorada (punt 2).
   - Si no es troba cap vídeo, es proposa duplicar la pantalla.
2. **Navegador integrat amb detecció activa.** Detalls a §6.
3. **Receptors DLNA i FCast.** Moltes apps sense botó de Chromecast sí que tenen botó DLNA (Play Shots, apps d'IPTV,
   VLC, BubbleUPnP, Web Video Caster). Amb FCast s'hi afegeixen Grayjay i el seu ecosistema.
4. **«Obrir amb» un reproductor extern.** Algunes apps permeten triar el reproductor (MX Player, VLC…) i envien un intent
   `VIEW` amb la URL i, de vegades, extres com `headers`. Si HPlay es declara reproductor de `video/*` (ja ho fa), rep
   aquesta URL. **Millora:** llegir-ne els extres de capçaleres. MX Player documenta l'extra `headers` [C], i moltes apps l'omplen [P].
5. **Duplicar la pantalla** com a últim recurs universal (sense DRM).

---

## 5. Play Shots, Pelisplanet i semblants

**Play Shots** (`com.shotstv.playshots`), segons l'anàlisi del codi feta en aquesta sessió:
- Envia amb **DLNA** fent servir DLNA-Cast (Cling) i `SetAVTransportURI`. És a dir, envia **una URL** i el receptor la reprodueix sol [D].
- Busca aparells 5 vegades amb `ssdp:all` i abans de cada cerca esborra la llista. D'aquí ve la ràfaga NOTIFY de la v1.4.
- Té reproductor propi. No hem trobat API pública ni enllaços documentats.
- Les capçaleres o la sessió que necessiten els seus fluxos no s'han comprovat [P]. Si el receptor rep la URL però no
  reprodueix, el `registre.txt` del .exe mostrarà la URL i la resposta.
- **Com integrar-lo:** ja hi està, com a receptor DLNA. Cal provar-ho amb el mòbil real.

**Pelisplanet:** no he trobat cap informació tècnica verificable (ni API, ni repositori, ni documentació). Sembla un
lloc o app de pel·lícules sense llicència clara. **No recomano fer-hi cap connector específic.** Hi funcionen les mateixes
vies genèriques que amb qualsevol web o app: el botó DLNA si en té, el navegador integrat, o duplicar la pantalla.

**Eina genèrica** per veure el contingut que aquestes apps fan accessible:
- No existeix cap manera legítima de llegir les dades internes d'una altra app Android (sandbox).
- Les úniques vies són les que l'app mateixa obre: compartir, «obrir amb», el seu botó DLNA, o una web.

**Com provar-ho sense el mòbil:** compilar l'app d'exemple de DLNA-Cast a l'emulador `immic34` i enviar als receptors.

---

## 6. Detectar vídeos accessibles

Cal distingir tres coses que no són iguals:
- **Detectar** un vídeo: saber que la pàgina en fa servir un.
- **Obtenir una URL que funcioni** fora de la pàgina.
- **Poder-la reproduir en un altre aparell.**

| Tècnica | Què troba | Estat a HPlay | Font |
|---|---|---|---|
| `shouldInterceptRequest` + extensió | `.m3u8`, `.mpd`, `.mp4`… | **Ja fet** | — |
| Script injectat: `HTMLMediaElement.src` i `currentSrc`, `<source>` | MP4 i HLS natius | Per fer | API web estàndard [D] |
| Script que envolta `fetch` i `XMLHttpRequest` i mira el `Content-Type` (`application/vnd.apple.mpegurl`, `application/x-mpegURL`, `application/dash+xml`, `video/*`) | Manifests sense extensió | Per fer | Patrons de stream-detector [D] |
| Detectar `MediaSource` i URLs `blob:` | Sap que hi ha un vídeo per MSE (el `blob:` no es pot enviar); llavors busca el manifest entre les peticions | Per fer | — |
| Llegir el manifest (Media3 `HlsPlaylistParser` / `DashManifestParser`) | Resolució, `CODECS`, àudio, subtítols, si és en directe | Per fer | Media3 [D] |
| Comparar amb el receptor (`GetProtocolInfo` de DLNA; `canPlayType` i `MediaSource.isTypeSupported` als receptors web) | Saber **abans d'enviar** si el receptor el podrà reproduir | Part ja feta (perfil) | UPnP AV [D] |
| Desar les capçaleres de la petició (`Referer`, `Origin`, `User-Agent`, `Cookie` del `CookieManager`) | El que cal per reproduir-lo fora | **Ja fet** amb `MediaProxy` | — |

**Quan no funciona fora de la pàgina:**
- Tokens que caduquen: cal reenviar.
- URL lligada a la IP pública: a la mateixa xarxa normalment funciona.
- Comprovació de `Referer` o `Origin`: es resol amb el proxy, o amb FCast i Kodi passant les capçaleres.
- Cookies de sessió: es resol amb el proxy.
- Bloqueig CORS: el receptor del .exe ja el desactiva per a la xarxa local.
- **DRM: impossible**, i ho continuarem avisant.
- Cap URL exposada (WebRTC, WebSocket, MSE amb segments xifrats propis): només es pot duplicar la pantalla.

---

## 7. Millores a l'arquitectura actual

| Millora | Component afectat | Què aporta | Feina |
|---|---|---|---|
| Via A «obrir a l'app oficial» | Nou `cast/AppLaunch.kt` + resolutor; receptor Android TV (nova ordre `POST /launch`); `RokuRemote` (`launch/<canal>?contentId`) | YouTube, Netflix, Disney+, Prime, Max, Twitch, Spotify i Plex a la tele, amb el mòbil apagat | Mitjana-baixa. A Android TV són ~40 línies a cada banda |
| FCast | Nou `cast/FcastRemote.kt`; mDNS `_fcast._tcp` a `discovery/Mdns`; servidor TCP 46899 als dos receptors | Nous emissors (Grayjay) i nous receptors; capçaleres sense proxy | Mitjana (protocol petit i documentat) |
| Kodi | Nou `KodiRemote`; mDNS `_xbmc-jsonrpc-h._tcp` [P] | Kodi com a destinació, amb capçaleres | Baixa |
| Adaptador webOS (SSAP) | Nou `WebOsRemote` (WebSocket 3000/3001, emparellament amb clau) | Obrir l'app i el contingut a les LG sense instal·lar res | Mitjana (cal acceptar l'emparellament a la tele) |
| DIAL | `discovery` (ja rep SSDP) + `POST /apps/<App>` | Obrir YouTube i Netflix a teles sense la nostra app | Baixa |
| Google Cast (SDK oficial) | Nou `CastRemote` (play-services-cast-framework), `MediaRouter` | Chromecast i Google TV com a destinacions (URL i duplicat HLS) | Mitjana, **però cal decidir la llicència (§10)** |
| Detecció activa al navegador | `ui/Screens.kt` (script injectat), `MainViewModel` | Molts més vídeos detectats | Baixa-mitjana |
| Media3 al receptor de tele | `receivers/androidtv` (`Player`) | DASH, més formats, capçaleres natives, millors errors | Mitjana (es canvia el WebView pel reproductor nadiu) |
| Shaka Player al .exe | `player.html` | DASH al PC | Baixa |
| Ajustar el codificador | `capture/VideoEncoder` (`KEY_LOW_LATENCY`, `KEY_PRIORITY=0`, `KEY_OPERATING_RATE`) | Uns quants ms menys de retard al duplicat (escala de scrcpy) | Molt baixa; mesurar abans i després |
| Llegir l'extra `headers` dels intents | `MainActivity` | URLs amb capçaleres des d'apps que fan servir «obrir amb» | Molt baixa |

**No cal substituir cap part de l'arquitectura.** Tot entra com un `Remote` més, una font de descobriment més o un
reproductor dins dels receptors.

---

## 8. Taula comparativa

Puntuació de l'1 al 5 (5 = millor).

| Tecnologia | Compatibilitat que aporta | Casos resolts | Maduresa | Facilitat | Cost | Manteniment | Llicència | Risc | Real |
|---|---|---|---|---|---|---|---|---|---|
| Via A Android TV (intents) | 5 | 5 | 5 | 5 | 1 | 4 | — | Baix | Sí |
| Via A Roku ECP | 3 | 4 | 4 | 5 | 1 | 4 | — | Baix (IDs a provar) | Sí |
| Via A webOS SSAP | 4 | 4 | 4 | 3 | 2 | 3 | Apache (referència) | Mitjà | Sí |
| DIAL | 3 | 2 | 3 | 4 | 1 | 3 | Especificació oberta | Mitjà (algunes teles l'han tret) | Varia |
| FCast | 3 | 4 | 4 | 4 | 2 | 5 | MIT | Baix | Sí |
| Kodi JSON-RPC | 2 | 3 | 5 | 5 | 1 | 5 | — | Baix | Sí |
| Google Cast SDK | 5 | 4 | 5 | 3 | 3 | 5 | **Propietari** | Llicència | Sí |
| Detecció JS + Media3 parser | 4 | 4 | 5 | 4 | 2 | 4 | Apache | Baix | Sí |
| yt-dlp + Chaquopy | 4 | 4 | 5 | 2 | 3 | 3 (es trenca sovint) | Unlicense/MIT | ToS / mida de l'APK | Sí |
| NewPipeExtractor | 3 | 3 | 5 | 4 | 2 | 3 | GPL-3 | ToS de YouTube | Sí |
| Media3 al receptor | 3 | 3 | 5 | 3 | 3 | 5 | Apache | Baix | Sí |
| WebRTC per duplicar | 2 | 2 | 4 | 2 | 4 | 3 | Apache/BSD | Navegadors de tele | Varia |
| RTSP/SRT (RootEncoder) | 1 | 2 | 4 | 3 | 3 | 4 | Apache | Baix | Sí |
| Matter Casting | 1 | 1 | 3 | 1 | 4 | 4 | Apache | Alt | Poc |

---

## 9. Millores per ordre de prioritat

### Prioritat alta

| # | Millora | Biblioteca o projecte | Per què | Part de HPlay | Com validar-la sense tocar la resta |
|---|---|---|---|---|---|
| A1 | **Obrir a l'app oficial (Android TV / Fire TV)** + resolutor d'enllaços | Cap: intents d'Android | Cobreix els serveis grans i respecta el DRM; tenim les dues bandes | Receptor TV (`POST /launch`), app (`AppLaunch`) | Emulador Android TV amb YouTube: `adb shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/watch?v=…"`; després, des de HPlay. Test unitari del resolutor |
| A2 | **Roku: obrir l'app amb contingut** | ECP (ja hi és `RokuRemote`) | Gairebé gratis | `cast/Remote.kt` | `curl -d '' http://ROKU:8060/launch/837?contentId=ID` [P] |
| A3 | **Detecció activa al navegador** | Patrons de stream-detector; `HlsPlaylistParser` de Media3 | Molts més vídeos de webs sense extensió | `ui/Screens.kt`, `MainViewModel` | 5 webs de prova (HLS sense extensió, MP4, DASH, `blob:`); comptar quants troba abans i després |
| A4 | **Kodi com a destinació** | JSON-RPC oficial | Capçaleres sense proxy, molt estable | Nou `KodiRemote` | Kodi d'escriptori al PC: `Player.Open` amb `curl`, i després des de l'app |
| A5 | **FCast** (receptors primer) | Especificació [futo-org/fcast](https://github.com/futo-org/fcast) | Grayjay hi pot enviar; protocol obert i petit | `receivers/windows`, `receivers/androidtv` | Emissor de terminal FCast contra el .exe; Grayjay al mòbil |

### Prioritat mitjana

| # | Millora | Biblioteca | Limitació | Validació |
|---|---|---|---|---|
| M1 | Google Cast com a emissor | `play-services-cast-framework` + Default Media Receiver | **Cal decidir la llicència** (§10); no envia capçaleres, així que cal el proxy | Un Chromecast o Google TV real; el *Cast Command and Control tool* de Google |
| M2 | Adaptador webOS (SSAP) | Format de missatges d'aiowebostv / Connect SDK | Emparellament a la tele; els identificadors d'app canvien amb el firmware | Una LG real: `listApps` i després `launch` |
| M3 | DIAL per obrir YouTube i Netflix | Especificació DIAL; codi de ytcast (només la part DIAL) | El format `v=` de YouTube només el confirma la comunitat | `curl -X POST http://TELE:PORT/apps/YouTube -d "v=ID"` |
| M4 | Media3 al receptor de tele | androidx/media | Reescriure `Player` del receptor | APK a part amb flavor «exo»; mateixa bateria de vídeos |
| M5 | Extractor per a webs sense app (yt-dlp) | yt-dlp + Chaquopy | APK més gran; s'ha d'actualitzar sovint; ToS | Mòdul opcional, com el `:updater` |
| M6 | Shaka Player al .exe | shaka-player | — | Un `.mpd` de prova |

### Experimental

| # | Prova de concepte | Què cal decidir |
|---|---|---|
| E1 | Ajustar el codificador per a baixa latència | Mesurar el retard amb un cronòmetre a la pantalla, abans i després |
| E2 | Duplicar per WebRTC al PC | Si baixa a menys de 0,3 s amb un cost raonable |
| E3 | Sortida RTSP/SRT (RootEncoder) | Si hi ha usuaris amb VLC, Kodi o OBS |
| E4 | Matter Casting | Quantes apps de Fire TV ho admeten |
| E5 | Llegir l'extra `headers` dels intents «obrir amb» | Quines apps l'envien |

---

## 10. Limitacions que les API públiques no poden superar

- **Ser receptor de Google Cast**: el mòbil comprova un certificat que Google només dona als fabricants que aprova. **Impossible.**
- **Ser receptor d'AirPlay o de Spotify Connect** de manera legal: només es pot per enginyeria inversa (UxPlay, librespot). **Exclòs** (regla 5).
- **DRM** (Netflix, Disney+, Prime, Max, Apple TV, la música de Spotify): ni via B ni via C. **Només la via A** (l'app oficial).
- **Llegir dades d'una altra app**: el sandbox d'Android no ho permet. Només el que l'app comparteix.
- **Llicència de Google Cast amb GPL-3**: el SDK és propietari. Si en Pol és l'únic autor, pot afegir una
  **excepció d'enllaç** a la GPL de HPlay. Però llavors **no** pot incloure codi GPL de tercers sense permís, com
  NewPipeExtractor. **Cal escollir:** Cast **o** NewPipeExtractor. yt-dlp (Unlicense) i Media3 (Apache) no tenen aquest conflicte.
- **Lounge API de YouTube** (control complet de l'app de YouTube de la tele): no està documentada i és propietària. Exclosa.
- **Control de posició i progrés amb la via A**: l'app oficial de la tele no informa HPlay. Només podem obrir el contingut.
- **DIAL**: algunes teles noves l'han tret o el limiten. Varia per model.

---

## 11. Pla d'acció

Cada pas ha de funcionar sol abans de passar al següent. Totes les proves s'han de poder fer al PC i a l'emulador.

1. **Resolutor d'enllaços** (funció pura amb tests unitaris): `youtube`, `youtu.be`, `netflix/title`, `twitch.tv`,
   `disneyplus`, `primevideo`, `open.spotify.com`, `plex`, i «desconegut». No toca res més.
2. **Via A a Android TV**: ordre `POST /launch {service,id}` al receptor, que llança l'intent. A l'app, un botó
   «Obrir a l'app de la tele» quan el destí és el receptor HPlay de TV. Es prova a l'emulador Android TV amb YouTube.
3. **Via A a Roku**: ampliar `RokuRemote` amb l'identificador del canal de cada servei. Es prova amb `curl` en un Roku real (si no n'hi ha, queda marcat [P]).
4. **Detecció activa al navegador** (script injectat + tipus MIME + lectura del manifest). Es prova amb la bateria de 5 webs.
5. **Kodi** com a destinació. Es prova amb Kodi d'escriptori.
6. **FCast als receptors** (.exe i després TV). Es prova amb l'emissor de terminal de FCast i amb Grayjay.
7. **Decidir la llicència**: Cast (excepció) o NewPipeExtractor. Després, M1 o M5.

Després de cada pas: §0 i §10 de `DOCUMENTACIO-HPLAY.md`, i publicar amb `bash build-apk.sh`.

---

## Fonts

**Repositoris**
- [futo-org/fcast](https://github.com/futo-org/fcast), protocol v2 a `docs/docs/protocol/v2.md`
- [futo-org/grayjay-android](https://github.com/futo-org/grayjay-android) (Source First License 1.1)
- [androidx/media](https://github.com/androidx/media)
- [shaka-project/shaka-player](https://github.com/shaka-project/shaka-player)
- [Dash-Industry-Forum/dash.js](https://github.com/Dash-Industry-Forum/dash.js)
- [video-dev/hls.js](https://github.com/video-dev/hls.js)
- [yt-dlp/yt-dlp](https://github.com/yt-dlp/yt-dlp)
- [chaquo/chaquopy](https://github.com/chaquo/chaquopy)
- [TeamNewPipe/NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
- [yausername/youtubedl-android](https://github.com/yausername/youtubedl-android)
- [streamlink/streamlink](https://github.com/streamlink/streamlink)
- [ConnectSDK/Connect-SDK-Android](https://github.com/ConnectSDK/Connect-SDK-Android)
- [home-assistant-libs/aiowebostv](https://github.com/home-assistant-libs/aiowebostv)
- [MarcoLucidi01/ytcast](https://github.com/MarcoLucidi01/ytcast)
- [alexballas/go2tv](https://github.com/alexballas/go2tv)
- [pedroSG94/RootEncoder](https://github.com/pedroSG94/RootEncoder)
- [GetStream/webrtc-android](https://github.com/GetStream/webrtc-android)
- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)
- [jmdns/jmdns](https://github.com/jmdns/jmdns)
- [project-chip/connectedhomeip](https://github.com/project-chip/connectedhomeip)
- [devin1014/DLNA-Cast](https://github.com/devin1014/DLNA-Cast)
- [54ac/stream-detector](https://github.com/54ac/stream-detector)
- Descartats: [jupnp/jupnp](https://github.com/jupnp/jupnp), [arthenica/ffmpeg-kit](https://github.com/arthenica/ffmpeg-kit),
  [FDH2/UxPlay](https://github.com/FDH2/UxPlay), [librespot-org/librespot](https://github.com/librespot-org/librespot),
  [vitalidze/chromecast-java-api-v2](https://github.com/vitalidze/chromecast-java-api-v2),
  [thibauts/node-castv2-client](https://github.com/thibauts/node-castv2-client),
  [home-assistant-libs/pychromecast](https://github.com/home-assistant-libs/pychromecast),
  [tronikos/androidtvremote2](https://github.com/tronikos/androidtvremote2),
  [xchwarze/samsung-tv-ws-api](https://github.com/xchwarze/samsung-tv-ws-api),
  [supersaiyanmode/PyWebOSTV](https://github.com/supersaiyanmode/PyWebOSTV)

**Documentació**
- [Roku: deep linking per ECP](https://developer.roku.com/docs/developer-program/discovery/implementing-deep-linking.md)
- [Google Cast: Web Receiver (Default Media Receiver)](https://developers.google.com/cast/docs/web_receiver)
- [Google Cast SDK: condicions addicionals](https://developers.google.com/cast/docs/terms)
- [Home Assistant: Android TV Remote (enllaços a apps)](https://www.home-assistant.io/integrations/androidtv_remote/)
- [Kodi: opcions de protocol HTTP](https://kodi.wiki/view/HTTP)
- [Amazon: DIAL](https://developer.amazon.com/docs/vega/0.24/dial-vega.html)
- [Amazon: Matter Casting](https://developer.amazon.com/docs/fire-tv/matter-casting-integration.html)
- [Connect SDK: webOS i obrir apps](https://connectsdk.com/en/latest/guide-and/and-launch-app.html)
- [Home Assistant: YouTube a LG amb contentId](https://community.home-assistant.io/t/play-youtube-on-smart-tv/101407)
- [Fòrum de Roku sobre DIAL (`v=`)](https://forum.developer.roku.com/t/dial-implementation-for-roku/4274)
