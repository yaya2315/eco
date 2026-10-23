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

Puedes guardar un momento desde cuatro lugares:

- el botón grande de la app;
- el **widget** de la pantalla de inicio;
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
- Instálalo desde ollama.com y ejecuta `ollama pull qwen3.5:4b` (unos 3 GB). Si no lo tienes, Eco usa `qwen2.5:3b`, que entiende bastante menos.
- En `config.toml` pon `proveedor = "ollama"`.
- Es más lento en tu Ryzen que en la nube, pero nada sale de tu casa.

### 6. Conecta Telegram

1. En Telegram, abre **@BotFather**, escribe `/newbot` y ponle un nombre. Copia el **token** que te da y pégalo en `[telegram] token`.
2. Reinicia Eco y escríbele `/start` a tu bot. Te va a responder con tu **chat_id**.
3. Pega ese número en `[telegram] chat_id` y reinicia Eco otra vez.

Más fácil: con el token puesto, en el panel la fila **Telegram** muestra **Conectar Telegram**. Ábrelo, toca **Iniciar** y listo (no hay que copiar el chat_id).

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

**El panel** (`http://localhost:8765`) muestra la línea de tu día, cada momento con sus tareas, datos e ideas, tus pendientes, una búsqueda en todo lo guardado y el estado de Eco. También lo puedes abrir desde el celular con Tailscale: `http://100.x.y.z:8765` y tu token.

**Pendientes que no se borran.** La columna *Pendientes* junta lo que tienes por hacer de **todos** los días, y abajo, en *Para recordar*, lo que dijiste para no olvidar (de tus momentos, notas de voz y fotos; lo de clases y anuncios se queda en su momento). Nada se va al cambiar el día: solo cuando lo marcas. Puedes escribir un pendiente nuevo arriba y, con el ícono del calendario, ponerle fecha y hora a cualquiera; con fecha, va a tu Google Calendar y te llega el aviso por Telegram. Las deudas ("le debo 10 dólares a Steven") cuentan como pendientes.

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
- Tus claves (`config.toml`, Google y GitHub) no se copian a la nube.

**Notas de voz y fotos por Telegram.**
- Mándale una **nota de voz** al bot: Eco la transcribe y la anota igual que un momento. Sirve para dictarte cosas ("mañana llevar la bata").
- Mándale una **foto** de la pizarra, del cuaderno o de la hoja de tareas: Eco lee el texto, saca las fechas y tareas, y guarda la foto en `notas/fotos/`. Si escribes algo junto a la foto, también lo toma en cuenta.
- Con Ollama, leer fotos necesita un modelo de visión. Instálalo una vez en PowerShell:
  ```
  ollama pull qwen2.5vl:3b
  ```
  En tu Ryzen tarda uno o dos minutos por foto. Si falta el modelo, Eco te avisa por Telegram y guarda la foto para leerla después.

**Resumen de la mañana.** A las **6:30** te llega por Telegram lo de hoy, los exámenes y entregas de la semana, lo que se te pasó y lo que viene. Si la laptop estaba dormida, te llega cuando despierte (hasta el mediodía). Lo cambias con `hora_manana` en `config.toml` (`""` lo apaga). En cualquier momento: `/semana`.

**Editar y borrar desde el panel.** Toca el lápiz junto al título de un momento:
- cambia el título, el resumen y la importancia;
- edita, cambia de tipo (tarea, para no olvidar, idea), agrega o quita renglones, y ponle fecha y hora a las tareas: el aviso se recalcula solo;
- **Borrar momento** lo quita con sus tareas, foto y transcripción (pide confirmación).

Atajos: `Ctrl + Enter` guarda, `Esc` cancela.

**Widget.** Mantén presionada la pantalla de inicio › **Widgets** › **Eco**. Muestra si Eco está escuchando y tiene un botón grande de **Guardar momento** (o *Encender* / *Reanudar*, según cómo esté).

**Doble toque a subir volumen.** Con Eco escuchando, exactamente dos toques rápidos a **subir volumen** guardan un momento, aunque la pantalla esté apagada y el teléfono en la bolsa. Sientes la misma vibración corta y el volumen vuelve a donde estaba. Un toque, tres o el botón sostenido suben el volumen como siempre. Para que Android le pase los botones con la pantalla apagada, Eco reproduce silencio absoluto mientras escucha (gasta un poquito más de batería). Si el volumen multimedia está al máximo, Eco lo baja 2 pasos mientras no suena nada y te los devuelve apenas pones música. Mientras suena música o un video, y durante llamadas, se pausa. Se apaga en la app, en el ícono del cronómetro; debajo del interruptor te dice qué vio cuando lo pruebas.

