package app.fizz

import android.content.Context
import android.content.SharedPreferences
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URL

object Net {
    fun get(url: String): String {
        val c = URL(url).openConnection()
        c.setRequestProperty("User-Agent", "Fizz/0.1")
        c.connectTimeout = 15000; c.readTimeout = 20000
        return c.getInputStream().bufferedReader().use { it.readText() }
    }
}

interface Http { fun get(url: String): String }

/** An extension is one JS file run in QuickJS. See assets/sample_mangadex.js. */
class Ext(val code: String) {
    val name = Regex("@name\\s+(.+)").find(code)?.groupValues?.get(1)?.trim() ?: "Unnamed"
    val type = Regex("@type\\s+(.+)").find(code)?.groupValues?.get(1)?.trim() ?: ""
    /** A valid extension has // @name and defines search, detail and content. */
    val valid: Boolean get() = Regex("@name\\s+\\S").containsMatchIn(code) &&
        listOf("search", "detail", "content").all { Regex("(function\\s+$it\\b|\\b$it\\s*=)").containsMatchIn(code) }

    suspend fun call(fn: String, vararg args: Any): Any = withContext(Dispatchers.IO) {
        QuickJs.create().use { js ->
            js.set("http", Http::class.java, object : Http { override fun get(url: String) = Net.get(url) })
            js.evaluate(code)
            val a = JSONArray(args.toList()).toString()
            JSONTokener(js.evaluate("JSON.stringify($fn(...$a))") as String).nextValue()
        }
    }
}

object Store {
    private lateinit var p: SharedPreferences
    fun init(c: Context) {
        p = c.getSharedPreferences("fizz", 0)
        // drop broken entries saved by older versions (shown as "Unnamed")
        val a = JSONArray(p.getString("ext", "[]")); val b = JSONArray()
        for (i in 0 until a.length()) if (Ext(a.getString(i)).valid) b.put(a.getString(i))
        if (b.length() != a.length()) p.edit().putString("ext", b.toString()).apply()
    }

    fun list(k: String): MutableList<JSONObject> {
        val a = JSONArray(p.getString(k, "[]"))
        return MutableList(a.length()) { a.getJSONObject(it) }
    }
    private fun save(k: String, l: List<JSONObject>) = p.edit().putString(k, JSONArray(l).toString()).apply()

    fun exts(): List<Ext> { val a = JSONArray(p.getString("ext", "[]")); return List(a.length()) { Ext(a.getString(it)) } }
    fun addExt(code: String) { val a = JSONArray(p.getString("ext", "[]")); a.put(code); p.edit().putString("ext", a.toString()).apply() }
    fun delExt(i: Int) { val a = JSONArray(p.getString("ext", "[]")); a.remove(i); p.edit().putString("ext", a.toString()).apply() }

    private fun same(a: JSONObject, e: String, item: JSONObject) =
        a.getString("ext") == e && a.getJSONObject("item").getString("url") == item.getString("url")
    fun isFav(e: Ext, item: JSONObject) = list("fav").any { same(it, e.name, item) }
    fun toggleFav(e: Ext, item: JSONObject): Boolean {
        val l = list("fav")
        val had = l.removeAll { same(it, e.name, item) }
        if (!had) l.add(JSONObject().put("ext", e.name).put("item", item))
        save("fav", l); return !had
    }
    fun addHist(e: Ext, item: JSONObject, ch: JSONObject) {
        val l = list("hist")
        l.removeAll { same(it, e.name, item) }
        l.add(0, JSONObject().put("ext", e.name).put("item", item).put("ch", ch))
        save("hist", l.take(100))
    }

    fun seen(e: Ext, item: JSONObject): Int = JSONObject(p.getString("seen", "{}")).optInt(e.name + "|" + item.getString("url"), -1)
    fun setSeen(e: Ext, item: JSONObject, n: Int) {
        val o = JSONObject(p.getString("seen", "{}")); o.put(e.name + "|" + item.getString("url"), n)
        p.edit().putString("seen", o.toString()).apply()
    }
    fun cats(): List<String> { val a = JSONArray(p.getString("cats", "[]")); return List(a.length()) { a.getString(it) } }
    fun addCat(n: String) {
        if (n.isBlank() || n.trim() in cats()) return
        val a = JSONArray(p.getString("cats", "[]")); a.put(n.trim()); p.edit().putString("cats", a.toString()).apply()
    }
    fun setCat(e: Ext, item: JSONObject, cat: String?) {
        val l = list("fav")
        val a = l.firstOrNull { same(it, e.name, item) } ?: JSONObject().put("ext", e.name).put("item", item).also { l.add(it) }
        if (cat == null) a.remove("cat") else a.put("cat", cat)
        save("fav", l)
    }
    var theme: String
        get() = p.getString("theme", "system")!!
        set(v) { p.edit().putString("theme", v).apply() }

    fun read(): Set<String> = p.getStringSet("read", emptySet())!!
    fun markRead(k: String) { p.edit().putStringSet("read", read().toMutableSet().apply { add(k) }).apply() }
    var mode: String
        get() = p.getString("mode", "webtoon")!!
        set(v) { p.edit().putString("mode", v).apply() }
}
