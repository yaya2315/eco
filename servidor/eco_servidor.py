#!/usr/bin/env python3
"""
Eco · servidor de la laptop
===========================

Recibe los "momentos" que guarda la app del teléfono, los transcribe con Whisper,
saca lo importante con IA y te lo deja:
  • en notas Markdown (compatibles con Obsidian), y
  • en un bot privado de Telegram que además te recuerda las cosas a tiempo.

Uso:
    python eco_servidor.py            → arranca Eco
    python eco_servidor.py --probar   → revisa configuración, IA, Telegram y Whisper
"""
from __future__ import annotations

import base64
import hmac
import html
import json
import logging
import math
import os
import queue
import re
import socket
import sqlite3
import sys
import threading
import time
import tomllib
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from contextlib import closing
from datetime import date, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from zoneinfo import ZoneInfo

BASE = Path(__file__).resolve().parent
TOKEN_DE_EJEMPLO = "cambia-esto-por-una-clave-larga"

# Descarga del modelo de Whisper: más paciencia con internet lento (el valor por defecto
# corta a los 10 s) y sin avisos que no importan en Windows.
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "120")
os.environ.setdefault("HF_HUB_ETAG_TIMEOUT", "60")
os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
os.environ.setdefault("HF_HUB_VERBOSITY", "error")
MAX_SUBIDA = 200 * 1024 * 1024  # 200 MB

log = logging.getLogger("eco")

# ════════════════════════════ Configuración ════════════════════════════

DEFAULTS: dict = {
    "usuario": {"nombre": "el usuario"},
    "servidor": {"puerto": 8765, "token": TOKEN_DE_EJEMPLO},
    "whisper": {"modelo": "small", "idioma": "es", "beam": 1, "hilos": 0},
    "ia": {
        "proveedor": "claude",
        "claude_modelo": "claude-haiku-5-5",
        "claude_api_key": "",
        "ollama_modelo": "qwen2.5:3b",
        "ollama_url": "http://localhost:11434",
    },
    "telegram": {"token": "", "chat_id": ""},
    "notas": {
        "carpeta": "notas",
        "zona_horaria": "America/El_Salvador",
        "hora_resumen": "21:00",
        "hora_recordatorio": "07:00",
        "aviso_antes_min": 60,
        "guardar_transcripcion": False,
        "guardar_audio": False,
    },
    "lugares": [],
}

CFG: dict = json.loads(json.dumps(DEFAULTS))
TZ = ZoneInfo("America/El_Salvador")
DATOS = BASE / "datos"
ENTRADA = DATOS / "entrada"
AUDIOS = DATOS / "audios"
DB_PATH = DATOS / "eco.db"
NOTAS = BASE / "notas"
COLA: "queue.Queue[Path]" = queue.Queue()


def cargar_config(ruta: Path) -> dict:
    cfg = json.loads(json.dumps(DEFAULTS))
    if ruta.exists():
        with open(ruta, "rb") as f:
            leido = tomllib.load(f)
        for clave, valor in leido.items():
            if isinstance(valor, dict) and isinstance(cfg.get(clave), dict):
                cfg[clave].update(valor)
            else:
                cfg[clave] = valor
    return cfg


def configurar(cfg: dict, base_datos: Path | None = None) -> None:
    """Aplica la configuración a las variables globales y prepara carpetas y base de datos."""
    global CFG, TZ, DATOS, ENTRADA, AUDIOS, DB_PATH, NOTAS
    CFG = cfg
    TZ = ZoneInfo(cfg["notas"]["zona_horaria"])
    DATOS = (base_datos or BASE) / "datos"
    ENTRADA = DATOS / "entrada"
    AUDIOS = DATOS / "audios"
    DB_PATH = DATOS / "eco.db"
    carpeta = Path(cfg["notas"]["carpeta"]).expanduser()
    NOTAS = carpeta if carpeta.is_absolute() else (base_datos or BASE) / carpeta
    for d in (DATOS, ENTRADA, NOTAS):
        d.mkdir(parents=True, exist_ok=True)
    init_db()


def _hhmm(texto: str, defecto: tuple[int, int]) -> tuple[int, int]:
    m = re.fullmatch(r"\s*(\d{1,2}):(\d{2})\s*", str(texto or ""))
    if not m:
        return defecto
    h, mi = int(m.group(1)), int(m.group(2))
    return (h, mi) if 0 <= h < 24 and 0 <= mi < 60 else defecto


# ════════════════════════════ Fechas en español ════════════════════════════

DIAS = ["lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"]
MESES = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
         "agosto", "septiembre", "octubre", "noviembre", "diciembre"]


def ahora() -> datetime:
    return datetime.now(TZ)


def fecha_larga(d: date) -> str:
    return f"{DIAS[d.weekday()]} {d.day} de {MESES[d.month - 1]} de {d.year}"


def fecha_corta(d: date) -> str:
    hoy = ahora().date()
    if d == hoy:
        return "hoy"
    if d == hoy + timedelta(days=1):
        return "mañana"
    return f"{DIAS[d.weekday()][:3]} {d.day} {MESES[d.month - 1][:3]}"


def duracion(seg: float | None) -> str:
    s = int(seg or 0)
    return f"{s // 60}:{s % 60:02d}"


# ════════════════════════════ Base de datos ════════════════════════════

ESQUEMA = """
CREATE TABLE IF NOT EXISTS capturas (
    id            INTEGER PRIMARY KEY,
    archivo       TEXT UNIQUE,
    origen        TEXT NOT NULL DEFAULT 'audio',
    inicio        TEXT NOT NULL,
    duracion      REAL,
    lat           REAL,
    lon           REAL,
    lugar         TEXT,
    titulo        TEXT,
    resumen       TEXT,
    transcripcion TEXT,
    importancia   INTEGER,
    ia_ok         INTEGER NOT NULL DEFAULT 1,
    procesado     TEXT
);
CREATE TABLE IF NOT EXISTS items (
    id            INTEGER PRIMARY KEY,
    captura_id    INTEGER REFERENCES capturas(id),
    tipo          TEXT NOT NULL,           -- tarea | dato | idea
    texto         TEXT NOT NULL,
    fecha         TEXT,
    hora          TEXT,
    recordar_ts   INTEGER,
    recordado     INTEGER NOT NULL DEFAULT 0,
    hecho         INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS items_pendientes ON items(tipo, hecho, recordado, recordar_ts);
CREATE TABLE IF NOT EXISTS estado (clave TEXT PRIMARY KEY, valor TEXT);
CREATE TABLE IF NOT EXISTS salida (id INTEGER PRIMARY KEY, texto TEXT NOT NULL, creado TEXT NOT NULL);
"""


def db() -> sqlite3.Connection:
    con = sqlite3.connect(DB_PATH, timeout=15)
    con.row_factory = sqlite3.Row
    return con


def init_db() -> None:
    with closing(db()) as con, con:
        con.executescript(ESQUEMA)


def estado_get(clave: str) -> str | None:
    with closing(db()) as con:
        fila = con.execute("SELECT valor FROM estado WHERE clave=?", (clave,)).fetchone()
        return fila["valor"] if fila else None


def estado_set(clave: str, valor) -> None:
    with closing(db()) as con, con:
        con.execute("INSERT INTO estado(clave, valor) VALUES(?, ?) "
                    "ON CONFLICT(clave) DO UPDATE SET valor=excluded.valor", (clave, str(valor)))


def ya_procesado(archivo: str) -> bool:
    with closing(db()) as con:
        return con.execute("SELECT 1 FROM capturas WHERE archivo=?", (archivo,)).fetchone() is not None


# ════════════════════════════ Lugares ════════════════════════════

