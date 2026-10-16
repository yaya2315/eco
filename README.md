# Eco · tu memoria de bolsillo

Eco escucha en tu teléfono y guarda **solo los momentos que tú le pides**. Tu laptop los convierte en notas, tareas y recordatorios.

```
 Honor X7a                        Laptop HP (Ryzen 5)                  Tú
 ┌──────────────────────┐  Wi-Fi  ┌───────────────────────────┐   ┌──────────────────────────┐
 │ Búfer de 3 min en RAM │ ──────▶ │ Whisper  → transcribe      │ ─▶│ Notas .md (Obsidian)      │
 │ "Guardar momento"     │         │ IA       → tareas, datos   │   │ Telegram: avisos,         │
 │ = 3 min antes + 1 después       │ SQLite   → recordatorios   │   │ recordatorios y resumen   │
 └──────────────────────┘         └───────────────────────────┘   └──────────────────────────┘
```

**Modo dashcam:** el micrófono llena un búfer en memoria que se sobrescribe solo. Nada se guarda en disco hasta que tocas **Guardar momento**. En ese momento Eco guarda los últimos 3 minutos y graba 1 minuto más. Las dos duraciones se ajustan desde la app.

Puedes guardar un momento desde tres lugares:

- el botón grande de la app;
- la notificación;
- el **botón rápido** del panel, que funciona incluso con la pantalla bloqueada.

Si lo tocas otra vez mientras guarda, alarga el momento.

---

## Qué hay en el proyecto

| Carpeta | Qué es |
|---|---|
| `android/` | App Android en Kotlin, sin librerías extra. |
| `servidor/` | Servidor en Python para la laptop: Whisper, IA, notas y bot de Telegram. |
| `.github/workflows/apk.yml` | Compila el APK en la nube de GitHub cada vez que haces push. Así no necesitas instalar Android Studio. |

---

## Puesta en marcha (unos 30 minutos)

### 1. Sube el proyecto a GitHub

1. Crea un repositorio **privado** llamado `eco` en github.com. No le agregues README.
2. Abre una terminal en la carpeta `eco` y ejecuta lo siguiente, cambiando `TU_USUARIO` por tu usuario de GitHub:

```bash
git init
git add .
git commit -m "Eco v1: app modo dashcam + servidor de la laptop"
git branch -M main
git remote add origin https://github.com/TU_USUARIO/eco.git
git push -u origin main
```

> El repo tiene que ser privado: incluye la llave con la que se firma tu APK.

### 2. Descarga el APK

1. En GitHub, entra a la pestaña **Actions** y abre **Construir APK de Eco**. Tarda unos 4 minutos.
2. Cuando termine, abajo aparece **Artifacts → Eco-apk**. Descárgalo y descomprímelo.
3. Pasa `app-debug.apk` al teléfono por cable, Drive o mandándotelo por Telegram.

### 3. Instala en el Honor

1. Abre el APK. Si el teléfono lo pide, permite **instalar apps desconocidas** para esa app.
2. Abre Eco y acepta los permisos de **micrófono, notificaciones y ubicación**. La ubicación es opcional.
3. Toca **Batería** dentro de la app y acepta. Luego, en los ajustes del Honor:
   **Ajustes › Batería › Inicio de aplicaciones › Eco** → apaga *Gestionar automáticamente* y deja las tres opciones encendidas.
   Si te saltas este paso, MagicOS va a cerrar Eco a media mañana.
4. Agrega el botón rápido: desliza el panel hacia abajo, toca el **lápiz** (editar) y arrastra **Guardar momento** a tus botones.

### 4. Prepara la laptop

1. Instala **Python 3.12** desde python.org y marca *Add python.exe to PATH*.
2. Dentro de `servidor/`, haz doble clic en **`instalar.bat`**. Instala todo y crea tu `config.toml`.
3. Abre `config.toml` y llena estos campos:
   - `token`: una clave larga que inventes. Es la misma que vas a escribir en la app.
   - La sección `[ia]`: lee el paso 5.
   - La sección `[telegram]`: lee el paso 6.
4. Clic derecho en **`abrir_firewall.bat`** → *Ejecutar como administrador*. Solo hay que hacerlo una vez.
5. Para revisar que todo esté bien, abre una terminal en `servidor/` y ejecuta `iniciar.bat --probar`.
6. Para encender Eco, haz doble clic en **`iniciar.bat`**. Te va a mostrar algo como `Dirección para la app: 192.168.1.50:8765`.
7. En la app del teléfono, escribe esa **dirección** y el **token**, y toca **Probar**.

> **Consejo:** la IP de la laptop cambia cuando cambias de red. Para tener una dirección fija que funcione en cualquier red, sigue el paso 7 (Tailscale).

La primera vez que llegue un audio, Whisper descarga el modelo `small` (unos 500 MB). Después ya no necesita descargar nada.

### 5. Elige la IA

