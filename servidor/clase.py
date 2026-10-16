"""
Eco · modo clase
================

Graba lo que suena en la laptop (Meet, Zoom, Teams, YouTube…) usando la "captura de
reproducción" de Windows (loopback WASAPI). No usa el micrófono: solo el audio de la clase.

La grabación se corta en partes de 10 minutos (16 kHz, mono) que van a la misma cola que los
audios del teléfono; así Eco transcribe mientras la clase sigue y, al terminar, solo falta la
última parte. Si la clase lleva mucho rato en silencio, se termina sola.
"""
from __future__ import annotations

import json
import threading
import time
import warnings
import wave
from datetime import datetime
from pathlib import Path
from typing import Callable

TASA = 16_000
MINUTOS_POR_PARTE = 10
UMBRAL_SILENCIO = 0.004  # volumen (RMS) por debajo del cual un segundo cuenta como silencio


class GrabadoraClase:
    def __init__(self, entrada: Path, entregar: Callable[[Path], None], log,
                 minutos_silencio: int = 20, minutos_parte: int = MINUTOS_POR_PARTE):
        self.entrada = entrada
        self.entregar = entregar          # recibe cada parte terminada (la mete a la cola de Eco)
        self.log = log
        self.minutos_silencio = minutos_silencio
        self.segundos_parte = minutos_parte * 60
        self._hilo: threading.Thread | None = None
        self._parar = threading.Event()
        self._lock = threading.Lock()
        self._estado: dict | None = None
        self.ultimo_error: str | None = None

    # ── Para el panel y el ícono ──
    def activa(self) -> bool:
        return self._hilo is not None and self._hilo.is_alive()

    def clave_activa(self) -> str | None:
        return self._estado["clave"] if self.activa() and self._estado else None

    def estado(self) -> dict:
        with self._lock:
            e = dict(self._estado) if (self._estado and self.activa()) else None
        if e:
            e["segundos"] = int(time.time() - e["inicio_ts"])
        return {"grabando": bool(e), "actual": e, "error": self.ultimo_error}

    # ── Control ──
    def iniciar(self, nombre: str | None = None) -> dict:
        if self.activa():
            return self.estado()
        ahora = datetime.now()
        clave = f"clase_{ahora:%Y%m%d_%H%M%S}"
        with self._lock:
            self._estado = {"clave": clave, "nombre": (nombre or "").strip()[:60] or None,
                            "inicio_ts": time.time(), "parte": 1, "silencio_s": 0}
        self.ultimo_error = None
        self._parar.clear()
        self._hilo = threading.Thread(target=self._grabar, name="modo-clase", daemon=True)
        self._hilo.start()
        self.log.info("Modo clase: grabando%s", f" «{nombre}»" if nombre else "")
        return self.estado()

    def terminar(self, esperar: bool = True) -> None:
        if not self.activa():
            return
        self._parar.set()
        if esperar and self._hilo:
            self._hilo.join(timeout=15)

    # ── Grabación ──
    def _grabar(self) -> None:
        try:
            import numpy as np
            import soundcard as sc
            warnings.filterwarnings("ignore", category=getattr(sc, "SoundcardRuntimeWarning", Warning))
        except Exception as ex:  # soundcard no instalado o sin audio
            self.ultimo_error = f"No pude abrir el audio de la laptop ({ex}). Corre instalar_inicio.bat."
            self.log.warning("Modo clase: %s", self.ultimo_error)
            return

        # Cada hilo que usa el audio de Windows tiene que iniciar COM por su cuenta.
        ole32 = getattr(getattr(__import__("ctypes"), "windll", None), "ole32", None)
        if ole32:
            ole32.CoInitializeEx(None, 0)

        e = self._estado
        parte = _Parte(self.entrada, e["clave"], 1)
        final_por_silencio = False
        try:
            while not self._parar.is_set():
                altavoz = sc.default_speaker()
                captura = sc.get_microphone(id=str(altavoz.id), include_loopback=True)
                self.log.info("Modo clase: escuchando «%s»", altavoz.name)
                revisado = time.time()
                with captura.recorder(samplerate=TASA, channels=1, blocksize=TASA // 10) as rec:
                    while not self._parar.is_set():
                        bloque = rec.record(numframes=TASA)  # 1 segundo
                        mono = bloque[:, 0] if bloque.ndim > 1 else bloque
                        parte.escribir(mono)

                        silencio = float(np.sqrt(np.mean(np.square(mono)))) < UMBRAL_SILENCIO if len(mono) else True
                        with self._lock:
                            e["silencio_s"] = e["silencio_s"] + 1 if silencio else 0
                        if e["silencio_s"] >= self.minutos_silencio * 60:
                            self.log.info("Modo clase: %d min en silencio, la doy por terminada", self.minutos_silencio)
                            final_por_silencio = True
                            self._parar.set()
                            break

                        if parte.segundos >= self.segundos_parte:
                            self._entregar(parte, final=False)
                            with self._lock:
                                e["parte"] += 1
                            parte = _Parte(self.entrada, e["clave"], e["parte"])

                        # Si cambias de audífonos a bocinas, seguimos la salida nueva.
                        if time.time() - revisado > 30:
                            revisado = time.time()
                            if sc.default_speaker().id != altavoz.id:
                                self.log.info("Modo clase: cambió la salida de audio, la sigo")
                                break
        except Exception as ex:
            self.ultimo_error = f"Se detuvo la grabación de la clase ({ex})."
            self.log.exception("Modo clase: error grabando")
        finally:
            if final_por_silencio and parte.segundos > 0:
                parte.recortar_silencio_final(self.minutos_silencio * 60)
            self._entregar(parte, final=True)
            self.log.info("Modo clase: terminada (%d partes)", e["parte"])
            if ole32:
                ole32.CoUninitialize()

    def _entregar(self, parte: "_Parte", final: bool) -> None:
        e = self._estado
        ruta = parte.cerrar({"sesion": {"clave": e["clave"], "tipo": "clase", "parte": parte.n,
                                        "final": final, "nombre": e.get("nombre")},
                             "app": "eco-laptop"})
        if ruta:
            self.entregar(ruta)


class _Parte:
    """Un WAV de hasta 10 min que se escribe mientras se graba."""

    def __init__(self, carpeta: Path, clave: str, n: int):
        self.n = n
        self.inicio_ms = int(time.time() * 1000)
        self.ruta = carpeta / f"{clave}_p{n:03d}.wav"
        self.tmp = self.ruta.with_name(self.ruta.name + ".part")
        self.muestras = 0
        self._w = wave.open(str(self.tmp), "wb")
        self._w.setnchannels(1)
        self._w.setsampwidth(2)
        self._w.setframerate(TASA)

    @property
    def segundos(self) -> float:
        return self.muestras / TASA

    def escribir(self, mono) -> None:
        import numpy as np
        datos = (np.clip(mono, -1.0, 1.0) * 32767).astype("<i2")
        self._w.writeframes(datos.tobytes())
        self.muestras += len(datos)

    def recortar_silencio_final(self, segundos: float) -> None:
        """Al terminar por silencio, no mandamos los 20 min de nada al final."""
        self._w.close()
        with wave.open(str(self.tmp), "rb") as r:
            conservar = max(0, r.getnframes() - int(segundos * TASA))
            datos = r.readframes(conservar)
        self._w = wave.open(str(self.tmp), "wb")
        self._w.setnchannels(1)
        self._w.setsampwidth(2)
        self._w.setframerate(TASA)
        self._w.writeframes(datos)
        self.muestras = conservar

    def cerrar(self, meta: dict) -> Path | None:
        self._w.close()
        meta = dict(meta, inicio_ms=self.inicio_ms, duracion_s=round(self.segundos, 2))
        self.ruta.with_suffix(".json").write_text(json.dumps(meta), encoding="utf-8")
        self.tmp.replace(self.ruta)
        return self.ruta
