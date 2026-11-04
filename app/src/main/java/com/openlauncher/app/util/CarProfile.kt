package com.openlauncher.app.util

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Which car this unit is set up for, read (never written) from the vendor car chooser's
 * own file `/sdcard/.CarChoose` (protocol notes, section 21): brand, model and CAN-box
 * maker as picked in the factory car list.
 *
 * MMI uses it to decide which raw CAN-box messages it may decode itself. Its own
 * decoders know the Raise box (doors, frame 0x24, used by every Raise box) and the GM
 * frames of the Astra J (tyres, range, oil life, coolant: 0x14 / 0x38 / 0x4A / 0x68). On
 * any other car those numbers mean something else, so they are left alone and the car
 * data comes only from the vendor's Car Info Service, which decodes every car in its
 * list. If the file cannot be read, MMI keeps decoding as before (the owner's Astra).
 */
object CarProfile {
    enum class Decoder { AUTO, GM, CAR_SERVICE_ONLY }

    data class Car(
        val brand: String? = null,
        val model: String? = null,
        val canBox: String? = null,
        /** The file was found and read. */
        val known: Boolean = false,
        val raise: Boolean = true,
        val gm: Boolean = true,
        /** The chosen entry ids, top down (CAN-box maker, brand, model, …). */
        val path: List<String> = emptyList(),
    ) {
        val name: String? get() = listOfNotNull(brand, model).joinToString(" ").ifBlank { null }
    }

    @Volatile var car = Car()
        private set
    @Volatile private var decoder = Decoder.AUTO
    private var readAt = 0L
    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
        decoder = runCatching {
            Decoder.valueOf(prefs().getString("car_decoder", Decoder.AUTO.name)!!)
        }.getOrDefault(Decoder.AUTO)
        refresh()
    }

    private fun prefs() = app!!.getSharedPreferences("mmi_car", Context.MODE_PRIVATE)

    fun decoderSetting(): Decoder = decoder

    fun setDecoder(d: Decoder) {
        decoder = d
        app?.let { prefs().edit().putString("car_decoder", d.name).apply() }
    }

    /**
     * Which built-in car picture MMI shows (Home CAR tile, start-up, Car information):
     * "opel", "sunny" or "none". AUTO follows the car chosen in the vendor car list
     * (Opel / GM → Opel, Nissan → Sunny); with no car list it is the owner's Opel on a
     * TW unit and none elsewhere (pick it once in Settings › Car).
     */
    fun artSetting(): String = app?.let { prefs().getString("car_art", "auto") } ?: "auto"

    fun setArt(a: String) {
        app?.let { prefs().edit().putString("car_art", if (a in ARTS) a else "auto").apply() }
    }

    fun art(): String {
        val s = artSetting()
        if (s != "auto") return s
        val c = car
        return when {
            c.known && c.brand?.startsWith("Nissan", ignoreCase = true) == true -> "sunny"
            c.known && c.gm -> "opel"
            c.known -> "none"
            twUnit -> "opel"
            else -> "none"
        }
    }

    private val ARTS = setOf("auto", "opel", "sunny", "none")

    /** A TW-platform unit (the owner's Astra unit): its car library is in the system. */
    private val twUnit: Boolean by lazy { runCatching { Class.forName("android.tw.john.TWUtil") }.isSuccess }

    /** May MMI decode the Raise box's generic door message (0x24)? */
    val raiseDoors: Boolean
        get() = when (decoder) {
            Decoder.GM -> true
            Decoder.CAR_SERVICE_ONLY -> false
            Decoder.AUTO -> car.raise
        }

    /** May MMI decode the GM frames (tyres, range, oil life, coolant)? */
    val gmFrames: Boolean
        get() = when (decoder) {
            Decoder.GM -> true
            Decoder.CAR_SERVICE_ONLY -> false
            Decoder.AUTO -> car.raise && car.gm
        }

    /**
     * The vendor Factory menu's door options ("Switch front / rear / left / right door",
     * Settings.System SYSTEM_DOOR bits 1 / 2 / 4 / 8) and the left- / right-hand-drive
     * choice (LEFT_RIGHT_SEAT), so MMI shows an open door on the same side the vendor's
     * own pop-up does. Read only; re-read with the car (at most every 30 s).
     */
    @Volatile var doorSwap = 0
        private set
    @Volatile var rightHandDrive = false
        private set

    /** Applies the Factory menu's door options to MMI's door bits (1 FL, 2 FR, 4 RL, 8 RR). */
    fun mapDoors(doors: Int): Int {
        fun swap(d: Int, a: Int, b: Int): Int {
            val hasA = d and a != 0
            val hasB = d and b != 0
            var r = d and (a or b).inv()
            if (hasA) r = r or b
            if (hasB) r = r or a
            return r
        }
        var d = doors
        if (doorSwap and 1 != 0) d = swap(d, 1, 2) // front doors
        if (doorSwap and 2 != 0) d = swap(d, 4, 8) // rear doors
        if (doorSwap and 4 != 0) d = swap(d, 1, 4) // left doors
        if (doorSwap and 8 != 0) d = swap(d, 2, 8) // right doors
        if (rightHandDrive) d = swap(d, 1, 2)
        return d
    }

    /** Re-reads the file (cheap; at most every 30 s). The car is changed rarely, in the vendor menu. */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - readAt < 30_000) return
        readAt = now
        car = runCatching { parse(File(Environment.getExternalStorageDirectory(), ".CarChoose").readText()) }.getOrElse { Car() }
        app?.contentResolver?.let { cr ->
            doorSwap = runCatching { android.provider.Settings.System.getInt(cr, "SYSTEM_DOOR", 0) }.getOrDefault(0)
            rightHandDrive = runCatching { android.provider.Settings.System.getInt(cr, "LEFT_RIGHT_SEAT", 0) == 1 }.getOrDefault(false)
        }
    }

    /** Records of `key=value` joined by `+`, appended one after another: later values win. */
    internal fun parse(text: String): Car {
        val map = HashMap<String, String>()
        text.replace("\n", "+").split('+').forEach { part ->
            val i = part.indexOf('=')
            if (i > 0) map[part.substring(0, i).trim()] = part.substring(i + 1).trim()
        }
        if (map.isEmpty()) return Car()
        val path = map["cardata"].orEmpty().split(',').map { it.trim() }
        val vendorText = map["tv_vendor"].orEmpty()
        val brandText = map["tv_cars"].orEmpty()
        val raise = "202" in path || "210" in path || RAISE.any { vendorText.contains(it, ignoreCase = true) }
        val gm = "81" in path || GM.any { brandText.contains(it, ignoreCase = true) }
        return Car(
            brand = brandText.takeIf { it.isNotBlank() }?.let { BRANDS[it] ?: it },
            model = map["tv_models"]?.takeIf { it.isNotBlank() }?.let(::model),
            canBox = vendorText.takeIf { it.isNotBlank() }?.let { if (RAISE.any { r -> it.contains(r, true) }) "Raise" else it },
            known = true,
            raise = raise,
            gm = gm,
            path = path.filter { it.isNotEmpty() }.let { p -> if (p.firstOrNull() == "2") p.drop(1) else p },
        )
    }

    fun brandName(s: String): String = BRANDS[s] ?: s
    fun modelName(s: String): String = model(s)

    /** "11-17款Astra J" → "Astra J (2011–17)". */
    private fun model(s: String): String {
        val m = Regex("""^(\d{2})(?:-(\d{2}))?款\s*(.+)$""").find(s) ?: return s.replace("款", " ").trim()
        val (from, to, name) = m.destructured
        return "$name (20$from" + (if (to.isNotEmpty()) "–$to" else "") + ")"
    }

    private val RAISE = listOf("睿志诚", "Raise", "RZC")
    private val GM = listOf("欧宝", "Opel", "Vauxhall", "雪佛兰", "Chevrolet", "别克", "Buick", "凯迪拉克", "Cadillac", "通用", "GM")

    // The car chooser's brand names are Chinese; the common ones in English.
    private val BRANDS = mapOf(
        "欧宝" to "Opel", "大众" to "Volkswagen", "丰田" to "Toyota", "本田" to "Honda", "日产" to "Nissan",
        "现代" to "Hyundai", "起亚" to "Kia", "福特" to "Ford", "雪佛兰" to "Chevrolet", "别克" to "Buick",
        "马自达" to "Mazda", "三菱" to "Mitsubishi", "斯柯达" to "Škoda", "奥迪" to "Audi", "宝马" to "BMW",
        "奔驰" to "Mercedes-Benz", "标致" to "Peugeot", "雪铁龙" to "Citroën", "雷诺" to "Renault", "菲亚特" to "Fiat",
        "吉普" to "Jeep", "路虎" to "Land Rover", "沃尔沃" to "Volvo", "斯巴鲁" to "Subaru", "铃木" to "Suzuki",
        "凯迪拉克" to "Cadillac", "雷克萨斯" to "Lexus", "英菲尼迪" to "Infiniti", "讴歌" to "Acura", "道奇" to "Dodge",
        "克莱斯勒" to "Chrysler", "西雅特" to "SEAT", "长城" to "Great Wall", "哈弗" to "Haval", "吉利" to "Geely",
        "奇瑞" to "Chery", "比亚迪" to "BYD", "名爵" to "MG", "荣威" to "Roewe", "长安" to "Changan", "捷豹" to "Jaguar",
        "保时捷" to "Porsche", "迷你" to "MINI", "斯玛特" to "smart", "雪佛兰/别克" to "Chevrolet / Buick",
        "现代起亚" to "Hyundai / Kia", "东风日产" to "Nissan (Dongfeng)", "美国通用" to "GM (USA)", "JEEP" to "Jeep",
        "标志雪铁龙" to "Peugeot / Citroën", "奔腾" to "Bestune", "名爵荣威全兼容" to "MG / Roewe", "上汽大通" to "Maxus",
        "宝骏" to "Baojun", "江淮" to "JAC", "广汽传祺" to "GAC Trumpchi", "海马" to "Haima", "东风" to "Dongfeng",
        "中华" to "Brilliance", "力帆" to "Lifan", "北汽" to "BAIC", "众泰" to "Zotye", "汉腾" to "Hanteng", "比速" to "Bisu",
        "陆风" to "Landwind", "启辰" to "Venucia", "东南" to "Soueast", "金杯" to "Jinbei", "江西五十铃" to "Isuzu (Jiangxi)",
        "君马" to "Junma", "纳智捷" to "Luxgen", "野马" to "Yema", "五菱" to "Wuling", "南骏" to "Nanjun", "斯威" to "SWM",
        "江铃" to "JMC", "银隆" to "Yinlong", "雷丁" to "Leding", "创维" to "Skyworth", "双龙" to "SsangYong", "红旗" to "Hongqi",
        "通用机按键板" to "Generic key board", "特斯拉" to "Tesla", "马恒达" to "Mahindra",
    )
}
