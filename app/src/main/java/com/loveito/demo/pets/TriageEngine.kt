package com.loveito.demo.pets

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.random.Random

object TriageEngine {

    private fun loadJson(ctx: Context, asset: String = "triage_v1.json"): JSONObject {
        val sb = StringBuilder()
        ctx.assets.open(asset).use { ins ->
            BufferedReader(InputStreamReader(ins)).useLines { lines ->
                lines.forEach { sb.append(it) }
            }
        }
        return JSONObject(sb.toString())
    }

    fun defaultResult(ctx: Context): Map<String, Any?> {
        val json = loadJson(ctx)
        val nodes = json.getJSONObject("nodes")
        var cur = json.getString("start")
        val path = mutableListOf<String>()
        while (true) {
            val n = nodes.getJSONObject(cur)
            path.add(cur)
            val type = n.getString("type")
            if (type == "result") {
                val res = mutableMapOf<String, Any?>()
                res["severity"] = n.getString("severity")
                res["title"] = n.getString("title")
                if (n.has("actions")) res["actions"] = n.getJSONArray("actions").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
                res["path"] = path
                return res
            } else {
                cur = n.getString("no")
            }
        }
    }

    fun randomResult(ctx: Context): Map<String, Any?> {
        val json = loadJson(ctx)
        val nodes = json.getJSONObject("nodes")

        val results = mutableListOf<Pair<String, JSONObject>>()
        nodes.keys().forEach { key ->
            val n = nodes.getJSONObject(key)
            if (n.getString("type") == "result") results.add(key to n)
        }

        if (results.isEmpty()) return defaultResult(ctx)

        fun weightOf(n: JSONObject): Int = when (n.getString("severity").lowercase()) {
            "red" -> 10
            "amber" -> 25
            else -> 65
        }

        val expanded = results.flatMap { (id, n) -> List(weightOf(n)) { id to n } }
        val (chosenId, chosen) = expanded[Random.nextInt(expanded.size)]

        val res = mutableMapOf<String, Any?>()
        res["severity"] = chosen.getString("severity")
        res["title"] = chosen.getString("title")
        if (chosen.has("actions")) res["actions"] = chosen.getJSONArray("actions").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        res["path"] = listOf(chosenId)
        return res
    }
}