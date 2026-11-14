package com.blackholecode.eco

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

/** Un movimiento: de la copia que mandó la laptop, o anotado aquí y todavía sin subir ([pendiente]). */
internal class Mov(
    val id: Long, val uid: String, val cuenta: Long, val tipo: String, val monto: Long, val categoria: String,
    val nota: String, val fecha: String, val origen: String, val meta: Long, val pendiente: Boolean, val orden: Long,
)

/** Una categoría: su clave, cómo se llama y su color. */
internal class Cat(val clave: String, val nombre: String, val color: String)

/**
 * Las finanzas guardadas en el teléfono, para que la app no dependa de la laptop:
 *  - una copia de todo lo que tiene la laptop (archivo finanzas.json), que se baja cuando hay conexión;
 *  - lo que anotas o borras aquí, que espera en una cola y se sube solo cuando la laptop responde.
 * La pantalla siempre se pinta con [vista] (la copia + la cola), así que anotar y mirar nunca esperan a la red.
 * [sincronizar] corre en segundo plano y hace las dos cosas: sube lo de aquí y baja lo de allá.
 */
internal object FinLocal {
    /** Cómo terminó la última sincronización. */
    const val AL_DIA = 0
    const val SIN_CONEXION = 1
    const val SIN_LAPTOP = 2      // todavía no has conectado la app con tu laptop
    const val TOKEN = 3
    const val VIEJA = 4           // la laptop tiene un Eco sin esta función
    const val APAGADO = 5         // finanzas apagadas en config.toml

    class Sync(val estado: Int, val cambio: Boolean, val avisos: List<String>)

