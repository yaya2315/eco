#!/usr/bin/env python3
"""
Eco · servidor de la laptop
===========================

Recibe los "momentos" que guarda la app del teléfono, los transcribe con Whisper,
saca lo importante con IA y te lo deja:
  • en notas Markdown (compatibles con Obsidian), y
  • en un bot privado de Telegram que además te recuerda las cosas a tiempo.

También recibe grabaciones largas (modo reunión del teléfono y modo clase de la laptop),
notas de voz y fotos por Telegram, responde preguntas sobre todo lo guardado, trae tus tareas
de Classroom, las pone en Google Calendar, hace un respaldo diario y le pasa al teléfono la
versión nueva de la app.

Uso:
    python eco_servidor.py            → arranca Eco (con ventana)
    python eco_servidor.py --probar   → revisa configuración, IA, Telegram y Whisper
    python eco_app.pyw                → arranca Eco en segundo plano, con ícono junto al reloj
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import html
import io
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
from datetime import date, datetime, timedelta, timezone
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
MAX_SUBIDA = 400 * 1024 * 1024  # 400 MB
EXT_AUDIO = (".wav", ".m4a", ".aac", ".mp4", ".ogg", ".oga", ".opus", ".mp3", ".webm")
EXT_IMAGEN = (".jpg", ".jpeg", ".png", ".webp")
MAX_TELEGRAM = 20 * 1024 * 1024  # lo más que Telegram deja bajar a un bot

log = logging.getLogger("eco")

# ════════════════════════════ Configuración ════════════════════════════

DEFAULTS: dict = {
    "usuario": {"nombre": "el usuario"},
    "servidor": {"puerto": 8765, "token": TOKEN_DE_EJEMPLO},
    "whisper": {
        "modelo": "small", "idioma": "es", "beam": 1, "hilos": 0,
        # Con la laptop enchufada usa un modelo más preciso (más lento). "" = siempre el mismo.
        "modelo_corriente": "medium", "beam_corriente": 2,
    },
    "ia": {
        "proveedor": "claude",
        "claude_modelo": "claude-haiku-5-5",
        "claude_api_key": "",
        "ollama_modelo": "qwen2.5:3b",
        "ollama_vision": "qwen2.5vl:3b",   # lee las fotos que mandas por Telegram
        "ollama_embed": "embeddinggemma",  # entiende el significado para "pregúntale a Eco" ("" = apagado)
        "ollama_url": "http://localhost:11434",
    },
    "telegram": {"token": "", "chat_id": ""},
    "notas": {
        "carpeta": "notas",
        "zona_horaria": "America/El_Salvador",
        "hora_resumen": "21:00",
        "hora_manana": "06:30",            # resumen de la mañana ("" = apagado)
        "hora_recordatorio": "07:00",
        "aviso_antes_min": 60,
        "guardar_transcripcion": False,
        "guardar_audio": False,
    },
    "sesiones": {
        # Clases y reuniones largas: la transcripción completa sirve para preguntarle a Eco después.
        "guardar_transcripcion_clase": True,
        "guardar_transcripcion_reunion": True,
        "clase_auto_terminar_min": 20,   # si la clase lleva este tiempo en silencio, se termina sola
    },
    "respaldo": {
        "activo": True,
        "carpeta": "",      # vacío = OneDrive\Eco respaldos
        "conservar": 14,    # cuántas copias guardar
    },
    "salud": {
        "alertas": True,          # avisos por Telegram cuando algo deja de funcionar
        "telefono_horas": 3,      # avisar si el teléfono no se reporta en este tiempo (de día)
        "silencio": "22:00-06:30",  # en este horario las alertas esperan a la mañana
    },
    "google": {
        "anuncios": True,    # leer los anuncios de Classroom y sacar fechas y tareas
        "classroom": True,   # traer las tareas de Classroom
        "calendar": True,    # poner las tareas con fecha en el calendario "Eco"
        "cada_min": 20,      # cada cuánto revisar Classroom
    },
    "github": {
        # Para que la laptop baje sola la app nueva del teléfono. El token también se puede
        # pegar desde el panel (queda en datos/, fuera de GitHub).
        "token": "",
        "repo": "yaya2315/eco",
        "artefacto": "Eco-apk",
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
COLA: "queue.Queue" = queue.Queue()
CLASE = None  # grabadora del modo clase (se crea al arrancar)


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
CREATE TABLE IF NOT EXISTS sesiones (
    id            INTEGER PRIMARY KEY,
    clave         TEXT UNIQUE NOT NULL,
    tipo          TEXT NOT NULL,           -- clase | reunion
    nombre        TEXT,
    inicio        TEXT NOT NULL,
    fin           TEXT,
    partes_total  INTEGER,                 -- se sabe cuando llega la última parte
    estado        TEXT NOT NULL DEFAULT 'grabando',   -- grabando | procesando | lista
    captura_id    INTEGER,
    lat           REAL,
    lon           REAL,
    actualizado   TEXT
);
CREATE TABLE IF NOT EXISTS sesion_partes (
    sesion_id     INTEGER NOT NULL,
    n             INTEGER NOT NULL,
    inicio_ms     INTEGER,
    duracion      REAL,
    texto         TEXT,
    PRIMARY KEY (sesion_id, n)
);
CREATE TABLE IF NOT EXISTS google_eventos (
    item_id       INTEGER PRIMARY KEY,     -- tarea de Eco
    evento        TEXT NOT NULL,           -- su evento en el calendario "Eco"
    firma         TEXT
);
CREATE TABLE IF NOT EXISTS classroom_anuncios (
    id            TEXT PRIMARY KEY,        -- id del anuncio en Classroom
    curso         TEXT,
    texto         TEXT,
    creado        TEXT,
    enlace        TEXT,
    estado        TEXT NOT NULL DEFAULT 'nuevo',   -- nuevo | ignorado | sin_nada | guardado
    captura_id    INTEGER
);
CREATE TABLE IF NOT EXISTS vectores (     -- "significado" de cada pedazo, para preguntar
    captura_id    INTEGER NOT NULL,
    n             INTEGER NOT NULL,
    texto         TEXT NOT NULL,
    vector        BLOB NOT NULL,
    modelo        TEXT,
    PRIMARY KEY (captura_id, n)
);
"""

# Columnas que se agregaron después: se añaden solas a una base de datos vieja.
MIGRACIONES = {
    "capturas": [("adjunto", "TEXT")],                      # foto guardada en notas/fotos
    "items": [("origen", "TEXT"), ("externo", "TEXT"),      # 'classroom' + id de Classroom
              ("enlace", "TEXT"), ("detalle", "TEXT")],     # link para abrirla + nombre de la clase
}

# Índice de búsqueda para "pregúntale a Eco" (va aparte: si SQLite no trae FTS5, Eco sigue funcionando).
ESQUEMA_INDICE = """
CREATE VIRTUAL TABLE IF NOT EXISTS indice USING fts5(
    texto, captura_id UNINDEXED, campo UNINDEXED, tokenize = 'unicode61 remove_diacritics 2'
);
"""
HAY_INDICE = False


def db() -> sqlite3.Connection:
    con = sqlite3.connect(DB_PATH, timeout=15)
    con.row_factory = sqlite3.Row
    return con


def init_db() -> None:
    global HAY_INDICE
    with closing(db()) as con, con:
        con.executescript(ESQUEMA)
        for tabla, columnas in MIGRACIONES.items():
            existentes = {f["name"] for f in con.execute(f"PRAGMA table_info({tabla})")}
            for nombre, tipo in columnas:
                if nombre not in existentes:
                    con.execute(f"ALTER TABLE {tabla} ADD COLUMN {nombre} {tipo}")
        con.execute("CREATE UNIQUE INDEX IF NOT EXISTS items_externo ON items(externo) WHERE externo IS NOT NULL")
    try:
        with closing(db()) as con, con:
            con.executescript(ESQUEMA_INDICE)
        HAY_INDICE = True
    except sqlite3.OperationalError as ex:
        HAY_INDICE = False
        log.warning("Sin índice de búsqueda (%s); las preguntas usarán una búsqueda simple.", ex)


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

_modelos: dict = {}            # modelos de Whisper ya cargados (nombre → modelo)
_whisper_lock = threading.Lock()
_modelo_listo: dict[str, bool] = {}
_bajando: set[str] = set()


def con_corriente() -> bool | None:
    """¿La laptop está enchufada? None si no se puede saber (no es Windows)."""
    if os.name != "nt":
        return None
    import ctypes

    class _Estado(ctypes.Structure):
        _fields_ = [("ACLineStatus", ctypes.c_ubyte), ("BatteryFlag", ctypes.c_ubyte),
                    ("BatteryLifePercent", ctypes.c_ubyte), ("SystemStatusFlag", ctypes.c_ubyte),
                    ("BatteryLifeTime", ctypes.c_ulong), ("BatteryFullLifeTime", ctypes.c_ulong)]

    e = _Estado()
    if not ctypes.windll.kernel32.GetSystemPowerStatus(ctypes.byref(e)):
        return None
    return {0: False, 1: True}.get(e.ACLineStatus)


def _modelo_descargado(nombre: str) -> bool:
    if _modelo_listo.get(nombre):
        return True
    try:
        from faster_whisper.utils import download_model
        download_model(nombre, local_files_only=True)
        _modelo_listo[nombre] = True
    except Exception:
        return False
    return True


def _bajar_en_segundo_plano(nombre: str) -> None:
    if nombre in _bajando:
        return
    _bajando.add(nombre)

    def bajar():
        from faster_whisper.utils import download_model
        for intento in range(1, 6):
            try:
                log.info("Bajando el Whisper '%s' para cuando la laptop esté enchufada (intento %d)…", nombre, intento)
                download_model(nombre)
                _modelo_listo[nombre] = True
                log.info("Whisper '%s' listo", nombre)
                return
            except Exception as ex:
                log.warning("No pude bajar Whisper '%s' (%s)", nombre, ex)
                time.sleep(120 * intento)
        _bajando.discard(nombre)

    threading.Thread(target=bajar, name="bajar-whisper", daemon=True).start()


