"""
Eco · modo clase
================

Graba lo que suena en la laptop (Meet, Zoom, Teams, YouTube…) usando la "captura de
reproducción" de Windows (loopback WASAPI). No usa el micrófono: solo el audio de la clase.

Se puede grabar como "clase" (tareas, fechas, temas de examen) o como "reunión" (crónica completa, puntos
clave y acuerdos): es la misma grabación, cambia cómo la analiza Eco al terminar.

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


def _hilo_de_audio() -> None:
    """La grabación no puede esperar: mientras Eco trabaja con prioridad inactiva y en modo de eficiencia (para no
    trabarte la laptop), este hilo sigue con prioridad de tiempo real y a toda velocidad, y el audio no se corta."""
    try:
        import ctypes

        class _Estado(ctypes.Structure):   # THREAD_POWER_THROTTLING_STATE
            _fields_ = [("Version", ctypes.c_uint32), ("ControlMask", ctypes.c_uint32), ("StateMask", ctypes.c_uint32)]

        k32 = ctypes.WinDLL("kernel32")
        k32.GetCurrentThread.restype = ctypes.c_void_p
        k32.SetThreadPriority.argtypes = [ctypes.c_void_p, ctypes.c_int]
        k32.SetThreadInformation.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint32]
        hilo = k32.GetCurrentThread()
        k32.SetThreadPriority(hilo, 15)   # THREAD_PRIORITY_TIME_CRITICAL
        e = _Estado(1, 0x1, 0)            # este hilo, sin modo de eficiencia
        k32.SetThreadInformation(hilo, 3, ctypes.byref(e), ctypes.sizeof(e))   # 3 = ThreadPowerThrottling
    except Exception:
        pass


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
    def iniciar(self, nombre: str | None = None, tipo: str = "clase") -> dict:
        if self.activa():
            return self.estado()
        ahora = datetime.now()
        clave = f"clase_{ahora:%Y%m%d_%H%M%S}"
        with self._lock:
            self._estado = {"clave": clave, "nombre": (nombre or "").strip()[:60] or None,
                            "tipo": "reunion" if tipo == "reunion" else "clase",
                            "inicio_ts": time.time(), "parte": 1, "silencio_s": 0}
        self.ultimo_error = None
        self._parar.clear()
        self._hilo = threading.Thread(target=self._grabar, name="modo-clase", daemon=True)
        self._hilo.start()
        self.log.info("Modo clase: grabando %s%s", "una reunión" if tipo == "reunion" else "una clase",
                      f" «{nombre}»" if nombre else "")
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
        _hilo_de_audio()

        e = self._estado
        parte = self._nueva_parte(1)
        final_por_silencio = False
        fallos = 0  # cortes de audio seguidos (cambio de audífonos, Bluetooth, FxSound…)
        try:
            while not self._parar.is_set():
                try:
                    altavoz = sc.default_speaker()
                    captura = sc.get_microphone(id=str(altavoz.id), include_loopback=True)
                    self.log.info("Modo clase: escuchando «%s»", altavoz.name)
                    revisado = time.time()
                    with captura.recorder(samplerate=TASA, channels=1, blocksize=TASA // 10) as rec:
                        while not self._parar.is_set():
                            bloque = rec.record(numframes=TASA)  # 1 segundo
                            if fallos:
                                self.log.info("Modo clase: el audio volvió, sigo grabando")
                                fallos = 0
                                self.ultimo_error = None
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
                                parte = self._nueva_parte(e["parte"])

                            # Si cambias de audífonos a bocinas, seguimos la salida nueva.
                            if time.time() - revisado > 30:
                                revisado = time.time()
                                if sc.default_speaker().id != altavoz.id:
                                    self.log.info("Modo clase: cambió la salida de audio, la sigo")
                                    break
                except Exception as ex:
                    # Windows reinició o cambió la salida de audio (audífonos Bluetooth, FxSound, una llamada…).
                    # Eso NO termina la reunión: se espera un poco, se vuelve a abrir la salida y se sigue.
                    if self._parar.is_set():
                        break
                    fallos += 1
                    espera = min(5, 1 + fallos)
                    self.ultimo_error = f"El audio se interrumpió ({ex}); sigo intentando."
                    if fallos <= 3 or fallos % 20 == 0:
                        self.log.warning("Modo clase: el audio se cortó (%s); reintento %d en %d s", ex, fallos, espera)
                    with self._lock:  # un audio que nunca vuelve cuenta como silencio: no queda grabando para siempre
                        e["silencio_s"] += espera
                    if e["silencio_s"] >= self.minutos_silencio * 60:
                        self.log.info("Modo clase: el audio no volvió en %d min, la doy por terminada", self.minutos_silencio)
                        self._parar.set()
                        break
                    self._parar.wait(espera)
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

    def _nueva_parte(self, n: int) -> "_Parte":
        e = self._estado
        return _Parte(self.entrada, e["clave"], n, {"sesion": {
            "clave": e["clave"], "tipo": e.get("tipo", "clase"), "parte": n, "final": True, "nombre": e.get("nombre")},
            "app": "eco-laptop"})

    def _entregar(self, parte: "_Parte", final: bool) -> None:
        e = self._estado
        ruta = parte.cerrar({"sesion": {"clave": e["clave"], "tipo": e.get("tipo", "clase"), "parte": parte.n,
                                        "final": final, "nombre": e.get("nombre")},
                             "app": "eco-laptop"})
        if ruta:
            self.entregar(ruta)


class _Parte:
    """Un WAV de hasta 10 min que se escribe mientras se graba."""

    def __init__(self, carpeta: Path, clave: str, n: int, pista: dict | None = None):
        self.n = n
        self.inicio_ms = int(time.time() * 1000)
        self.ruta = carpeta / f"{clave}_p{n:03d}.wav"
        self.tmp = self.ruta.with_name(self.ruta.name + ".part")
        self.pista = self.tmp.with_name(self.tmp.name + ".json")   # de quién es esta parte, por si Eco muere a medias
        self.muestras = 0
        self._ultimo_volcado = time.time()
        self._w = wave.open(str(self.tmp), "wb")
        self._w.setnchannels(1)
        self._w.setsampwidth(2)
        self._w.setframerate(TASA)
        if pista:
            try:
                self.pista.write_text(json.dumps(dict(pista, inicio_ms=self.inicio_ms)), encoding="utf-8")
            except OSError:
                pass

    @property
    def segundos(self) -> float:
        return self.muestras / TASA

    def escribir(self, mono) -> None:
        import numpy as np
        datos = (np.clip(mono, -1.0, 1.0) * 32767).astype("<i2")
        self._w.writeframes(datos.tobytes())
        self.muestras += len(datos)
        if time.time() - self._ultimo_volcado >= 10:  # al disco cada 10 s: si Windows se reinicia, se pierde poco
            self._ultimo_volcado = time.time()
            try:
                self._w._file.flush()
            except Exception:
                pass

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
        try:
            self.pista.unlink()
        except OSError:
            pass
        return self.ruta


def recuperar_huerfanas(entrada: Path, log) -> list[Path]:
    """Si Eco o Windows murieron a media grabación, queda un «.wav.part» sin cerrar. Al volver a abrir Eco se
    convierte en un audio normal (con lo que alcanzó a grabarse) y entra a la cola como cualquier parte."""
    rescatadas: list[Path] = []
    for tmp in sorted(entrada.glob("*.wav.part")):
        pista = tmp.with_name(tmp.name + ".json")
        try:
            datos = tmp.read_bytes()[44:]
            muestras = len(datos) // 2
            if muestras < 3 * TASA:  # menos de 3 s no sirven
                tmp.unlink(missing_ok=True)
                pista.unlink(missing_ok=True)
                continue
            clave, _, resto = tmp.name[: -len(".wav.part")].rpartition("_p")
            meta = {}
            try:
                meta = json.loads(pista.read_text(encoding="utf-8")) if pista.exists() else {}
            except (OSError, ValueError):
                pass
            ses = dict(meta.get("sesion") or {})
            if not ses:  # sin pista (grabaciones de antes): se toma lo de otra parte de la misma sesión
                for hermana in sorted(entrada.glob(f"{clave}_p*.json")):
                    try:
                        ses = dict(json.loads(hermana.read_text(encoding="utf-8")).get("sesion") or {})
                        break
                    except (OSError, ValueError):
                        continue
            ses.update(clave=clave, parte=int(resto or 1), final=True)   # lo último que se grabó: cierra la sesión
            ses.setdefault("tipo", "clase")
            duracion = muestras / TASA
            inicio = int(meta.get("inicio_ms") or (tmp.stat().st_mtime * 1000 - duracion * 1000))
            destino = tmp.with_name(tmp.name[: -len(".part")])
            temporal = destino.with_name(destino.name + ".rescate")
            with wave.open(str(temporal), "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(TASA)
                w.writeframes(datos[: muestras * 2])
            destino.with_suffix(".json").write_text(json.dumps({
                "sesion": ses, "app": "eco-laptop", "inicio_ms": inicio, "duracion_s": round(duracion, 2),
                "rescatada": True}), encoding="utf-8")
            temporal.replace(destino)   # el .wav aparece al final: Eco nunca ve un audio a medias
            tmp.unlink(missing_ok=True)
            pista.unlink(missing_ok=True)
            rescatadas.append(destino)
            log.info("Rescaté %s: %.0f s de una grabación que se cortó", destino.name, duracion)
        except Exception as ex:
            log.warning("No pude rescatar %s (%s)", tmp.name, ex)
    return rescatadas