    private const val ARCHIVO = "finanzas.json"
    private const val MAXIMO = 10_000_000_000L   // el mismo tope que pone la laptop, en centavos
    private val PERIODOS = listOf("dia", "semana", "mes", "anio")
    private val DIAS = arrayOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
    private val MESES = arrayOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
        "agosto", "septiembre", "octubre", "noviembre", "diciembre"
    )

    /** Las mismas categorías que la laptop (FIN_CATEGORIAS), para poder anotar antes de la primera conexión. */
    private val BASE: Map<String, List<Cat>> = mapOf(
        "gasto" to listOf(
            Cat("comida", "Comida y súper", "#C98500"), Cat("restaurantes", "Comer fuera", "#D95926"),
            Cat("transporte", "Transporte", "#3987E5"), Cat("educacion", "Educación", "#9085E9"),
            Cat("salud", "Salud", "#199E70"), Cat("trabajo", "Trabajo y herramientas", "#008300"),
            Cat("tecnologia", "Tecnología", "#D55181"), Cat("suscripciones", "Suscripciones", "#E66767"),
            Cat("hogar", "Casa y servicios", "#3987E5"), Cat("ropa", "Ropa y cuidado", "#C98500"),
            Cat("ocio", "Ocio y salidas", "#D55181"), Cat("regalos", "Regalos y familia", "#E66767"),
            Cat("recargas", "Saldo y recargas", "#199E70"), Cat("deudas", "Deudas y préstamos", "#9085E9"),
            Cat("publicidad", "Publicidad", "#D95926"), Cat("impuestos", "Impuestos y trámites", "#008300"),
            Cat("otros", "Otros", "#8C8CA6"),
        ),
        "ingreso" to listOf(
            Cat("clientes", "Clientes y proyectos", "#199E70"), Cat("sueldo", "Sueldo", "#3987E5"),
            Cat("mesada", "Mesada y familia", "#9085E9"), Cat("ventas", "Ventas", "#C98500"),
            Cat("regalo", "Regalos", "#D55181"), Cat("reembolso", "Reembolsos y cobros", "#D95926"),
            Cat("otros_ingresos", "Otros ingresos", "#8C8CA6"),
        ),
    )
    private val AHORRO = Cat("ahorro", "Ahorro", "#199E70")

    private val red = Any()       // una sola sincronización a la vez
    private val disco = Any()     // un solo hilo escribe el archivo
    @Volatile private var copia: JSONObject? = null
    @Volatile private var leida = false
    @Volatile private var movsCopia: List<Mov> = emptyList()
    @Volatile private var tabla: Map<String, Cat> = tablaDe(null)

    // ───────────────────────── La copia de la laptop ─────────────────────────

    @Synchronized
    private fun leerCopia(c: Context): JSONObject? {
        if (!leida) {
            val j = try {
                val f = File(c.filesDir, ARCHIVO)
                if (f.exists()) JSONObject(f.readText(Charsets.UTF_8)) else null
            } catch (_: Exception) {
                null
            }
            enMemoria(j)
        }
        return copia
    }

    @Synchronized
    private fun enMemoria(j: JSONObject?) {
        copia = j
        movsCopia = movsDe(j)
        tabla = tablaDe(j)
        leida = true
    }

    /** Escribe la copia en el archivo (primero en uno temporal: si la app muere a medias, no se daña). */
    private fun guardar(c: Context) {
        synchronized(disco) {
            val contenido = synchronized(this) { copia?.toString() } ?: return
            try {
                val destino = File(c.filesDir, ARCHIVO)
                val temporal = File(c.filesDir, "$ARCHIVO.tmp")
                temporal.writeText(contenido, Charsets.UTF_8)
                if (!temporal.renameTo(destino)) {
                    destino.delete()
                    temporal.renameTo(destino)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun texto(j: JSONObject, clave: String): String = if (j.isNull(clave)) "" else j.optString(clave)

    private fun movsDe(j: JSONObject?): List<Mov> {
        val lista = j?.optJSONArray("movimientos") ?: return emptyList()
        val salida = ArrayList<Mov>(lista.length())
        for (i in 0 until lista.length()) {
            val m = lista.optJSONObject(i) ?: continue
            val id = m.optLong("id")
            salida.add(
                Mov(
                    id, texto(m, "uid"), m.optLong("cuenta"), m.optString("tipo"), m.optLong("monto"),
                    m.optString("categoria"), texto(m, "nota"), m.optString("fecha"), texto(m, "origen"),
                    m.optLong("meta"), false, id
                )
            )
        }
        return salida
    }

    private fun listaDe(j: JSONObject?, tipo: String): List<Cat> {
        val arr = j?.optJSONObject("categorias")?.optJSONArray(tipo)
        if (arr != null) {
            val salida = ArrayList<Cat>(arr.length())
            for (i in 0 until arr.length()) {
                val x = arr.optJSONObject(i) ?: continue
                salida.add(Cat(x.optString("clave"), x.optString("nombre"), x.optString("color")))
            }
            if (salida.isNotEmpty()) return salida
        }
        return BASE[tipo] ?: emptyList()
    }

    private fun tablaDe(j: JSONObject?): Map<String, Cat> {
        val t = HashMap<String, Cat>()
        t[AHORRO.clave] = AHORRO
        for (tipo in listOf("gasto", "ingreso")) {
            for (x in listaDe(j, tipo)) t[x.clave] = x
        }
        return t
    }

    private fun cat(clave: String): Cat = tabla[clave] ?: tabla["otros"] ?: Cat(clave, "Otros", "#8C8CA6")

    // ───────────────────────── Lo que la pantalla pregunta ─────────────────────────

    /** Tus cuentas. Antes de la primera conexión hay una sola, «Personal» (la de siempre en la laptop). */
    fun cuentas(c: Context): JSONArray {
        val arr = leerCopia(c)?.optJSONArray("cuentas")
        if (arr != null && arr.length() > 0) return arr
        return JSONArray().put(JSONObject().put("id", 0L).put("nombre", "Personal").put("tipo", "personal"))
    }

    /** La cuenta con ese id; si no existe, la de siempre (la que elegiste en la laptop, o la primera). */
    fun cuentaReal(c: Context, pedida: Long): Long {
        val lista = cuentas(c)
        val defecto = leerCopia(c)?.optLong("cuenta_defecto") ?: 0L
        var primera = 0L
        var hayPedida = false
        var hayDefecto = false
        for (i in 0 until lista.length()) {
            val id = lista.optJSONObject(i)?.optLong("id") ?: continue
            if (i == 0) primera = id
            if (id == pedida) hayPedida = true
            if (id == defecto) hayDefecto = true
        }
        return if (hayPedida) pedida else if (hayDefecto) defecto else primera
    }

    fun moneda(c: Context, sp: SharedPreferences): String {
        val m = leerCopia(c)?.optString("moneda") ?: ""
        return if (m.isNotBlank()) m else sp.getString("moneda", "$") ?: "$"
    }

    /** Las categorías de gasto e ingreso, primero las que más usas en esa cuenta. */
    fun categorias(c: Context, sp: SharedPreferences, cuenta: Long): JSONObject {
        val guardada = leerCopia(c)
        val cid = cuentaReal(c, cuenta)
        return categoriasJson(guardada, todos(c, sp).filter { it.cuenta == cid }, LocalDate.now())
    }

    fun montoValido(centavos: Long): Boolean = centavos in 1L..MAXIMO

    // ───────────────────────── Lo anotado aquí: espera en el teléfono ─────────────────────────

    private fun lista(sp: SharedPreferences, clave: String): JSONArray =
        try { JSONArray(sp.getString(clave, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }

    /** Cuántos cambios hechos aquí faltan por subir a la laptop. */
    fun pendientes(sp: SharedPreferences): Int = lista(sp, "cola").length() + lista(sp, "borrados").length()

    @Synchronized
    private fun encolar(sp: SharedPreferences, item: JSONObject) {
        val cola = lista(sp, "cola")
        cola.put(item)
        sp.edit().putString("cola", cola.toString()).apply()
    }

    /** Saca de la cola lo que lleva ese uid. Devuelve si estaba. */
    @Synchronized
    private fun quitarDeCola(sp: SharedPreferences, uid: String): Boolean {
        val cola = lista(sp, "cola")
        val resto = JSONArray()
        var estaba = false
        for (i in 0 until cola.length()) {
            val x = cola.optJSONObject(i) ?: continue
            if (x.optString("uid") == uid) estaba = true else resto.put(x)
        }
        if (estaba) sp.edit().putString("cola", resto.toString()).apply()
        return estaba
    }

    @Synchronized
    private fun quitarBorrado(sp: SharedPreferences, id: Long) {
        val borrados = lista(sp, "borrados")
        val resto = JSONArray()
        for (i in 0 until borrados.length()) {
            if (borrados.optLong(i) != id) resto.put(borrados.optLong(i))
        }
        sp.edit().putString("borrados", resto.toString()).apply()
    }

    /** Anota un gasto o un ingreso. Queda guardado en el teléfono al instante; a la laptop sube después. */
    fun anotar(sp: SharedPreferences, tipo: String, centavos: Long, categoria: String, nota: String, fecha: String, cuenta: Long) {
        encolar(
            sp,
            JSONObject().put("uid", UUID.randomUUID().toString()).put("accion", "movimiento").put("tipo", tipo)
                .put("centavos", centavos).put("categoria", categoria).put("nota", nota).put("fecha", fecha)
                .put("cuenta", cuenta)
        )
    }

    /** Aparta dinero para una meta de ahorro (también funciona sin conexión). */
    fun apartar(sp: SharedPreferences, meta: Long, nombre: String, cuenta: Long, centavos: Long) {
        encolar(
            sp,
            JSONObject().put("uid", UUID.randomUUID().toString()).put("accion", "meta").put("tipo", "ahorro")
                .put("centavos", centavos).put("categoria", "ahorro").put("nota", nombre)
                .put("fecha", LocalDate.now().toString()).put("cuenta", cuenta).put("meta", meta)
        )
    }

    /** Borra un movimiento: si aún no había subido, solo sale de la cola; si ya está en la laptop, se borra allá después. */
    fun borrar(c: Context, sp: SharedPreferences, id: Long, uid: String, pendiente: Boolean) {
        var real = id
        if (pendiente || id <= 0L) {
            if (uid.isEmpty() || quitarDeCola(sp, uid)) return
            // Subió justo mientras lo mirabas: ya está en la copia, con su número de la laptop.
            leerCopia(c)
            real = movsCopia.firstOrNull { it.uid == uid }?.id ?: return
        }
        if (real <= 0L) return
        synchronized(this) {
            val borrados = lista(sp, "borrados")
            borrados.put(real)
            sp.edit().putString("borrados", borrados.toString()).apply()
            val j = leerCopia(c)
            val movs = j?.optJSONArray("movimientos")
            if (j != null && movs != null) {
                val resto = JSONArray()
                for (i in 0 until movs.length()) {
                    val m = movs.optJSONObject(i) ?: continue
                    if (m.optLong("id") != real) resto.put(m)
                }
                j.put("movimientos", resto)
                enMemoria(j)
            }
        }
        guardar(c)
    }

    private fun centavosDe(x: JSONObject): Long {
        if (x.has("centavos")) return x.optLong("centavos")
        // Lo que anotó una versión anterior de la app traía el monto escrito ("3.50").
        val v = x.optString("monto").replace(',', '.').toDoubleOrNull() ?: return 0L
        return Math.round(v * 100.0)
    }

    /** Todos los movimientos: los de la copia (menos los que borraste aquí) y los que esperan en la cola. */
    private fun todos(c: Context, sp: SharedPreferences): List<Mov> {
        leerCopia(c)
        val base = movsCopia
        val cola = lista(sp, "cola")
        val quitados = lista(sp, "borrados")
        if (cola.length() == 0 && quitados.length() == 0) return base
        val borrados = HashSet<Long>()
        for (i in 0 until quitados.length()) borrados.add(quitados.optLong(i))
        val uids = HashSet<String>()
        val salida = ArrayList<Mov>(base.size + cola.length())
        for (m in base) {
            if (m.id in borrados) continue
            if (m.uid.isNotEmpty()) uids.add(m.uid)
            salida.add(m)
        }
        for (i in 0 until cola.length()) {
            val x = cola.optJSONObject(i) ?: continue
            val uid = x.optString("uid")
            if (uid in uids) continue   // ya subió: está en la copia
            val esMeta = x.optString("accion") == "meta"
            salida.add(
                Mov(
                    0L, uid, cuentaReal(c, x.optLong("cuenta")), if (esMeta) "ahorro" else x.optString("tipo"), centavosDe(x),
                    if (esMeta) "ahorro" else x.optString("categoria"), texto(x, "nota"), x.optString("fecha"), "app",
                    x.optLong("meta"), true, Long.MAX_VALUE / 2 + i
                )
            )
        }
        return salida
    }

    // ───────────────────────── Los números de la pantalla ─────────────────────────

    private class Totales {
        var ingreso = 0L
        var gasto = 0L
        var ahorro = 0L
        val queda: Long get() = ingreso - gasto - ahorro
        fun json(): JSONObject = JSONObject().put("ingreso", ingreso).put("gasto", gasto).put("ahorro", ahorro).put("queda", queda)
    }

    private class Periodo(val desde: LocalDate, val hasta: LocalDate, val titulo: String)

    private fun fecha(iso: String?): LocalDate? =
        try { if (iso.isNullOrEmpty()) null else LocalDate.parse(iso.take(10)) } catch (_: Exception) { null }

    private fun finDeMes(d: LocalDate): LocalDate = d.withDayOfMonth(d.lengthOfMonth())

    private fun lunesDe(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

    /** Primer y último día del periodo que contiene a [ref], y cómo se llama. */
    private fun periodo(tipo: String, ref: LocalDate): Periodo = when (tipo) {
        "dia" -> Periodo(
            ref, ref,
            "${DIAS[ref.dayOfWeek.value - 1]} ${ref.dayOfMonth} de ${MESES[ref.monthValue - 1]} de ${ref.year}"
                .replaceFirstChar { it.uppercase() }
        )
        "semana" -> {
            val lunes = lunesDe(ref)
            val dom = lunes.plusDays(6)
            val titulo = if (lunes.monthValue == dom.monthValue) {
                "Semana del ${lunes.dayOfMonth} al ${dom.dayOfMonth} de ${MESES[dom.monthValue - 1]}"
            } else {
                "Semana del ${lunes.dayOfMonth} de ${Fin.MESES[lunes.monthValue - 1]} al ${dom.dayOfMonth} de ${Fin.MESES[dom.monthValue - 1]}"
            }
            Periodo(lunes, dom, titulo)
        }
        "anio" -> Periodo(LocalDate.of(ref.year, 1, 1), LocalDate.of(ref.year, 12, 31), ref.year.toString())
        else -> Periodo(
            ref.withDayOfMonth(1), finDeMes(ref),
            MESES[ref.monthValue - 1].replaceFirstChar { it.uppercase() } + " de ${ref.year}"
        )
    }

    private fun mover(tipo: String, ref: LocalDate, n: Int): LocalDate = try {
        when (tipo) {
            "dia" -> ref.plusDays(n.toLong())
            "semana" -> ref.plusDays(7L * n)
            "anio" -> LocalDate.of((ref.year + n).coerceIn(1, 9999), 1, 1)
            else -> ref.withDayOfMonth(1).plusMonths(n.toLong())
        }
    } catch (_: Exception) {
        ref
    }

    private fun totales(movs: List<Mov>, desde: String, hasta: String): Totales {
        val t = Totales()
        for (m in movs) {
            if (m.fecha < desde || m.fecha > hasta) continue
            when (m.tipo) {
                "ingreso" -> t.ingreso += m.monto
                "gasto" -> t.gasto += m.monto
                "ahorro" -> t.ahorro += m.monto
            }
        }
        return t
    }

    private fun porCategoria(movs: List<Mov>, desde: String, hasta: String, tipo: String): JSONArray {
        val sumas = LinkedHashMap<String, Long>()
        var total = 0L
        for (m in movs) {
            if (m.tipo != tipo || m.fecha < desde || m.fecha > hasta) continue
            sumas[m.categoria] = (sumas[m.categoria] ?: 0L) + m.monto
            total += m.monto
        }
        val salida = JSONArray()
        for (x in sumas.entries.sortedByDescending { it.value }) {
            val k = cat(x.key)
            salida.put(
                JSONObject().put("clave", x.key).put("nombre", k.nombre).put("color", k.color).put("total", x.value)
                    .put("pct", if (total > 0L) (x.value * 100.0 / total).roundToInt() else 0)
            )
        }
        return salida
    }

    /** Las barras: últimos 7 días, últimas 6 semanas, últimos 6 meses o los 12 meses del año. */
    private fun serie(movs: List<Mov>, tipo: String, ref: LocalDate): JSONArray {
        val tramos = ArrayList<Periodo>()
        when (tipo) {
            "dia" -> for (i in 6 downTo 0) {
                val d = ref.minusDays(i.toLong())
                tramos.add(Periodo(d, d, DIAS[d.dayOfWeek.value - 1].take(3)))
            }
            "semana" -> {
                val lunes = lunesDe(ref)
                for (i in 5 downTo 0) {
                    val ini = lunes.minusDays(7L * i)
                    tramos.add(Periodo(ini, ini.plusDays(6), "${ini.dayOfMonth}/${ini.monthValue}"))
                }
            }
            "anio" -> for (mes in 1..12) {
                val ini = LocalDate.of(ref.year, mes, 1)
                tramos.add(Periodo(ini, finDeMes(ini), Fin.MESES[mes - 1]))
            }
            else -> for (i in 5 downTo 0) {
                val ini = ref.withDayOfMonth(1).minusMonths(i.toLong())
                tramos.add(Periodo(ini, finDeMes(ini), Fin.MESES[ini.monthValue - 1]))
            }
        }
        val salida = JSONArray()
        for (p in tramos) {
            val t = totales(movs, p.desde.toString(), p.hasta.toString())
            salida.put(
                JSONObject().put("etiqueta", p.titulo).put("gasto", t.gasto).put("ingreso", t.ingreso)
                    .put("actual", !ref.isBefore(p.desde) && !ref.isAfter(p.hasta))
            )
        }
        return salida
    }

    /** Los presupuestos son por mes: cuánto llevas gastado en cada categoría en el mes de [ref]. */
    private fun presupuestos(copia: JSONObject?, movs: List<Mov>, cid: Long, ref: LocalDate): JSONArray {
        val salida = JSONArray()
        val arr = copia?.optJSONArray("presupuestos") ?: return salida
        val ini = ref.withDayOfMonth(1).toString()
        val fin = finDeMes(ref).toString()
        val gastado = HashMap<String, Long>()
        for (m in movs) {
            if (m.tipo == "gasto" && m.fecha >= ini && m.fecha <= fin) gastado[m.categoria] = (gastado[m.categoria] ?: 0L) + m.monto
        }
        val mios = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            if (p.optLong("cuenta") == cid) mios.add(p)
        }
        mios.sortByDescending { it.optLong("monto") }
        for (p in mios) {
            val clave = p.optString("categoria")
            val k = cat(clave)
            val limite = p.optLong("monto")
            val llevas = gastado[clave] ?: 0L
            salida.put(
                JSONObject().put("categoria", clave).put("nombre", k.nombre).put("color", k.color)
                    .put("limite", limite).put("gastado", llevas).put("resta", limite - llevas)
            )
        }
        return salida
    }

    private fun metas(copia: JSONObject?, todos: List<Mov>, cid: Long, hoy: LocalDate): JSONArray {
        val salida = JSONArray()
        val arr = copia?.optJSONArray("metas") ?: return salida
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            if (m.optLong("cuenta") != cid) continue
            val id = m.optLong("id")
            val objetivo = m.optLong("objetivo")
            var ahorrado = 0L
            for (x in todos) {
                if (x.meta == id && x.tipo == "ahorro") ahorrado += x.monto
            }
            val falta = maxOf(0L, objetivo - ahorrado)
            val limite = fecha(texto(m, "fecha"))
            var dias: Int? = null
            var porSemana: Long? = null
            if (limite != null) {
                val d = ChronoUnit.DAYS.between(hoy, limite).toInt()
                dias = d
                if (falta > 0L && d >= 0) {
                    val semanas = maxOf(1, (d + 6) / 7)
                    porSemana = (falta + semanas - 1) / semanas
                }
            }
            val j = JSONObject().put("id", id).put("nombre", m.optString("nombre")).put("objetivo", objetivo)
                .put("ahorrado", ahorrado).put("falta", falta)
                .put("pct", if (objetivo > 0L) minOf(100, (ahorrado * 100.0 / objetivo).roundToInt()) else 0)
            if (limite == null) j.put("fecha", JSONObject.NULL) else j.put("fecha", limite.toString())
            if (dias == null) j.put("dias", JSONObject.NULL) else j.put("dias", dias)
            if (porSemana == null) j.put("por_semana", JSONObject.NULL) else j.put("por_semana", porSemana)
            salida.put(j)
        }
        return salida
    }

    private fun fijos(copia: JSONObject?, cid: Long, hoy: LocalDate): JSONArray {
        val salida = JSONArray()
        val arr = copia?.optJSONArray("fijos") ?: return salida
        val mes = hoy.toString().take(7)
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            if (f.optLong("cuenta") != cid) continue
            val k = cat(f.optString("categoria"))
            val dia = f.optInt("dia")
            val vence = hoy.withDayOfMonth(dia.coerceIn(1, hoy.lengthOfMonth()))   // el 31 en febrero es el 28
            salida.put(
                JSONObject().put("id", f.optLong("id")).put("tipo", f.optString("tipo")).put("monto", f.optLong("monto"))
                    .put("categoria", f.optString("categoria")).put("nombre", k.nombre).put("color", k.color)
                    .put("nota", texto(f, "nota")).put("dia", dia).put("listo", texto(f, "registrado") == mes)
                    .put("vence", vence.toString()).put("en_dias", ChronoUnit.DAYS.between(hoy, vence).toInt())
            )
        }
        return salida
    }

    /** La ayuda para ahorrar: cuánto puedes gastar por día y dos o tres observaciones de este mes. */
    private fun consejo(movs: List<Mov>, hoy: LocalDate, metas: JSONArray, fijos: JSONArray, moneda: String, pct: Int): JSONObject {
        val ini = hoy.withDayOfMonth(1)
        val fin = finDeMes(hoy)
        val t = totales(movs, ini.toString(), fin.toString())
        var porPagar = 0L
        for (i in 0 until fijos.length()) {
            val f = fijos.optJSONObject(i) ?: continue
            if (f.optString("tipo") == "gasto" && !f.optBoolean("listo")) porPagar += f.optLong("monto")
        }
        val dias = ChronoUnit.DAYS.between(hoy, fin).toInt() + 1
        val libre = t.queda - porPagar
        val diario = maxOf(0L, libre / dias)
        val textos = JSONArray()
        val salida = JSONObject().put("dias", dias).put("por_pagar", porPagar).put("libre", libre)
        if (t.ingreso == 0L && t.gasto == 0L && t.ahorro == 0L) {
            textos.put("Anota lo que entra y lo que sale este mes. Con una semana de datos ya te puedo aconsejar.")
            return salida.put("diario", 0L).put("textos", textos)
        }
        if (t.ingreso > 0L && libre < 0L) {
            textos.put(
                "Vas ${Fin.dinero(-libre, moneda)} por encima de lo que entró este mes" +
                    (if (porPagar > 0L) " contando tus pagos fijos" else "") + ". Frena lo que no sea necesario."
            )
        } else if (t.ingreso > 0L) {
            textos.put(
                "Puedes gastar hasta ${Fin.dinero(diario, moneda)} por día y llegar bien a fin de mes" +
                    (if (porPagar > 0L) " (ya aparté ${Fin.dinero(porPagar, moneda)} de pagos fijos)." else ".")
            )
        }
        val mesPasado = ini.minusMonths(1)
        val antes = HashMap<String, Long>()
        val pasado = porCategoria(movs, mesPasado.toString(), finDeMes(mesPasado).toString(), "gasto")
        for (i in 0 until pasado.length()) {
            val x = pasado.optJSONObject(i) ?: continue
            antes[x.optString("clave")] = x.optLong("total")
        }
        val ahora = porCategoria(movs, ini.toString(), fin.toString(), "gasto")
        var sube: JSONObject? = null
        var extra = 0L
        for (i in 0 until ahora.length()) {
            val x = ahora.optJSONObject(i) ?: continue
            val previo = antes[x.optString("clave")] ?: 0L
            val total = x.optLong("total")
            if (previo > 0L && total * 10L > previo * 12L && total - previo >= 500L && total - previo > extra) {
                sube = x
                extra = total - previo
            }
        }
        val mayor = ahora.optJSONObject(0)
        if (sube != null) {
            textos.put(
                "En ${sube.optString("nombre")} llevas ${Fin.dinero(sube.optLong("total"), moneda)}: " +
                    "${Fin.dinero(extra, moneda)} más que todo el mes pasado."
            )
        } else if (mayor != null && t.gasto > 0L) {
            textos.put(
                "Tu gasto más grande es ${mayor.optString("nombre")}: ${Fin.dinero(mayor.optLong("total"), moneda)} " +
                    "(${mayor.optInt("pct")} % de lo que salió)."
            )
        }
        if (pct > 0 && t.ingreso > 0L && t.ahorro * 100L < t.ingreso * pct) {
            var meta: JSONObject? = null
            for (i in 0 until metas.length()) {
                val m = metas.optJSONObject(i) ?: continue
                if (m.optLong("falta") > 0L) {
                    meta = m
                    break
                }
            }
            val sugerido = t.ingreso * pct / 100L - t.ahorro
            if (meta != null) {
                textos.put(
                    "Para cumplir tu $pct % de ahorro te falta apartar ${Fin.dinero(sugerido, moneda)} este mes " +
                        "para «${meta.optString("nombre")}»."
                )
            } else {
                textos.put(
                    "Aparta ${Fin.dinero(sugerido, moneda)} ($pct % de lo que entró) antes de gastarlo. " +
                        "Crea una meta de ahorro y yo te llevo la cuenta."
                )
            }
        } else if (pct > 0 && t.ingreso > 0L && t.ahorro > 0L) {
            textos.put("Ya apartaste ${Fin.dinero(t.ahorro, moneda)} este mes (${t.ahorro * 100L / t.ingreso} % de lo que entró). Bien.")
        }
        return salida.put("diario", diario).put("textos", textos)
    }

    /** Las categorías de gasto e ingreso; primero las que más usas en esa cuenta (últimos 4 meses), «Otros» al final. */
    private fun categoriasJson(copia: JSONObject?, movs: List<Mov>, hoy: LocalDate): JSONObject {
        val desde = hoy.minusDays(120).toString()
        val usos = HashMap<String, Int>()
        for (m in movs) {
            if (m.fecha >= desde) usos[m.categoria] = (usos[m.categoria] ?: 0) + 1
        }
        val salida = JSONObject()
        for (tipo in listOf("gasto", "ingreso")) {
            val otros = if (tipo == "gasto") "otros" else "otros_ingresos"
            // sortedBy respeta el orden original en los empates.
            val orden = listaDe(copia, tipo).sortedBy { if (it.clave == otros) 1_000_000 else -(usos[it.clave] ?: 0) }
            val arr = JSONArray()
            for (x in orden) arr.put(JSONObject().put("clave", x.clave).put("nombre", x.nombre).put("color", x.color))
            salida.put(tipo, arr)
        }
        return salida
    }

    private fun json(m: Mov): JSONObject {
        val k = cat(m.categoria)
        return JSONObject().put("id", m.id).put("uid", m.uid).put("cuenta", m.cuenta).put("tipo", m.tipo)
            .put("monto", m.monto).put("categoria", m.categoria).put("nombre", k.nombre).put("color", k.color)
            .put("nota", m.nota).put("fecha", m.fecha).put("origen", m.origen).put("meta", m.meta)
            .put("pendiente", m.pendiente)
    }

    /**
     * Todo lo que pinta la pantalla de Finanzas para una cuenta y un periodo, con la misma forma que manda la
     * laptop en /api/finanzas, pero calculado aquí con lo guardado en el teléfono. No toca la red.
     */
    fun vista(c: Context, sp: SharedPreferences, cuentaPedida: Long, periodoPedido: String, refIso: String?): JSONObject {
        val guardada = leerCopia(c)
        val hoy = LocalDate.now()
        val tipo = if (periodoPedido in PERIODOS) periodoPedido else "mes"
        val ref = fecha(refIso) ?: hoy
        val p = periodo(tipo, ref)
        val desde = p.desde.toString()
        val hasta = p.hasta.toString()
        val anterior = mover(tipo, ref, -1)
        val pAntes = periodo(tipo, anterior)
        val cid = cuentaReal(c, cuentaPedida)
        val simbolo = moneda(c, sp)
        val pct = guardada?.optInt("ahorro_pct", 20) ?: 20
        val deTodas = todos(c, sp)
        val movs = deTodas.filter { it.cuenta == cid }
        val susMetas = metas(guardada, deTodas, cid, hoy)
        val susFijos = fijos(guardada, cid, hoy)
        val susMovs = JSONArray()
        val delPeriodo = movs.filter { it.fecha >= desde && it.fecha <= hasta }
            .sortedWith(compareByDescending<Mov> { it.fecha }.thenByDescending { it.orden })
        for (m in delPeriodo.take(500)) susMovs.put(json(m))
        return JSONObject()
            .put("ok", true).put("hoy", hoy.toString()).put("moneda", simbolo)
            .put("cuentas", cuentas(c)).put("cuenta", cid)
            .put("categorias", categoriasJson(guardada, movs, hoy))
            .put(
                "periodo",
                JSONObject().put("tipo", tipo).put("ref", ref.toString()).put("desde", desde).put("hasta", hasta)
                    .put("titulo", p.titulo).put("anterior", anterior.toString())
                    .put("siguiente", mover(tipo, ref, 1).toString())
                    .put("es_actual", !hoy.isBefore(p.desde) && !hoy.isAfter(p.hasta))
            )
            .put("totales", totales(movs, desde, hasta).json())
            .put("antes", totales(movs, pAntes.desde.toString(), pAntes.hasta.toString()).json())
            .put("saldo", totales(movs, "0000-00-00", "9999-99-99").queda)
            .put("por_categoria", porCategoria(movs, desde, hasta, "gasto"))
            .put("por_categoria_ingreso", porCategoria(movs, desde, hasta, "ingreso"))
            .put("serie", serie(movs, tipo, ref))
            .put("presupuestos", presupuestos(guardada, movs, cid, ref))
            .put("metas", susMetas).put("fijos", susFijos)
            .put("consejo", consejo(movs, hoy, susMetas, susFijos, simbolo, pct))
            .put("movimientos", susMovs)
            .put("ahorro_pct", pct)
    }

    // ───────────────────────── Sincronizar con la laptop ─────────────────────────

    /** Lo que se le manda a la laptop por un elemento de la cola: la acción y sus datos. */
    private fun envio(x: JSONObject): Pair<String, JSONObject> {
        val monto = Fin.plano(centavosDe(x))
        if (x.optString("accion") == "meta") {
            return "meta" to JSONObject().put("id", x.optLong("meta")).put("aportar", monto)
                .put("fecha", x.optString("fecha")).put("origen", "app").put("uid", x.optString("uid"))
        }
        val d = JSONObject().put("tipo", x.optString("tipo")).put("monto", monto).put("categoria", x.optString("categoria"))
            .put("nota", texto(x, "nota")).put("fecha", x.optString("fecha")).put("origen", "app").put("uid", x.optString("uid"))
        if (x.optLong("cuenta") > 0L) d.put("cuenta", x.optLong("cuenta"))
        return "movimiento" to d
    }

    /** Lo que acaba de subir entra a la copia enseguida, para que no desaparezca si la bajada falla después. */
    private fun agregarACopia(c: Context, mov: JSONObject, uid: String) {
        synchronized(this) {
            val j = leerCopia(c) ?: JSONObject()
            val movs = j.optJSONArray("movimientos") ?: JSONArray()
            val id = mov.optLong("id")
            for (i in 0 until movs.length()) {
                if (movs.optJSONObject(i)?.optLong("id") == id) return   // ya estaba
            }
            movs.put(
                JSONObject().put("id", id).put("cuenta", mov.optLong("cuenta")).put("tipo", mov.optString("tipo"))
                    .put("monto", mov.optLong("monto")).put("categoria", mov.optString("categoria"))
                    .put("nota", texto(mov, "nota")).put("fecha", mov.optString("fecha"))
                    .put("origen", texto(mov, "origen")).put("meta", mov.optLong("meta")).put("uid", uid)
            )
            j.put("movimientos", movs)
            enMemoria(j)
        }
        guardar(c)
    }

    /**
     * Sube lo anotado (y lo borrado) aquí y baja la copia nueva de la laptop. Llamar SIEMPRE fuera del hilo
     * principal. Si la laptop no responde no pasa nada: todo sigue guardado y se reintenta después.
     */
    fun sincronizar(c: Context, sp: SharedPreferences, server: String, token: String): Sync {
        if (server.isBlank()) return Sync(SIN_LAPTOP, false, emptyList())
        return synchronized(red) { sincronizarYa(c.applicationContext, sp, server, token) }
    }

    private fun sincronizarYa(c: Context, sp: SharedPreferences, server: String, token: String): Sync {
        var cambio = false
        val avisos = ArrayList<String>()

        // 1) Lo que anotaste aquí. La laptop no lo duplica aunque se reintente: cada uno lleva su uid.
        while (true) {
            val item = lista(sp, "cola").optJSONObject(0) ?: break
            val uid = item.optString("uid")
            val (accion, datos) = envio(item)
            val r = Net.finanzasCambio(server, token, accion, datos) ?: return Sync(SIN_CONEXION, cambio, avisos)
            when (r.codigo) {
                200 -> {
                    val nuevos = r.json?.optJSONArray("avisos")
                    if (nuevos != null) {
                        for (i in 0 until nuevos.length()) {
                            val a = nuevos.optString(i)
                            if (a.isNotBlank()) avisos.add(a)
                        }
                    }
                    val mov = r.json?.optJSONObject("movimiento")
                    if (mov != null) agregarACopia(c, mov, uid)
                    quitarDeCola(sp, uid)
                    cambio = true
                }
                400 -> {   // un dato que la laptop nunca va a aceptar: no puede quedarse tapando la cola
                    quitarDeCola(sp, uid)
                    cambio = true
                }
                401 -> return Sync(TOKEN, cambio, avisos)
                404 -> return Sync(VIEJA, cambio, avisos)
                else -> return Sync(SIN_CONEXION, cambio, avisos)
            }
        }

        // 2) Lo que borraste aquí.
        while (true) {
            val borrados = lista(sp, "borrados")
            if (borrados.length() == 0) break
            val id = borrados.optLong(0)
            if (id > 0L) {
                val r = Net.finanzasCambio(server, token, "movimiento/borrar", JSONObject().put("id", id))
                    ?: return Sync(SIN_CONEXION, cambio, avisos)
                if (r.codigo == 401) return Sync(TOKEN, cambio, avisos)
                // 404 = ya no existía allá (lo borraste también en el panel): cuenta como hecho.
                if (r.codigo != 200 && r.codigo != 404 && r.codigo != 400) return Sync(SIN_CONEXION, cambio, avisos)
            }
            quitarBorrado(sp, id)
            cambio = true
        }

        // 3) Lo nuevo de la laptop (lo anotado en el panel, por Telegram o por voz).
        val version = leerCopia(c)?.optString("version") ?: ""
        val r = Net.finanzasTodo(server, token, version) ?: return Sync(SIN_CONEXION, cambio, avisos)
        if (r.codigo == 401) return Sync(TOKEN, cambio, avisos)
        if (r.codigo == 404) return Sync(VIEJA, cambio, avisos)
        val j = r.json ?: return Sync(SIN_CONEXION, cambio, avisos)
        if (!j.optBoolean("ok", false)) return Sync(APAGADO, cambio, avisos)
        if (!j.optBoolean("igual", false)) {
            enMemoria(j)
            guardar(c)
            cambio = true
        }
        sp.edit().putLong("sincronizado", System.currentTimeMillis()).apply()
        return Sync(AL_DIA, cambio, avisos)
    }
}