def elegir_modelo() -> tuple[str, int, str]:
    """(modelo, beam, por qué). Enchufada = el preciso; con batería o con mucha cola = el rápido."""
    w = CFG["whisper"]
    rapido = (str(w["modelo"]), int(w.get("beam") or 1))
    mejor = str(w.get("modelo_corriente") or "").strip()
    if not mejor or mejor == rapido[0]:
        return (*rapido, "")
    enchufada = con_corriente()
    if not enchufada:
        return (*rapido, "con batería" if enchufada is False else "")
    if COLA.qsize() >= 4:
        return (*rapido, "hay mucho en cola")
    if not _modelo_descargado(mejor):
        _bajar_en_segundo_plano(mejor)
        return (*rapido, f"bajando {mejor}")
    return mejor, int(w.get("beam_corriente") or rapido[1]), "con corriente"


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
        return _decodificar(ruta)
    if ancho != 2:
        return _decodificar(ruta)
    audio = np.frombuffer(crudo, dtype="<i2").astype(np.float32) / 32768.0
    if canales > 1:
        audio = audio[: len(audio) // canales * canales].reshape(-1, canales).mean(axis=1)
    if tasa != 16000 and len(audio):
        n = int(round(len(audio) * 16000 / tasa))
        audio = np.interp(np.linspace(0, len(audio) - 1, n), np.arange(len(audio)), audio).astype(np.float32)
    return audio


def _decodificar(ruta: Path):
    """M4A/AAC y otros formatos → 16 kHz mono, usando PyAV directamente (sin la opción que falla)."""
    import av
    import numpy as np
    pedazos = []
    with av.open(str(ruta)) as contenedor:
        flujo = next(s for s in contenedor.streams if s.type == "audio")
        remuestreo = av.AudioResampler(format="s16", layout="mono", rate=16000)
        for cuadro in contenedor.decode(flujo):
            for c in remuestreo.resample(cuadro):
                pedazos.append(c.to_ndarray().reshape(-1))
        for c in remuestreo.resample(None):
            pedazos.append(c.to_ndarray().reshape(-1))
    if not pedazos:
        return np.zeros(0, dtype=np.float32)
    return np.concatenate(pedazos).astype(np.float32) / 32768.0


# Frases que Whisper "escucha" en silencio o ruido (vienen de los subtítulos con que lo entrenaron).
ALUCINACIONES = (
    "subtitulos realizados por la comunidad de amara", "subtitulos por la comunidad de amara",
    "gracias por ver el video", "gracias por ver", "suscribete al canal", "no olvides suscribirte",
    "subtitulado por", "amara.org", "musica de fondo", "gracias por su atencion",
)


def _segmento_valido(s) -> bool:
    texto = re.sub(r"[^a-z0-9. ]", "", _sin_acentos(s.text)).strip()
    if not texto:
        return False
    if getattr(s, "no_speech_prob", 0) > 0.6 and getattr(s, "avg_logprob", 0) < -1.0:
        return False  # Whisper mismo duda que ahí haya voz
    if len(texto) < 60 and any(a in texto for a in ALUCINACIONES):
        return False
    return True


def transcribir(ruta: Path) -> str:
    w = CFG["whisper"]
    nombre, beam, motivo = elegir_modelo()
    with _whisper_lock:
        modelo = _modelos.get(nombre)
        if modelo is None:
            from faster_whisper import WhisperModel  # import lento: solo cuando hace falta
            log.info("Cargando Whisper '%s'%s…", nombre, f" ({motivo})" if motivo else "")
            modelo = WhisperModel(nombre, device="cpu", compute_type="int8", cpu_threads=int(w.get("hilos") or 0))
            _modelos[nombre] = modelo
        ESTADO["whisper_usado"] = nombre
        audio = leer_audio(ruta)
        if not isinstance(audio, str) and len(audio) < 1600:  # menos de 0.1 s: nada que escuchar
            return ""
        segmentos, _info = modelo.transcribe(
            audio, language=w.get("idioma") or None, beam_size=beam,
            vad_filter=True, condition_on_previous_text=False,
        )
        return " ".join(s.text.strip() for s in segmentos if _segmento_valido(s)).strip()


def estado_whisper() -> str:
    nombre, _beam, motivo = elegir_modelo()
    return nombre + (f" · {motivo}" if motivo else "")


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
- Las deudas y préstamos de {nombre} SON TAREAS, con el monto y el nombre: "le debo 10 dólares a Steven"
  → "Pagarle $10 a Steven"; "Ana me debe 5" → "Cobrarle $5 a Ana"; "le presté mi cargador a Luis"
  → "Pedirle a Luis que devuelva el cargador".
- Si dice una fecha, ponla siempre en la tarea (va a su calendario). Si solo dice el mes o el año
  ("en diciembre", "en 2030"), deja fecha vacía y escríbelo en el texto: "Comprar una PS5 (en 2030)".
- Horas en formato 24 h. Si no dicen "de la mañana" o "de la madrugada", "a las 1" hasta "a las 6"
  son de la tarde: "a las 3" = 15:00. "a las 8" sin más = 08:00.
- titulo: concreto, de qué trata ("Entrega del proyecto de física"). Nunca genérico como "Tareas" o "Recuerdos".
- No inventes nada. Si no hay nada útil, deja las listas vacías y dilo en el resumen.
- De otras personas anota solo lo necesario para la tarea o el dato ("Mandarle el archivo a Carlos").
  No anotes detalles íntimos, de salud o de la vida privada de terceros (lo que {nombre} debe o le
  deben sí se anota, como tarea).
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


def _prompt_usuario(texto: str, cuando: datetime, lugar: str | None, origen: str,
                    contexto: str | None = None) -> str:
    nombre = CFG["usuario"]["nombre"]
    tipo = {
        "texto": "Nota escrita por él mismo",
        "voz": "Nota de voz que grabó él mismo para acordarse (transcripción automática)",
        "foto": "Lo que se lee en la foto",
        "anuncio": "Anuncio del profesor",
    }.get(origen, "Transcripción del audio")
    partes = [
        f"Fecha y hora del fragmento: {fecha_larga(cuando.date())}, {cuando:%H:%M} ({CFG['notas']['zona_horaria']}).",
        f"Hoy es {fecha_larga(ahora().date())}.",
    ]
    if origen == "clase":
        partes.append(
            f"Esto es parte de una CLASE VIRTUAL que recibió {nombre} (hablan el profesor y los compañeros, "
            f"{nombre} casi no habla). Tareas = lo que dejaron de tarea, lo que hay que entregar, estudiar o "
            "preparar, con su fecha si la dicen. Datos = temas de examen, fechas, instrucciones, criterios de "
            "calificación y conceptos clave explicados. No pongas como tarea lo que hizo el profesor en clase.")
    elif origen == "reunion":
        partes.append(
            f"Esto es parte de una conversación o reunión larga en la que participó {nombre}. "
            f"Anota los acuerdos, los compromisos de {nombre} y los datos que le sirvan.")
    elif origen == "foto":
        partes.append(
            f"{nombre} le tomó una FOTO a algo para no olvidarlo: una pizarra, un cuaderno, una hoja de "
            "tareas, un horario o una captura de pantalla. Abajo está lo que se lee en ella. Tareas = lo que hay "
            "que entregar, estudiar, llevar o hacer, con su fecha si aparece (resuelve 'viernes', '23/10', etc.). "
            "Datos = fechas de examen, temas, aulas, instrucciones y criterios. Si la foto no tiene nada útil, "
            "dilo en el resumen.")
    elif origen == "voz":
        partes.append(f"{nombre} se lo dictó a sí mismo: casi todo lo que dice es algo que quiere recordar o hacer.")
    elif origen == "anuncio":
        partes.append(
            f"Es un ANUNCIO que un profesor publicó en Google Classroom para toda la clase de {nombre}. "
            f"Tareas = lo que {nombre} tiene que entregar, estudiar, llevar o hacer, con su fecha (resuelve 'el "
            "viernes', 'mañana', '23/10' con la fecha del anuncio). Datos = fechas de exámenes, cambios de horario o "
            "aula, temas e instrucciones. Ignora saludos y frases de cortesía. El título debe decir de qué trata "
            "(por ejemplo 'Examen de química el jueves').")
    if contexto:
        partes.append(contexto)
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


def _llamar_claude(sistema: str, usuario: str, json_mode: bool = True) -> str:
    try:
        import anthropic
    except ImportError as e:
        raise RuntimeError("falta instalar la librería anthropic (pip install anthropic)") from e
    ia = CFG["ia"]
    cliente = anthropic.Anthropic(api_key=ia.get("claude_api_key") or None)
    msg = cliente.messages.create(
        model=ia["claude_modelo"], max_tokens=2000, temperature=0.2,
        system=sistema, messages=[{"role": "user", "content": usuario}],
    )
    return "".join(getattr(b, "text", "") for b in msg.content)


def _llamar_ollama(sistema: str, usuario: str, json_mode: bool = True) -> str:
    ia = CFG["ia"]
    pedido = {
        "model": ia["ollama_modelo"], "stream": False,
        # num_ctx: Ollama trae 2048-4096 de fábrica y recorta en silencio lo que no cabe.
        "options": {"temperature": 0.2, "num_ctx": int(ia.get("ollama_contexto") or 8192)},
        "messages": [{"role": "system", "content": sistema}, {"role": "user", "content": usuario}],
    }
    if json_mode:
        pedido["format"] = "json"
    cuerpo = json.dumps(pedido).encode()
    req = urllib.request.Request(ia["ollama_url"].rstrip("/") + "/api/chat", data=cuerpo,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        return json.load(r)["message"]["content"]


def llamar_ia(sistema: str, usuario: str, json_mode: bool = True) -> str:
    proveedor = str(CFG["ia"].get("proveedor", "claude")).lower()
    llamar = _llamar_ollama if proveedor == "ollama" else _llamar_claude
    return llamar(sistema, usuario, json_mode)


def extraer(texto: str, cuando: datetime, lugar: str | None, origen: str = "audio",
            contexto: str | None = None) -> dict:
    respuesta = llamar_ia(_prompt_sistema(), _prompt_usuario(texto, cuando, lugar, origen, contexto))
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


# Deudas, préstamos y compras son algo por hacer aunque el modelo los ponga en "datos"
# ("Deudas con Steven (10 dólares)" tiene que quedar pendiente hasta que lo pagues).
_PARECE_TAREA = re.compile(
    r"\b(le debo|debo|me debe|me deben|deuda|deudas|prestamo|le preste|prestarle|devolverle|pagarle|cobrarle)\b"
    r"|^(la |el |una |un )?(compra|pago|devolucion|entrega) de\b")


def parece_tarea(texto: str) -> bool:
    t = _sin_acentos(texto).strip()
    return _clave(texto).startswith(VERBOS_DE_TAREA) or bool(_RECORDAR.match(t)) or bool(_PARECE_TAREA.search(t))


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
        if parece_tarea(d):
            info["tareas"].append({"texto": _quitar_recordar(d), "fecha": None, "hora": None})
        else:
            datos.append(d)
    info["datos"] = datos
    ideas = []
    for i in info["ideas"]:
        if _RECORDAR.match(_sin_acentos(i)) or _PARECE_TAREA.search(_sin_acentos(i)):
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
                    transcripcion: str | None, ia_ok: bool, adjunto: str | None = None) -> int:
    with closing(db()) as con, con:
        cur = con.execute(
            "INSERT INTO capturas(archivo, origen, inicio, duracion, lat, lon, lugar, titulo, resumen, "
            "transcripcion, importancia, ia_ok, procesado, adjunto) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (archivo, origen, inicio.isoformat(timespec="seconds"), dur, lat, lon, lugar, info["titulo"],
             info["resumen"], transcripcion, info["importancia"], int(ia_ok), ahora().isoformat(timespec="seconds"),
             adjunto),
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
    indexar(cid)
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
            if c["origen"] in ETIQUETA_ORIGEN:
                meta.append(ETIQUETA_ORIGEN[c["origen"]].lower())
            meta.append("●" * (c["importancia"] or 3) + "○" * (5 - (c["importancia"] or 3)))
            lineas += [f"## {c['titulo']}", " · ".join(meta), ""]
            if c["adjunto"]:
                lineas += [f"![[{c['adjunto']}|420]]", ""]
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
            if c["transcripcion"] and c["origen"] in TIPOS_SESION:
                enlace = archivo_transcripcion(c).relative_to(NOTAS).as_posix().removesuffix(".md")
                lineas += [f"📄 Transcripción completa: [[{enlace}]]", ""]
            elif c["transcripcion"]:
                resumen = "Lo que se lee en la foto" if c["origen"] == "foto" else "Transcripción"
                lineas += [f"<details><summary>{resumen}</summary>", "", c["transcripcion"], "", "</details>", ""]
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
        enlace = f" · [Classroom]({t['enlace']})" if t["enlace"] else ""
        lineas.append(f"- [ ] {t['texto']}" + (f" — 📅 {cuando}" if cuando else "") + enlace + f" `#{t['id']}`")
    ruta = NOTAS / "Pendientes.md"
    ruta.write_text("\n".join(lineas) + "\n", encoding="utf-8")
    return ruta


def actualizar_notas(d: date | None) -> None:
    """Reescribe la nota del día (si se indica) y la de pendientes. Se llama después de cualquier
    cambio en momentos o tareas, así que también avisa a Google Calendar que hay algo nuevo."""
    try:
        if d is not None:
            render_dia(d)
        render_pendientes()
    except OSError as e:
        log.warning("No pude escribir las notas: %s", e)
    avisar_google()


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
    if c["origen"] in ("voz", "foto", "anuncio"):
        meta.append(ETIQUETA_ORIGEN[c["origen"]].lower())
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
    "/semana — lo que viene en los próximos 7 días\n"
    "/pendientes — lo que tienes por hacer\n"
    "/hecho 12 — marcar la tarea #12 como hecha\n"
    "/buscar palabra — buscar en todo lo guardado\n\n"
    "Pregúntame lo que quieras con signo de pregunta, por ejemplo:\n"
    "<i>¿Qué dejó de tarea el profe de física?</i>\n\n"
    "Si me escribes cualquier otra cosa (\"examen de física el martes\"), la anoto.\n"
    "🎙️ Mándame una <b>nota de voz</b> y la anoto igual que un momento.\n"
    "📷 Mándame una <b>foto</b> de la pizarra o de la hoja de tareas y saco las fechas y tareas."
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
    elif comando in ("/semana", "/manana", "/agenda"):
        enviar(agenda(ahora().date(), manana=False) or "No tienes nada con fecha para los próximos 7 días. 🎉")
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
            actualizar_notas(datetime.fromisoformat(f["inicio"]).date() if f["inicio"] else None)
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
    elif comando == "/pregunta" or (not comando.startswith("/") and es_pregunta(texto)):
        pregunta = resto if comando == "/pregunta" else texto
        if not pregunta:
            enviar("¿Qué quieres saber? <code>/pregunta qué dejaron de tarea</code>")
            return
        try:
            tg("sendChatAction", chat_id=dueño, action="typing")
        except Exception:
            pass
        try:
            r = responder_pregunta(pregunta)
        except Exception as ex:
            log.warning("No pude responder la pregunta (%s)", ex)
            enviar("No pude pensar la respuesta ahora (¿está encendido Ollama?). Inténtalo en un rato.")
            return
        partes = [f"💬 {e(r['respuesta'])}"]
        if r["fuentes"]:
            partes.append("\n<i>Lo saqué de: " + "; ".join(f"{e(f['titulo'])} ({e(f['cuando'])})"
                                                        for f in r["fuentes"][:3]) + "</i>")
        enviar("\n".join(partes))
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
                chat = str(m.get("chat", {}).get("id", ""))
                texto = (m.get("text") or "").strip()
                try:
                    if texto:
                        manejar_mensaje(chat, texto)
                    elif any(k in m for k in ("voice", "audio", "video_note", "photo", "document")):
                        manejar_adjunto(chat, m)
                except Exception:
                    log.exception("Error atendiendo un mensaje de Telegram")
        except Exception as ex:
            log.warning("Telegram sin conexión (%s); reintento en 15 s", ex)
            time.sleep(15)


def manejar_adjunto(chat: str, m: dict) -> None:
    """Notas de voz y fotos que le mandas al bot: se bajan a datos/entrada y siguen el mismo
    camino que los momentos del teléfono (el trabajador las procesa una por una)."""
    dueño = str(CFG["telegram"].get("chat_id") or "")
    if not dueño or chat != dueño:
        return
    doc = m.get("document") or {}
    tipo_doc = str(doc.get("mime_type") or "")
    if m.get("voice") or m.get("audio") or m.get("video_note") or tipo_doc.startswith(("audio/", "video/")):
        archivo, clase = (m.get("voice") or m.get("audio") or m.get("video_note") or doc), "voz"
    elif m.get("photo") or tipo_doc.startswith("image/"):
        archivo = max(m["photo"], key=lambda p: p.get("width", 0) * p.get("height", 0)) if m.get("photo") else doc
        clase = "foto"
    else:
        enviar("Por ahora entiendo texto, notas de voz y fotos. 🙂")
        return
    if int(archivo.get("file_size") or 0) > MAX_TELEGRAM:
        enviar("Ese archivo pasa de 20 MB, lo más que Telegram me deja bajar. Mándalo más corto o más liviano.")
        return
    meta = {"inicio_ms": int(m.get("date") or time.time()) * 1000, "origen": clase, "app": "telegram"}
    if archivo.get("duration"):
        meta["duracion_s"] = float(archivo["duration"])
    pie = (m.get("caption") or "").strip()
    if pie:
        meta["texto"] = pie[:1000]
    try:
        ruta = descargar_de_telegram(archivo["file_id"], f"tg_{m.get('message_id', int(time.time()))}_{clase}", meta)
    except Exception as ex:
        log.warning("No pude bajar el archivo de Telegram (%s)", ex)
        enviar("No pude bajar eso de Telegram. Mándamelo otra vez en un rato.")
        return
    if ruta is None:
        enviar("Ese tipo de archivo no lo entiendo todavía. Mándalo como nota de voz o como foto.")
        return
    COLA.put(ruta)
    ESTADO["ultimo_audio"] = ahora().isoformat(timespec="seconds")
    log.info("Llegó %s por Telegram", ruta.name)
    if clase == "voz":
        enviar("🎧 Recibido. Te escucho y te lo anoto en un momento.")
    else:
        lenta = str(CFG["ia"].get("proveedor")).lower() == "ollama"
        enviar("📷 Recibida. Leo la foto y te digo qué encontré" + (" (con Ollama tarda un par de minutos)." if lenta else "."))


def descargar_de_telegram(file_id: str, base: str, meta: dict) -> Path | None:
    r = tg("getFile", file_id=file_id) or {}
    ruta_tg = (r.get("result") or {}).get("file_path")
    if not ruta_tg:
        raise RuntimeError("Telegram no dio la ruta del archivo")
    ext = Path(ruta_tg).suffix.lower()
    ext = ".ogg" if ext == ".oga" else ext
    if ext not in EXT_AUDIO + EXT_IMAGEN:
        return None
    nombre = re.sub(r"[^A-Za-z0-9_-]", "_", base)[:60]
    destino = ENTRADA / (nombre + ext)
    if ya_procesado(destino.name) or destino.exists():
        return destino
    url = f"https://api.telegram.org/file/bot{CFG['telegram']['token']}/{ruta_tg}"
    parcial = destino.with_suffix(".part")
    try:
        with urllib.request.urlopen(url, timeout=120) as resp, open(parcial, "wb") as f:
            restante = MAX_TELEGRAM + 1
            while True:
                bloque = resp.read(1 << 16)
                if not bloque:
                    break
                restante -= len(bloque)
                if restante < 0:
                    raise RuntimeError("archivo demasiado grande")
                f.write(bloque)
    except Exception:
        _limpiar(parcial)
        raise
    destino.with_suffix(".json").write_text(json.dumps(meta), encoding="utf-8")
    parcial.replace(destino)
    return destino


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
    if isinstance(meta.get("sesion"), dict):
        return procesar_parte(ruta, meta)
    if ya_procesado(archivo):
        _limpiar(ruta, meta_ruta)
        return None
    if ruta.suffix.lower() in EXT_IMAGEN:
        return procesar_foto(ruta, meta)

    inicio = (datetime.fromtimestamp(float(meta["inicio_ms"]) / 1000, TZ)
              if meta.get("inicio_ms") else ahora())
    dur = meta.get("duracion_s")
    lat, lon = ubicacion_de(meta)
    lugar = lugar_de(lat, lon)
    origen = "voz" if meta.get("origen") == "voz" else "audio"

    t0 = time.time()
    texto = transcribir(ruta)
    log.info("Transcrito %s (%s de audio) en %.0f s", archivo, duracion(dur), time.time() - t0)
    if origen == "voz" and meta.get("texto"):
        texto = f"{meta['texto']}\n{texto}".strip()  # lo que escribiste junto a la nota de voz

    # Tus notas de voz son tuyas: su texto se guarda siempre. Lo que escucha el teléfono, solo si lo pides.
    guardar_texto = origen == "voz" or bool(CFG["notas"].get("guardar_transcripcion"))
    minimo = 1 if origen == "voz" else 4  # "comprar pan" es una nota de voz válida
    if len(texto.split()) < minimo:
        info = normalizar({"titulo": "Sin voz clara", "resumen": "No se entendió lo suficiente en este momento."})
        cid = guardar_captura(archivo=archivo, origen=origen, inicio=inicio, dur=dur, lat=lat, lon=lon,
                              lugar=lugar, info=info, transcripcion=texto or None, ia_ok=True)
    else:
        try:
            info = extraer(texto, inicio, lugar, origen)
            ia_ok = True
        except Exception as ex:
            log.warning("La IA falló (%s); guardo la transcripción para no perder nada", ex)
            info = normalizar({"titulo": "Momento guardado", "resumen": texto[:200] + ("…" if len(texto) > 200 else "")})
            ia_ok = False
        cid = guardar_captura(archivo=archivo, origen=origen, inicio=inicio, dur=dur, lat=lat, lon=lon,
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


# ── Fotos (pizarra, cuaderno, hoja de tareas) ──

PEDIDO_FOTO = (
    "Transcribe TODO el texto que se lee en esta imagen, tal cual y en orden, respetando fechas, horas, "
    "números, nombres de materias y nombres de personas. Si es una pizarra, un cuaderno, una hoja de tareas, "
    "un horario, un calendario o una captura de pantalla, copia su contenido completo. No resumas ni inventes. "
    "Al final agrega una línea que empiece con 'Descripción:' y diga en una oración qué es la imagen."
)


def _imagen_para_ia(ruta: Path) -> tuple[bytes, str]:
    """La foto en JPEG de máximo 1600 px: suficiente para leer letra de pizarra y mucho más rápida."""
    try:
        from PIL import Image, ImageOps
        with Image.open(ruta) as im:
            im = ImageOps.exif_transpose(im).convert("RGB")
            im.thumbnail((1600, 1600))
            salida = io.BytesIO()
            im.save(salida, "JPEG", quality=88)
            return salida.getvalue(), "image/jpeg"
    except Exception:
        tipo = {".png": "image/png", ".webp": "image/webp"}.get(ruta.suffix.lower(), "image/jpeg")
        return ruta.read_bytes(), tipo


def leer_imagen(ruta: Path) -> str:
    datos, tipo = _imagen_para_ia(ruta)
    b64 = base64.b64encode(datos).decode()
    ia = CFG["ia"]
    if str(ia.get("proveedor", "claude")).lower() == "ollama":
        pedido = {
            "model": ia.get("ollama_vision") or "qwen2.5vl:3b", "stream": False,
            "options": {"temperature": 0.1, "num_ctx": int(ia.get("ollama_contexto") or 8192)},
            "messages": [{"role": "user", "content": PEDIDO_FOTO, "images": [b64]}],
        }
        req = urllib.request.Request(ia["ollama_url"].rstrip("/") + "/api/chat", data=json.dumps(pedido).encode(),
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=1200) as r:
                return json.load(r)["message"]["content"].strip()
        except urllib.error.HTTPError as ex:
            detalle = ex.read().decode("utf-8", "replace")
            if ex.code == 404 or "not found" in detalle:
                raise ModeloFaltante(pedido["model"]) from ex
            raise
    import anthropic
    cliente = anthropic.Anthropic(api_key=ia.get("claude_api_key") or None)
    msg = cliente.messages.create(
        model=ia["claude_modelo"], max_tokens=2000, temperature=0,
        messages=[{"role": "user", "content": [
            {"type": "image", "source": {"type": "base64", "media_type": tipo, "data": b64}},
            {"type": "text", "text": PEDIDO_FOTO}]}],
    )
    return "".join(getattr(b, "text", "") for b in msg.content).strip()


class ModeloFaltante(RuntimeError):
    pass


def procesar_foto(ruta: Path, meta: dict) -> int:
    meta_ruta = ruta.with_suffix(".json")
    inicio = (datetime.fromtimestamp(float(meta["inicio_ms"]) / 1000, TZ)
              if meta.get("inicio_ms") else ahora())
    pie = str(meta.get("texto") or "").strip()
    t0 = time.time()
    try:
        leido = leer_imagen(ruta)
    except ModeloFaltante as ex:
        if _intentos.get(ruta.name, 0) == 0:
            enviar_o_guardar(f"📷 Para leer fotos falta el modelo de visión en tu laptop. Abre PowerShell y escribe:\n"
                             f"<code>ollama pull {e(ex.args[0])}</code>\nLa foto queda guardada y la leo apenas esté.")
        raise
    log.info("Foto %s leída en %.0f s", ruta.name, time.time() - t0)

    contenido = leido + (f"\n\nLo que {CFG['usuario']['nombre']} escribió junto a la foto: {pie}" if pie else "")
    try:
        info = extraer(contenido, inicio, None, "foto")
        ia_ok = True
    except Exception as ex:
        log.warning("La IA falló con la foto (%s); guardo lo que se leyó", ex)
        info = normalizar({"titulo": "Foto guardada", "resumen": leido[:200]})
        ia_ok = False

    carpeta = NOTAS / "fotos"
    carpeta.mkdir(parents=True, exist_ok=True)
    destino = carpeta / f"{inicio:%Y-%m-%d %H%M%S}{ruta.suffix.lower()}"
    n = 1
    while destino.exists():
        n += 1
        destino = carpeta / f"{inicio:%Y-%m-%d %H%M%S}-{n}{ruta.suffix.lower()}"
    ruta.replace(destino)
    cid = guardar_captura(archivo=ruta.name, origen="foto", inicio=inicio, dur=None, lat=None, lon=None, lugar=None,
                          info=info, transcripcion=contenido or None, ia_ok=ia_ok,
                          adjunto=destino.relative_to(NOTAS).as_posix())
    _limpiar(meta_ruta)
    actualizar_notas(inicio.date())
    enviar_o_guardar(mensaje_captura(cid))
    return cid


# ════════════════════════════ Sesiones largas (clase y reunión) ════════════════════════════
# El teléfono (modo reunión) y la laptop (modo clase) mandan la grabación en partes de ~10 min.
# Cada parte se transcribe apenas llega; cuando llega la última, se analiza todo junto.

TIPOS_SESION = {"clase": "Clase", "reunion": "Reunión"}
ETIQUETA_ORIGEN = {"texto": "Nota escrita", "voz": "Nota de voz", "foto": "Foto",
                   "anuncio": "Anuncio de Classroom", **TIPOS_SESION}
PALABRAS_POR_BLOQUE = 1100  # lo que la IA analiza de una vez (~8 min de habla)


def _clave_segura(clave: str) -> str:
    return re.sub(r"[^A-Za-z0-9_-]", "_", str(clave or ""))[:60] or f"s{int(time.time())}"


def procesar_parte(ruta: Path, meta: dict) -> int | None:
    s = meta["sesion"]
    clave = _clave_segura(s.get("clave"))
    tipo = s.get("tipo") if s.get("tipo") in TIPOS_SESION else "reunion"
    n = max(1, int(s.get("parte") or 1))
    final = bool(s.get("final"))
    nombre = (str(s.get("nombre") or "").strip() or None)
    inicio_ms = int(meta.get("inicio_ms") or time.time() * 1000)
    dur = float(meta.get("duracion_s") or 0)
    lat, lon = ubicacion_de(meta)
    inicio_iso = datetime.fromtimestamp(inicio_ms / 1000, TZ).isoformat(timespec="seconds")

    with closing(db()) as con, con:
        fila = con.execute("SELECT * FROM sesiones WHERE clave=?", (clave,)).fetchone()
        if fila is None:
            con.execute("INSERT INTO sesiones(clave, tipo, nombre, inicio, lat, lon, actualizado) "
                        "VALUES(?,?,?,?,?,?,?)", (clave, tipo, nombre, inicio_iso, lat, lon, ahora().isoformat()))
            fila = con.execute("SELECT * FROM sesiones WHERE clave=?", (clave,)).fetchone()
        elif inicio_iso < fila["inicio"]:
            con.execute("UPDATE sesiones SET inicio=? WHERE id=?", (inicio_iso, fila["id"]))
        sid = fila["id"]
        if fila["estado"] == "lista":  # ya se cerró (parte repetida que llegó tarde)
            _limpiar(ruta, ruta.with_suffix(".json"))
            return fila["captura_id"]
        if nombre and not fila["nombre"]:
            con.execute("UPDATE sesiones SET nombre=? WHERE id=?", (nombre, sid))
        if lat is not None and fila["lat"] is None:
            con.execute("UPDATE sesiones SET lat=?, lon=? WHERE id=?", (lat, lon, sid))
        con.execute("INSERT OR IGNORE INTO sesion_partes(sesion_id, n, inicio_ms, duracion) VALUES(?,?,?,?)",
                    (sid, n, inicio_ms, dur))
        if final:
            fin = datetime.fromtimestamp(inicio_ms / 1000 + dur, TZ).isoformat(timespec="seconds")
            con.execute("UPDATE sesiones SET partes_total=?, fin=? WHERE id=?", (n, fin, sid))
        ya = con.execute("SELECT texto FROM sesion_partes WHERE sesion_id=? AND n=?", (sid, n)).fetchone()

    if ya["texto"] is None:
        t0 = time.time()
        texto = transcribir(ruta)
        log.info("%s %s · parte %d transcrita (%s de audio) en %.0f s",
                 TIPOS_SESION[tipo], clave, n, duracion(dur), time.time() - t0)
        with closing(db()) as con, con:
            con.execute("UPDATE sesion_partes SET texto=?, duracion=? WHERE sesion_id=? AND n=?",
                        (texto, dur, sid, n))
            con.execute("UPDATE sesiones SET actualizado=? WHERE id=?", (ahora().isoformat(), sid))

    _archivar_audio(ruta)
    return cerrar_sesion_si_esta_completa(sid)


def _archivar_audio(ruta: Path) -> None:
    meta_ruta = ruta.with_suffix(".json")
    if CFG["notas"].get("guardar_audio"):
        AUDIOS.mkdir(parents=True, exist_ok=True)
        ruta.replace(AUDIOS / ruta.name)
        _limpiar(meta_ruta)
    else:
        _limpiar(ruta, meta_ruta)


def cerrar_sesion_si_esta_completa(sid: int, forzar: bool = False) -> int | None:
    with closing(db()) as con:
        ses = con.execute("SELECT * FROM sesiones WHERE id=?", (sid,)).fetchone()
        listas = con.execute("SELECT count(*) FROM sesion_partes WHERE sesion_id=? AND texto IS NOT NULL",
                             (sid,)).fetchone()[0]
    if ses["estado"] == "lista":
        return ses["captura_id"]
    if not forzar and (not ses["partes_total"] or listas < ses["partes_total"]):
        return None  # todavía faltan partes por llegar o por transcribir
    return cerrar_sesion(sid)


def _bloques(texto: str, palabras: int = PALABRAS_POR_BLOQUE) -> list[str]:
    p = texto.split()
    return [" ".join(p[i:i + palabras]) for i in range(0, len(p), palabras)]


def extraer_largo(texto: str, inicio: datetime, lugar: str | None, tipo: str, nombre: str | None) -> dict:
    """Analiza una transcripción larga por bloques y une el resultado."""
    bloques = _bloques(texto)
    infos = []
    for i, b in enumerate(bloques, 1):
        contexto = f"Este es el bloque {i} de {len(bloques)} de la transcripción." if len(bloques) > 1 else None
        if nombre:
            contexto = f"Nombre de la {TIPOS_SESION[tipo].lower()}: {nombre}. " + (contexto or "")
        infos.append(extraer(b, inicio, lugar, tipo, contexto))
        log.info("%s · bloque %d de %d analizado", TIPOS_SESION[tipo], i, len(bloques))

    unido = {
        "titulo": infos[0]["titulo"], "resumen": infos[0]["resumen"],
        "tareas": [t for i in infos for t in i["tareas"]][:30],
        "datos": [d for i in infos for d in i["datos"]][:30],
        "ideas": [d for i in infos for d in i["ideas"]][:20],
        "importancia": max(i["importancia"] for i in infos),
    }
    if len(infos) > 1:
        resumenes = "\n".join(f"- {i['titulo']}: {i['resumen']}" for i in infos)
        try:
            r = _json_de(llamar_ia(
                "Escribe en español el título y el resumen de una grabación larga a partir de los resúmenes "
                "de sus partes. Responde solo JSON: {\"titulo\": \"máximo 8 palabras\", \"resumen\": "
                "\"3 a 5 oraciones con lo más importante\"}",
                f"Tipo: {TIPOS_SESION[tipo]}" + (f" ({nombre})" if nombre else "") + f"\nPartes:\n{resumenes}"))
            unido["titulo"] = str(r.get("titulo") or unido["titulo"]).strip()[:80]
            unido["resumen"] = str(r.get("resumen") or unido["resumen"]).strip()[:1200]
        except Exception as ex:
            log.warning("No pude escribir el resumen general (%s); uso el de la primera parte", ex)
    return pulir(unido, texto)


def cerrar_sesion(sid: int) -> int:
    with closing(db()) as con, con:
        ses = con.execute("SELECT * FROM sesiones WHERE id=?", (sid,)).fetchone()
        partes = con.execute("SELECT * FROM sesion_partes WHERE sesion_id=? ORDER BY n", (sid,)).fetchall()
        con.execute("UPDATE sesiones SET estado='procesando', actualizado=? WHERE id=?", (ahora().isoformat(), sid))
    tipo, nombre = ses["tipo"], ses["nombre"]
    etiqueta = TIPOS_SESION[tipo]
    inicio = datetime.fromisoformat(ses["inicio"])
    dur = sum(p["duracion"] or 0 for p in partes)
    texto = "\n\n".join(p["texto"] for p in partes if p["texto"])
    lugar = lugar_de(ses["lat"], ses["lon"])
    faltan = [str(p["n"]) for p in partes if p["texto"] is None]
    log.info("Analizando %s %s (%s, %d partes)…", etiqueta.lower(), ses["clave"], duracion(dur), len(partes))

    ia_ok = True
    if len(texto.split()) < 20:
        info = normalizar({"titulo": f"{etiqueta} sin voz clara",
                           "resumen": "Casi no se escuchó voz en esta grabación."})
    else:
        try:
            with despierta():
                info = extraer_largo(texto, inicio, lugar, tipo, nombre)
        except Exception as ex:
            log.warning("La IA falló con la %s (%s); guardo la transcripción completa", etiqueta.lower(), ex)
            info = normalizar({"titulo": f"{etiqueta} sin analizar",
                               "resumen": "No pude analizarla con IA; la transcripción completa está en tus notas."})
            ia_ok = False
    info["resumen"] = info["resumen"][:1200]
    if nombre and not info["titulo"].lower().startswith(nombre.lower()):
        info["titulo"] = f"{nombre}: {info['titulo']}"[:90]
    if faltan:
        info["resumen"] += f" (No llegaron todas las partes: faltó la {', '.join(faltan)}.)"

    clave_cfg = "guardar_transcripcion_clase" if tipo == "clase" else "guardar_transcripcion_reunion"
    guardar = bool(CFG["sesiones"].get(clave_cfg, True)) or not ia_ok
    cid = guardar_captura(archivo=f"sesion:{ses['clave']}", origen=tipo, inicio=inicio, dur=dur,
                          lat=ses["lat"], lon=ses["lon"], lugar=lugar, info=info,
                          transcripcion=texto if guardar else None, ia_ok=ia_ok)
    with closing(db()) as con, con:
        con.execute("UPDATE sesiones SET estado='lista', captura_id=?, actualizado=? WHERE id=?",
                    (cid, ahora().isoformat(), sid))
        if not guardar:  # sin transcripción guardada, los pedazos tampoco se quedan
            con.execute("UPDATE sesion_partes SET texto='' WHERE sesion_id=?", (sid,))
    if guardar:
        escribir_transcripcion(cid)
    actualizar_notas(inicio.date())
    enviar_o_guardar(mensaje_captura(cid))
    log.info("%s lista: %s", etiqueta, info["titulo"])
    return cid


def archivo_transcripcion(c) -> Path:
    ini = datetime.fromisoformat(c["inicio"])
    titulo = re.sub(r'[\\/:*?"<>|]', "", c["titulo"] or "")[:60].strip()
    return NOTAS / "transcripciones" / f"{ini:%Y-%m-%d %H%M} {titulo}.md"


def escribir_transcripcion(cid: int) -> None:
    with closing(db()) as con:
        c = con.execute("SELECT * FROM capturas WHERE id=?", (cid,)).fetchone()
    if not c or not c["transcripcion"]:
        return
    ruta = archivo_transcripcion(c)
    ruta.parent.mkdir(parents=True, exist_ok=True)
    ini = datetime.fromisoformat(c["inicio"])
    cuerpo = (f"# {c['titulo']}\n\n{fecha_larga(ini.date()).capitalize()}, {ini:%H:%M} · "
              f"{duracion(c['duracion'])}\n\n> {c['resumen']}\n\n---\n\n{c['transcripcion']}\n")
    ruta.write_text(cuerpo, encoding="utf-8")


def revisar_sesiones_abandonadas() -> None:
    """Si una sesión dejó de recibir partes hace 2 h (el teléfono se apagó, Eco se cerró), se cierra
    con lo que llegó para que no quede colgada."""
    limite = (ahora() - timedelta(hours=2)).isoformat()
    with closing(db()) as con:
        viejas = con.execute("SELECT id, clave FROM sesiones WHERE estado != 'lista' AND actualizado < ?",
                             (limite,)).fetchall()
    for v in viejas:
        if CLASE is not None and CLASE.clave_activa() == v["clave"]:
            continue
        with closing(db()) as con, con:  # para no volver a encolarla en cada vuelta del reloj
            con.execute("UPDATE sesiones SET actualizado=? WHERE id=?", (ahora().isoformat(), v["id"]))
        COLA.put(("cerrar_sesion", v["id"]))


def sesiones_en_curso() -> list[dict]:
    with closing(db()) as con:
        filas = con.execute(
            "SELECT s.*, (SELECT count(*) FROM sesion_partes p WHERE p.sesion_id=s.id) AS recibidas, "
            "(SELECT coalesce(sum(duracion),0) FROM sesion_partes p WHERE p.sesion_id=s.id) AS segundos "
            "FROM sesiones s WHERE s.estado != 'lista' ORDER BY s.inicio").fetchall()
    return [{"tipo": f["tipo"], "etiqueta": TIPOS_SESION.get(f["tipo"], "Sesión"), "nombre": f["nombre"],
             "estado": f["estado"], "partes": f["recibidas"], "segundos": f["segundos"], "inicio": f["inicio"],
             "terminada": f["partes_total"] is not None} for f in filas]


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
        if isinstance(ruta, tuple):  # orden interna: ("cerrar_sesion", id)
            try:
                ESTADO["procesando"] = "sesión"
                cerrar_sesion_si_esta_completa(ruta[1], forzar=True)
            except Exception:
                log.exception("No pude cerrar la sesión %s", ruta[1])
            finally:
                ESTADO["procesando"] = None
                COLA.task_done()
            continue
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
            revisar_manana()
            revisar_recordatorios()
            revisar_resumen()
            revisar_sesiones_abandonadas()
            revisar_respaldo()
            revisar_salud()
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


# ── Resumen de la mañana ──

_EXAMEN = re.compile(r"\b(examen(es)?|prueba|parcial|quiz|evaluacion|exposicion|presentacion|laboratorio|"
                     r"entrega|proyecto|test)\b")


def _linea_agenda(t, con_dia: bool) -> str:
    partes = []
    if con_dia and t["fecha"]:
        partes.append(fecha_corta(date.fromisoformat(t["fecha"])))
    if t["hora"]:
        partes.append(t["hora"])
    cuando = f"<i>{e(' · '.join(partes))}</i>  " if partes else ""
    enlace = f'  <a href="{e(t["enlace"])}">abrir</a>' if t["enlace"] else ""
    return f"• {cuando}{e(t['texto'])}{enlace}"


def agenda(d: date, manana: bool = True) -> str | None:
    """Lo que viene: vencidas, hoy, exámenes y entregas, y el resto de la semana."""
    hoy, fin = d.isoformat(), (d + timedelta(days=7)).isoformat()
    with closing(db()) as con:
        base = "SELECT * FROM items WHERE tipo='tarea' AND hecho=0 AND "
        orden = " ORDER BY fecha, hora IS NULL, hora, id"
        vencidas = con.execute(base + "fecha < ? AND fecha >= ?" + orden,
                               (hoy, (d - timedelta(days=21)).isoformat())).fetchall()
        de_hoy = con.execute(base + "fecha = ?" + orden, (hoy,)).fetchall()
        semana = con.execute(base + "fecha > ? AND fecha <= ?" + orden, (hoy, fin)).fetchall()
        sin_fecha = con.execute(base + "fecha IS NULL").fetchall()
    if not (vencidas or de_hoy or semana):
        return None
    importantes = [t for t in semana if _EXAMEN.search(_sin_acentos(t["texto"]))]
    ya = {t["id"] for t in importantes}
    resto = [t for t in semana if t["id"] not in ya]
    titulo = f"☀️ <b>Buenos días · {e(fecha_larga(d))}</b>" if manana else f"🗓️ <b>Lo que viene · {e(fecha_larga(d))}</b>"
    partes = [titulo]
    if de_hoy:
        partes += ["", "📌 <b>Para hoy</b>"] + [_linea_agenda(t, False) for t in de_hoy]
    if importantes:
        partes += ["", "📝 <b>Exámenes y entregas esta semana</b>"] + [_linea_agenda(t, True) for t in importantes]
    if resto:
        partes += ["", "🗓️ <b>El resto de la semana</b>"] + [_linea_agenda(t, True) for t in resto[:15]]
    if vencidas:
        partes += ["", "⚠️ <b>Se te pasaron</b>"] + [_linea_agenda(t, True) for t in vencidas[:8]]
        partes.append("<i>Si ya las hiciste: /hecho y el número (los ves en /pendientes).</i>")
    if sin_fecha:
        partes += ["", f"<i>Y {plural(len(sin_fecha), 'pendiente', 'pendientes')} sin fecha → /pendientes</i>"]
    return "\n".join(partes)


def plural(n: int, uno: str, varios: str) -> str:
    return f"{n} {uno if n == 1 else varios}"


def revisar_manana() -> None:
    hora = str(CFG["notas"].get("hora_manana") or "").strip()
    if not hora or hora.lower() in ("no", "off", "apagado"):
        return
    h, m = _hhmm(hora, (6, 30))
    actual = ahora()
    hoy = actual.date().isoformat()
    # Si la laptop estuvo dormida a esa hora, el resumen llega cuando despierte (hasta el mediodía).
    if (actual.hour, actual.minute) < (h, m) or actual.hour >= 12 or estado_get("ultimo_manana") == hoy:
        return
    texto = agenda(actual.date(), manana=True)
    if texto is None or enviar(texto) or not CFG["telegram"].get("chat_id"):
        estado_set("ultimo_manana", hoy)
        if texto:
            # Las tareas de hoy sin hora ya salieron en el resumen: no hace falta otro aviso a las 7.
            with closing(db()) as con, con:
                con.execute("UPDATE items SET recordado=1 WHERE tipo='tarea' AND hecho=0 AND fecha=? AND hora IS NULL",
                            (hoy,))


# ════════════════════════════ Pregúntale a Eco ════════════════════════════

PALABRAS_VACIAS = set("""a al algo alguien alguna algunas alguno algunos ante antes aqui asi aun cada como con contra
cual cuales cualquier cuando cuanto de del desde donde dos el ella ellas ello ellos en entre era eran es esa esas ese eso
esos esta estaba estan estar este esto estos fue fueron ha habia han has hasta hay la las le les lo los mas me mi mis
mucho muy nada ni no nos o otra otro para pero poco por porque pues que quien quienes se sea ser si sido sin sobre son
su sus tal tambien te tengo tiene tienen todo todos tu tus un una uno unos y ya yo dijo dijeron decir dime sabes
recuerdas acuerdas acuerdo hacer hago hice eco favor oye cosa cosas sobre algo dije dijiste""".split())

CAMPOS_INDICE = ("ficha", "transcripcion")


def indexar(cid: int) -> None:
    _vec_cola.put(cid)  # el "significado" se calcula aparte, sin frenar lo demás
    if not HAY_INDICE:
        return
    with closing(db()) as con, con:
        c = con.execute("SELECT * FROM capturas WHERE id=?", (cid,)).fetchone()
        if not c:
            return
        its = items_de(con, cid)
        con.execute("DELETE FROM indice WHERE captura_id=?", (cid,))
        ficha = "\n".join(x for x in [c["titulo"], c["resumen"], c["lugar"]] + [i["texto"] for i in its] if x)
        con.execute("INSERT INTO indice(texto, captura_id, campo) VALUES(?,?,?)", (ficha, cid, "ficha"))
        if c["transcripcion"]:
            for trozo in _bloques(c["transcripcion"], 160):
                con.execute("INSERT INTO indice(texto, captura_id, campo) VALUES(?,?,?)", (trozo, cid, "transcripcion"))


def reindexar_si_hace_falta() -> None:
    if not HAY_INDICE:
        return
    try:
        with closing(db()) as con:
            hay = con.execute("SELECT count(*) FROM indice").fetchone()[0]
            ids = [r[0] for r in con.execute("SELECT id FROM capturas").fetchall()]
        if not hay and ids:
            for cid in ids:
                indexar(cid)
            log.info("Índice de búsqueda listo (%d momentos)", len(ids))
    except Exception:
        log.exception("No pude armar el índice de búsqueda")


def _palabras_clave(pregunta: str) -> list[str]:
    palabras = re.findall(r"[a-z0-9]+", _sin_acentos(pregunta))
    return list(dict.fromkeys(w for w in palabras if len(w) > 2 and w not in PALABRAS_VACIAS))[:12]


def buscar_para_pregunta(pregunta: str, limite: int = 6) -> list[tuple[sqlite3.Row, str]]:
    """Junta dos búsquedas: por palabras (índice) y por significado (vectores). Lo que sale
    arriba en las dos gana (fusión por posición), con máximo 2 pedazos del mismo momento."""
    claves = _palabras_clave(pregunta)
    por_palabras: list[tuple[int, str]] = []
    with closing(db()) as con:
        if claves and HAY_INDICE:
            consulta = " OR ".join(f'"{w}"*' for w in claves)
            filas = con.execute("SELECT captura_id, texto FROM indice WHERE indice MATCH ? "
                                "ORDER BY bm25(indice) LIMIT 24", (consulta,)).fetchall()
            por_palabras = [(int(f["captura_id"]), f["texto"]) for f in filas]
        elif claves:
            condicion = " OR ".join(["titulo LIKE ? OR resumen LIKE ? OR transcripcion LIKE ?"] * len(claves))
            valores = [f"%{w}%" for w in claves for _ in range(3)]
            filas = con.execute(f"SELECT id AS captura_id, coalesce(resumen,'') AS texto FROM capturas "
                                f"WHERE {condicion} ORDER BY inicio DESC LIMIT 24", valores).fetchall()
            por_palabras = [(int(f["captura_id"]), f["texto"]) for f in filas]
    try:
        por_significado = [(cid, texto) for cid, texto, _ in buscar_semantico(pregunta, k=16)]
    except Exception as ex:  # sin Ollama o sin el modelo: queda la búsqueda por palabras
        log.debug("Búsqueda por significado no disponible (%s)", ex)
        por_significado = []

    puntaje: dict[tuple[int, str], float] = {}
    for lista in (por_palabras, por_significado):
        for puesto, (cid, texto) in enumerate(lista):
            clave = (cid, texto[:120])
            puntaje[clave] = puntaje.get(clave, 0.0) + 1.0 / (60 + puesto)
    textos = {(cid, t[:120]): t for lista in (por_palabras, por_significado) for cid, t in lista}

    elegidos: list[tuple[sqlite3.Row, str]] = []
    por_captura: dict[int, int] = {}
    with closing(db()) as con:
        for clave in sorted(puntaje, key=lambda k: -puntaje[k]):
            cid = clave[0]
            if por_captura.get(cid, 0) >= 2:
                continue
            c = con.execute("SELECT * FROM capturas WHERE id=?", (cid,)).fetchone()
            if not c:
                continue
            por_captura[cid] = por_captura.get(cid, 0) + 1
            elegidos.append((c, textos[clave]))
            if len(elegidos) >= limite:
                break
    return elegidos


# ── Búsqueda por significado (embeddings locales con Ollama) ──
# Cada momento se parte en pedazos; cada pedazo se vuelve una lista de números que representa
# su significado. Preguntar "¿qué dijo de energía?" encuentra también "trabajo y potencia".

_vec_cola: "queue.Queue[int]" = queue.Queue()
_vec_version = [0]
_vec_cache: dict = {"version": -1, "ids": [], "textos": [], "m": None, "modelo": None}
SEMANTICA: dict = {"error": None, "vectores": None}


def _modelo_embed() -> str:
    return str(CFG["ia"].get("ollama_embed") or "").strip()


def embeber(textos: list[str], consulta: bool = False) -> list[list[float]]:
    modelo = _modelo_embed()
    if not modelo:
        raise RuntimeError("búsqueda por significado apagada")
    if "gemma" in modelo:
        prefijo = "task: search result | query: " if consulta else "title: none | text: "
    elif "nomic" in modelo:
        prefijo = "search_query: " if consulta else "search_document: "
    else:
        prefijo = ""
    pedido = {"model": modelo, "input": [prefijo + t for t in textos], "keep_alive": "10m"}
    req = urllib.request.Request(CFG["ia"]["ollama_url"].rstrip("/") + "/api/embed", data=json.dumps(pedido).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return json.load(r)["embeddings"]
    except urllib.error.HTTPError as ex:
        detalle = ex.read().decode("utf-8", "replace")
        if ex.code == 404 or "not found" in detalle:
            raise ModeloFaltante(modelo) from ex
        raise


def _pedazos(c, its) -> list[str]:
    ficha = "\n".join(x for x in [c["titulo"], c["resumen"], c["lugar"]] + [i["texto"] for i in its] if x)
    pedazos = [ficha[:2500]]
    if c["transcripcion"]:
        pedazos += _bloques(c["transcripcion"], 120)[:300]
    return [p for p in pedazos if p.strip()]


def vectorizar(cid: int) -> int:
    import numpy as np
    with closing(db()) as con:
        c = con.execute("SELECT * FROM capturas WHERE id=?", (cid,)).fetchone()
        its = items_de(con, cid) if c else []
    if not c:
        with closing(db()) as con, con:
            con.execute("DELETE FROM vectores WHERE captura_id=?", (cid,))
        _vec_version[0] += 1
        return 0
    pedazos = _pedazos(c, its)
    vectores: list[list[float]] = []
    for i in range(0, len(pedazos), 16):
        vectores += embeber(pedazos[i:i + 16])
    filas = []
    for n, (texto, v) in enumerate(zip(pedazos, vectores)):
        a = np.asarray(v, dtype=np.float32)
        a /= (np.linalg.norm(a) or 1.0)
        filas.append((cid, n, texto, a.tobytes(), _modelo_embed()))
    with closing(db()) as con, con:
        con.execute("DELETE FROM vectores WHERE captura_id=?", (cid,))
        con.executemany("INSERT INTO vectores(captura_id, n, texto, vector, modelo) VALUES(?,?,?,?,?)", filas)
    _vec_version[0] += 1
    return len(filas)


def _faltan_vectores() -> list[int]:
    with closing(db()) as con:
        return [r[0] for r in con.execute(
            "SELECT id FROM capturas WHERE id NOT IN (SELECT DISTINCT captura_id FROM vectores WHERE modelo=?) "
            "ORDER BY inicio DESC", (_modelo_embed(),)).fetchall()]


def vectores_hilo() -> None:
    time.sleep(30)
    for cid in _faltan_vectores():  # lo que se guardó antes de tener esto
        _vec_cola.put(cid)
    while True:
        try:
            cid = _vec_cola.get(timeout=3600)
        except queue.Empty:
            for cid in _faltan_vectores()[:200]:
                _vec_cola.put(cid)
            continue
        if not _modelo_embed():
            continue
        try:
            vectorizar(cid)
            SEMANTICA["error"] = None
        except ModeloFaltante as ex:
            SEMANTICA["error"] = f"falta el modelo {ex.args[0]}"
            _vaciar(_vec_cola)  # se reintenta en una hora, no uno por uno
        except Exception as ex:
            if SEMANTICA["error"] is None:
                log.info("Todavía no puedo calcular el significado de los momentos (%s)", ex)
            SEMANTICA["error"] = "Ollama no responde"
            _vaciar(_vec_cola)


def _vaciar(cola: queue.Queue) -> None:
    try:
        while True:
            cola.get_nowait()
    except queue.Empty:
        pass


def _matriz_vectores():
    import numpy as np
    modelo = _modelo_embed()
    if _vec_cache["version"] != _vec_version[0] or _vec_cache["modelo"] != modelo:
        with closing(db()) as con:
            filas = con.execute("SELECT captura_id, texto, vector FROM vectores WHERE modelo=?", (modelo,)).fetchall()
        vecs = [np.frombuffer(f["vector"], dtype=np.float32) for f in filas]
        dim = max((len(v) for v in vecs), default=0)
        buenos = [(f, v) for f, v in zip(filas, vecs) if len(v) == dim and dim]
        _vec_cache.update(version=_vec_version[0], modelo=modelo,
                          ids=[int(f["captura_id"]) for f, _ in buenos], textos=[f["texto"] for f, _ in buenos],
                          m=np.vstack([v for _, v in buenos]) if buenos else None)
    return _vec_cache


def buscar_semantico(pregunta: str, k: int = 12, minimo: float = 0.3) -> list[tuple[int, str, float]]:
    import numpy as np
    cache = _matriz_vectores()
    if cache["m"] is None:
        return []
    q = np.asarray(embeber([pregunta], consulta=True)[0], dtype=np.float32)
    q /= (np.linalg.norm(q) or 1.0)
    if q.shape[0] != cache["m"].shape[1]:
        return []
    puntajes = cache["m"] @ q
    orden = np.argsort(-puntajes)[:k]
    return [(cache["ids"][i], cache["textos"][i], float(puntajes[i])) for i in orden if puntajes[i] >= minimo]


def estado_semantica() -> dict:
    with closing(db()) as con:
        total = con.execute("SELECT count(*) FROM capturas").fetchone()[0]
        con_vec = con.execute("SELECT count(DISTINCT captura_id) FROM vectores WHERE modelo=?",
                              (_modelo_embed(),)).fetchone()[0]
    return {"modelo": _modelo_embed() or None, "listos": con_vec, "total": total, "error": SEMANTICA["error"]}


def responder_pregunta(pregunta: str) -> dict:
    nombre = CFG["usuario"]["nombre"]
    hallados = buscar_para_pregunta(pregunta)
    notas = []
    for i, (c, texto) in enumerate(hallados, 1):
        ini = datetime.fromisoformat(c["inicio"])
        tipo = TIPOS_SESION.get(c["origen"], "Momento")
        notas.append(f"[{i}] {tipo} del {fecha_larga(ini.date())}, {ini:%H:%M} — «{c['titulo']}»\n{texto[:1400]}")
    tareas = pendientes(15)
    lista_tareas = "\n".join(f"- {t['texto']}" + (f" ({_cuando_tarea(t)})" if t["fecha"] else "") for t in tareas)
    sistema = f"""Eres Eco, la memoria personal de {nombre}. Respondes sus preguntas sobre lo que Eco guardó:
momentos del día, clases, reuniones y tareas.
Reglas:
- Usa SOLO la información de las notas y tareas que te doy. Si la respuesta no está ahí, dilo claro
  ("No encontré eso en lo que guardé") y sugiere qué palabra buscar.
- Responde en español, directo y corto: máximo 6 oraciones o una lista corta.
- Cuando uses una nota, di de cuándo es (por ejemplo: "en la clase del jueves 15 a las 9:00").
- No inventes fechas, nombres ni datos. Las transcripciones automáticas pueden tener errores."""
    actual = ahora()
    usuario = (f"Hoy es {fecha_larga(actual.date())}, {actual:%H:%M}.\n\nPregunta de {nombre}: {pregunta}\n\n"
               f"Notas encontradas:\n" + ("\n\n".join(notas) if notas else "(ninguna)") +
               f"\n\nTareas pendientes ahora:\n{lista_tareas or '(ninguna)'}")
    respuesta = llamar_ia(sistema, usuario, json_mode=False).strip()
    vistos, fuentes = set(), []
    for c, _ in hallados:
        if c["id"] in vistos:
            continue
        vistos.add(c["id"])
        ini = datetime.fromisoformat(c["inicio"])
        fuentes.append({"id": c["id"], "fecha": ini.date().isoformat(), "hora": f"{ini:%H:%M}",
                        "titulo": c["titulo"], "cuando": f"{fecha_corta(ini.date())} {ini:%H:%M}"})
    return {"respuesta": respuesta, "fuentes": fuentes[:5]}


def es_pregunta(texto: str) -> bool:
    t = texto.strip()
    return t.endswith("?") or t.startswith("¿")


# ════════════════════════════ Respaldo diario ════════════════════════════

def carpeta_respaldo() -> Path | None:
    elegida = str(CFG["respaldo"].get("carpeta") or "").strip()
    if elegida:
        return Path(elegida).expanduser()
    for variable in ("OneDriveConsumer", "OneDrive"):
        raiz = os.environ.get(variable)
        if raiz and Path(raiz).exists():
            return Path(raiz) / "Eco respaldos"
    casa = Path.home() / "OneDrive"
    return casa / "Eco respaldos" if casa.exists() else None


def hacer_respaldo() -> Path:
    import zipfile
    destino = carpeta_respaldo()
    if destino is None:
        raise RuntimeError("OneDrive no está configurado en esta laptop")
    destino.mkdir(parents=True, exist_ok=True)
    copia_db = DATOS / "respaldo_tmp.db"
    _limpiar(copia_db)
    with closing(db()) as origen, closing(sqlite3.connect(copia_db)) as copia:
        origen.backup(copia)  # copia consistente aunque Eco esté escribiendo
    nombre = f"eco_{ahora():%Y-%m-%d_%H%M}.zip"
    temporal = destino / (nombre + ".part")
    try:
        with zipfile.ZipFile(temporal, "w", zipfile.ZIP_DEFLATED) as z:
            z.write(copia_db, "datos/eco.db")
            for f in NOTAS.rglob("*"):
                if f.is_file():
                    z.write(f, Path("notas") / f.relative_to(NOTAS))
        temporal.replace(destino / nombre)
    finally:
        _limpiar(copia_db, temporal)
    conservar = max(1, int(CFG["respaldo"].get("conservar") or 14))
    for viejo in sorted(destino.glob("eco_*.zip"))[:-conservar]:
        _limpiar(viejo)
    estado_set("ultimo_respaldo", ahora().isoformat(timespec="seconds"))
    estado_set("respaldo_error", "")
    log.info("Respaldo guardado en %s", destino / nombre)
    return destino / nombre


def revisar_respaldo() -> None:
    if not CFG["respaldo"].get("activo", True):
        return
    ultimo = estado_get("ultimo_respaldo")
    if ultimo and ahora() - datetime.fromisoformat(ultimo) < timedelta(hours=20):
        return
    intento = estado_get("respaldo_intento")
    if intento and ahora() - datetime.fromisoformat(intento) < timedelta(hours=1):
        return  # si falló, reintenta cada hora y no en cada vuelta del reloj
    estado_set("respaldo_intento", ahora().isoformat(timespec="seconds"))
    try:
        hacer_respaldo()
    except Exception as ex:
        estado_set("respaldo_error", str(ex))
        log.warning("No pude hacer el respaldo: %s", ex)


def estado_respaldo() -> dict:
    destino = carpeta_respaldo()
    return {"activo": bool(CFG["respaldo"].get("activo", True)), "ultimo": estado_get("ultimo_respaldo"),
            "carpeta": str(destino) if destino else None, "error": estado_get("respaldo_error") or None}


# ════════════════════════════ Google: Classroom y Calendar ════════════════════════════
# Eco se conecta con tu cuenta (la del colegio) usando una llave que creas una vez en Google Cloud.
# Permisos que pide, y nada más:
#   • ver tus clases y tus tareas de Classroom (solo leer),
#   • crear su propio calendario "Eco" y manejar SOLO los eventos de ese calendario.

GOOGLE_ALCANCES = " ".join((
    "openid", "email",
    "https://www.googleapis.com/auth/classroom.courses.readonly",
    "https://www.googleapis.com/auth/classroom.coursework.me.readonly",
    "https://www.googleapis.com/auth/classroom.announcements.readonly",
    "https://www.googleapis.com/auth/calendar.app.created",
))
GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
GOOGLE_TOKEN = "https://oauth2.googleapis.com/token"
CLASSROOM = "https://classroom.googleapis.com/v1"
CALENDAR = "https://www.googleapis.com/calendar/v3"

_google_evento = threading.Event()
_google_lock = threading.Lock()
_google_pendientes: dict[str, tuple[str, float]] = {}  # state → (verificador PKCE, cuándo)
_google_forzar = {"classroom": False}


class GoogleError(Exception):
    def __init__(self, codigo: int, razon: str, mensaje: str):
        super().__init__(f"{codigo} {razon}: {mensaje}")
        self.codigo, self.razon, self.mensaje = codigo, razon or "", mensaje or ""


def avisar_google(classroom: bool = False) -> None:
    """Despierta la sincronización (se llama después de cada cambio en tareas)."""
    if classroom:
        _google_forzar["classroom"] = True
    _google_evento.set()


def _b64url(datos: bytes) -> str:
    return base64.urlsafe_b64encode(datos).rstrip(b"=").decode()


def google_cliente() -> dict | None:
    for ruta in (DATOS / "google_cliente.json", BASE / "google_cliente.json"):
        if ruta.exists():
            try:
                d = json.loads(ruta.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                return None
            c = d.get("installed") or d.get("web") or d
            if c.get("client_id") and c.get("client_secret"):
                return {"id": c["client_id"], "secreto": c["client_secret"]}
    return None


def guardar_google_cliente(texto: str) -> None:
    """El archivo .json que bajas de Google Cloud (Clientes → Descargar JSON)."""
    try:
        d = json.loads(texto)
    except ValueError:
        raise ValueError("Ese archivo no es el JSON de Google Cloud.") from None
    c = d.get("installed") or d.get("web") or {}
    if not str(c.get("client_id", "")).endswith(".apps.googleusercontent.com") or not c.get("client_secret"):
        raise ValueError("Ese JSON no es un cliente OAuth. En Google Cloud crea uno de tipo «App de escritorio».")
    if "web" in d and "installed" not in d:
        raise ValueError("Ese cliente es de tipo «Aplicación web». Crea uno de tipo «App de escritorio».")
    (DATOS / "google_cliente.json").write_text(json.dumps(d), encoding="utf-8")
    estado_set("google_error", "")


def _token_google_ruta() -> Path:
    return DATOS / "google_token.json"


def google_token_guardado() -> dict | None:
    try:
        return json.loads(_token_google_ruta().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def _guardar_token_google(t: dict) -> None:
    ruta = _token_google_ruta()
    tmp = ruta.with_suffix(".tmp")
    tmp.write_text(json.dumps(t), encoding="utf-8")
    tmp.replace(ruta)


def google_redirect() -> str:
    # Google deja usar cualquier puerto de 127.0.0.1 con clientes de escritorio.
    return f"http://127.0.0.1:{int(CFG['servidor']['puerto'])}/"


def google_url_conectar() -> str:
    cliente = google_cliente()
    if not cliente:
        raise ValueError("Falta la llave de Google Cloud.")
    verificador = _b64url(os.urandom(48))
    estado = _b64url(os.urandom(24))
    limite = time.time() - 900
    for k in [k for k, (_, t) in _google_pendientes.items() if t < limite]:
        _google_pendientes.pop(k, None)
    _google_pendientes[estado] = (verificador, time.time())
    params = {
        "client_id": cliente["id"], "redirect_uri": google_redirect(), "response_type": "code",
        "scope": GOOGLE_ALCANCES, "state": estado, "access_type": "offline", "prompt": "consent",
        "code_challenge": _b64url(hashlib.sha256(verificador.encode()).digest()), "code_challenge_method": "S256",
    }
    return GOOGLE_AUTH + "?" + urllib.parse.urlencode(params)


def _post_formulario(url: str, campos: dict) -> dict:
    req = urllib.request.Request(url, data=urllib.parse.urlencode(campos).encode(),
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.load(r)
    except urllib.error.HTTPError as ex:
        try:
            err = json.load(ex)
        except ValueError:
            err = {}
        raise GoogleError(ex.code, str(err.get("error", "")), str(err.get("error_description", ""))) from None


def _email_de(id_token: str | None) -> str | None:
    try:
        cuerpo = id_token.split(".")[1]
        return json.loads(base64.urlsafe_b64decode(cuerpo + "=" * (-len(cuerpo) % 4))).get("email")
    except Exception:
        return None


ERRORES_OAUTH = {
    "access_denied": "Cancelaste el permiso. Cuando quieras, vuelve a tocar «Conectar con Google».",
    "admin_policy_enforced": "Tu colegio no permite que esta cuenta use apps externas como Eco (lo decide el "
                             "administrador del colegio). Puedes conectar tu cuenta personal de Gmail para tener "
                             "Calendar, o pedirle al colegio que permita la app.",
    "org_internal": "Ese proyecto de Google Cloud solo acepta cuentas de su organización. En «Público» "
                    "(Audience) elige «Externo».",
}


def google_callback(params: dict) -> tuple[bool, str]:
    pendiente = _google_pendientes.pop(str(params.get("state") or ""), None)
    if not pendiente:
        return False, "Ese enlace ya venció. Vuelve a tocar «Conectar con Google» en el panel."
    if params.get("error"):
        return False, ERRORES_OAUTH.get(params["error"], f"Google no dio el permiso ({params['error']}).")
    cliente = google_cliente()
    if not cliente or not params.get("code"):
        return False, "Falta la llave de Google Cloud."
    try:
        r = _post_formulario(GOOGLE_TOKEN, {
            "code": params["code"], "client_id": cliente["id"], "client_secret": cliente["secreto"],
            "redirect_uri": google_redirect(), "grant_type": "authorization_code", "code_verifier": pendiente[0],
        })
    except GoogleError as ex:
        return False, f"Google no aceptó la conexión ({ex.razon or ex.codigo}). Inténtalo otra vez."
    except OSError:
        return False, "No hay internet ahora mismo. Inténtalo otra vez en un rato."
    if not r.get("refresh_token"):
        return False, "Google no dio un permiso permanente. Vuelve a intentarlo."
    email = _email_de(r.get("id_token"))
    _guardar_token_google({
        "refresh_token": r["refresh_token"], "access_token": r.get("access_token"),
        "expira": time.time() + int(r.get("expires_in") or 3600), "email": email,
        "alcances": r.get("scope", ""), "conectado": ahora().isoformat(timespec="seconds"),
    })
    for k in ("google_error", "classroom_error", "calendar_error", "anuncios_error"):
        estado_set(k, "")
    estado_set("google_primera", "1")
    log.info("Google conectado (%s)", email)
    avisar_google(classroom=True)
    return True, f"Listo: Eco quedó conectado con {email or 'tu cuenta de Google'}."


def google_desconectar() -> None:
    t = google_token_guardado()
    if t:
        try:
            _post_formulario("https://oauth2.googleapis.com/revoke", {"token": t["refresh_token"]})
        except Exception:
            pass  # si ya no servía, igual lo borramos
    _limpiar(_token_google_ruta())
    log.info("Google desconectado")


def google_access_token() -> str:
    with _google_lock:
        t = google_token_guardado()
        if not t:
            raise GoogleError(401, "sin_conexion", "Eco no está conectado con Google")
        if t.get("access_token") and float(t.get("expira") or 0) - 60 > time.time():
            return t["access_token"]
        cliente = google_cliente()
        if not cliente:
            raise GoogleError(401, "sin_cliente", "Falta la llave de Google Cloud")
        try:
            r = _post_formulario(GOOGLE_TOKEN, {"client_id": cliente["id"], "client_secret": cliente["secreto"],
                                                "refresh_token": t["refresh_token"], "grant_type": "refresh_token"})
        except GoogleError as ex:
            if ex.razon in ("invalid_grant", "unauthorized_client", "invalid_client"):
                _limpiar(_token_google_ruta())
                estado_set("google_error", "Google cerró la conexión (cambiaste la contraseña, quitaste el permiso o "
                                           "venció). Vuelve a tocar «Conectar con Google».")
            raise
        t["access_token"] = r["access_token"]
        t["expira"] = time.time() + int(r.get("expires_in") or 3600)
        _guardar_token_google(t)
        return t["access_token"]


def google_api(metodo: str, url: str, cuerpo: dict | None = None, params: dict | None = None) -> dict:
    if params:
        url += "?" + urllib.parse.urlencode(params, doseq=True)
    req = urllib.request.Request(url, method=metodo, data=json.dumps(cuerpo).encode() if cuerpo is not None else None,
                                 headers={"Authorization": f"Bearer {google_access_token()}",
                                          "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            crudo = r.read()
            return json.loads(crudo) if crudo else {}
    except urllib.error.HTTPError as ex:
        try:
            err = json.load(ex).get("error") or {}
        except (ValueError, AttributeError):
            err = {}
        if not isinstance(err, dict):
            err = {"message": str(err)}
        errores = err.get("errors") or [{}]
        razon = errores[0].get("reason") or err.get("status") or ""
        raise GoogleError(ex.code, str(razon), str(err.get("message") or ex.reason)) from None


def _paginas(url: str, clave: str, params: dict, max_paginas: int = 5):
    params = dict(params)
    for _ in range(max_paginas):
        r = google_api("GET", url, params=params)
        yield from r.get(clave) or []
        if not r.get("nextPageToken"):
            return
        params["pageToken"] = r["nextPageToken"]


def _error_google(ex: GoogleError, que: str) -> str:
    m, r = ex.mensaje.lower(), ex.razon.lower()
    if ex.codigo == 403 and ("has not been used" in m or "is disabled" in m or "accessnotconfigured" in r
                             or "service_disabled" in r):
        api = "Google Classroom API" if que == "classroom" else "Google Calendar API"
        return f"Falta activar «{api}» en tu proyecto de Google Cloud (README, sección 10, paso 2)."
    if que == "classroom" and ex.codigo == 403:
        return ("Tu colegio no deja que apps externas lean Classroom (lo decide el administrador). "
                "Lo demás de Eco sigue funcionando.")
    if ex.codigo == 401:
        return "Google pidió volver a iniciar sesión. Toca «Conectar con Google» otra vez."
    if ex.codigo == 403 and que == "calendar":
        return "Google no dio permiso para el calendario. Vuelve a conectar y deja marcada la casilla de Calendar."
    return f"Google respondió: {ex.mensaje[:160] or ex.codigo}"


# ── Classroom → tareas de Eco ──

def _vence_classroom(t: dict) -> tuple[str | None, str | None]:
    d = t.get("dueDate") or {}
    if not d.get("year"):
        return None, None
    hm = t.get("dueTime")
    if not hm:
        return date(d["year"], d["month"], d["day"]).isoformat(), None
    # Classroom guarda la hora en UTC.
    local = datetime(d["year"], d["month"], d["day"], int(hm.get("hours", 0)), int(hm.get("minutes", 0)),
                     tzinfo=timezone.utc).astimezone(TZ)
    if (local.hour, local.minute) == (23, 59):  # "para el día", sin hora
        return local.date().isoformat(), None
    return local.date().isoformat(), f"{local:%H:%M}"


def sincronizar_classroom() -> dict:
    cursos = list(_paginas(f"{CLASSROOM}/courses", "courses",
                           {"studentId": "me", "courseStates": "ACTIVE", "pageSize": 50}))
    hoy = ahora().date()
    viejo, muy_viejo = (hoy - timedelta(days=14)).isoformat(), (hoy - timedelta(days=21)).isoformat()
    nuevas: list[dict] = []
    cambiadas = 0
    for curso in cursos:
        clase = (curso.get("name") or "Clase").strip()
        try:
            trabajos = list(_paginas(f"{CLASSROOM}/courses/{curso['id']}/courseWork", "courseWork",
                                     {"courseWorkStates": "PUBLISHED", "orderBy": "updateTime desc", "pageSize": 50},
                                     max_paginas=2))
            entregas = list(_paginas(f"{CLASSROOM}/courses/{curso['id']}/courseWork/-/studentSubmissions",
                                     "studentSubmissions", {"userId": "me", "pageSize": 100}, max_paginas=3))
        except GoogleError as ex:
            if ex.codigo in (403, 404) and cursos:
                log.info("Classroom: no pude leer la clase %s (%s)", clase, ex.codigo)
                continue
            raise
        estados = {s.get("courseWorkId"): s.get("state") for s in entregas}
        for t in trabajos:
            fecha, hora = _vence_classroom(t)
            creado = str(t.get("creationTime") or "")[:10]
            if (fecha and fecha < viejo) or (not fecha and creado and creado < muy_viejo):
                continue  # cosas viejas: no llenamos tus pendientes con el pasado
            entregada = estados.get(t.get("id")) in ("TURNED_IN", "RETURNED")
            texto = f"{clase}: {(t.get('title') or 'Tarea').strip()}"[:300]
            externo = f"classroom:{t['id']}"
            with closing(db()) as con, con:
                previo = con.execute("SELECT * FROM items WHERE externo=?", (externo,)).fetchone()
                if previo is None:
                    if entregada:
                        continue
                    f, h, ts = calcular_recordatorio(fecha, hora, ahora())
                    con.execute("INSERT INTO items(captura_id, tipo, texto, fecha, hora, recordar_ts, origen, externo, "
                                "enlace, detalle) VALUES(NULL, 'tarea', ?, ?, ?, ?, 'classroom', ?, ?, ?)",
                                (texto, f, h, ts, externo, t.get("alternateLink"), clase))
                    nuevas.append({"texto": texto, "fecha": f, "hora": h, "enlace": t.get("alternateLink")})
                    continue
                cambio = False
                if (fecha, hora) != (previo["fecha"], previo["hora"]):  # el profe movió la fecha
                    f, h, ts = calcular_recordatorio(fecha, hora, ahora())
                    con.execute("UPDATE items SET fecha=?, hora=?, recordar_ts=?, recordado=0 WHERE id=?",
                                (f, h, ts, previo["id"]))
                    cambio = True
                if texto != previo["texto"]:
                    con.execute("UPDATE items SET texto=? WHERE id=?", (texto, previo["id"]))
                    cambio = True
                if entregada and not previo["hecho"]:
                    con.execute("UPDATE items SET hecho=1 WHERE id=?", (previo["id"],))
                    cambio = True
                cambiadas += cambio

    with closing(db()) as con:
        pendientes_cr = con.execute("SELECT count(*) FROM items WHERE origen='classroom' AND hecho=0").fetchone()[0]
    if estado_get("google_primera") == "1":
        estado_set("google_primera", "")
        enviar_o_guardar(f"📚 <b>Classroom conectado</b>\n{plural(len(cursos), 'clase', 'clases')} · "
                         f"{plural(pendientes_cr, 'tarea pendiente', 'tareas pendientes')}.\n"
                         "Te aviso cada vez que dejen algo nuevo.")
    elif nuevas:
        lineas = ["📚 <b>" + ("Nueva tarea" if len(nuevas) == 1 else "Nuevas tareas") + " en Classroom</b>"]
        for n in nuevas[:10]:
            cuando = _cuando_tarea(n)
            lineas.append(f"• {e(n['texto'])}" + (f" — <i>{e(cuando)}</i>" if cuando else "")
                          + (f'  <a href="{e(n["enlace"])}">abrir</a>' if n["enlace"] else ""))
        enviar_o_guardar("\n".join(lineas))
    if nuevas or cambiadas:
        actualizar_notas(None)
    estado_set("classroom_ultimo", ahora().isoformat(timespec="seconds"))
    estado_set("classroom_cursos", len(cursos))
    estado_set("classroom_error", "")
    _cursos_cache[:] = [(c["id"], (c.get("name") or "Clase").strip()) for c in cursos]
    log.info("Classroom: %d clases, %d tareas nuevas, %d cambios", len(cursos), len(nuevas), cambiadas)
    return {"cursos": len(cursos), "nuevas": len(nuevas), "cambios": cambiadas}


# ── Anuncios de Classroom → fechas y tareas ──
# Muchos profes avisan exámenes y cambios en un anuncio, no como tarea. Eco lee los anuncios
# de la última semana, descarta los que no traen nada (saludos, recordatorios sin fecha) y saca
# lo importante con la misma IA de los momentos.

_cursos_cache: list[tuple[str, str]] = []
POR_VUELTA_ANUNCIOS = 12
_UTIL = re.compile(
    r"examen|prueba|parcial|quiz|entrega|entreg|tarea|fecha|plazo|lunes|martes|miercoles|jueves|viernes|sabado|"
    r"domingo|manana|proxim|semana|recuerd|traer|llevar|evaluaci|exposici|laboratorio|proyecto|guia|aula|horario|"
    r"suspend|cancel|cambio|\b\d{1,2}\s*/\s*\d{1,2}\b|\b\d{1,2} de (enero|febrero|marzo|abril|mayo|junio|julio|"
    r"agosto|septiembre|octubre|noviembre|diciembre)|"
    # La clase de inglés anuncia en inglés
    r"\bexam|\btest\b|homework|assignment|\bdue\b|deadline|monday|tuesday|wednesday|thursday|friday|saturday|"
    r"sunday|tomorrow|next week|\bbring\b|presentation|project|essay|reminder")


def _parece_util(texto: str) -> bool:
    return bool(_UTIL.search(_sin_acentos(texto or "")))


def _rfc3339(texto: str) -> datetime:
    t = str(texto or "").replace("Z", "+00:00")
    t = re.sub(r"\.(\d{6})\d+", r".\1", t)  # Classroom manda hasta 9 decimales
    return datetime.fromisoformat(t).astimezone(TZ)


def _repetida_de_classroom(curso: str, texto: str) -> bool:
    """¿Esta tarea ya está como tarea de Classroom de la misma clase? (evita duplicados)."""
    nuevas = set(_clave(texto).split()) - PALABRAS_VACIAS
    if len(nuevas) < 2:
        return False
    with closing(db()) as con:
        filas = con.execute("SELECT texto FROM items WHERE origen='classroom' AND detalle=? AND hecho=0",
                            (curso,)).fetchall()
    for f in filas:
        previas = set(_clave(f["texto"]).split()) - PALABRAS_VACIAS
        if previas and len(nuevas & previas) / min(len(nuevas), len(previas)) >= 0.6:
            return True
    return False


def sincronizar_anuncios() -> dict:
    desde = ahora() - timedelta(days=7)
    for curso_id, curso in _cursos_cache:
        try:
            r = google_api("GET", f"{CLASSROOM}/courses/{curso_id}/announcements",
                           params={"announcementStates": "PUBLISHED", "orderBy": "updateTime desc", "pageSize": 15})
        except GoogleError as ex:
            if ex.codigo in (403, 404):
                continue
            raise
        with closing(db()) as con, con:
            for a in r.get("announcements") or []:
                try:
                    creado = _rfc3339(a.get("creationTime"))
                except ValueError:
                    continue
                if creado < desde:
                    continue
                con.execute("INSERT OR IGNORE INTO classroom_anuncios(id, curso, texto, creado, enlace) VALUES(?,?,?,?,?)",
                            (a["id"], curso, (a.get("text") or "").strip()[:6000], creado.isoformat(timespec="seconds"),
                             a.get("alternateLink")))
    with closing(db()) as con:
        nuevos = con.execute("SELECT * FROM classroom_anuncios WHERE estado='nuevo' ORDER BY creado").fetchall()
    primera = estado_get("anuncios_listos") != "1"
    guardados = []
    for a in nuevos[:POR_VUELTA_ANUNCIOS]:
        estado, cid = "ignorado", None
        if _parece_util(a["texto"]):
            creado = datetime.fromisoformat(a["creado"])
            info = extraer(a["texto"], creado, None, "anuncio", contexto=f"Clase: {a['curso']}.")
            info["tareas"] = [t for t in info["tareas"] if not _repetida_de_classroom(a["curso"], t["texto"])]
            if info["tareas"] or info["datos"]:
                if not _sin_acentos(info["titulo"]).startswith(_sin_acentos(a["curso"])):
                    info["titulo"] = f"{a['curso']}: {info['titulo']}"[:90]
                cid = guardar_captura(archivo=f"anuncio:{a['id']}", origen="anuncio", inicio=creado, dur=None,
                                      lat=None, lon=None, lugar=None, info=info, transcripcion=a["texto"], ia_ok=True)
                actualizar_notas(creado.date())
                guardados.append(cid)
                estado = "guardado"
                if not primera:
                    enviar_o_guardar("📣 " + mensaje_captura(cid)
                                     + (f'\n<a href="{e(a["enlace"])}">Ver el anuncio</a>' if a["enlace"] else ""))
            else:
                estado = "sin_nada"
        with closing(db()) as con, con:
            con.execute("UPDATE classroom_anuncios SET estado=?, captura_id=? WHERE id=?", (estado, cid, a["id"]))
    quedan = max(0, len(nuevos) - POR_VUELTA_ANUNCIOS)
    if primera and not quedan:
        estado_set("anuncios_listos", "1")
        with closing(db()) as con:
            total = con.execute("SELECT count(*) FROM classroom_anuncios WHERE estado='guardado'").fetchone()[0]
        if total:
            enviar_o_guardar(f"📣 <b>Revisé los anuncios de Classroom de la última semana</b>\n"
                             f"{plural(total, 'anuncio traía', 'anuncios traían')} fechas o tareas: ya están en tu panel "
                             "y en tus pendientes. Desde ahora te aviso de cada anuncio nuevo que importe.")
    estado_set("anuncios_ultimo", ahora().isoformat(timespec="seconds"))
    estado_set("anuncios_error", "")
    if nuevos:
        log.info("Anuncios de Classroom: %d revisados, %d con fechas o tareas, %d en espera",
                 min(len(nuevos), POR_VUELTA_ANUNCIOS), len(guardados), quedan)
    return {"revisados": min(len(nuevos), POR_VUELTA_ANUNCIOS), "guardados": len(guardados), "quedan": quedan}


# ── Tareas de Eco → calendario "Eco" ──

def _q(texto: str) -> str:
    return urllib.parse.quote(texto, safe="")


def calendario_eco() -> str:
    cal = estado_get("google_calendario")
    if cal:
        try:
            google_api("GET", f"{CALENDAR}/calendars/{_q(cal)}")
            return cal
        except GoogleError as ex:
            if ex.codigo not in (404, 410):
                raise
            log.info("El calendario «Eco» ya no existe; lo creo de nuevo")
            with closing(db()) as con, con:
                con.execute("DELETE FROM google_eventos")
    r = google_api("POST", f"{CALENDAR}/calendars", {
        "summary": "Eco", "description": "Tareas, entregas y exámenes que anotó Eco.",
        "timeZone": CFG["notas"]["zona_horaria"]})
    estado_set("google_calendario", r["id"])
    return r["id"]


def _evento_de(t) -> dict:
    zona = CFG["notas"]["zona_horaria"]
    cuerpo = {"summary": t["texto"], "description": "Anotado por Eco." + (f"\n{t['enlace']}" if t["enlace"] else "")}
    if t["hora"]:
        ini = datetime.fromisoformat(f"{t['fecha']}T{t['hora']}")
        fin = ini + timedelta(minutes=30)
        cuerpo["start"] = {"dateTime": ini.isoformat(timespec="seconds"), "timeZone": zona}
        cuerpo["end"] = {"dateTime": fin.isoformat(timespec="seconds"), "timeZone": zona}
        minutos = int(CFG["notas"].get("aviso_antes_min", 60))
        cuerpo["reminders"] = {"useDefault": False, "overrides": [{"method": "popup", "minutes": minutos}]}
    else:
        d = date.fromisoformat(t["fecha"])
        cuerpo["start"] = {"date": d.isoformat()}
        cuerpo["end"] = {"date": (d + timedelta(days=1)).isoformat()}
        cuerpo["transparency"] = "transparent"  # no te marca como ocupado todo el día
        cuerpo["reminders"] = {"useDefault": False, "overrides": []}
    return cuerpo


def sincronizar_calendar() -> int:
    cal = calendario_eco()
    base = f"{CALENDAR}/calendars/{_q(cal)}/events"
    desde = (ahora().date() - timedelta(days=1)).isoformat()
    with closing(db()) as con:
        # Las de Classroom ya aparecen en tu calendario por el propio Classroom: no las duplicamos.
        tareas = con.execute("SELECT * FROM items WHERE tipo='tarea' AND hecho=0 AND fecha IS NOT NULL "
                             "AND coalesce(origen, '') != 'classroom'").fetchall()
        mapa = {f["item_id"]: f for f in con.execute("SELECT * FROM google_eventos").fetchall()}
    vivas: set[int] = set()
    cambios = 0
    for t in tareas:
        previo = mapa.get(t["id"])
        if previo is None and t["fecha"] < desde:
            continue  # lo viejo que nunca estuvo en el calendario no se sube
        vivas.add(t["id"])
        firma = f"{t['texto']}|{t['fecha']}|{t['hora']}"
        if previo is not None and previo["firma"] == firma:
            continue
        cuerpo = _evento_de(t)
        evento = None
        if previo is not None:
            try:
                google_api("PATCH", f"{base}/{_q(previo['evento'])}", cuerpo)
                evento = previo["evento"]
            except GoogleError as ex:
                if ex.codigo not in (404, 410):  # lo borraste a mano en Calendar: se vuelve a crear
                    raise
        if evento is None:
            evento = google_api("POST", base, cuerpo)["id"]
        with closing(db()) as con, con:
            con.execute("INSERT INTO google_eventos(item_id, evento, firma) VALUES(?,?,?) ON CONFLICT(item_id) "
                        "DO UPDATE SET evento=excluded.evento, firma=excluded.firma", (t["id"], evento, firma))
        cambios += 1
    for item_id, f in mapa.items():
        if item_id in vivas:
            continue
        try:  # tarea hecha, borrada o sin fecha: su evento se va
            google_api("DELETE", f"{base}/{_q(f['evento'])}")
        except GoogleError as ex:
            if ex.codigo not in (404, 410):
                raise
        with closing(db()) as con, con:
            con.execute("DELETE FROM google_eventos WHERE item_id=?", (item_id,))
        cambios += 1
    estado_set("calendar_ultimo", ahora().isoformat(timespec="seconds"))
    estado_set("calendar_error", "")
    if cambios:
        log.info("Calendar: %d cambios en el calendario «Eco»", cambios)
    return cambios


def google_hilo() -> None:
    ultimo_classroom = 0.0
    time.sleep(15)
    _google_evento.set()  # primera revisión apenas enciende Eco
    while True:
        avisado = _google_evento.wait(timeout=600)
        _google_evento.clear()
        if avisado:
            time.sleep(3)  # junta varios cambios seguidos en una sola vuelta
            _google_evento.clear()
        t = google_token_guardado()
        if not t or not google_cliente():
            continue
        g, alcances = CFG["google"], str(t.get("alcances") or "")
        cada = max(5, int(g.get("cada_min") or 20)) * 60
        if g.get("classroom", True) and (_google_forzar["classroom"] or time.time() - ultimo_classroom > cada):
            _google_forzar["classroom"] = False
            ultimo_classroom = time.time()
            if "classroom" not in alcances:
                estado_set("classroom_error", "No diste permiso para Classroom. Vuelve a conectar y deja marcadas "
                                              "las casillas de Classroom.")
            else:
                try:
                    sincronizar_classroom()
                except GoogleError as ex:
                    estado_set("classroom_error", _error_google(ex, "classroom"))
                    log.warning("Classroom: %s", ex)
                except Exception as ex:  # sin internet: se reintenta en la próxima vuelta
                    ultimo_classroom = 0.0
                    log.warning("Classroom no respondió (%s)", ex)
                if g.get("anuncios", True) and _cursos_cache:
                    if "classroom.announcements" not in alcances:
                        estado_set("anuncios_error", "Para leer los anuncios, vuelve a conectar Google "
                                                     "(Desconectar → Conectar con Google).")
                    else:
                        try:
                            if sincronizar_anuncios()["quedan"]:
                                ultimo_classroom = time.time() - cada + 120  # sigue en 2 min con el resto
                                siguiente = threading.Timer(125, _google_evento.set)
                                siguiente.daemon = True
                                siguiente.start()
                        except GoogleError as ex:
                            estado_set("anuncios_error", _error_google(ex, "classroom"))
                            log.warning("Anuncios de Classroom: %s", ex)
                        except Exception as ex:  # IA apagada o sin internet: se reintenta en la próxima vuelta
                            log.warning("No pude revisar los anuncios (%s)", ex)
        if g.get("calendar", True):
            if "calendar" not in alcances:
                estado_set("calendar_error", "No diste permiso para Calendar. Vuelve a conectar y deja marcada "
                                             "la casilla del calendario.")
                continue
            try:
                sincronizar_calendar()
            except GoogleError as ex:
                estado_set("calendar_error", _error_google(ex, "calendar"))
                log.warning("Calendar: %s", ex)
            except Exception as ex:
                log.warning("Calendar no respondió (%s)", ex)


def estado_google() -> dict:
    t = google_token_guardado() or {}
    alcances = str(t.get("alcances") or "")
    with closing(db()) as con:
        tareas_cr = con.execute("SELECT count(*) FROM items WHERE origen='classroom' AND hecho=0").fetchone()[0]
        eventos = con.execute("SELECT count(*) FROM google_eventos").fetchone()[0]
        anuncios_ok = con.execute("SELECT count(*) FROM classroom_anuncios WHERE estado='guardado'").fetchone()[0]
    return {
        "cliente": google_cliente() is not None, "conectado": bool(t), "email": t.get("email"),
        "error": estado_get("google_error") or None,
        "classroom": {"activo": bool(CFG["google"].get("classroom", True)), "permiso": "classroom" in alcances,
                      "ultimo": estado_get("classroom_ultimo"), "cursos": int(estado_get("classroom_cursos") or 0),
                      "pendientes": tareas_cr, "error": estado_get("classroom_error") or None},
        "anuncios": {"activo": bool(CFG["google"].get("anuncios", True)),
                     "permiso": "classroom.announcements" in alcances, "ultimo": estado_get("anuncios_ultimo"),
                     "guardados": anuncios_ok, "error": estado_get("anuncios_error") or None},
        "calendar": {"activo": bool(CFG["google"].get("calendar", True)), "permiso": "calendar" in alcances,
                     "ultimo": estado_get("calendar_ultimo"), "eventos": eventos,
                     "error": estado_get("calendar_error") or None},
    }


# ════════════════════════════ Actualizar la app del teléfono ════════════════════════════
# GitHub compila la app cada vez que subes cambios. La laptop baja esa versión sola (con un permiso
# de solo lectura) y se la ofrece al teléfono, que la instala con un toque. Sin entrar a GitHub.

_app_evento = threading.Event()


def _carpeta_app() -> Path:
    return DATOS / "app"


def _github_token() -> str:
    t = str(CFG["github"].get("token") or "").strip()
    if t:
        return t
    try:
        return str(json.loads((DATOS / "github.json").read_text(encoding="utf-8")).get("token") or "")
    except (OSError, ValueError):
        return ""


def guardar_github_token(token: str) -> None:
    token = token.strip()
    if not re.fullmatch(r"(github_pat_|ghp_)[A-Za-z0-9_]{20,}", token):
        raise ValueError("Eso no parece un token de GitHub (empieza con github_pat_).")
    (DATOS / "github.json").write_text(json.dumps({"token": token}), encoding="utf-8")
    estado_set("app_error", "")
    _app_evento.set()


def _github(url: str, binario: bool = False):
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json",
                                               "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "Eco-laptop"})
    # "unredirected": el token NO viaja a la página de descarga a la que GitHub redirige.
    req.add_unredirected_header("Authorization", f"Bearer {_github_token()}")
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read() if binario else json.load(r)


def info_app() -> dict | None:
    try:
        d = json.loads((_carpeta_app() / "version.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return d if (_carpeta_app() / "eco.apk").exists() else None


def revisar_app() -> None:
    repo = str(CFG["github"].get("repo") or "").strip()
    if not _github_token() or not repo:
        return
    nombre = str(CFG["github"].get("artefacto") or "Eco-apk")
    lista = _github(f"https://api.github.com/repos/{repo}/actions/artifacts?name={_q(nombre)}&per_page=10")
    vivos = [a for a in lista.get("artifacts") or [] if not a.get("expired")]
    estado_set("app_revisado", ahora().isoformat(timespec="seconds"))
    estado_set("app_error", "")
    if not vivos:
        return
    ultimo = max(vivos, key=lambda a: a.get("created_at") or "")
    actual = info_app()
    if (actual and str(actual.get("artefacto")) == str(ultimo["id"])) or estado_get("app_sin_version") == str(ultimo["id"]):
        return
    import zipfile
    crudo = _github(ultimo["archive_download_url"], binario=True)
    with zipfile.ZipFile(io.BytesIO(crudo)) as z:
        nombres = z.namelist()
        apk = next((n for n in nombres if n.lower().endswith(".apk")), None)
        version = json.loads(z.read("version.json")) if "version.json" in nombres else {}
        datos_apk = z.read(apk) if apk else b""
    if not datos_apk or not version.get("versionCode"):
        estado_set("app_sin_version", str(ultimo["id"]))  # compilada antes de este cambio: se ignora
        log.info("La app más reciente en GitHub no trae número de versión; espero la próxima")
        return
    carpeta = _carpeta_app()
    carpeta.mkdir(parents=True, exist_ok=True)
    tmp = carpeta / "eco.apk.part"
    tmp.write_bytes(datos_apk)
    tmp.replace(carpeta / "eco.apk")
    info = {"versionCode": int(version["versionCode"]), "versionName": str(version.get("versionName") or ""),
            "commit": version.get("commit"), "tamano": len(datos_apk),
            "sha256": hashlib.sha256(datos_apk).hexdigest(), "artefacto": ultimo["id"],
            "creado": ultimo.get("created_at"), "bajado": ahora().isoformat(timespec="seconds")}
    (carpeta / "version.json").write_text(json.dumps(info), encoding="utf-8")
    log.info("App del teléfono lista para instalar: versión %s", info["versionName"])
    if actual and info["versionCode"] > int(actual.get("versionCode") or 0):
        enviar_o_guardar(f"📲 <b>Versión nueva de la app Eco</b> ({e(info['versionName'])})\n"
                         "Abre Eco en tu teléfono y toca <b>Actualizar</b>.")


def actualizaciones_hilo() -> None:
    time.sleep(20)
    _app_evento.set()
    while True:
        _app_evento.wait(timeout=30 * 60)
        _app_evento.clear()
        try:
            revisar_app()
        except urllib.error.HTTPError as ex:
            estado_set("app_error", {
                401: "El token de GitHub no sirve o ya venció. Crea otro (README, sección 11).",
                403: "El token de GitHub no tiene permiso para ver «Actions» de tu repositorio.",
                404: "GitHub no encuentra el repositorio con ese token: revisa que le diste acceso al repositorio eco.",
            }.get(ex.code, f"GitHub respondió con error {ex.code}."))
            log.warning("Actualización de la app: GitHub respondió %s", ex.code)
        except Exception as ex:  # sin internet: se intenta en la próxima vuelta
            log.warning("No pude revisar si hay app nueva (%s)", ex)


# ════════════════════════════ Salud: avisar cuando algo deja de funcionar ════════════════════════════
# Cada 5 minutos Eco revisa sus piezas. Si algo falla, te avisa UNA vez por Telegram (y otra vez
# si sigue igual 12 horas después), y te dice cuando se arregla. De noche espera a la mañana.

ALERTAS: dict[str, str] = {}      # clave → texto de lo que está fallando ahora
_salud = {"ultimo": 0.0}


def _en_silencio(actual: datetime) -> bool:
    m = re.fullmatch(r"\s*(\d{1,2}:\d{2})\s*-\s*(\d{1,2}:\d{2})\s*", str(CFG["salud"].get("silencio") or ""))
    if not m:
        return False
    ini, fin = _hhmm(m.group(1), (22, 0)), _hhmm(m.group(2), (6, 30))
    hm = (actual.hour, actual.minute)
    return (hm >= ini or hm < fin) if ini > fin else (ini <= hm < fin)


def alerta(clave: str, activa: bool, texto: str = "", resuelto: str | None = None, repetir_h: float = 12) -> None:
    previo = estado_get(f"alerta_{clave}") or ""
    if not activa:
        ALERTAS.pop(clave, None)
        if previo:
            estado_set(f"alerta_{clave}", "")
            if resuelto and CFG["salud"].get("alertas", True):
                enviar_o_guardar(resuelto)
        return
    ALERTAS[clave] = texto
    actual = ahora()
    if not CFG["salud"].get("alertas", True) or _en_silencio(actual):
        return
    if previo and actual - datetime.fromisoformat(previo) < timedelta(hours=repetir_h):
        return
    if enviar(texto):
        estado_set(f"alerta_{clave}", actual.isoformat(timespec="seconds"))


def _ollama_modelos() -> list[str] | None:
    try:
        with urllib.request.urlopen(CFG["ia"]["ollama_url"].rstrip("/") + "/api/tags", timeout=5) as r:
            return [m.get("name", "") for m in json.load(r).get("models", [])]
    except Exception:
        return None


def _tiene(modelos: list[str], nombre: str) -> bool:
    nombre = nombre.strip()
    return any(m == nombre or m == f"{nombre}:latest" or m.split(":")[0] == nombre for m in modelos)


def revisar_salud(forzar: bool = False) -> None:
    if not forzar and time.time() - _salud["ultimo"] < 300:
        return
    _salud["ultimo"] = time.time()
    ia = CFG["ia"]
    usa_ollama = str(ia.get("proveedor")).lower() == "ollama"

    # Ollama y sus modelos
    if usa_ollama or _modelo_embed():
        modelos = _ollama_modelos()
        alerta("ollama", usa_ollama and modelos is None,
               "🔴 <b>Ollama no responde</b>\nLos momentos siguen llegando, pero no se pueden analizar. "
               "Abre <b>Ollama</b> en la laptop (ícono de la llama junto al reloj) o reinicia la laptop.",
               resuelto="✅ Ollama volvió a responder. Lo que quedó pendiente se procesa solo.")
        if modelos is not None:
            falta = ia["ollama_modelo"] if usa_ollama and not _tiene(modelos, ia["ollama_modelo"]) else None
            alerta("modelo_ia", bool(falta),
                   f"🔴 <b>Falta el modelo de IA</b> {e(falta)}\nEn PowerShell: <code>ollama pull {e(falta)}</code>")
            emb = _modelo_embed()
            alerta("modelo_embed", bool(emb) and not _tiene(modelos, emb),
                   f"💡 Para que «pregúntale a Eco» entienda el significado y no solo palabras exactas, instala "
                   f"un modelo chico (unos 600 MB). En PowerShell:\n<code>ollama pull {e(emb)}</code>",
                   resuelto="✅ Listo: las preguntas ya buscan por significado.", repetir_h=24 * 7)

    # Google
    g = estado_google()
    if g["cliente"]:
        alerta("google", bool(g["error"]) and not g["conectado"],
               f"🔴 <b>Google se desconectó</b>\n{e(g['error'])}\nEn Chrome: http://localhost:8765 → Conectar con Google.",
               resuelto="✅ Google volvió a conectarse.")
        problema = g["classroom"]["error"] or g["calendar"]["error"] if g["conectado"] else None
        alerta("google_parcial", bool(problema), f"⚠️ <b>Classroom o Calendar con problemas</b>\n{e(problema)}",
               repetir_h=24)

    # Teléfono: la app se reporta cada 10 minutos
    tel = estado_telefono()
    horas = float(CFG["salud"].get("telefono_horas") or 3)
    perdido = bool(tel and tel["estado"] != "apagado" and tel["hace_min"] is not None and tel["hace_min"] > horas * 60)
    alerta("telefono", perdido,
           f"📵 <b>Tu teléfono no se reporta</b> desde las {e(tel['hora'] if tel else '')}.\nRevisa que Eco siga "
           "encendido en el teléfono y que Tailscale esté activo. Si Android lo cerró, ábrelo y toca Encender.",
           resuelto="✅ El teléfono volvió a reportarse.")

    # Audios atascados
    viejos = [f for f in ENTRADA.iterdir() if f.suffix.lower() in EXT_AUDIO + EXT_IMAGEN
              and time.time() - f.stat().st_mtime > 2 * 3600] if ENTRADA.exists() else []
    alerta("cola", bool(viejos),
           f"⚠️ <b>Hay {len(viejos)} {'archivo' if len(viejos) == 1 else 'archivos'} sin procesar</b> desde hace más de "
           "2 horas. Revisa el registro en el panel (o reinicia Eco desde el ícono junto al reloj).",
           resuelto="✅ Ya se procesó todo lo que estaba en espera.")

    # Espacio en disco
    import shutil
    libre = shutil.disk_usage(DATOS).free / 1024 ** 3
    alerta("disco", libre < 2, f"⚠️ <b>Queda poco espacio en la laptop</b> ({libre:.1f} GB). "
                               "Si se llena, Eco no puede guardar audios ni notas.", repetir_h=24)

    # Respaldo
    if CFG["respaldo"].get("activo", True):
        ultimo = estado_get("ultimo_respaldo")
        viejo = not ultimo or ahora() - datetime.fromisoformat(ultimo) > timedelta(days=3)
        alerta("respaldo", viejo and bool(estado_get("respaldo_error")),
               f"⚠️ <b>El respaldo diario lleva días fallando</b>\n{e(estado_get('respaldo_error'))}",
               resuelto="✅ El respaldo volvió a funcionar.", repetir_h=48)


# ── El teléfono se reporta ("latido") ──

def registrar_latido(datos: dict) -> None:
    info = {
        "estado": str(datos.get("estado") or "?")[:20],
        "bateria": datos.get("bateria"), "cargando": bool(datos.get("cargando")),
        "version": str(datos.get("version") or "")[:20], "pendientes": datos.get("pendientes"),
        "visto": ahora().isoformat(timespec="seconds"),
    }
    estado_set("telefono", json.dumps(info))


def estado_telefono() -> dict | None:
    try:
        info = json.loads(estado_get("telefono") or "")
    except ValueError:
        return None
    visto = datetime.fromisoformat(info["visto"])
    info["hace_min"] = int((ahora() - visto).total_seconds() // 60)
    info["hora"] = f"{visto:%H:%M}" + ("" if visto.date() == ahora().date() else f" del {fecha_corta(visto.date())}")
    return info


def estado_app() -> dict:
    info = info_app() or {}
    return {"token": bool(_github_token()), "version": info.get("versionName"), "codigo": info.get("versionCode"),
            "creado": info.get("creado"), "revisado": estado_get("app_revisado"),
            "error": estado_get("app_error") or None}


# ════════════════════════════ Servidor HTTP ════════════════════════════

def _nombre_seguro(nombre: str) -> str:
    base = re.sub(r"[^A-Za-z0-9_.-]", "_", Path(nombre or "").name)[:80].strip("._")
    if not base:
        base = f"eco_{int(time.time())}"
    return base if Path(base).suffix.lower() in EXT_AUDIO else base + ".wav"


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
        "origen": t["origen"], "enlace": t["enlace"], "clase": t["detalle"],
    }


def _captura_json(con: sqlite3.Connection, c) -> dict:
    its = items_de(con, c["id"])
    ini = datetime.fromisoformat(c["inicio"])
    return {
        "id": c["id"], "fecha": ini.date().isoformat(), "hora": f"{ini:%H:%M}",
        "duracion": c["duracion"], "lugar": c["lugar"], "lat": c["lat"], "lon": c["lon"],
        "titulo": c["titulo"], "resumen": c["resumen"], "importancia": c["importancia"] or 3,
        "origen": c["origen"], "ia_ok": bool(c["ia_ok"]), "transcripcion": c["transcripcion"],
        "foto": bool(c["adjunto"]),
        "tareas": [_tarea_json(i) for i in its if i["tipo"] == "tarea"],
        "datos": [i["texto"] for i in its if i["tipo"] == "dato"],
        "ideas": [i["texto"] for i in its if i["tipo"] == "idea"],
        # Para editar: todo junto, con su id
        "items": [{"id": i["id"], "tipo": i["tipo"], "texto": i["texto"], "fecha": i["fecha"],
                   "hora": i["hora"], "hecho": bool(i["hecho"])} for i in its],
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
        "whisper": estado_whisper(), "whisper_cargado": bool(_modelos),
        "telefono": estado_telefono(), "alertas": [{"clave": k, "texto": t} for k, t in ALERTAS.items()],
        "semantica": estado_semantica() if es_local else None,
        "telegram": bool(CFG["telegram"].get("token") and CFG["telegram"].get("chat_id")),
        "momentos_hoy": momentos_hoy, "pendientes": pendientes_n,
        "proximo": _tarea_json(prox) if prox else None,
        "hoy": hoy, "es_local": es_local, "notas": str(NOTAS) if es_local else None,
        "sesiones": sesiones_en_curso(),
        "clase": CLASE.estado() if CLASE is not None else None,
        "respaldo": estado_respaldo() if es_local else None,
        "google": estado_google() if es_local else None,
        "app": estado_app() if es_local else None,
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
        resultado = [_captura_json(con, c) for c in filas]
    if q and len(resultado) < 5:
        # Pocas coincidencias exactas: se suman las que hablan de lo mismo con otras palabras.
        try:
            ya = {m["id"] for m in resultado}
            extra = []
            for cid, _texto, _p in buscar_semantico(q, k=20, minimo=0.45):
                if cid not in ya:
                    ya.add(cid)
                    extra.append(cid)
            with closing(db()) as con:
                for cid in extra[:10]:
                    c = con.execute("SELECT * FROM capturas WHERE id=?", (cid,)).fetchone()
                    if c:
                        resultado.append({**_captura_json(con, c), "parecido": True})
        except Exception:
            pass
    return resultado


# Lo que dijiste tú (en un momento, nota, voz o foto). Lo de clases, reuniones y anuncios se queda
# en su momento: son muchos datos y no son recordatorios tuyos.
ORIGENES_PERSONALES = ("audio", "voz", "texto", "foto")


def api_pendientes() -> list:
    """Todo lo que tienes por hacer, de cualquier día, hasta que lo marques como hecho."""
    with closing(db()) as con:
        filas = con.execute(
            "SELECT i.*, c.titulo AS de, c.inicio AS de_inicio, g.evento AS en_calendar FROM items i "
            "LEFT JOIN capturas c ON c.id=i.captura_id LEFT JOIN google_eventos g ON g.item_id=i.id "
            "WHERE i.tipo='tarea' AND i.hecho=0 "
            "ORDER BY i.fecha IS NULL, i.fecha, i.hora IS NULL, i.hora, i.id LIMIT 200").fetchall()
    return [{**_tarea_json(f, f["de"]), "de_fecha": (f["de_inicio"] or "")[:10] or None,
             "en_calendar": bool(f["en_calendar"])} for f in filas]


def api_recordar() -> list:
    """Lo que dijiste para no olvidar (datos e ideas): se queda hasta que lo marques como listo."""
    marcas = ",".join("?" * len(ORIGENES_PERSONALES))
    with closing(db()) as con:
        filas = con.execute(
            "SELECT i.*, c.titulo AS de, c.inicio AS de_inicio FROM items i LEFT JOIN capturas c ON c.id=i.captura_id "
            f"WHERE i.tipo IN ('dato', 'idea') AND i.hecho=0 AND (i.captura_id IS NULL OR c.origen IN ({marcas})) "
            "ORDER BY coalesce(c.inicio, '') DESC, i.id DESC LIMIT 200", ORIGENES_PERSONALES).fetchall()
    return [{**_tarea_json(f, f["de"]), "tipo": f["tipo"], "de_fecha": (f["de_inicio"] or "")[:10] or None}
            for f in filas]


def api_marcar_tarea(item_id: int, hecho: bool) -> dict | None:
    """Marca como hecha una tarea, o como "listo" algo de Para recordar (no se borra: se archiva)."""
    with closing(db()) as con, con:
        fila = con.execute("SELECT i.*, c.inicio FROM items i LEFT JOIN capturas c ON c.id=i.captura_id "
                           "WHERE i.id=?", (item_id,)).fetchone()
        if not fila:
            return None
        con.execute("UPDATE items SET hecho=? WHERE id=?", (int(hecho), item_id))
    actualizar_notas(datetime.fromisoformat(fila["inicio"]).date() if fila["inicio"] else None)
    with closing(db()) as con:
        return _tarea_json(con.execute("SELECT * FROM items WHERE id=?", (item_id,)).fetchone())


def api_nuevo_pendiente(datos: dict) -> dict | None:
    """Pendiente escrito a mano en el panel. Con fecha, va a tu Google Calendar."""
    texto = re.sub(r"\s+", " ", str(datos.get("texto") or "")).strip()[:300]
    if not texto:
        return None
    fecha = _fecha_valida(datos.get("fecha")) if datos.get("fecha") else None
    hora = _hora_valida(datos.get("hora")) if datos.get("hora") else None
    f, h, ts = calcular_recordatorio(fecha, hora, ahora())
    with closing(db()) as con, con:
        iid = con.execute("INSERT INTO items(captura_id, tipo, texto, fecha, hora, recordar_ts, origen) "
                          "VALUES(NULL, 'tarea', ?, ?, ?, ?, 'panel')", (texto, f, h, ts)).lastrowid
    actualizar_notas(None)
    log.info("Pendiente #%d agregado desde el panel", iid)
    with closing(db()) as con:
        return _tarea_json(con.execute("SELECT * FROM items WHERE id=?", (iid,)).fetchone())


def api_fecha_item(datos: dict) -> dict | None:
    """Ponerle (o quitarle) fecha y hora a algo. Lo de Para recordar pasa a ser tarea: así va al calendario."""
    item_id = int(datos["id"])
    fecha = _fecha_valida(datos.get("fecha")) if datos.get("fecha") else None
    hora = _hora_valida(datos.get("hora")) if datos.get("hora") and fecha else None
    with closing(db()) as con, con:
        fila = con.execute("SELECT i.*, c.inicio FROM items i LEFT JOIN capturas c ON c.id=i.captura_id "
                           "WHERE i.id=?", (item_id,)).fetchone()
        if not fila or fila["origen"] == "classroom":  # las fechas de Classroom las pone el profe
            return None
        f, h, ts = calcular_recordatorio(fecha, hora, ahora())
        con.execute("UPDATE items SET tipo='tarea', fecha=?, hora=?, recordar_ts=?, recordado=0 WHERE id=?",
                    (f, h, ts, item_id))
    actualizar_notas(datetime.fromisoformat(fila["inicio"]).date() if fila["inicio"] else None)
    with closing(db()) as con:
        return _tarea_json(con.execute("SELECT * FROM items WHERE id=?", (item_id,)).fetchone())


def migrar_recordar() -> None:
    """Una sola vez: lo que ya habías guardado como "dato" pero es una deuda o algo por hacer
    pasa a Pendientes (por ejemplo, "Deudas con Steven")."""
    if estado_get("migracion_recordar") == "1":
        return
    marcas = ",".join("?" * len(ORIGENES_PERSONALES))
    with closing(db()) as con, con:
        filas = con.execute(
            "SELECT i.id, i.texto FROM items i JOIN capturas c ON c.id=i.captura_id "
            f"WHERE i.tipo IN ('dato', 'idea') AND i.hecho=0 AND c.origen IN ({marcas})",
            ORIGENES_PERSONALES).fetchall()
        movidas = [f["id"] for f in filas if parece_tarea(f["texto"])]
        for iid in movidas:
            con.execute("UPDATE items SET tipo='tarea' WHERE id=?", (iid,))
    estado_set("migracion_recordar", "1")
    if movidas:
        log.info("Pasé %d cosas de 'datos' a Pendientes (deudas y cosas por hacer)", len(movidas))
        try:
            render_pendientes()
        except OSError:
            pass


# ── Editar y borrar desde el panel ──

TIPOS_ITEM = ("tarea", "dato", "idea")


def api_guardar_momento(datos: dict) -> dict | None:
    """Guarda lo que editaste: título, resumen, importancia y la lista completa de tareas, datos e ideas.
    Lo que ya no viene en la lista se borra; lo nuevo (sin id) se agrega."""
    cid = int(datos["id"])
    with closing(db()) as con, con:
        c = con.execute("SELECT * FROM capturas WHERE id=?", (cid,)).fetchone()
        if not c:
            return None
        titulo = str(datos.get("titulo") or "").strip()[:90] or c["titulo"]
        resumen = str(datos["resumen"]).strip()[:1500] if datos.get("resumen") is not None else (c["resumen"] or "")
        try:
            importancia = max(1, min(5, int(datos.get("importancia") or c["importancia"] or 3)))
        except (TypeError, ValueError):
            importancia = c["importancia"] or 3
        transcripcion_vieja = archivo_transcripcion(c) if c["origen"] in TIPOS_SESION and c["transcripcion"] else None
        con.execute("UPDATE capturas SET titulo=?, resumen=?, importancia=? WHERE id=?",
                    (titulo, resumen, importancia, cid))

        if isinstance(datos.get("items"), list):
            actuales = {i["id"]: i for i in items_de(con, cid)}
            quedan: set[int] = set()
            for it in datos["items"][:120]:
                if not isinstance(it, dict) or it.get("tipo") not in TIPOS_ITEM:
                    continue
                tipo = it["tipo"]
                texto = re.sub(r"\s+", " ", str(it.get("texto") or "")).strip()[:300]
                if not texto:
                    continue
                fecha = _fecha_valida(it.get("fecha")) if tipo == "tarea" and it.get("fecha") else None
                hora = _hora_valida(it.get("hora")) if tipo == "tarea" else None
                try:
                    previo = actuales.get(int(it["id"])) if it.get("id") is not None else None
                except (TypeError, ValueError):
                    previo = None
                if previo is None:
                    f, h, ts = calcular_recordatorio(fecha, hora, ahora()) if tipo == "tarea" else (None, None, None)
                    con.execute("INSERT INTO items(captura_id, tipo, texto, fecha, hora, recordar_ts) VALUES(?,?,?,?,?,?)",
                                (cid, tipo, texto, f, h, ts))
                    continue
                quedan.add(previo["id"])
                if tipo != "tarea":
                    con.execute("UPDATE items SET tipo=?, texto=?, fecha=NULL, hora=NULL, recordar_ts=NULL, "
                                "recordado=0, hecho=0 WHERE id=?", (tipo, texto, previo["id"]))
                elif previo["tipo"] != "tarea" or (fecha, hora) != (previo["fecha"], previo["hora"]):
                    f, h, ts = calcular_recordatorio(fecha, hora, ahora())  # nueva fecha → nuevo aviso
                    con.execute("UPDATE items SET tipo='tarea', texto=?, fecha=?, hora=?, recordar_ts=?, recordado=0 "
                                "WHERE id=?", (texto, f, h, ts, previo["id"]))
                else:
                    con.execute("UPDATE items SET texto=? WHERE id=?", (texto, previo["id"]))
            for iid in set(actuales) - quedan:
                con.execute("DELETE FROM items WHERE id=?", (iid,))

    indexar(cid)
    with closing(db()) as con:
        c = con.execute("SELECT * FROM capturas WHERE id=?", (cid,)).fetchone()
        respuesta = _captura_json(con, c)
    if transcripcion_vieja is not None:
        escribir_transcripcion(cid)
        if transcripcion_vieja != archivo_transcripcion(c):
            _limpiar(transcripcion_vieja)  # cambió el título: el archivo se renombra
    actualizar_notas(datetime.fromisoformat(c["inicio"]).date())
    log.info("Momento #%d editado desde el panel", cid)
    return respuesta


def api_borrar_momento(cid: int) -> bool:
    with closing(db()) as con, con:
        c = con.execute("SELECT * FROM capturas WHERE id=?", (cid,)).fetchone()
        if not c:
            return False
        con.execute("DELETE FROM items WHERE captura_id=?", (cid,))
        con.execute("DELETE FROM vectores WHERE captura_id=?", (cid,))
        con.execute("UPDATE classroom_anuncios SET captura_id=NULL WHERE captura_id=?", (cid,))
        if HAY_INDICE:
            con.execute("DELETE FROM indice WHERE captura_id=?", (cid,))
        # La sesión queda marcada como lista (sin momento), así una parte que llegue tarde no la revive.
        for s in con.execute("SELECT id FROM sesiones WHERE captura_id=?", (cid,)).fetchall():
            con.execute("UPDATE sesiones SET captura_id=NULL, estado='lista' WHERE id=?", (s["id"],))
            con.execute("UPDATE sesion_partes SET texto='' WHERE sesion_id=?", (s["id"],))
        con.execute("DELETE FROM capturas WHERE id=?", (cid,))
    if c["origen"] in TIPOS_SESION and c["transcripcion"]:
        _limpiar(archivo_transcripcion(c))
    if c["adjunto"]:
        _limpiar(NOTAS / c["adjunto"])
    _vec_version[0] += 1
    actualizar_notas(datetime.fromisoformat(c["inicio"]).date())
    log.info("Momento #%d borrado desde el panel", cid)
    return True


def ruta_foto(cid: int) -> Path | None:
    with closing(db()) as con:
        c = con.execute("SELECT adjunto FROM capturas WHERE id=?", (cid,)).fetchone()
    if not c or not c["adjunto"]:
        return None
    ruta = (NOTAS / c["adjunto"]).resolve()
    if NOTAS.resolve() not in ruta.parents or not ruta.is_file():
        return None
    return ruta


def datos_conexion() -> dict:
    """Lo que necesita el teléfono para conectarse, en un enlace que cabe en un código QR."""
    puerto = int(CFG["servidor"]["puerto"])
    fija = direccion_fija()
    wifi = f"{ip_local()}:{puerto}"
    principal = f"{fija}:{puerto}" if fija else wifi
    consulta = urllib.parse.urlencode({"v": 1, "d": principal, "w": wifi, "t": str(CFG["servidor"]["token"])})
    enlace = f"eco://conectar?{consulta}"
    svg = None
    try:
        import segno
        svg = segno.make(enlace, error="m", micro=False).svg_inline(
            scale=1, border=3, dark="#0A0A12", light="#FFFFFF", omitsize=True, svgclass="qr")
    except Exception as ex:  # segno sin instalar: el panel muestra los datos para escribirlos a mano
        log.warning("Sin código QR (%s); corre instalar_inicio.bat", ex)
    return {"direccion": principal, "fija": bool(fija), "svg": svg, "enlace": enlace}


_PANEL: bytes | None = None


def panel_html() -> bytes:
    global _PANEL
    if _PANEL is None:
        _PANEL = (BASE / "panel.html").read_bytes()
    return _PANEL


class Manejador(BaseHTTPRequestHandler):
    server_version = "Eco/1.3"

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
                         "img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'")
        self.end_headers()
        self.wfile.write(cuerpo)

    def _redirigir(self, destino: str) -> None:
        self.send_response(302)
        self.send_header("Location", destino)
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.end_headers()

    def _archivo(self, ruta: Path, tipo: str) -> None:
        tam = ruta.stat().st_size
        self.send_response(200)
        self.send_header("Content-Type", tipo)
        self.send_header("Content-Length", str(tam))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        with open(ruta, "rb") as f:
            while bloque := f.read(1 << 16):
                self.wfile.write(bloque)

    def _pagina_google(self, ok: bool, mensaje: str) -> None:
        color = "#3DDC97" if ok else "#FFB547"
        cuerpo = f"""<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Eco · Google</title>
{'<meta http-equiv="refresh" content="3; url=/#google">' if ok else ''}
<style>body{{margin:0;min-height:100vh;display:grid;place-items:center;background:#0A0A12;color:#EEEEF6;
font:400 1rem/1.5 "Segoe UI Variable Text","Segoe UI",system-ui,sans-serif;padding:16px}}
main{{max-width:440px;padding:32px;border-radius:20px;background:#14141F;border:1px solid #2A2A3D}}
h1{{margin:0 0 8px;font:600 1.5rem/1.2 "Segoe UI Variable Display","Segoe UI",system-ui,sans-serif;color:{color}}}
p{{margin:0;color:#A3A3BA}}a{{display:inline-block;margin-top:24px;color:#B8AEFF}}</style></head>
<body><main><h1>{'Conectado' if ok else 'No se pudo conectar'}</h1><p>{html.escape(mensaje)}</p>
<a href="/#google">Volver al panel</a></main></body></html>"""
        self._html(cuerpo.encode())

    # ── Rutas ──
    def do_GET(self) -> None:
        ruta, _, consulta = self.path.partition("?")
        params = {k: v[-1] for k, v in urllib.parse.parse_qs(consulta).items()}
        if ruta in ("/", "/panel"):
            if params.get("state") and ("code" in params or "error" in params) and self._es_local():
                return self._pagina_google(*google_callback(params))  # Google vuelve aquí al conectar
            return self._html(panel_html())
        if ruta == "/google/conectar":
            if not self._es_local():
                return self._json(403, {"ok": False, "error": "Solo desde la laptop"})
            try:
                return self._redirigir(google_url_conectar())
            except ValueError as ex:
                return self._pagina_google(False, str(ex))
        if ruta == "/estado":  # lo usa la app del teléfono
            if not self._autorizado():
                return self._json(401, {"ok": False, "error": "token"})
            return self._json(200, {"ok": True, "nombre": "Eco", "en_cola": COLA.qsize()})
        if ruta in ("/app/version", "/app/eco.apk"):  # la app del teléfono pregunta si hay versión nueva
            if not self._autorizado():
                return self._json(401, {"ok": False, "error": "token"})
            info = info_app()
            if not info:
                return self._json(404, {"ok": False})
            if ruta == "/app/version":
                return self._json(200, {"ok": True, **{k: info[k] for k in ("versionCode", "versionName", "tamano", "sha256")}})
            return self._archivo(_carpeta_app() / "eco.apk", "application/vnd.android.package-archive")
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
            if ruta == "/api/recordar":
                return self._json(200, api_recordar())
            if ruta == "/api/foto":
                try:
                    foto = ruta_foto(int(params.get("id") or 0))
                except ValueError:
                    foto = None
                if not foto:
                    return self._json(404, {"ok": False})
                tipo = {".png": "image/png", ".webp": "image/webp"}.get(foto.suffix.lower(), "image/jpeg")
                return self._archivo(foto, tipo)
            if ruta == "/api/conectar":
                if not self._es_local():  # el QR lleva tu token: solo se muestra en la pantalla de la laptop
                    return self._json(403, {"ok": False, "error": "Solo desde la laptop"})
                return self._json(200, datos_conexion())
        self._json(404, {"ok": False})

    def do_POST(self) -> None:
        ruta = self.path.split("?")[0]
        if ruta == "/subir":
            return self._subir()
        if ruta == "/latido":  # la app del teléfono avisa que sigue viva (cada 10 min)
            if not self._autorizado():
                return self._json(401, {"ok": False, "error": "token"})
            try:
                largo = min(int(self.headers.get("Content-Length") or 0), 4096)
                registrar_latido(json.loads(self.rfile.read(largo) or b"{}"))
            except (ValueError, AttributeError):
                return self._json(400, {"ok": False})
            return self._json(200, {"ok": True})
        if ruta.startswith("/api/"):
            # La cabecera propia impide que otra página web mande esto a tus espaldas.
            if self.headers.get("X-Eco-Panel") != "1" or not self._puede_ver_panel():
                return self._json(401, {"ok": False, "error": "token"})
            try:
                largo = min(int(self.headers.get("Content-Length") or 0), 256 * 1024)
                datos = json.loads(self.rfile.read(largo) or b"{}")
                if not isinstance(datos, dict):
                    raise ValueError
            except ValueError:
                return self._json(400, {"ok": False})
            if ruta == "/api/momento":
                try:
                    momento = api_guardar_momento(datos)
                except (KeyError, TypeError, ValueError):
                    return self._json(400, {"ok": False})
                return self._json(200 if momento else 404, {"ok": bool(momento), "momento": momento})
            if ruta == "/api/momento/borrar":
                try:
                    ok = api_borrar_momento(int(datos["id"]))
                except (KeyError, TypeError, ValueError):
                    return self._json(400, {"ok": False})
                return self._json(200 if ok else 404, {"ok": ok})
            if ruta.startswith(("/api/google", "/api/github")) and not self._es_local():
                return self._json(403, {"ok": False, "error": "Solo desde la laptop"})
            if ruta == "/api/google/cliente":
                try:
                    guardar_google_cliente(str(datos.get("contenido") or ""))
                except ValueError as ex:
                    return self._json(200, {"ok": False, "error": str(ex)})
                return self._json(200, {"ok": True, "google": estado_google()})
            if ruta == "/api/google":
                if datos.get("accion") == "desconectar":
                    google_desconectar()
                else:
                    avisar_google(classroom=True)
                return self._json(200, {"ok": True, "google": estado_google()})
            if ruta == "/api/github":
                if datos.get("token"):
                    try:
                        guardar_github_token(str(datos["token"]))
                    except ValueError as ex:
                        return self._json(200, {"ok": False, "error": str(ex)})
                else:
                    _app_evento.set()
                return self._json(200, {"ok": True, "app": estado_app()})
            if ruta == "/api/preguntar":
                pregunta = str(datos.get("pregunta") or "").strip()[:500]
                if not pregunta:
                    return self._json(400, {"ok": False, "error": "vacía"})
                try:
                    return self._json(200, {"ok": True, **responder_pregunta(pregunta)})
                except Exception as ex:
                    log.warning("No pude responder la pregunta del panel (%s)", ex)
                    return self._json(503, {"ok": False, "error": "La inteligencia no respondió. ¿Está encendido Ollama?"})
            if ruta in ("/api/clase", "/api/respaldo") and not self._es_local():
                return self._json(403, {"ok": False, "error": "Solo desde la laptop"})
            if ruta == "/api/clase":
                if CLASE is None:
                    return self._json(503, {"ok": False})
                if datos.get("accion") == "iniciar":
                    return self._json(200, {"ok": True, "clase": CLASE.iniciar(datos.get("nombre"))})
                CLASE.terminar(esperar=True)
                return self._json(200, {"ok": True, "clase": CLASE.estado()})
            if ruta == "/api/respaldo":
                try:
                    hecho = hacer_respaldo()
                    return self._json(200, {"ok": True, "archivo": str(hecho)})
                except Exception as ex:
                    estado_set("respaldo_error", str(ex))
                    return self._json(200, {"ok": False, "error": str(ex)})
        if ruta == "/api/tarea":
            try:
                tarea = api_marcar_tarea(int(datos["id"]), bool(datos.get("hecho", True)))
            except (ValueError, KeyError, TypeError):
                return self._json(400, {"ok": False})
            return self._json(200 if tarea else 404, {"ok": bool(tarea), "tarea": tarea})
        if ruta == "/api/pendiente":
            tarea = api_nuevo_pendiente(datos)
            return self._json(200 if tarea else 400, {"ok": bool(tarea), "tarea": tarea})
        if ruta == "/api/item/fecha":
            try:
                tarea = api_fecha_item(datos)
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
    global CLASE
    from clase import GrabadoraClase
    CLASE = GrabadoraClase(ENTRADA, COLA.put, log,
                           minutos_silencio=int(CFG["sesiones"].get("clase_auto_terminar_min") or 20))
    for f in sorted(ENTRADA.iterdir()):  # lo que quedó pendiente la última vez
        if f.suffix.lower() in EXT_AUDIO + EXT_IMAGEN:
            COLA.put(f)
    threading.Thread(target=reindexar_si_hace_falta, name="indice", daemon=True).start()
    migrar_recordar()
    for destino in (trabajador, bot, reloj, google_hilo, actualizaciones_hilo, vectores_hilo):
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