**Opción A: Claude** (recomendada). Entiende mucho mejor el contexto y resuelve bien fechas como "el viernes".
- Crea una API key en la Claude Console (console.anthropic.com) y pégala en `claude_api_key`.
- Se cobra por uso. Con Haiku cada momento cuesta muy poco, pero no es gratis, así que pon un límite de gasto en la consola.
- Ten en cuenta que el **texto** transcrito sale a internet. El audio no.

**Opción B: Ollama** (todo se queda en tu laptop y es gratis).
- Instálalo desde ollama.com y ejecuta `ollama pull qwen2.5:3b`.
- En `config.toml` pon `proveedor = "ollama"`.
- Es más lento en tu Ryzen y algo menos fino, pero nada sale de tu casa.

### 6. Conecta Telegram

1. En Telegram, abre **@BotFather**, escribe `/newbot` y ponle un nombre. Copia el **token** que te da y pégalo en `[telegram] token`.
2. Reinicia Eco y escríbele `/start` a tu bot. Te va a responder con tu **chat_id**.
3. Pega ese número en `[telegram] chat_id` y reinicia Eco otra vez.

### 7. Dirección fija con Tailscale (recomendado)

Tailscale crea una red privada entre tu laptop y tu teléfono. La laptop recibe una dirección `100.x.y.z` que **no cambia nunca**, y funciona aunque estén en redes distintas, incluso con datos móviles.

1. **Laptop:** descarga Tailscale desde tailscale.com/download, instálalo e inicia sesión (con Google, por ejemplo).
2. **Teléfono:** instala Tailscale desde la Play Store, inicia sesión **con la misma cuenta** y actívalo.
3. Vuelve a correr **`abrir_firewall.bat`** como administrador (abre el puerto solo para tus dispositivos de Tailscale).
4. Enciende Eco: la ventana te muestra la dirección fija `100.x.y.z:8765`. Ponla en la app y toca **Probar**.
5. Para que no se desconecte:
   - en el Honor: **Ajustes › Batería › Inicio de aplicaciones › Tailscale**, apaga *Gestionar automáticamente* y deja las tres opciones encendidas;
   - activa **VPN siempre activada** para Tailscale (Ajustes › Más conexiones › VPN › ⚙ junto a Tailscale).

Solo tus dispositivos con tu cuenta pueden entrar a esa red. Eco además sigue pidiendo tu token.

### 8. Eco siempre encendido + tu panel

Haz doble clic en **`instalar_inicio.bat`** y acepta el permiso de administrador. Eso:

- instala lo que necesita la app de Eco;
- hace que Eco **encienda solo** cada vez que inicias sesión en Windows, sin ventana negra, y que se reinicie solo si se cae;
- ajusta la energía para que, **conectada a la corriente**, la laptop no se duerma ni con la tapa cerrada (con batería se comporta como siempre);
- crea el acceso directo **Eco** en el escritorio y en el menú Inicio;
- lo enciende de una vez.

Eco vive junto al reloj (ícono de anillos). Clic → abre tu **panel**; clic derecho → carpeta de notas, registro o **Apagar Eco**.

**El panel** (`http://localhost:8765`) muestra la línea de tu día, cada momento con sus tareas, datos e ideas, tus pendientes (los marcas como hechos ahí mismo), una búsqueda en todo lo guardado y el estado de Eco. También lo puedes abrir desde el celular con Tailscale: `http://100.x.y.z:8765` y tu token.

> Si la laptop está **dormida o apagada**, no puede recibir nada: los momentos esperan en el teléfono y se suben solos cuando la enciendes.

Para quitarlo: **`desinstalar_inicio.bat`**. Tus notas y datos no se tocan.

### 9. Modo clase, modo reunión, preguntas y respaldo

**Modo clase (laptop).** Graba lo que suena en la laptop (Meet, Zoom, Teams), no tu micrófono.
- Empieza desde el panel (**Grabar la clase**, con nombre opcional) o con clic derecho en el ícono › **Grabar la clase**.
- Cada 10 minutos Eco transcribe lo grabado; al terminar te manda por Telegram las tareas, fechas y temas de examen.
- Si pasan 20 minutos en silencio, se termina sola.
- La transcripción completa queda en `notas/transcripciones/`.

**Modo reunión (teléfono).** Botón **Iniciar modo reunión** en la app: graba por horas hasta que tocas **Terminar reunión** (en la app o en la notificación).
- Usa unos 15 MB por hora y sube una parte cada 10 minutos, así que la laptop va adelantando el trabajo.
- Mientras dura la reunión, el botón de momentos se apaga: la reunión ya lo graba todo.

**Pregúntale a Eco.**
- En Telegram, escribe una pregunta con signo de interrogación: *¿Qué dejó de tarea el profe de física?*
- En el panel, cambia a **Preguntarle a Eco** arriba a la derecha.
- Eco busca en todo lo guardado (momentos, clases, reuniones) y te dice de dónde lo sacó.

