#!/usr/bin/env bash
# Compila HPlay (móvil + tele + .exe), lo firma, genera update.json firmado y lo publica como release de GitHub.
#
#   bash build-apk.sh                       compila y publica la versión de gradle.properties
#   NOTES="- cambio 1" bash build-apk.sh    con notas (las ven también los actualizadores)
#   NOPUBLISH=1 bash build-apk.sh           solo compila y copia a Downloads (no toca GitHub)
#
# La versión se sube SOLO en gradle.properties (hplay.versionName / hplay.versionCode / hplay.minRequired).
# Los actualizadores leen .../releases/latest/download/update.json(+.sig): no hay ningún otro servidor.
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

REPO="PolHernaez/hplay"
KEYS="${HPLAY_KEYS:-/c/Users/Pol/HPlay-claus}"
prop() { sed -n "s/^$1=\(.*\)/\1/p" gradle.properties | tr -d '\r' | head -1; }
VER=$(prop hplay.versionName); CODE=$(prop hplay.versionCode); MIN=$(prop hplay.minRequired)
[ -n "$VER" ] && [ -n "$CODE" ] && [ -n "$MIN" ] || { echo "ERROR: falta hplay.versionName/versionCode/minRequired en gradle.properties"; exit 1; }
CERT_OK=$(tr -d ' \r\n' < tools/cert-sha256.txt)
APK="app/build/outputs/apk/release/app-release.apk"
TV="receivers/androidtv/build/outputs/apk/release/androidtv-release.apk"
EXE="receivers/windows/out/HPlay-Receptor.exe"
echo "== HPlay v$VER (versionCode $CODE, mínimo $MIN) =="

# ---- Comprobaciones previas ----
[ -f "$KEYS/signatura.properties" ] && [ -f "$KEYS/update-signing.pem" ] || { echo "ERROR: no encuentro las claves en $KEYS"; exit 1; }
# La documentación tiene que hablar de esta versión antes de publicarla (modelo NoAds checkDocs).
grep -q "· $VER" DOCUMENTACIO-HPLAY.md || { echo "ERROR: DOCUMENTACIO-HPLAY.md §10 no tiene la entrada «· $VER». Actualízala antes."; exit 1; }
if [ -z "$NOPUBLISH" ]; then
  if gh release view "v$VER" -R "$REPO" >/dev/null 2>&1; then echo "ERROR: la v$VER ya está publicada. Sube hplay.versionName/versionCode."; exit 1; fi
  # El release tiene que corresponder a código guardado en git.
  [ -z "$(git status --porcelain)" ] || { git status --short; echo "ERROR: hay cambios sin commit. Haz commit antes de publicar."; exit 1; }
fi

[ -f local.properties ] || echo "sdk.dir=$(cygpath -m "$LOCALAPPDATA/Android/Sdk")" > local.properties

echo "[1/7] Tests y compilación firmada (móvil + tele)..."
rm -f "$APK" "$TV"
GLOG="$(mktemp)"
for intento in 1 2 3; do
  if ./gradlew testDebugUnitTest assembleRelease --no-daemon -q > "$GLOG" 2>&1; then break; fi
  # A Windows, Gradle a veces falla al arrancar con «BindException» (puerto reservado por el sistema): se reintenta.
  if grep -q "BindException" "$GLOG" && [ $intento -lt 3 ]; then echo "  (Gradle: puerto ocupado, reintento $intento)"; continue; fi
  grep -v "^w: \|warning:" "$GLOG" | tail -40; echo "ERROR: tests o compilación"; exit 1
done
rm -f "$GLOG"
[ -f "$APK" ] && [ -f "$TV" ] || { echo "ERROR: no se han generado las APK firmadas (¿falta signatura.properties?)"; exit 1; }

echo "[2/7] Comprobando la firma..."
BT=$(ls -d "$LOCALAPPDATA/Android/Sdk/build-tools/"* | sort -V | tail -1)
for f in "$APK" "$TV"; do
  CERT=$("$BT/apksigner.bat" verify --print-certs "$f" 2>/dev/null | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | tr -d ' \r\n')
  if [ "$CERT" != "$CERT_OK" ]; then
    echo "ERROR: $f no está firmado con la clave de HPlay (tengo '$CERT'). Sin la misma clave no se actualiza. No se publica."; exit 1
  fi
done
echo "  firma buena"

