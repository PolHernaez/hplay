## Qué descargar

| Fichero | Para qué |
|---|---|
| **HPlay-Receptor.exe** | PC con Windows 10/11. Ábrelo y el PC aparece en el botón «enviar a la tele» de las apps de vídeo (DLNA). Portable, sin instalar. Se actualiza solo. |
| **HPlay-Receptor-TV.apk** | Android TV, Google TV o Fire TV. Lo mismo, en la tele. |
| **HPlay.apk** | Móvil Android: duplicar pantalla y enviar vídeos desde su navegador integrado. |
| HPlay-webOS-src.zip / HPlay-Tizen-src.zip | Lanzadores para teles LG/Samsung (modo desarrollador). |
| update.json + update.json.sig | Lo leen los actualizadores (versión, tamaños, SHA-256 y firma). |

Primera vez en Windows: si sale el aviso del Firewall, marca **Redes privadas** y pulsa *Permitir*.

Las APK están firmadas con la clave de HPlay (SHA-256 del certificado en `tools/cert-sha256.txt`).
**Si tenías la v1.0** (firmada con clave de prueba), desinstálala una vez e instala esta; a partir de aquí las
actualizaciones se instalan solas desde la propia app.