**Alertas de salud.** Cada 5 minutos Eco revisa sus piezas y te avisa por Telegram (una vez, y otra si sigue igual 12 horas después) cuando:
- Ollama no responde o le falta un modelo;
- Google se desconectó o Classroom/Calendar fallan;
- el teléfono lleva 3 horas sin reportarse (salvo que lo hayas apagado tú);
- hay audios sin procesar desde hace más de 2 horas;
- queda poco espacio en la laptop o el respaldo lleva días fallando.

Cuando se arregla, te dice «✅». De 22:00 a 6:30 las alertas esperan a la mañana. Las mismas alertas salen arriba en el panel.

**Whisper más preciso con corriente.** Con la laptop enchufada, Eco transcribe con el modelo `medium` (más preciso, más lento); con batería, o si hay mucho en cola, usa el rápido (`small`). La primera vez baja `medium` solo (~1.5 GB).

**Preguntas por significado.** «Pregúntale a Eco» busca por palabras y también por significado: si preguntas por «energía», encuentra la clase de «trabajo y potencia». Necesita un modelo chico de Ollama (una vez, en PowerShell):
```
ollama pull embeddinggemma
```
En la búsqueda del panel, si hay pocos resultados exactos, salen también los parecidos (con la etiqueta *Parecido*).

**Pausa por batería.** Con **20 %** de batería y sin cargador, Eco deja de escuchar solo y te lo dice en la notificación. Vuelve solo cuando conectas el cargador (o pasa del 25 %). Si lo necesitas igual, toca **Reanudar**. El modo reunión no se corta: si lo iniciaste, sigue grabando.

### 10. Classroom y Google Calendar

Eco trae tus tareas de **Classroom** a tus pendientes (con su fecha, aviso y enlace para abrirlas) y pone tus pendientes con fecha en un calendario **«Eco»** de Google Calendar. Solo lee Classroom; en Calendar solo toca su propio calendario.

**Una sola vez, la llave en Google Cloud (unos 5 minutos):**

1. Entra a **console.cloud.google.com** con tu **Gmail personal** (las cuentas del colegio a veces no dejan crear proyectos). Arriba, **Seleccionar proyecto › Proyecto nuevo** → nombre `Eco` → **Crear**.
2. Menú › **APIs y servicios › Biblioteca**. Busca y **habilita** estas dos:
   - **Google Classroom API**
   - **Google Calendar API**
3. Menú › **Google Auth Platform** (o *Pantalla de consentimiento de OAuth*) › **Comenzar**:
   - nombre de la app: `Eco`; correo de asistencia: tu Gmail;
   - público: **Externo**;
   - correo de contacto: tu Gmail → **Crear**.
4. En **Público**, toca **Publicar app** y confirma. Si la dejas «en prueba», Google te desconecta cada 7 días.
5. En **Clientes**, toca **Crear cliente**:
   - tipo de aplicación: **App de escritorio**; nombre: `Eco laptop`;
   - **Crear** → **Descargar JSON**.

**Conectar:**

1. En la laptop, abre el panel. En **Classroom y Calendar**, arrastra el archivo `.json` que bajaste (o tócalo para elegirlo).
2. Toca **Conectar con Google** y entra con tu **cuenta del colegio**.
3. Si aparece *«Google no verificó esta app»*: **Configuración avanzada › Ir a Eco**. La app es tuya y nadie más la usa.
4. Marca todas las casillas y toca **Continuar**. Te llega un mensaje a Telegram con tus clases y tareas.

A partir de ahí:
- Eco revisa Classroom cada 20 minutos y te avisa por Telegram de cada tarea nueva. Si el profe cambia la fecha, se actualiza; cuando la entregas, se marca como hecha.
- Las tareas de Classroom no se copian al calendario «Eco», porque Classroom ya las pone en tu calendario del colegio.
- **Anuncios:** Eco lee los anuncios de la última semana de cada clase, descarta los saludos y saca las fechas de exámenes y entregas que el profe avisa ahí (también en inglés). Cada anuncio útil queda como un momento «Anuncio de Classroom» y te llega por Telegram. Si al conectar no diste ese permiso, el panel te pide volver a conectar.

> **Si el colegio lo bloquea**, Google dice que tu institución no permite la app, o el panel dice que el colegio no deja leer Classroom. Eso lo decide el administrador del colegio. Puedes conectar tu **Gmail personal** para tener al menos el calendario.

### 11. La app se actualiza sola desde la laptop

Cada vez que GitHub compila una versión nueva de la app, la laptop la baja sola y el teléfono te ofrece **Actualizar**. No tienes que entrar a GitHub ni pasar el APK a mano.