def _distancia_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6_371_000
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def lugar_de(lat: float | None, lon: float | None) -> str | None:
    if lat is None or lon is None:
        return None
    mejor, mejor_d = None, float("inf")
    for l in CFG.get("lugares") or []:
        try:
            d = _distancia_m(lat, lon, float(l["lat"]), float(l["lon"]))
        except (KeyError, TypeError, ValueError):
            continue
        if d <= float(l.get("radio_m", 150)) and d < mejor_d:
            mejor, mejor_d = str(l.get("nombre", "")).strip() or None, d
    return mejor


def ubicacion_de(meta: dict) -> tuple[float | None, float | None]:
    """Devuelve lat/lon solo si la ubicación es de la misma hora que el momento."""
    try:
        lat, lon = float(meta["lat"]), float(meta["lon"])
    except (KeyError, TypeError, ValueError):
        return None, None
    loc_ms, ini_ms = meta.get("loc_ms"), meta.get("inicio_ms")
    if loc_ms and ini_ms:
        fin_ms = float(ini_ms) + float(meta.get("duracion_s") or 0) * 1000
        if abs(fin_ms - float(loc_ms)) > 30 * 60 * 1000:
            return None, None  # ubicación vieja: mejor no inventar dónde estabas
    return lat, lon


# ════════════════════════════ Whisper ════════════════════════════

_whisper = None
_whisper_lock = threading.Lock()