**Conectar el teléfono con un QR.** Así no escribes la dirección ni el token a mano:
1. En la laptop, abre el panel y toca **Conectar el teléfono con un QR**, o usa clic derecho en el ícono › **Conectar el teléfono (QR)**.
2. En la app, en **Tu laptop**, toca **Escanear código QR** y apunta al código.

La app se queda con la dirección fija de Tailscale (o la del Wi-Fi si no tienes Tailscale) y prueba la conexión sola.
- El código lleva tu token: solo se muestra en la pantalla de la laptop. No le tomes foto.
- El escáner es el de Google Play Services, así que no pide permiso de cámara; la primera vez necesita internet para bajarse.

**Respaldo diario.** Una copia ZIP de tus notas y tu base de datos en `OneDrive\Eco respaldos`; guarda las últimas 14.
- Necesitas tener **OneDrive** iniciado en la laptop.
- En el panel ves cuándo fue la última copia y puedes hacer una al momento con **Respaldar ahora**.
- Tus claves (`config.toml`) no se copian a la nube.

---

## Uso diario

- **Pasa algo importante:** toca *Guardar momento*. Vas a sentir una vibración corta.
- **Subida:** con Tailscale, los momentos se suben solos desde donde estés mientras la laptop esté encendida. Sin Tailscale, se suben cuando el teléfono está en el mismo Wi-Fi que la laptop. También puedes tocar *Subir ahora*.
- **Telegram:**
  - te manda cada momento ya analizado;
  - te avisa **1 hora antes** de lo que tiene hora, y a las **7:00** de lo que solo tiene fecha;
  - te manda un **resumen a las 9 pm**.
- **Comandos del bot:**
  - `/hoy` — resumen de hoy;
  - `/pendientes` — lo que tienes por hacer;
  - `/hecho 12` — marca como hecha la tarea número 12;
  - `/buscar examen` — busca en todo lo guardado.
- **Notas rápidas:** escríbele cualquier cosa al bot, por ejemplo *"examen de física el martes"*, y queda anotado con su fecha.
- **Panel:** el acceso directo **Eco** o el ícono junto al reloj abren tu panel con todo lo guardado.
- **Notas en Markdown:** quedan en `servidor/notas/`, una por día más `Pendientes.md`. Si quieres verlas en Obsidian, apunta `carpeta` en `config.toml` a tu bóveda.

## Actualizar la app

Cambias el código y luego ejecutas:

```bash
git add .
git commit -m "Describe el cambio"
git push
```

Actions vuelve a compilar el APK. Lo instalas encima del anterior y tus ajustes se conservan.

---

## Privacidad: cómo está pensado

- El audio vive **solo en la RAM** del teléfono y se sobrescribe cada pocos minutos.
- Al **pausar** o **apagar** Eco, lo que había en memoria se borra.
- Se crea un archivo **solo cuando tú tocas Guardar**. Ese archivo se borra del teléfono apenas llega a la laptop, y se borra de la laptop apenas se transcribe (`guardar_audio = false`).
- Por defecto se guardan **las notas, no la conversación completa** (`guardar_transcripcion = false`).
- A la IA se le pide anotar de otras personas solo lo necesario para tus tareas, y **nunca** contraseñas, códigos ni tarjetas.
- La laptop solo acepta audios que traen tu token. El bot de Telegram solo le responde a tu `chat_id`.
- Las demás personas no saben que Eco está escuchando. Grabarlas sin que sepan puede ser ilegal y, en el colegio, ir contra las normas. Lo más sano es usarlo para tus propias cosas, avisar cuando guardas algo de una conversación y pausarlo donde no corresponde.

## Problemas comunes

| Pasa esto | Prueba esto |
|---|---|
| "No encuentro la laptop" | El teléfono tiene que estar en el **mismo Wi-Fi**. Revisa que corriste `abrir_firewall.bat` y que la IP no cambió. |
| Eco se detiene solo | Revisa los ajustes de batería del Honor (paso 3.3). Si Android lo cierra, Eco te deja una notificación para reactivarlo. |
| Sale `timed out` al descargar Whisper | Apaga Eco y haz doble clic en **`descargar_modelo.bat`**. Baja el modelo con calma y reintenta solo si se corta. Cuando termine, enciende Eco. Los audios que ya llegaron se procesan solos; si falla el internet, Eco los reintenta cada pocos minutos. |
| La transcripción tarda mucho | En `config.toml` usa `modelo = "base"`. |
| La transcripción sale con errores | Usa `modelo = "medium"` o `beam = 3`. Lleva el teléfono en la bolsa de la camisa, no en la del pantalón. |
| Telegram no responde | Revisa `token` y `chat_id`, y ejecuta `iniciar.bat --probar`. |
| Falla la compilación en Actions | Copia el error del log y pásamelo. |

## Lo que viene

- **v2:** registro de ubicación del día, resumen con mapa y tareas directo a Google Calendar.
- **Siguiente:** tareas de Google Classroom y Google Calendar con la cuenta del colegio.
- **v3:**
  - modo *siempre escuchando* opcional;