**Una sola vez, un permiso de GitHub de solo lectura:**

1. En github.com, toca tu foto › **Settings › Developer settings › Personal access tokens › Fine-grained tokens › Generate new token**.
2. Llénalo así:
   - nombre: `Eco laptop`; vencimiento: 1 año;
   - **Repository access › Only select repositories** → `eco`;
   - **Permissions › Repository permissions › Actions: Read-only**.
3. **Generate token** y cópialo (empieza con `github_pat_`).
4. En el panel de la laptop, en **Eco en esta laptop › App del teléfono**, pégalo y toca **Guardar**.

**Así funciona:**

1. Haces `git push` con cambios en la app. Actions la compila (unos 4 minutos).
2. En menos de media hora la laptop la baja y te avisa por Telegram.
3. En el teléfono, abre Eco (o toca el aviso) › **Actualizar**. La primera vez Android pide permitir *«instalar desde esta fuente»*: actívalo y vuelve.
4. Confirma **Actualizar**. Si Eco estaba escuchando, te deja un aviso para volver a encenderlo con un toque.

> Esta versión de la app es la que trae el botón **Actualizar**: instálala a mano una última vez, como siempre (paso 2 y 3). Las siguientes llegan solas.

**Si el teléfono no ve la laptop** (otra red, Tailscale apagado), bájala directo de GitHub: en el teléfono abre `github.com/yaya2315/eco/releases/latest`, toca **Eco-1.3.N.apk** e instálala encima. No pierdes tus ajustes ni tus contactos. Cada versión que compila Actions aparece ahí sola.

---

## Uso diario

