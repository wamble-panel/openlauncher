package com.openlauncher.app.util

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStreamReader

/**
 * The vendor car chooser's full car list (CAN-box maker → brand → model → trim …), read
 * straight from the car chooser app on the unit (its assets/json.txt, about 34,000
 * entries), for the developer page. Read only: the car is still chosen in the vendor's
 * own password-protected menu. Streamed (not loaded as one big JSON tree), once, on a
 * background thread, and kept only as a small parent → children index.
 */
object CarList {
    data class Node(val id: String, val name: String, val rank: Int)

    @Volatile private var byParent: Map<String, List<Node>>? = null
    @Volatile var loading = false
        private set
    @Volatile var error: String? = null
        private set

    fun ensure(context: Context) {
        if (byParent != null || loading) return
        loading = true
        error = null
        val app = context.applicationContext
        Thread {
            try {
                val pc = app.createPackageContext("com.tw.carchoose", 0)
                val map = HashMap<String, MutableList<Node>>()
                pc.assets.open("json.txt").use { ins ->
                    val r = JsonReader(InputStreamReader(ins, Charsets.UTF_8))
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() != "RECORDS") { r.skipValue(); continue }
                        r.beginArray()
                        while (r.hasNext()) {
                            var id: String? = null
                            var name: String? = null
                            var parent: String? = null
                            var rank = 0
                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "configId" -> id = str(r)?.trim()
                                    "name" -> name = str(r)?.trim()
                                    "remark" -> parent = str(r)?.trim()
                                    "rank" -> rank = str(r)?.toIntOrNull() ?: 0
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                            if (id != null && parent != null) map.getOrPut(parent) { mutableListOf() } += Node(id, name ?: id, rank)
                        }
                        r.endArray()
                    }
                    r.endObject()
                }
                map.values.forEach { it.sortByDescending { n -> n.rank } }
                byParent = map
            } catch (e: Exception) {
                error = if (e is android.content.pm.PackageManager.NameNotFoundException) "The vendor car chooser is not on this unit." else (e.message ?: "Could not read the car list.")
            } finally {
                loading = false
            }
        }.start()
    }

    private fun str(r: JsonReader): String? = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else r.nextString()

    /**
     * The entries under [path] ("2" = landscape units: the CAN-box makers), with the
     * one on the unit's chosen car marked.
     */
    fun list(context: Context, path: String): Map<String, Any?> {
        ensure(context)
        val index = byParent
        val chosen = CarProfile.car.path
        val depth = path.split('_').size - 1 // 0 = CAN-box makers
        return mapOf(
            "loading" to (index == null && loading),
            "error" to error,
            "path" to path,
            "items" to index?.get(path).orEmpty().map { n ->
                val child = "${path}_${n.id}"
                mapOf(
                    "id" to n.id,
                    "path" to child,
                    "name" to english(n.name, depth),
                    "original" to n.name,
                    "more" to index.containsKey(child),
                    "chosen" to (chosen.getOrNull(depth) == n.id),
                )
            },
        )
    }

    private fun english(name: String, depth: Int): String = when (depth) {
        0 -> CAN_BOX[name] ?: name
        1 -> CarProfile.brandName(name)
        else -> CarProfile.modelName(name)
    }

    private val CAN_BOX = mapOf(
        "通用机" to "No CAN box (generic)", "睿志诚" to "Raise (RZC)", "睿志诚(特殊)" to "Raise (special)",
        "Hiworld威驰" to "Hiworld", "欣朴" to "XinPu", "无协议" to "No protocol", "IR协议" to "IR protocol",
    )
}
