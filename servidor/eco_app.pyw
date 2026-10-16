"""
Eco · app de la laptop
======================

Corre Eco en segundo plano, sin ventana negra, con un ícono junto al reloj:
  • clic (o doble clic) en el ícono → abre tu panel
  • clic derecho → panel, carpeta de notas, registro, apagar Eco

Windows lo enciende solo al iniciar sesión (lo configura instalar_inicio.bat).
Si lo abres cuando ya está encendido, solo te abre el panel.
"""
import ctypes
import os
import sys
import threading
import webbrowser
from pathlib import Path

BASE = Path(__file__).resolve().parent
sys.path.insert(0, str(BASE))
os.chdir(BASE)

# Sin consola, stdout/stderr no existen; algunas librerías escriben ahí y fallarían.
(BASE / "datos").mkdir(exist_ok=True)
if sys.stdout is None:
    sys.stdout = open(os.devnull, "w", encoding="utf-8")
if sys.stderr is None:
    sys.stderr = open(BASE / "datos" / "errores.log", "a", encoding="utf-8")

import eco_servidor as eco  # noqa: E402

SILENCIOSO = "--inicio" in sys.argv  # al encender Windows no abrimos el navegador


def aviso(texto: str, error: bool = False) -> None:
    """Ventanita de Windows (no hay consola donde mostrar mensajes)."""
    try:
        ctypes.windll.user32.MessageBoxW(None, texto, "Eco", 0x10 if error else 0x40)
    except Exception:
        print(texto)


def main() -> None:
    try:
        servidor = eco.arrancar(consola=False)
    except eco.YaEncendido as ex:
        if not SILENCIOSO:
            webbrowser.open(f"http://localhost:{ex.args[0]}/")
        return
    except eco.ConfigIncompleta as ex:
        aviso(str(ex), error=True)
        return
    except Exception as ex:  # config.toml dañado, puerto bloqueado, etc.
        aviso(f"Eco no pudo encender:\n\n{ex}\n\nRevisa servidor\\datos\\eco.log", error=True)
        raise

    puerto = int(eco.CFG["servidor"]["puerto"])
    panel = f"http://localhost:{puerto}/"
    hilo = threading.Thread(target=servidor.serve_forever, name="servidor", daemon=True)
    hilo.start()
    if not SILENCIOSO:
        webbrowser.open(panel)

    try:
        import pystray
        from PIL import Image
    except ImportError:
        eco.log.warning("Sin ícono junto al reloj (falta pystray/pillow); Eco sigue funcionando.")
        hilo.join()
        return

    def abrir_panel(icon=None, item=None):
        webbrowser.open(panel)

    def abrir_notas(icon=None, item=None):
        os.startfile(eco.NOTAS)

    def abrir_registro(icon=None, item=None):
        os.startfile(eco.DATOS / "eco.log")

    def apagar(icon, item=None):
        eco.log.info("Eco apagado desde el ícono")
        servidor.shutdown()
        icon.stop()

    def texto_estado(item):
        if eco.ESTADO.get("procesando") or eco.COLA.qsize():
            return "Procesando un momento…"
        return "Recibiendo momentos"

    icono = pystray.Icon(
        "Eco",
        Image.open(BASE / "eco.ico"),
        "Eco · recibiendo momentos",
        menu=pystray.Menu(
            pystray.MenuItem(texto_estado, None, enabled=False),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Abrir panel", abrir_panel, default=True),
            pystray.MenuItem("Abrir carpeta de notas", abrir_notas),
            pystray.MenuItem("Ver registro", abrir_registro),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Apagar Eco", apagar),
        ),
    )
    icono.run()


if __name__ == "__main__":
    main()
