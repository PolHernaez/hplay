"""Genera update.json para los actualizadores (móvil, tele y .exe).

Uso: python -I tools/make_update_json.py <carpeta> <versionName> <versionCode> <minRequired> [notas]
Por cada fichero publicado: tamaño exacto y SHA-256 (los actualizadores no instalan nada que no coincida).
build-apk.sh lo firma después con la clave privada de HPlay-claus.
"""
import hashlib
import json
import os
import sys

FILES = {
    "HPlay.apk": "com.hplay.app",
    "HPlay-Receptor-TV.apk": "com.hplay.receiver",
    "HPlay-Receptor.exe": None,
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    folder, version, code, minimum = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
    notes = sys.argv[5] if len(sys.argv) > 5 else ""
    assert minimum <= code, "minRequired no puede ser mayor que versionCode"
    files = {}
    for name, package in FILES.items():
        path = os.path.join(folder, name)
        entry = {"size": os.path.getsize(path), "sha256": sha256(path)}
        if package:
            entry["package"] = package
        files[name] = entry
    manifest = {"version": version, "versionCode": code, "minRequired": minimum, "notes": notes, "files": files}
    with open(os.path.join(folder, "update.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