def leer_audio(ruta: Path):
    """Lee el .wav del teléfono directo a memoria (16 kHz, mono), como lo pide Whisper.

    Así no dependemos de PyAV: faster-whisper 1.2.1 le pasa una opción que PyAV 15+ ya no acepta
    ("metadata_errors"). Si el archivo no es un WAV normal, se lo dejamos a Whisper tal cual.
    """
    import wave
    import numpy as np
    try:
        with wave.open(str(ruta), "rb") as w:
            canales, ancho, tasa = w.getnchannels(), w.getsampwidth(), w.getframerate()
            crudo = w.readframes(w.getnframes())
    except (wave.Error, EOFError):
        return str(ruta)
    if ancho != 2:
        return str(ruta)
    audio = np.frombuffer(crudo, dtype="<i2").astype(np.float32) / 32768.0
    if canales > 1:
        audio = audio[: len(audio) // canales * canales].reshape(-1, canales).mean(axis=1)
    if tasa != 16000 and len(audio):
        n = int(round(len(audio) * 16000 / tasa))
        audio = np.interp(np.linspace(0, len(audio) - 1, n), np.arange(len(audio)), audio).astype(np.float32)
    return audio


def transcribir(ruta: Path) -> str:
    global _whisper
    w = CFG["whisper"]
    with _whisper_lock:
        if _whisper is None:
            from faster_whisper import WhisperModel  # import lento: solo cuando hace falta
            log.info("Cargando Whisper '%s'…", w["modelo"])
            _whisper = WhisperModel(w["modelo"], device="cpu", compute_type="int8",
                                    cpu_threads=int(w.get("hilos") or 0))
        segmentos, _info = _whisper.transcribe(
            leer_audio(ruta), language=w.get("idioma") or None, beam_size=int(w.get("beam") or 1),
            vad_filter=True, condition_on_previous_text=False,
        )
        return " ".join(s.text.strip() for s in segmentos).strip()


# ════════════════════════════ IA: qué es importante ════════════════════════════

FORMA_JSON = """{
  "titulo": "máximo 8 palabras",
  "resumen": "1 o 2 oraciones",
  "tareas": [{"texto": "verbo en infinitivo + qué", "fecha": "AAAA-MM-DD o null", "hora": "HH:MM o null"}],
  "datos": ["dato concreto que podría olvidar"],
  "ideas": ["idea o plan que expresó"],
  "importancia": 3
}"""


def _prompt_sistema() -> str:
    nombre = CFG["usuario"]["nombre"]
    return f"""Eres Eco, el asistente de memoria personal de {nombre}.
Recibes un fragmento que {nombre} decidió guardar porque algo le pareció importante. Si viene de audio,
es una transcripción automática: puede tener errores y no indica quién habla.

Extrae SOLO lo que le sirve a {nombre}:
- tareas: lo que {nombre} tiene que hacer o prometió hacer. Resuelve "mañana", "el viernes", "en dos semanas"
  usando la fecha del fragmento. Si solo hay hora, usa ese mismo día (o el siguiente si la hora ya pasó).
- datos: información concreta que podría olvidar (fechas de entrega o examen, montos, direcciones,
  instrucciones, nombres de cosas).
- ideas: ideas o planes que {nombre} expresó.

Reglas:
- Todo lo que {nombre} tenga que hacer (comprar, mandar, llevar, estudiar, pagar, llamar…) es una TAREA,
  aunque lo diga como "acordarme de…". Cada cosa va en UNA sola lista: no la repitas en datos ni en ideas.
- Horas en formato 24 h. Si no dicen "de la mañana" o "de la madrugada", "a las 1" hasta "a las 6"
  son de la tarde: "a las 3" = 15:00. "a las 8" sin más = 08:00.
- titulo: concreto, de qué trata ("Entrega del proyecto de física"). Nunca genérico como "Tareas" o "Recuerdos".
- No inventes nada. Si no hay nada útil, deja las listas vacías y dilo en el resumen.
- De otras personas anota solo lo necesario para la tarea o el dato ("Mandarle el archivo a Carlos").
  No anotes detalles íntimos, de salud, de dinero o de la vida privada de terceros.
- Nunca anotes contraseñas, códigos de verificación ni números de tarjeta.
- Español claro y corto. Tareas en infinitivo ("Enviar el informe al profe").
- importancia: 1 (trivial) a 5 (no se puede olvidar).

Ejemplo (fragmento del viernes 9 de octubre de 2026):
"El lunes a las 2 es la reunión de robótica, acordate de llevar el cargador. El aula nueva es la 12.
 La contraseña del wifi del lab es Robot2026. Y se me ocurrió hacer un brazo con servos para el robot."
Respuesta:
{{"titulo": "Reunión de robótica del lunes", "resumen": "Reunión de robótica el lunes por la tarde en el aula 12; hay que llevar el cargador.",
 "tareas": [{{"texto": "Ir a la reunión de robótica", "fecha": "2026-10-12", "hora": "14:00"}},
            {{"texto": "Llevar el cargador a la reunión", "fecha": "2026-10-12", "hora": "14:00"}}],
 "datos": ["La reunión de robótica es en el aula 12"],
 "ideas": ["Hacer un brazo con servos para el robot"], "importancia": 4}}
(La contraseña no se anota.)

Responde únicamente con un objeto JSON válido con esta forma:
{FORMA_JSON}"""


def _prompt_usuario(texto: str, cuando: datetime, lugar: str | None, origen: str) -> str:
    tipo = "Transcripción del audio" if origen == "audio" else "Nota escrita por él mismo"
    partes = [
        f"Fecha y hora del fragmento: {fecha_larga(cuando.date())}, {cuando:%H:%M} ({CFG['notas']['zona_horaria']}).",
        f"Hoy es {fecha_larga(ahora().date())}.",
    ]
    if lugar:
        partes.append(f"Lugar: {lugar}.")
    partes.append(f'{tipo}:\n"""\n{texto}\n"""')
    return "\n".join(partes)


def _json_de(texto: str) -> dict:
    m = re.search(r"\{.*\}", texto or "", re.S)
    if not m:
        raise ValueError("la IA no devolvió JSON")
    return json.loads(m.group(0))


def _fecha_valida(valor) -> str | None:
    try:
        return date.fromisoformat(str(valor).strip()[:10]).isoformat()
    except (TypeError, ValueError):
        return None


def _hora_valida(valor) -> str | None:
    m = re.fullmatch(r"(\d{1,2}):(\d{2})", str(valor or "").strip())
    if not m or int(m.group(1)) > 23 or int(m.group(2)) > 59:
        return None
    return f"{int(m.group(1)):02d}:{m.group(2)}"


def normalizar(d: dict) -> dict:
    def lista(x) -> list[str]:
        if not isinstance(x, list):
            return []
        return [str(s).strip()[:300] for s in x if str(s).strip()][:12]

    tareas = []
    for t in d.get("tareas") or []:
        if isinstance(t, str):
            t = {"texto": t}
        if not isinstance(t, dict):
            continue
        texto = str(t.get("texto") or "").strip()
        if texto:
            tareas.append({"texto": texto[:300], "fecha": _fecha_valida(t.get("fecha")),
                           "hora": _hora_valida(t.get("hora"))})
    try:
        importancia = max(1, min(5, int(d.get("importancia") or 3)))
    except (TypeError, ValueError):
        importancia = 3
    return {
        "titulo": (str(d.get("titulo") or "").strip() or "Momento guardado")[:80],
        "resumen": str(d.get("resumen") or "").strip()[:700],
        "tareas": tareas[:12],
        "datos": lista(d.get("datos")),
        "ideas": lista(d.get("ideas")),
        "importancia": importancia,
    }


def _llamar_claude(sistema: str, usuario: str) -> str:
    try:
        import anthropic
    except ImportError as e:
        raise RuntimeError("falta instalar la librería anthropic (pip install anthropic)") from e
    ia = CFG["ia"]
    cliente = anthropic.Anthropic(api_key=ia.get("claude_api_key") or None)
    msg = cliente.messages.create(
        model=ia["claude_modelo"], max_tokens=1500, temperature=0.2,
        system=sistema, messages=[{"role": "user", "content": usuario}],
    )
    return "".join(getattr(b, "text", "") for b in msg.content)


def _llamar_ollama(sistema: str, usuario: str) -> str:
    ia = CFG["ia"]
    cuerpo = json.dumps({
        "model": ia["ollama_modelo"], "stream": False, "format": "json",
        "options": {"temperature": 0.2},
        "messages": [{"role": "system", "content": sistema}, {"role": "user", "content": usuario}],
    }).encode()
    req = urllib.request.Request(ia["ollama_url"].rstrip("/") + "/api/chat", data=cuerpo,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        return json.load(r)["message"]["content"]


def extraer(texto: str, cuando: datetime, lugar: str | None, origen: str = "audio") -> dict:
    proveedor = str(CFG["ia"].get("proveedor", "claude")).lower()
    llamar = _llamar_ollama if proveedor == "ollama" else _llamar_claude
    respuesta = llamar(_prompt_sistema(), _prompt_usuario(texto, cuando, lugar, origen))
    return pulir(normalizar(_json_de(respuesta)), texto)


# Red de seguridad para modelos chicos (como los de Ollama), que a veces se equivocan
# en lo mismo: horas de madrugada, tareas metidas en "datos" y cosas repetidas.

VERBOS_DE_TAREA = (
    "comprar", "mandar", "enviar", "entregar", "llevar", "traer", "pagar", "llamar", "estudiar",
    "terminar", "imprimir", "revisar", "subir", "preguntar", "avisar", "recoger", "ir ", "escribir",
    "responder", "contestar", "preparar", "practicar", "buscar", "sacar", "agendar", "reservar",
    "devolver", "cobrar", "confirmar", "hacer la", "hacer el", "hacer las", "hacer los",
)
_RECORDAR = re.compile(r"^(recordar|acordarme|acordarse|acuerdate|no olvidar|no olvides|no te olvides)"
                       r"\s+(de\s+|que\s+)?")


def _sin_acentos(t: str) -> str:
    t = unicodedata.normalize("NFKD", t.lower())
    return "".join(c for c in t if not unicodedata.combining(c))


def _clave(t: str) -> str:
    t = _RECORDAR.sub("", _sin_acentos(t).strip())
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", "", t)).strip()


def _quitar_recordar(t: str) -> str:
    m = _RECORDAR.match(_sin_acentos(t).strip())
    t = t.strip()[m.end():] if m else t.strip()
    return t[:1].upper() + t[1:]


def pulir(info: dict, texto: str) -> dict:
    base = _sin_acentos(texto)
    madrugada = re.search(r"madrugada|(de|en|por) la manana|\ba\.?\s?m\b", base)
    for t in info["tareas"]:
        if t["hora"] and not madrugada:
            h, m = map(int, t["hora"].split(":"))
            if 1 <= h <= 6:  # "a las 3" casi nunca es de madrugada
                t["hora"] = f"{h + 12:02d}:{m:02d}"

    datos = []
    for d in info["datos"]:
        if _clave(d).startswith(VERBOS_DE_TAREA) or _RECORDAR.match(_sin_acentos(d)):
            info["tareas"].append({"texto": _quitar_recordar(d), "fecha": None, "hora": None})
        else:
            datos.append(d)
    info["datos"] = datos
    ideas = []
    for i in info["ideas"]:
        if _RECORDAR.match(_sin_acentos(i)):
            info["tareas"].append({"texto": _quitar_recordar(i), "fecha": None, "hora": None})
        else:
            ideas.append(i)
    info["ideas"] = ideas

    vistos: set[str] = set()
    for lista in ("tareas", "datos", "ideas"):
        filtrada = []
        for x in info[lista]:
            k = _clave(x["texto"] if lista == "tareas" else x)
            if k and k not in vistos:
                vistos.add(k)
                filtrada.append(x)
        info[lista] = filtrada
    return info


# ════════════════════════════ Guardar ════════════════════════════

def calcular_recordatorio(fecha: str | None, hora: str | None, referencia: datetime) -> tuple[str | None, str | None, int | None]:
    """Devuelve (fecha, hora, timestamp_del_aviso). Sin fecha no hay aviso, solo queda pendiente."""
    if hora and not fecha:
        h, m = map(int, hora.split(":"))
        dia = referencia.date()
        if (h, m) <= (referencia.hour, referencia.minute):
            dia += timedelta(days=1)
        fecha = dia.isoformat()
    if not fecha:
        return None, None, None
    d = date.fromisoformat(fecha)
    if hora:
        h, m = map(int, hora.split(":"))
        evento = datetime(d.year, d.month, d.day, h, m, tzinfo=TZ)
        aviso = evento - timedelta(minutes=int(CFG["notas"].get("aviso_antes_min", 60)))
        limite = evento
    else:
        h, m = _hhmm(CFG["notas"].get("hora_recordatorio"), (7, 0))
        aviso = datetime(d.year, d.month, d.day, h, m, tzinfo=TZ)
        limite = datetime(d.year, d.month, d.day, 23, 59, tzinfo=TZ)
    actual = ahora()
    if limite <= actual:
        return fecha, hora, None  # ya pasó: queda anotado, sin aviso
    if aviso <= actual:
        aviso = actual + timedelta(minutes=1)
    return fecha, hora, int(aviso.timestamp())


def guardar_captura(*, archivo: str | None, origen: str, inicio: datetime, dur: float | None,
                    lat: float | None, lon: float | None, lugar: str | None, info: dict,
                    transcripcion: str | None, ia_ok: bool) -> int:
    with closing(db()) as con, con:
        cur = con.execute(
            "INSERT INTO capturas(archivo, origen, inicio, duracion, lat, lon, lugar, titulo, resumen, "
            "transcripcion, importancia, ia_ok, procesado) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (archivo, origen, inicio.isoformat(timespec="seconds"), dur, lat, lon, lugar, info["titulo"],
             info["resumen"], transcripcion, info["importancia"], int(ia_ok), ahora().isoformat(timespec="seconds")),
        )
        cid = cur.lastrowid
        for t in info["tareas"]:
            fecha, hora, ts = calcular_recordatorio(t["fecha"], t["hora"], inicio)
            con.execute("INSERT INTO items(captura_id, tipo, texto, fecha, hora, recordar_ts) VALUES(?,?,?,?,?,?)",
                        (cid, "tarea", t["texto"], fecha, hora, ts))
        for tipo in ("datos", "ideas"):
            for texto in info[tipo]:
                con.execute("INSERT INTO items(captura_id, tipo, texto) VALUES(?,?,?)",
                            (cid, "dato" if tipo == "datos" else "idea", texto))
    return cid


def items_de(con: sqlite3.Connection, cid: int) -> list[sqlite3.Row]:
    return con.execute("SELECT * FROM items WHERE captura_id=? ORDER BY id", (cid,)).fetchall()


# ════════════════════════════ Notas Markdown (Obsidian) ════════════════════════════

def _cuando_tarea(it) -> str:
    if not it["fecha"]:
        return ""
    s = fecha_corta(date.fromisoformat(it["fecha"]))
    return f"{s}, {it['hora']}" if it["hora"] else s


def render_dia(d: date) -> Path:
    with closing(db()) as con:
        capturas = con.execute("SELECT * FROM capturas WHERE substr(inicio,1,10)=? ORDER BY inicio",
                               (d.isoformat(),)).fetchall()
        lineas = [
            "---", f"fecha: {d.isoformat()}", "tipo: eco-diario", "---", "",
            f"# {fecha_larga(d).capitalize()}", "",
            "> Generado por Eco. Este archivo se reescribe solo: tus propias notas ponlas en otro archivo.", "",
        ]
        if not capturas:
            lineas.append("_Hoy no guardaste momentos._")
        for c in capturas:
            ini = datetime.fromisoformat(c["inicio"])
            meta = [f"{ini:%H:%M}"]
            if c["lugar"]:
                meta.append(f"📍 {c['lugar']}")
            elif c["lat"] is not None:
                meta.append(f"[📍 mapa](https://maps.google.com/?q={c['lat']:.5f},{c['lon']:.5f})")
            if c["duracion"]:
                meta.append(duracion(c["duracion"]))
            if c["origen"] == "texto":
                meta.append("nota escrita")
            meta.append("●" * (c["importancia"] or 3) + "○" * (5 - (c["importancia"] or 3)))
            lineas += [f"## {c['titulo']}", " · ".join(meta), ""]
            if c["resumen"]:
                lineas += [c["resumen"], ""]
            its = items_de(con, c["id"])
            tareas = [i for i in its if i["tipo"] == "tarea"]
            if tareas:
                lineas.append("**Por hacer**")
                for t in tareas:
                    cuando = _cuando_tarea(t)
                    lineas.append(f"- [{'x' if t['hecho'] else ' '}] {t['texto']}"
                                  + (f" — 📅 {cuando}" if cuando else "") + f" `#{t['id']}`")
                lineas.append("")
            for tipo, titulo in (("dato", "**Para no olvidar**"), ("idea", "**Ideas**")):
                grupo = [i for i in its if i["tipo"] == tipo]
                if grupo:
                    lineas.append(titulo)
                    lineas += [f"- {i['texto']}" for i in grupo]
                    lineas.append("")
            if c["transcripcion"]:
                lineas += ["<details><summary>Transcripción</summary>", "", c["transcripcion"], "", "</details>", ""]
    ruta = NOTAS / f"{d.isoformat()}.md"
    ruta.write_text("\n".join(lineas).rstrip() + "\n", encoding="utf-8")
    return ruta


def pendientes(limite: int = 30) -> list[sqlite3.Row]:
    with closing(db()) as con:
        return con.execute(
            "SELECT * FROM items WHERE tipo='tarea' AND hecho=0 "
            "ORDER BY fecha IS NULL, fecha, hora IS NULL, hora, id LIMIT ?", (limite,)).fetchall()


def render_pendientes() -> Path:
    filas = pendientes(200)
    lineas = ["# Pendientes", "", "> Generado por Eco. Marca como hecho con /hecho N en Telegram.", ""]
    if not filas:
        lineas.append("_Nada pendiente. 🎉_")
    for t in filas:
        cuando = _cuando_tarea(t)
        lineas.append(f"- [ ] {t['texto']}" + (f" — 📅 {cuando}" if cuando else "") + f" `#{t['id']}`")
    ruta = NOTAS / "Pendientes.md"
    ruta.write_text("\n".join(lineas) + "\n", encoding="utf-8")
    return ruta


def actualizar_notas(d: date) -> None:
    try:
        render_dia(d)
        render_pendientes()
    except OSError as e:
        log.warning("No pude escribir las notas: %s", e)


# ════════════════════════════ Telegram ════════════════════════════

def tg(metodo: str, **params) -> dict | None:
    token = CFG["telegram"].get("token")
    if not token:
        return None
    req = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/{metodo}", data=json.dumps(params).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=params.get("timeout", 10) + 15) as r:
        return json.load(r)


def enviar(texto: str, chat_id: str | None = None) -> bool:
    destino = chat_id or str(CFG["telegram"].get("chat_id") or "")
    if not destino or not CFG["telegram"].get("token"):
        return False
    if len(texto) > 4000:
        texto = texto[:3990] + "…"
    try:
        tg("sendMessage", chat_id=destino, text=texto, parse_mode="HTML", disable_web_page_preview=True)
        return True
    except Exception as e:  # sin internet, token mal puesto, etc.
        log.warning("Telegram no respondió: %s", e)
        return False


def enviar_o_guardar(texto: str) -> None:
    """Si no hay internet, el mensaje queda en cola y se manda en cuanto vuelva."""
    if not CFG["telegram"].get("token") or not CFG["telegram"].get("chat_id"):
        return
    if not enviar(texto):
        with closing(db()) as con, con:
            con.execute("INSERT INTO salida(texto, creado) VALUES(?, ?)",
                        (texto, ahora().isoformat(timespec="seconds")))


def vaciar_salida() -> None:
    with closing(db()) as con:
        filas = con.execute("SELECT * FROM salida ORDER BY id").fetchall()
    for f in filas:
        if not enviar(f["texto"]):
            return  # sigue sin internet: lo intentamos en la próxima vuelta
        with closing(db()) as con, con:
            con.execute("DELETE FROM salida WHERE id=?", (f["id"],))


def e(texto) -> str:
    return html.escape(str(texto or ""))


def mensaje_captura(cid: int) -> str:
    with closing(db()) as con:
        c = con.execute("SELECT * FROM capturas WHERE id=?", (cid,)).fetchone()
        its = items_de(con, cid)
    ini = datetime.fromisoformat(c["inicio"])
    meta = [f"{ini:%H:%M}"]
    if c["lugar"]:
        meta.append(c["lugar"])
    if c["duracion"]:
        meta.append(duracion(c["duracion"]))
    partes = [f"📌 <b>{e(c['titulo'])}</b>", f"<i>{e(' · '.join(meta))}</i>"]
    if c["resumen"]:
        partes += ["", e(c["resumen"])]
    tareas = [i for i in its if i["tipo"] == "tarea"]
    if tareas:
        partes += ["", "✅ <b>Por hacer</b>"]
        for t in tareas:
            cuando = _cuando_tarea(t)
            partes.append(f"• {e(t['texto'])}" + (f" — {e(cuando)}" if cuando else "") + f"  <code>#{t['id']}</code>")
    for tipo, titulo in (("dato", "🧠 <b>Para no olvidar</b>"), ("idea", "✨ <b>Ideas</b>")):
        grupo = [i for i in its if i["tipo"] == tipo]
        if grupo:
            partes += ["", titulo] + [f"• {e(i['texto'])}" for i in grupo]
    if not c["ia_ok"]:
        partes += ["", "⚠️ No pude analizarlo con IA; guardé la transcripción en tus notas."]
    return "\n".join(partes)


def resumen_del_dia(d: date) -> str | None:
    with closing(db()) as con:
        capturas = con.execute("SELECT * FROM capturas WHERE substr(inicio,1,10)=? ORDER BY inicio",
                               (d.isoformat(),)).fetchall()
        ids = [c["id"] for c in capturas]
        marcas = ",".join("?" * len(ids)) or "NULL"
        datos = con.execute(f"SELECT * FROM items WHERE tipo='dato' AND captura_id IN ({marcas})", ids).fetchall()
        ideas = con.execute(f"SELECT * FROM items WHERE tipo='idea' AND captura_id IN ({marcas})", ids).fetchall()
    tareas = pendientes(15)
    if not capturas and not tareas:
        return None
    n = len(capturas)
    partes = [f"🌙 <b>Tu día · {e(fecha_larga(d))}</b>",
              f"<i>{n} momento{'s' if n != 1 else ''} guardado{'s' if n != 1 else ''}</i>"]
    if capturas:
        partes += ["", "<b>Lo que pasó</b>"]
        for c in capturas:
            ini = datetime.fromisoformat(c["inicio"])
            lugar = f" ({e(c['lugar'])})" if c["lugar"] else ""
            partes.append(f"• {ini:%H:%M} · <b>{e(c['titulo'])}</b>{lugar}")
    if tareas:
        partes += ["", "✅ <b>Por hacer</b>"]
        for t in tareas:
            cuando = _cuando_tarea(t)
            partes.append(f"• {e(t['texto'])}" + (f" — {e(cuando)}" if cuando else "") + f"  <code>#{t['id']}</code>")
    if datos:
        partes += ["", "🧠 <b>Para no olvidar</b>"] + [f"• {e(i['texto'])}" for i in datos]
    if ideas:
        partes += ["", "✨ <b>Ideas</b>"] + [f"• {e(i['texto'])}" for i in ideas]
    if tareas:
        partes += ["", "Cuando termines algo: <code>/hecho 12</code>"]
    return "\n".join(partes)


AYUDA = (
    "🫧 <b>Eco</b> — tu memoria de bolsillo\n\n"
    "/hoy — resumen de hoy\n"
    "/pendientes — lo que tienes por hacer\n"
    "/hecho 12 — marcar la tarea #12 como hecha\n"
    "/buscar palabra — buscar en todo lo guardado\n\n"
    "También puedes escribirme cualquier cosa (\"examen de física el martes\") y la anoto."
)


def manejar_mensaje(chat: str, texto: str) -> None:
    dueño = str(CFG["telegram"].get("chat_id") or "")
    if not dueño:
        if texto.startswith("/start"):
            enviar(f"Hola 👋 Tu chat_id es <code>{e(chat)}</code>.\n"
                   "Pégalo en <b>config.toml</b> → [telegram] chat_id y reinicia Eco.", chat)
        return
    if chat != dueño:
        if texto.startswith("/start"):
            enviar("Este bot es privado.", chat)
        return

    comando, _, resto = texto.partition(" ")
    comando = comando.split("@")[0].lower()
    resto = resto.strip()

    if comando in ("/start", "/ayuda", "/help"):
        enviar(AYUDA)
    elif comando == "/hoy":
        enviar(resumen_del_dia(ahora().date()) or "Hoy todavía no guardaste momentos.")
    elif comando == "/pendientes":
        filas = pendientes(30)
        if not filas:
            enviar("Nada pendiente. 🎉")
        else:
            lineas = ["📋 <b>Pendientes</b>"]
            for t in filas:
                cuando = _cuando_tarea(t)
                lineas.append(f"• <code>#{t['id']}</code> {e(t['texto'])}" + (f" — {e(cuando)}" if cuando else ""))
            enviar("\n".join(lineas))
    elif comando == "/hecho":
        ids = [int(x) for x in re.findall(r"\d+", resto)]
        if not ids:
            enviar("Dime cuál: <code>/hecho 12</code>")
            return
        hechas = []
        with closing(db()) as con, con:
            for i in ids:
                fila = con.execute("SELECT i.texto, c.inicio FROM items i LEFT JOIN capturas c ON c.id=i.captura_id "
                                   "WHERE i.id=? AND i.tipo='tarea'", (i,)).fetchone()
                if fila:
                    con.execute("UPDATE items SET hecho=1 WHERE id=?", (i,))
                    hechas.append(fila)
        for f in hechas:
            actualizar_notas(datetime.fromisoformat(f["inicio"]).date())
        enviar("✓ Hecho: " + "; ".join(e(f["texto"]) for f in hechas) if hechas else "No encontré esa tarea.")
    elif comando == "/buscar":
        if not resto:
            enviar("¿Qué busco? <code>/buscar examen</code>")
            return
        q = f"%{resto}%"
        with closing(db()) as con:
            filas = con.execute(
                "SELECT DISTINCT c.id, c.inicio, c.titulo, c.resumen FROM capturas c "
                "LEFT JOIN items i ON i.captura_id=c.id "
                "WHERE c.titulo LIKE ? OR c.resumen LIKE ? OR c.transcripcion LIKE ? OR i.texto LIKE ? "
                "ORDER BY c.inicio DESC LIMIT 8", (q, q, q, q)).fetchall()
        if not filas:
            enviar(f"No encontré nada con «{e(resto)}».")
            return
        lineas = [f"🔎 <b>{e(resto)}</b>"]
        for f in filas:
            ini = datetime.fromisoformat(f["inicio"])
            lineas.append(f"• <b>{e(f['titulo'])}</b> — {e(fecha_corta(ini.date()))} {ini:%H:%M}\n  {e(f['resumen'])}")
        enviar("\n".join(lineas))
    elif comando.startswith("/"):
        enviar("No conozco ese comando.\n\n" + AYUDA)
    else:
        cid = procesar_texto(texto)
        enviar("📝 Anotado.\n\n" + mensaje_captura(cid))


def bot() -> None:
    if not CFG["telegram"].get("token"):
        log.info("Telegram desactivado (falta el token en config.toml)")
        return
    offset = int(estado_get("tg_offset") or 0)
    log.info("Bot de Telegram escuchando")
    while True:
        try:
            r = tg("getUpdates", offset=offset, timeout=30, allowed_updates=["message"]) or {}
            for u in r.get("result", []):
                offset = u["update_id"] + 1
                estado_set("tg_offset", offset)
                m = u.get("message") or {}
                texto = (m.get("text") or "").strip()
                if texto:
                    try:
                        manejar_mensaje(str(m.get("chat", {}).get("id", "")), texto)
                    except Exception:
                        log.exception("Error atendiendo un mensaje de Telegram")
        except Exception as ex:
            log.warning("Telegram sin conexión (%s); reintento en 15 s", ex)
            time.sleep(15)


# ════════════════════════════ Procesamiento ════════════════════════════

def procesar(ruta: Path) -> int | None:
    archivo = ruta.name
    meta_ruta = ruta.with_suffix(".json")
    meta = {}
    if meta_ruta.exists():
        try:
            meta = json.loads(meta_ruta.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            meta = {}
    if ya_procesado(archivo):
        _limpiar(ruta, meta_ruta)
        return None

    inicio = (datetime.fromtimestamp(float(meta["inicio_ms"]) / 1000, TZ)
              if meta.get("inicio_ms") else ahora())
    dur = meta.get("duracion_s")
    lat, lon = ubicacion_de(meta)
    lugar = lugar_de(lat, lon)

    t0 = time.time()
    texto = transcribir(ruta)
    log.info("Transcrito %s (%s de audio) en %.0f s", archivo, duracion(dur), time.time() - t0)

    guardar_texto = bool(CFG["notas"].get("guardar_transcripcion"))
    if len(texto.split()) < 4:
        info = normalizar({"titulo": "Sin voz clara", "resumen": "No se entendió lo suficiente en este momento."})
        cid = guardar_captura(archivo=archivo, origen="audio", inicio=inicio, dur=dur, lat=lat, lon=lon,
                              lugar=lugar, info=info, transcripcion=texto or None, ia_ok=True)
    else:
        try:
            info = extraer(texto, inicio, lugar, "audio")
            ia_ok = True
        except Exception as ex:
            log.warning("La IA falló (%s); guardo la transcripción para no perder nada", ex)
            info = normalizar({"titulo": "Momento guardado", "resumen": texto[:200] + ("…" if len(texto) > 200 else "")})
            ia_ok = False
        cid = guardar_captura(archivo=archivo, origen="audio", inicio=inicio, dur=dur, lat=lat, lon=lon,
                              lugar=lugar, info=info, transcripcion=texto if (guardar_texto or not ia_ok) else None,
                              ia_ok=ia_ok)

    actualizar_notas(inicio.date())
    enviar_o_guardar(mensaje_captura(cid))

    if CFG["notas"].get("guardar_audio"):
        AUDIOS.mkdir(parents=True, exist_ok=True)
        ruta.replace(AUDIOS / archivo)
        if meta_ruta.exists():
            meta_ruta.unlink()
    else:
        _limpiar(ruta, meta_ruta)
    return cid


def procesar_texto(texto: str) -> int:
    cuando = ahora()
    try:
        info = extraer(texto, cuando, None, "texto")
        ia_ok = True
    except Exception as ex:
        log.warning("La IA falló con la nota escrita (%s)", ex)
        info = normalizar({"titulo": "Nota rápida", "tareas": [{"texto": texto}]})
        ia_ok = True  # la nota quedó guardada tal cual, no hay nada perdido
    if not (info["tareas"] or info["datos"] or info["ideas"]):
        info["datos"] = [texto[:300]]
    cid = guardar_captura(archivo=None, origen="texto", inicio=cuando, dur=None, lat=None, lon=None,
                          lugar=None, info=info, transcripcion=None, ia_ok=ia_ok)
    actualizar_notas(cuando.date())
    return cid


def _limpiar(*rutas: Path) -> None:
    for r in rutas:
        try:
            r.unlink(missing_ok=True)
        except OSError:
            pass


class despierta:
    """Mientras Eco procesa un audio, Windows no se suspende (no evita que la apagues tú)."""
    _kernel = getattr(getattr(__import__("ctypes"), "windll", None), "kernel32", None)

    def __enter__(self):
        if self._kernel:
            self._kernel.SetThreadExecutionState(0x80000000 | 0x00000001)  # CONTINUOUS | SYSTEM_REQUIRED
        return self

    def __exit__(self, *exc):
        if self._kernel:
            self._kernel.SetThreadExecutionState(0x80000000)
        return False


ESPERA_REINTENTO_S = 60
MAX_REINTENTOS = 6
_intentos: dict[str, int] = {}


def trabajador() -> None:
    while True:
        ruta = COLA.get()
        try:
            if ruta.exists():
                ESTADO["procesando"] = ruta.name
                with despierta():
                    procesar(ruta)
            _intentos.pop(ruta.name, None)
        except Exception as ex:
            # Lo más común es que se haya ido el internet (descarga de Whisper, IA en la nube).
            # El audio sigue en datos/entrada; se reintenta solo, cada vez esperando más.
            n = _intentos.get(ruta.name, 0) + 1
            _intentos[ruta.name] = n
            if n <= MAX_REINTENTOS:
                espera = ESPERA_REINTENTO_S * n
                log.warning("No pude procesar %s (%s). Reintento en %d s.", ruta.name, ex, espera)
                t = threading.Timer(espera, COLA.put, args=(ruta,))
                t.daemon = True
                t.start()
            else:
                log.exception("No pude procesar %s tras varios intentos; queda en datos/entrada "
                              "y se reintenta la próxima vez que enciendas Eco", ruta.name)
        finally:
            ESTADO["procesando"] = None
            COLA.task_done()


def descargar_modelo() -> None:
    """Baja el modelo de Whisper con calma, reintentando si el internet se corta."""
    modelo = CFG["whisper"]["modelo"]
    try:
        from faster_whisper.utils import download_model
    except ImportError:
        print("✕ Whisper no está instalado: ejecuta instalar.bat")
        return
    for intento in range(1, 31):
        try:
            print(f"\nDescargando Whisper '{modelo}' (intento {intento})… si se corta, sigue desde donde quedó.")
            ruta = download_model(modelo)
            print(f"\n✓ Modelo listo en {ruta}\n  Ya puedes encender Eco con iniciar.bat")
            return
        except KeyboardInterrupt:
            print("\nDescarga cancelada.")
            return
        except Exception as ex:
            print(f"  Se cortó ({ex.__class__.__name__}). Reintento en 15 s…")
            time.sleep(15)
    print("\n✕ No se pudo completar la descarga. Revisa tu internet y vuelve a intentarlo.")


def reloj() -> None:
    """Cada 30 s: manda recordatorios vencidos y, a la hora indicada, el resumen del día."""
    while True:
        try:
            vaciar_salida()
            revisar_recordatorios()
            revisar_resumen()
        except Exception:
            log.exception("Error en el reloj")
        time.sleep(30)


def revisar_recordatorios() -> None:
    with closing(db()) as con:
        vencidos = con.execute(
            "SELECT * FROM items WHERE tipo='tarea' AND hecho=0 AND recordado=0 "
            "AND recordar_ts IS NOT NULL AND recordar_ts <= ? ORDER BY recordar_ts", (int(time.time()),)).fetchall()
    for it in vencidos:
        cuando = _cuando_tarea(it)
        ok = enviar(f"⏰ <b>Recordatorio</b>\n{e(it['texto'])}" + (f"\n<i>{e(cuando)}</i>" if cuando else "")
                    + f"\n\n<code>/hecho {it['id']}</code> cuando lo termines")
        if ok or not CFG["telegram"].get("chat_id"):
            with closing(db()) as con, con:
                con.execute("UPDATE items SET recordado=1 WHERE id=?", (it["id"],))


def revisar_resumen() -> None:
    actual = ahora()
    h, m = _hhmm(CFG["notas"].get("hora_resumen"), (21, 0))
    hoy = actual.date().isoformat()
    if (actual.hour, actual.minute) < (h, m) or estado_get("ultimo_resumen") == hoy:
        return
    texto = resumen_del_dia(actual.date())
    if texto is None or enviar(texto) or not CFG["telegram"].get("chat_id"):
        estado_set("ultimo_resumen", hoy)


# ════════════════════════════ Servidor HTTP ════════════════════════════

def _nombre_seguro(nombre: str) -> str:
    base = re.sub(r"[^A-Za-z0-9_.-]", "_", Path(nombre or "").name)[:80].strip("._")
    if not base:
        base = f"eco_{int(time.time())}"
    return base if base.endswith(".wav") else base + ".wav"


# Lo que el panel muestra sobre Eco mientras corre.
ESTADO: dict = {"inicio": None, "ultimo_audio": None, "procesando": None}
_ip_fija = {"valor": None, "cuando": 0.0}


def direccion_fija() -> str | None:
    if time.time() - _ip_fija["cuando"] > 300:
        _ip_fija["valor"], _ip_fija["cuando"] = ip_tailscale(), time.time()
    return _ip_fija["valor"]


def _tarea_json(t, titulo: str | None = None) -> dict:
    hoy = ahora().date().isoformat()
    return {
        "id": t["id"], "texto": t["texto"], "fecha": t["fecha"], "hora": t["hora"],
        "cuando": _cuando_tarea(t), "hecho": bool(t["hecho"]),
        "hoy": t["fecha"] == hoy, "vencida": bool(t["fecha"] and t["fecha"] < hoy and not t["hecho"]),
        "captura_id": t["captura_id"], "de": titulo,
    }


def _captura_json(con: sqlite3.Connection, c) -> dict:
    its = items_de(con, c["id"])
    ini = datetime.fromisoformat(c["inicio"])
    return {
        "id": c["id"], "fecha": ini.date().isoformat(), "hora": f"{ini:%H:%M}",
        "duracion": c["duracion"], "lugar": c["lugar"], "lat": c["lat"], "lon": c["lon"],
        "titulo": c["titulo"], "resumen": c["resumen"], "importancia": c["importancia"] or 3,
        "origen": c["origen"], "ia_ok": bool(c["ia_ok"]), "transcripcion": c["transcripcion"],
        "tareas": [_tarea_json(i) for i in its if i["tipo"] == "tarea"],
        "datos": [i["texto"] for i in its if i["tipo"] == "dato"],
        "ideas": [i["texto"] for i in its if i["tipo"] == "idea"],
    }


def api_estado(es_local: bool) -> dict:
    hoy = ahora().date().isoformat()
    with closing(db()) as con:
        momentos_hoy = con.execute("SELECT count(*) FROM capturas WHERE substr(inicio,1,10)=?", (hoy,)).fetchone()[0]
        pendientes_n = con.execute("SELECT count(*) FROM items WHERE tipo='tarea' AND hecho=0").fetchone()[0]
        prox = con.execute(
            "SELECT * FROM items WHERE tipo='tarea' AND hecho=0 AND fecha >= ? "
            "ORDER BY fecha, hora IS NULL, hora LIMIT 1", (hoy,)).fetchone()
    puerto = int(CFG["servidor"]["puerto"])
    fija = direccion_fija()
    ia = CFG["ia"]
    return {
        "ok": True, "inicio": ESTADO["inicio"], "ultimo_audio": ESTADO["ultimo_audio"],
        "procesando": ESTADO["procesando"], "en_cola": COLA.qsize(),
        "direccion_fija": f"{fija}:{puerto}" if fija else None,
        "direccion_wifi": f"{ip_local()}:{puerto}",
        "ia": f"{ia['proveedor']} · {ia['ollama_modelo'] if ia['proveedor'] == 'ollama' else ia['claude_modelo']}",
        "whisper": str(CFG["whisper"]["modelo"]), "whisper_cargado": _whisper is not None,
        "telegram": bool(CFG["telegram"].get("token") and CFG["telegram"].get("chat_id")),
        "momentos_hoy": momentos_hoy, "pendientes": pendientes_n,
        "proximo": _tarea_json(prox) if prox else None,
        "hoy": hoy, "es_local": es_local, "notas": str(NOTAS) if es_local else None,
    }


def api_dias() -> list:
    with closing(db()) as con:
        filas = con.execute(
            "SELECT substr(inicio,1,10) AS dia, count(*) AS n FROM capturas "
            "GROUP BY dia ORDER BY dia DESC LIMIT 180").fetchall()
    return [{"fecha": f["dia"], "momentos": f["n"]} for f in filas]


def api_momentos(fecha: str | None, q: str | None) -> list:
    with closing(db()) as con:
        if q:
            patron = f"%{q}%"
            filas = con.execute(
                "SELECT DISTINCT c.* FROM capturas c LEFT JOIN items i ON i.captura_id=c.id "
                "WHERE c.titulo LIKE ? OR c.resumen LIKE ? OR c.transcripcion LIKE ? OR c.lugar LIKE ? "
                "OR i.texto LIKE ? ORDER BY c.inicio DESC LIMIT 60", (patron,) * 5).fetchall()
        else:
            dia = _fecha_valida(fecha) or ahora().date().isoformat()
            filas = con.execute("SELECT * FROM capturas WHERE substr(inicio,1,10)=? ORDER BY inicio",
                                (dia,)).fetchall()
        return [_captura_json(con, c) for c in filas]


def api_pendientes() -> list:
    with closing(db()) as con:
        filas = con.execute(
            "SELECT i.*, c.titulo AS de FROM items i LEFT JOIN capturas c ON c.id=i.captura_id "
            "WHERE i.tipo='tarea' AND i.hecho=0 "
            "ORDER BY i.fecha IS NULL, i.fecha, i.hora IS NULL, i.hora, i.id LIMIT 200").fetchall()
    return [_tarea_json(f, f["de"]) for f in filas]


def api_marcar_tarea(tarea_id: int, hecho: bool) -> dict | None:
    with closing(db()) as con, con:
        fila = con.execute("SELECT i.*, c.inicio FROM items i LEFT JOIN capturas c ON c.id=i.captura_id "
                           "WHERE i.id=? AND i.tipo='tarea'", (tarea_id,)).fetchone()
        if not fila:
            return None
        con.execute("UPDATE items SET hecho=? WHERE id=?", (int(hecho), tarea_id))
    if fila["inicio"]:
        actualizar_notas(datetime.fromisoformat(fila["inicio"]).date())
    with closing(db()) as con:
        return _tarea_json(con.execute("SELECT * FROM items WHERE id=?", (tarea_id,)).fetchone())


_PANEL: bytes | None = None


def panel_html() -> bytes:
    global _PANEL
    if _PANEL is None:
        _PANEL = (BASE / "panel.html").read_bytes()
    return _PANEL


class Manejador(BaseHTTPRequestHandler):
    server_version = "Eco/1.1"

    # ── Permisos ──
    def _autorizado(self) -> bool:
        recibido = (self.headers.get("X-Eco-Token") or "").encode()
        return hmac.compare_digest(recibido, str(CFG["servidor"]["token"]).encode())

    def _es_local(self) -> bool:
        """Abierto desde la misma laptop (http://localhost:8765). El nombre del host se revisa
        para que una página web cualquiera no pueda hacerse pasar por local."""
        host = (self.headers.get("Host") or "").rsplit(":", 1)[0].strip("[]").lower()
        return self.client_address[0] in ("127.0.0.1", "::1") and host in ("localhost", "127.0.0.1", "::1")

    def _puede_ver_panel(self) -> bool:
        return self._es_local() or self._autorizado()

    # ── Respuestas ──
    def _json(self, codigo: int, datos) -> None:
        cuerpo = json.dumps(datos, ensure_ascii=False).encode()
        self.send_response(codigo)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(cuerpo)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(cuerpo)

    def _html(self, cuerpo: bytes) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(cuerpo)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy",
                         "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; "
                         "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'")
        self.end_headers()
        self.wfile.write(cuerpo)

    # ── Rutas ──
    def do_GET(self) -> None:
        ruta, _, consulta = self.path.partition("?")
        params = {k: v[-1] for k, v in urllib.parse.parse_qs(consulta).items()}
        if ruta in ("/", "/panel"):
            return self._html(panel_html())
        if ruta == "/estado":  # lo usa la app del teléfono
            if not self._autorizado():
                return self._json(401, {"ok": False, "error": "token"})
            return self._json(200, {"ok": True, "nombre": "Eco", "en_cola": COLA.qsize()})
        if ruta.startswith("/api/"):
            if not self._puede_ver_panel():
                return self._json(401, {"ok": False, "error": "token"})
            if ruta == "/api/estado":
                return self._json(200, api_estado(self._es_local()))
            if ruta == "/api/dias":
                return self._json(200, api_dias())
            if ruta == "/api/momentos":
                return self._json(200, api_momentos(params.get("fecha"), (params.get("q") or "").strip()[:80]))
            if ruta == "/api/pendientes":
                return self._json(200, api_pendientes())
        self._json(404, {"ok": False})

    def do_POST(self) -> None:
        ruta = self.path.split("?")[0]
        if ruta == "/subir":
            return self._subir()
        if ruta == "/api/tarea":
            # La cabecera propia impide que otra página web mande esto a tus espaldas.
            if self.headers.get("X-Eco-Panel") != "1" or not self._puede_ver_panel():
                return self._json(401, {"ok": False, "error": "token"})
            try:
                largo = min(int(self.headers.get("Content-Length") or 0), 2048)
                datos = json.loads(self.rfile.read(largo) or b"{}")
                tarea = api_marcar_tarea(int(datos["id"]), bool(datos.get("hecho", True)))
            except (ValueError, KeyError, TypeError):
                return self._json(400, {"ok": False})
            return self._json(200 if tarea else 404, {"ok": bool(tarea), "tarea": tarea})
        self._json(404, {"ok": False})

    def _subir(self) -> None:
        if not self._autorizado():
            return self._json(401, {"ok": False, "error": "token"})
        try:
            largo = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            largo = 0
        if largo <= 44 or largo > MAX_SUBIDA:
            return self._json(400, {"ok": False, "error": "tamaño"})

        archivo = _nombre_seguro(self.headers.get("X-Eco-Archivo") or "")
        destino = ENTRADA / archivo
        if ya_procesado(archivo) or destino.exists():
            self.rfile.read(largo)  # ya lo teníamos (reintento del teléfono)
            return self._json(200, {"ok": True, "repetido": True})

        meta = {}
        crudo = self.headers.get("X-Eco-Meta")
        if crudo:
            try:
                meta = json.loads(base64.b64decode(crudo).decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                meta = {}

        parcial = destino.with_suffix(".part")
        restante = largo
        with open(parcial, "wb") as f:
            while restante > 0:
                bloque = self.rfile.read(min(1 << 20, restante))
                if not bloque:
                    break
                f.write(bloque)
                restante -= len(bloque)
        if restante:
            _limpiar(parcial)
            return self._json(400, {"ok": False, "error": "incompleto"})
        destino.with_suffix(".json").write_text(json.dumps(meta), encoding="utf-8")
        parcial.replace(destino)
        COLA.put(destino)
        ESTADO["ultimo_audio"] = ahora().isoformat(timespec="seconds")
        log.info("Llegó %s desde el teléfono", archivo)
        self._json(200, {"ok": True})

    def log_message(self, formato: str, *args) -> None:
        log.debug("%s - %s", self.address_string(), formato % args)


# ════════════════════════════ Arranque ════════════════════════════

def ip_local() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


def ip_tailscale() -> str | None:
    """La dirección fija que Tailscale le da a esta laptop (100.x.y.z), si está instalado."""
    import ipaddress
    import subprocess
    red = ipaddress.ip_network("100.64.0.0/10")
    candidatos: list[str] = []
    try:
        r = subprocess.run(["tailscale", "ip", "-4"], capture_output=True, text=True, timeout=5,
                           creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        candidatos += r.stdout.split()
    except (OSError, subprocess.SubprocessError):
        pass
    try:
        candidatos += [i[4][0] for i in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)]
    except OSError:
        pass
    for ip in candidatos:
        try:
            if ipaddress.ip_address(ip) in red:
                return ip
        except ValueError:
            continue
    return None


def preparar_logs(consola: bool = True) -> None:
    archivo = logging.FileHandler(DATOS / "eco.log", encoding="utf-8")
    archivo.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    manejadores: list[logging.Handler] = [archivo]
    if consola and sys.stdout is not None:
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass
        pantalla = logging.StreamHandler(sys.stdout)
        pantalla.setFormatter(logging.Formatter("%(asctime)s  %(message)s", "%H:%M:%S"))
        manejadores.insert(0, pantalla)
    log.setLevel(logging.INFO)
    log.handlers[:] = manejadores


def diagnostico() -> None:
    print("\nRevisando Eco…\n")
    tok = str(CFG["servidor"]["token"])
    print(("✓" if tok != TOKEN_DE_EJEMPLO and len(tok) >= 12 else "✕"), "Token del servidor",
          "" if tok != TOKEN_DE_EJEMPLO else "(cámbialo en config.toml)")
    print("✓ Notas en", NOTAS)
    if CFG["telegram"].get("token"):
        try:
            yo = tg("getMe") or {}
            print("✓ Telegram:", "@" + yo.get("result", {}).get("username", "?"))
            print(("✓" if CFG["telegram"].get("chat_id") else "✕"), "chat_id",
                  "" if CFG["telegram"].get("chat_id") else "(escríbele /start a tu bot con Eco encendido)")
        except Exception as ex:
            print("✕ Telegram:", ex)
    else:
        print("– Telegram sin configurar (opcional)")
    try:
        prueba = extraer("Mañana a las 3 tengo que entregarle el proyecto de física al profe Ramírez, "
                         "y acordarme de comprar filamento negro.", ahora(), None, "texto")
        print(f"✓ IA ({CFG['ia']['proveedor']}):", json.dumps(prueba, ensure_ascii=False))
    except Exception as ex:
        print(f"✕ IA ({CFG['ia']['proveedor']}):", ex)
    try:
        import faster_whisper  # noqa: F401
        print("✓ Whisper instalado (el modelo se descarga la primera vez que llegue un audio)")
    except ImportError:
        print("✕ Whisper no está instalado: pip install -r requirements.txt")
    print()


class ServidorEco(ThreadingHTTPServer):
    daemon_threads = True
    # En Windows, reusar la dirección dejaría que dos Eco "compartan" el puerto sin avisar.
    allow_reuse_address = os.name != "nt"


class YaEncendido(Exception):
    pass


class ConfigIncompleta(Exception):
    pass


def puerto_ocupado(puerto: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", puerto), timeout=1):
            return True
    except OSError:
        return False


def arrancar(consola: bool = True) -> ServidorEco:
    """Prepara todo y devuelve el servidor listo para serve_forever()."""
    configurar(cargar_config(BASE / "config.toml"))
    preparar_logs(consola)
    if str(CFG["servidor"]["token"]) == TOKEN_DE_EJEMPLO:
        raise ConfigIncompleta("Cambia el token de ejemplo en config.toml antes de encender Eco.")
    puerto = int(CFG["servidor"]["puerto"])
    if puerto_ocupado(puerto):
        raise YaEncendido(puerto)
    servidor = ServidorEco(("0.0.0.0", puerto), Manejador)
    ESTADO["inicio"] = ahora().isoformat(timespec="seconds")
    for f in sorted(ENTRADA.glob("*.wav")):  # lo que quedó pendiente la última vez
        COLA.put(f)
    for destino in (trabajador, bot, reloj):
        threading.Thread(target=destino, name=destino.__name__, daemon=True).start()
    log.info("Eco encendido (puerto %d)", puerto)
    return servidor


def main() -> None:
    if "--probar" in sys.argv or "--descargar" in sys.argv:
        configurar(cargar_config(BASE / "config.toml"))
        preparar_logs()
        diagnostico() if "--probar" in sys.argv else descargar_modelo()
        return
    try:
        servidor = arrancar()
    except ConfigIncompleta as ex:
        log.error(str(ex))
        sys.exit(1)
    except YaEncendido as ex:
        print(f"\n  Eco ya está encendido en segundo plano.\n  Panel: http://localhost:{ex.args[0]}\n")
        return

    puerto = int(CFG["servidor"]["puerto"])
    fija = direccion_fija()
    if fija:
        direcciones = (f"     Dirección para la app:  {fija}:{puerto}   (fija, funciona en cualquier red)\n"
                       f"     En tu Wi-Fi:            {ip_local()}:{puerto}\n")
    else:
        direcciones = (f"     Dirección para la app:  {ip_local()}:{puerto}   (cambia si cambias de red)\n"
                       "     Tip: con Tailscale tendrías una dirección fija. Mira el README.\n")
    print(
        "\n  ◉  Eco está encendido\n"
        + direcciones +
        f"     Panel:                  http://localhost:{puerto}\n"
        f"     Notas:                  {NOTAS}\n"
        f"     IA:                     {CFG['ia']['proveedor']}  ·  Whisper: {CFG['whisper']['modelo']}\n"
        "     (Ctrl + C para apagar)\n",
        flush=True,
    )
    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        print("\nEco apagado.")


if __name__ == "__main__":
    main()
