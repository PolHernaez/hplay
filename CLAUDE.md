# HPlay — instrucciones para la IA

1. Leer primero `DOCUMENTACIO-HPLAY.md` (estado, decisiones, compatibilidad, limitaciones) y `CONTEXT-BASE.md`.
2. Compilar y probar: `./gradlew assembleDebug testDebugUnitTest` (JDK 25 vale; `local.properties` con barras `/`).
3. Emulador de pruebas: `immic34` (API 34). `adb forward tcp:8765 tcp:8765` y abrir `http://localhost:8765/<código>/` para ver el receptor.
4. Textos de la app: `values/strings.xml` (inglés) y `values-es/strings.xml` siempre a la vez.
5. Nada de DRM, FLAG_SECURE ni protocolos propietarios (Cast/AirPlay) por ingeniería inversa: límites legales del proyecto.
6. Tras un cambio importante, actualizar §0 y §9 de `DOCUMENTACIO-HPLAY.md`.