echo "[3/7] Receptor de Windows..."
rm -rf "$EXE" receivers/windows/obj receivers/windows/bin # sin restos: la versión incrustada debe ser la nueva
# .NET admite 4 partes: X.Y.Z.versionCode (1.7 → 1.7.0.8, 1.7.1 → 1.7.1.9); AutoUpdate.cs lee el código en la cuarta.
ASMVER="$(echo "$VER.0.0" | cut -d. -f1-3).$CODE"
dotnet publish receivers/windows/HPlayReceiver.csproj -c Release -o receivers/windows/out -p:Version="$ASMVER" -nologo -v q > /dev/null
[ -f "$EXE" ] || { echo "ERROR: no se ha generado el .exe"; exit 1; }

echo "[4/7] Paquete y update.json firmado..."
DIST="$(mktemp -d)"
cp "$APK" "$DIST/HPlay.apk"
cp "$TV" "$DIST/HPlay-Receptor-TV.apk"
cp "$EXE" "$DIST/HPlay-Receptor.exe"
(cd receivers/webos && python -I -m zipfile -c "$DIST/HPlay-webOS-src.zip" .)
(cd receivers/tizen && python -I -m zipfile -c "$DIST/HPlay-Tizen-src.zip" .)
python -I tools/make_update_json.py "$DIST" "$VER" "$CODE" "$MIN" "${NOTES:-}"
openssl dgst -sha256 -sign "$KEYS/update-signing.pem" -out "$DIST/update.json.sig" "$DIST/update.json"
openssl dgst -sha256 -verify tools/update-public.pem -signature "$DIST/update.json.sig" "$DIST/update.json" > /dev/null \
  || { echo "ERROR: la firma de update.json no se verifica con tools/update-public.pem"; exit 1; }
cat "$DIST/update.json"; echo

echo "[5/7] Copiando a Downloads..."
cp -f "$DIST/HPlay.apk" "$USERPROFILE/Downloads/HPlay-v$VER.apk"
cp -f "$DIST/HPlay-Receptor-TV.apk" "$USERPROFILE/Downloads/HPlay-Receptor-TV-v$VER.apk"
cp -f "$DIST/HPlay-Receptor.exe" "$USERPROFILE/Downloads/HPlay-Receptor.exe"
echo "  Downloads: HPlay-v$VER.apk, HPlay-Receptor-TV-v$VER.apk, HPlay-Receptor.exe"

if [ -n "$NOPUBLISH" ]; then echo "[6/7] Publicación saltada (NOPUBLISH)"; rm -rf "$DIST"; exit 0; fi

echo "[6/7] Publicando v$VER..."
git tag -a "v$VER" -m "HPlay $VER"
git push -q origin HEAD "v$VER"
{ [ -n "$NOTES" ] && printf '%s\n\n' "$NOTES"; cat tools/release-notes.md; } > "$DIST/notes.md"
gh release create "v$VER" -R "$REPO" -t "HPlay v$VER" -F "$DIST/notes.md" \
  "$DIST/HPlay.apk" "$DIST/HPlay-Receptor-TV.apk" "$DIST/HPlay-Receptor.exe" \
  "$DIST/HPlay-webOS-src.zip" "$DIST/HPlay-Tizen-src.zip" "$DIST/update.json" "$DIST/update.json.sig" > /dev/null

echo "[7/7] Comprobando lo que verán las apps..."
# GitHub tarda unos segundos en servir el release nuevo como «latest»: hasta 6 intentos.
for intento in 1 2 3 4 5 6; do
  sleep 5
  LATEST=$(gh api "repos/$REPO/releases/latest" --jq .tag_name 2>/dev/null || true)
  REMOTE_JSON=$(curl -sL --max-time 20 "https://github.com/$REPO/releases/latest/download/update.json" | sha256sum | cut -d' ' -f1)
  LOCAL_JSON=$(sha256sum "$DIST/update.json" | cut -d' ' -f1)
  OK=1
  for a in HPlay.apk HPlay-Receptor-TV.apk HPlay-Receptor.exe update.json.sig; do
    R=$(curl -sIL --max-time 20 "https://github.com/$REPO/releases/latest/download/$a" | tr -d '\r' | grep -i '^content-length:' | tail -1 | awk '{print $2}')
    L=$(wc -c < "$DIST/$a" | tr -d ' ')
    [ "$R" = "$L" ] || OK=0
  done
  if [ "$LATEST" = "v$VER" ] && [ "$REMOTE_JSON" = "$LOCAL_JSON" ] && [ $OK = 1 ]; then
    echo "OK  releases/latest = v$VER; update.json y todos los ficheros coinciden con lo compilado."
    rm -rf "$DIST"
    exit 0
  fi
done
echo "=============================================================================="
echo " ATENCIÓN: GitHub dice latest=$LATEST y algún fichero no coincide. Míralo antes de avisar a nadie."
echo " Ficheros locales en $DIST"
echo "=============================================================================="
exit 1