- **Pasa algo importante:** toca *Guardar momento*. Vas a sentir una vibración corta.
- **Con la voz:** di **«Eco, recuérdame…»** (o «Eco, anota…», «Eco, haz…»). Mira la sección [Activar con la voz](#activar-con-la-voz-eco-recuérdame).
- **Subida:** con Tailscale, los momentos se suben solos desde donde estés mientras la laptop esté encendida. Sin Tailscale, se suben cuando el teléfono está en el mismo Wi-Fi que la laptop. También puedes tocar *Subir ahora*.
- **Telegram:**
  - te manda cada momento ya analizado;
  - a las **6:30** te manda el resumen de la mañana (hoy, exámenes y entregas, lo que se te pasó);
  - te avisa **1 hora antes** de lo que tiene hora, y a las **7:00** de lo que solo tiene fecha (si no salió ya en el resumen de la mañana);
  - te manda un **resumen a las 9 pm**.
- **Comandos del bot:**
  - `/hoy` — resumen de hoy;
  - `/semana` — lo que viene en los próximos 7 días;
  - `/pendientes` — lo que tienes por hacer;
  - `/hecho 12` — marca como hecha la tarea número 12;
  - `/buscar examen` — busca en todo lo guardado.
- **Notas rápidas:** escríbele cualquier cosa al bot, por ejemplo *"examen de física el martes"*, y queda anotado con su fecha. También sirven una **nota de voz** o una **foto** de la pizarra.
- **Panel:** el acceso directo **Eco** o el ícono junto al reloj abren tu panel con todo lo guardado.
- **Notas en Markdown:** quedan en `servidor/notas/`, una por día más `Pendientes.md`. Si quieres verlas en Obsidian, apunta `carpeta` en `config.toml` a tu bóveda.

## Actualizar la app

Cambias el código y luego ejecutas:

```bash
git add .
git commit -m "Describe el cambio"
git push
```

Actions vuelve a compilar el APK. Si ya pegaste el permiso de GitHub en el panel (paso 11), la app del teléfono te ofrece **Actualizar** sola. Si no, lo bajas de Actions y lo instalas encima: tus ajustes se conservan.

---

## Para que entienda mejor

- **Transcripción:** con la laptop enchufada Eco usa Whisper **large-v3-turbo** (casi tan preciso como el más grande y bastante más rápido; se baja solo la primera vez, ~1.6 GB). Usa todos los núcleos, pero con prioridad baja: si tú estás usando la laptop, Windows te da preferencia. Con batería usa `small`.
- **Nombres:** Whisper recibe tu nombre, tus clases y los nombres de personas que Eco ya anotó, para escribir "Steven" y no "Estiven". Puedes agregar más en `config.toml` → `[whisper] palabras = ["…"]`.
- **El toque:** el teléfono le dice a la laptop en qué segundo tocaste *Guardar*. La IA sabe que lo importante está alrededor de ese punto y que lo de mucho antes puede ser la tele u otra conversación, así que ya no mezcla temas.
- **La IA:** si instalaste `qwen3.5:4b`, Eco lo usa sin el modo "pensar" (en tu CPU tardaría minutos). Te lo sugiere por Telegram si falta.
- **Para grabar mejor:** el micrófono principal del Honor está abajo. En la bolsa del pantalón el audio sale apagado; en la bolsa de la camisa, con el micrófono hacia afuera, o sobre la mesa se entiende mucho mejor.

## Activar con la voz: «Eco, recuérdame…»

Sin tocar nada: dices **«Eco»** y una orden, y Eco guarda lo que ya estaba escuchando **más lo que le pides**, hasta que te callas (máximo 30 segundos). Lo que pediste queda como tarea o dato, con fecha y hora si las dices, y por Telegram te llega **«Me pediste: …»**.

- Ejemplos: *«Eco, recuérdame pagarle a Steven el viernes»*, *«Eco, anota que el examen es el martes a las 2»*, *«Eco, recuérdame eso»* (usa lo que se acababa de decir), *«Eco, haz una lista de lo que falta para el robot»*.
- Palabras que lo activan, después de «Eco»: recuérdame / recordame / acordate, anota, apunta, guarda, haz / haceme, pon / poneme, avísame, agenda, escribe, toma (nota), busca.
- Cuando te escucha, el teléfono **vibra dos veces** (el toque de Guardar vibra una).
- **Encenderlo:** en la app, sección del momento (el cronómetro) → **Activar con la voz**. La primera vez el teléfono baja un modelo de voz de tu laptop (unos 40 MB, una sola vez; la laptop lo baja de internet si no lo tiene).
- **Privacidad:** el modelo corre **dentro del teléfono, sin internet**, y solo entiende «Eco + orden»: todo lo demás le suena a ruido. Solo trabaja cuando hay voz cerca, para no gastar batería.
- **Doble revisión:** en la laptop, Whisper confirma que de verdad dijiste «Eco». Si el teléfono se activó por error (nadie dijo «Eco»), ese audio se borra y no se guarda nada.
- Funciona con la pantalla apagada, mientras Eco está escuchando (no en pausa ni en modo reunión).

## Emergencia: «¿Estás bien?»

- En la app, ícono de la **sirena** → agrega hasta 5 contactos y pon tu nombre. Toca **Enviar prueba** para que sepan que los pusiste.
- Si un momento que guardaste (o una nota de voz o de texto) suena a peligro —un asalto, una amenaza, que te siguen, un accidente, alguien pidiendo ayuda—, la laptop te pregunta en el teléfono **«¿Estás bien?»** (y por Telegram).
- **Nunca se manda nada solo.** El mensaje con tu ubicación sale únicamente si, en la pantalla de emergencia, **mantienes presionado el botón rojo 2 segundos**. Si lo sueltas antes, no pasa nada. «Estoy bien» descarta la pregunta.
- **Por WhatsApp, sin tocar nada:** en la sección de la sirena toca **Activar envío automático** y, en Ajustes › Accesibilidad, enciende **«Eco: alerta por WhatsApp»** (una sola vez). Al confirmar una alerta, Eco abre el chat de cada contacto y toca **Enviar** por ti, uno tras otro. Ese servicio solo actúa en la alerta que confirmaste (20 segundos por contacto): no lee tus chats ni hace nada más. Si el teléfono tiene PIN, te lo pide una vez para poder abrir WhatsApp. Si Android dice «ajuste restringido»: Ajustes › Apps › Eco › ⋮ › *Permitir ajustes restringidos*.
- Sin el envío automático, Eco abre cada chat con el mensaje listo y tú tocas Enviar. Los números de El Salvador de 8 dígitos llevan el +503 solo.
- **Sin internet:** Eco lo detecta y manda **SMS** (no necesitan datos, solo señal). Si tampoco hay señal, reintenta cada 30 segundos mientras la pantalla siga abierta. Si mandas por WhatsApp sin internet, WhatsApp lo guarda y lo envía cuando vuelve la conexión.
- **SMS también con internet** (encendido de fábrica): el SMS llega aunque no puedas seguir tocando la pantalla. Lo puedes apagar en la sección de la sirena.
- **Avisar ahora** abre la misma pantalla sin esperar a la laptop. Cada contacto tiene además **Llamar**, y abajo está **Llamar al 911**.
- Cómo decide: primero busca palabras de peligro (sin IA); si encuentra alguna, la IA revisa si es real o es una película, una canción o "ayúdame con la tarea". Lo de clases y reuniones no se revisa.
- Para que llegue rápido, guarda el momento con el doble toque de volumen: la pregunta aparece cuando la laptop termina de analizarlo (un par de minutos). Si estás lejos de tu Wi-Fi, la laptop solo lo recibe con Tailscale.

## Eco para otra persona (por ejemplo, tu abuelo)

Su teléfono usa **tu laptop** como cerebro: no necesita computadora propia. En tu laptop corre un segundo Eco, aparte del tuyo:

- **Su propio puerto** (8766, el tuyo sigue en 8765), su token, sus momentos, sus pendientes y su respaldo (`OneDrive\Eco respaldos\abuelo`). No ve nada tuyo ni tú mezclas lo suyo.
- **Su propio bot de Telegram** para sus recordatorios.
- Guarda momentos, recordatorios y **Emergencia**. Sin Modo clase, sin Classroom ni Calendar.
- Usa la **misma IA y el mismo Whisper** que el tuyo (ya están instalados). Si los dos mandan audio a la vez, se turnan; y si él no manda nada en 20 minutos, su Whisper sale de la memoria para que tu laptop no se ponga lenta.
- Solo funciona mientras tu laptop esté encendida y con tu sesión iniciada. Si estaba apagada, su teléfono guarda los momentos y los sube después.

### En tu laptop (5 minutos)

1. Crea su bot: en Telegram, **@BotFather** → `/newbot`. Copia el token (tiene que ser un bot nuevo, no el tuyo).
2. Doble clic en `servidor\nuevo_perfil.bat` → **Sí** al permiso de administrador. Escribe su nombre y pega el token del bot (clic derecho para pegar).
3. Se abre **su panel** (un acceso directo «Eco de …» queda en el escritorio) con el QR y el enlace para su teléfono.

Se enciende solo con Windows igual que el tuyo. Su configuración queda en `servidor\perfiles\<nombre>\config.toml` (nunca se sube a GitHub).

### Su teléfono (en otra casa)

1. **Tailscale.** Él instala **Tailscale** de Play Store y entra con su cuenta de Google. Tú, en [login.tailscale.com](https://login.tailscale.com) → Machines → los tres puntos de tu laptop → **Share** → **Copy invite link**, y se lo mandas. Él lo abre y acepta. Así su teléfono llega **solo a tu laptop**, no a tu teléfono ni a nada más.
2. **El enlace de Eco.** En su panel → **Conectar el teléfono con un QR** → **Enviar por WhatsApp**. Él abre ese enlace con Tailscale encendido y sigue cuatro botones: **Descargar Eco**, **Conectar Eco**, **Abrir Telegram** y Encender.
3. **Emergencia.** Que te ponga como contacto: si algo le pasa y lo confirma, te llega a ti también.

Si están juntos, es más rápido: escanea el QR de su panel con su teléfono.

La app se le actualiza sola, igual que la tuya: tu Eco baja cada versión de GitHub y la comparte con el suyo. Para quitarlo: Programador de tareas → borra la tarea «Eco (nombre)» y la carpeta `servidor\perfiles\nombre`.

## Privacidad: cómo está pensado

- El audio vive **solo en la RAM** del teléfono y se sobrescribe cada pocos minutos.
- Al **pausar** o **apagar** Eco, lo que había en memoria se borra.
- Se crea un archivo **solo cuando tú tocas Guardar** (o dices «Eco, …»: eso lo reconoce el teléfono sin internet). Ese archivo se borra del teléfono apenas llega a la laptop, y se borra de la laptop apenas se transcribe (`guardar_audio = false`).
- Por defecto se guardan **las notas, no la conversación completa** (`guardar_transcripcion = false`).
- A la IA se le pide anotar de otras personas solo lo necesario para tus tareas, y **nunca** contraseñas, códigos ni tarjetas.
- La laptop solo acepta audios que traen tu token. El bot de Telegram solo le responde a tu `chat_id`.
- Tus contactos de emergencia viven **solo en el teléfono**; la laptop no los conoce. Eco no lee tu agenda: solo el contacto que tú eliges.
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
| Google dice `redirect_uri_mismatch` o «cliente no válido» | El cliente de Google Cloud tiene que ser de tipo **App de escritorio** (paso 10.5), no «Aplicación web». |
| Google te desconecta cada semana | La app quedó «en prueba»: en Google Cloud › **Público** toca **Publicar app** y vuelve a conectar. |
| La app no ofrece Actualizar | Revisa en el panel la fila **App del teléfono**. Si dice que el token no sirve, crea otro (paso 11). La versión nueva tarda hasta media hora en llegar a la laptop. |

## Lo que viene

- **v2:** registro de ubicación del día y resumen con mapa.
- **v3:**
  - modo *siempre escuchando* opcional;
