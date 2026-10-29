package com.openlauncher.app.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Where the offline Egypt package lives once imported from USB. */
object MapData {
    fun dir(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "map").apply { mkdirs() }
    fun tiles(context: Context) = File(dir(context), "egypt.mbtiles")
    fun search(context: Context) = File(dir(context), "egypt-search.db")
    fun safety(context: Context) = File(dir(context), "egypt-safety.db")
    fun isInstalled(context: Context) = tiles(context).length() > 0 && search(context).length() > 0

    /**
     * Copies the picked files into app storage. Files are recognised by name
     * (*.mbtiles, *.db) so the user can multi-select both at once.
     */
    suspend fun import(context: Context, uris: List<Uri>, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val jobs = uris.mapNotNull { uri ->
            var name = ""
            var size = 0L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: ""
                    size = c.getLong(1)
                }
            }
            when {
                name.endsWith(".mbtiles", ignoreCase = true) -> Triple(uri, tiles(context), size)
                name.contains("safety", ignoreCase = true) && name.endsWith(".db", ignoreCase = true) -> Triple(uri, safety(context), size)
                name.endsWith(".db", ignoreCase = true) -> Triple(uri, search(context), size)
                else -> null
            }
        }
        if (jobs.isEmpty()) return@withContext false
        val total = jobs.sumOf { it.third }.coerceAtLeast(1L)
        var copied = 0L
        val buffer = ByteArray(1 shl 16)
        for ((uri, target, _) in jobs) {
            val tmp = File(target.parentFile, target.name + ".part")
            resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        onProgress((copied.toFloat() / total).coerceAtMost(1f))
                    }
                }
            } ?: return@withContext false
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        true
    }
}

data class Place(
    val id: Long,
    val name: String,
    val nameEn: String,
    val nameAr: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val rank: Int,
) {
    /** Latin name when there is one; the device locale here is English-first. */
    val title: String get() = nameEn.ifEmpty { name }
    val subtitle: String get() = listOf(if (nameEn.isNotEmpty() && name != nameEn) name else nameAr, kindLabel(kind))
        .filter { it.isNotEmpty() }.joinToString(" · ")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("nameEn", nameEn).put("nameAr", nameAr)
        .put("kind", kind).put("lat", lat).put("lon", lon).put("rank", rank)

    companion object {
        fun fromJson(o: JSONObject) = Place(
            o.optLong("id"), o.optString("name"), o.optString("nameEn"), o.optString("nameAr"),
            o.optString("kind"), o.optDouble("lat"), o.optDouble("lon"), o.optInt("rank"),
        )
    }
}

fun kindLabel(kind: String): String {
    val value = kind.substringAfter(':', kind)
    return when {
        kind.startsWith("street") -> "Street"
        kind in setOf("city", "town", "village", "suburb", "quarter", "neighbourhood", "hamlet", "locality") ->
            kind.replaceFirstChar { it.uppercase() }
        else -> value.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** Great-circle distance in metres. */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

fun formatDistance(meters: Double, metric: Boolean): String = if (metric) {
    if (meters < 1000) "${(meters / 10).roundToInt() * 10} m" else "%.1f km".format(meters / 1000)
} else {
    val miles = meters / 1609.34
    if (miles < 0.2) "${(meters * 3.281 / 50).roundToInt() * 50} ft" else "%.1f mi".format(miles)
}

/** Offline place search over the FTS4 index built by tools/map/build_search_index.py. */
class PlaceSearch(private val file: File) {
    private var db: SQLiteDatabase? = null

    private fun open(): SQLiteDatabase? {
        db?.let { return it }
        if (!file.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        }.getOrNull()?.also { db = it }
    }

    suspend fun search(query: String, nearLat: Double?, nearLon: Double?): List<Place> = withContext(Dispatchers.IO) {
        val terms = normalise(query).split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return@withContext emptyList()
        val match = terms.joinToString(" ") { it.replace("\"", "") + "*" }
        val db = open() ?: return@withContext emptyList()
        val rows = runCatching {
            db.rawQuery(
                "SELECT p.id, p.name, p.name_en, p.name_ar, p.kind, p.lat, p.lon, p.rank " +
                    "FROM places_fts f JOIN places p ON p.id = f.rowid WHERE f.search MATCH ? LIMIT 400",
                arrayOf(match),
            ).use { it.toPlaces() }
        }.getOrDefault(emptyList())
        rank(rows, nearLat, nearLon).take(60)
    }

    /** Points of interest of the given OSM kinds within ~[radiusKm] of a position. */
    suspend fun nearby(kinds: List<String>, lat: Double, lon: Double, radiusKm: Double = 15.0): List<Place> = withContext(Dispatchers.IO) {
        val db = open() ?: return@withContext emptyList()
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        val placeholders = kinds.joinToString(",") { "?" }
        val args = kinds + listOf(lat - dLat, lat + dLat, lon - dLon, lon + dLon).map { it.toString() }
        val rows = runCatching {
            db.rawQuery(
                "SELECT id, name, name_en, name_ar, kind, lat, lon, rank FROM places " +
                    "WHERE kind IN ($placeholders) AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 800",
                args.toTypedArray(),
            ).use { it.toPlaces() }
        }.getOrDefault(emptyList())
        rows.sortedBy { distanceMeters(lat, lon, it.lat, it.lon) }.take(60)
    }

    private fun rank(rows: List<Place>, lat: Double?, lon: Double?): List<Place> = rows.sortedByDescending { p ->
        val distanceKm = if (lat != null && lon != null) distanceMeters(lat, lon, p.lat, p.lon) / 1000.0 else 0.0
        p.rank - distanceKm * 0.6
    }

    private fun android.database.Cursor.toPlaces(): List<Place> {
        val out = ArrayList<Place>(count)
        while (moveToNext()) {
            out += Place(
                getLong(0), getString(1) ?: "", getString(2) ?: "", getString(3) ?: "",
                getString(4) ?: "", getDouble(5), getDouble(6), getInt(7),
            )
        }
        return out
    }

    companion object {
        private val TASHKEEL = Regex("[ؐ-ًؚ-ٰٟۖ-ۭـ]")
        private val NON_WORD = Regex("[^\\p{L}\\p{N}_]+")

        /** Must match normalise() in tools/map/build_search_index.py. */
        fun normalise(text: String): String = TASHKEEL.replace(text.lowercase(), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
            .replace('ة', 'ه').replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي')
            .replace(NON_WORD, " ").trim()
    }
}

/** Favourites and recent destinations, kept as small JSON lists in preferences. */
class Destinations(context: Context) {
    private val prefs = context.getSharedPreferences("mmi_destinations", Context.MODE_PRIVATE)

    fun favorites(): List<Place> = read("favorites")
    fun recents(): List<Place> = read("recents")
    fun isFavorite(place: Place) = favorites().any { it.id == place.id }

    fun toggleFavorite(place: Place) {
        val list = favorites().toMutableList()
        if (!list.removeAll { it.id == place.id }) list.add(0, place)
        write("favorites", list)
    }

    fun addRecent(place: Place) {
        val list = recents().filterNot { it.id == place.id }.toMutableList()
        list.add(0, place)
        write("recents", list.take(20))
    }

    private fun read(key: String): List<Place> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        List(arr.length()) { Place.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun write(key: String, list: List<Place>) {
        prefs.edit().putString(key, JSONArray(list.map { it.toJson() }).toString()).apply()
    }
}
