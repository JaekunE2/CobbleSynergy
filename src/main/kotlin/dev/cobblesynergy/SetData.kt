package dev.cobblesynergy

import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * AI 트레이너 판단 보조 자료: Pokémon Showdown 랜덤배틀 세트 (pkmn/randbats, data.pkmn.cc).
 * 종족마다 역할(Fast Attacker, Bulky Support …)과 그 역할에서 쓰는 기술·아이템이 들어 있다.
 *
 *  - AI 트레이너를 처음 넣을 때만 내려받는다 (일반 플레이에는 인터넷을 쓰지 않는다).
 *  - config/cobblesynergy/sets/ 에 보관하고 7일마다 새로 받는다. 받지 못하면 보관본, 그것도 없으면 종족값 기준으로만 판단한다.
 *  - 끄기: config/cobblesynergy/bot.json 의 "onlineSets": false
 */
object SetData {
    private val FORMATS = listOf("gen9randombattle", "gen8randombattle", "gen7randombattle") // 앞쪽 자료 우선
    private const val URL = "https://data.pkmn.cc/randbats/%s.json"
    private val MAX_AGE = Duration.ofDays(7)

    class RoleSet(val name: String, val moves: Set<String>, val items: Set<String>)
    class Entry(val roles: List<RoleSet>, val items: Set<String>)

    @Volatile private var data: Map<String, Entry> = emptyMap()
    @Volatile private var started = false
    val loaded get() = data.size

    private val dir get() = FabricLoader.getInstance().configDir.resolve("cobblesynergy")

    private fun enabled(): Boolean = runCatching {
        val f = dir.resolve("bot.json")
        if (!Files.exists(f)) { Files.createDirectories(dir); Files.writeString(f, "{\n  \"onlineSets\": true\n}\n"); true }
        else JsonParser.parseString(Files.readString(f)).asJsonObject.get("onlineSets")?.asBoolean != false
    }.getOrDefault(true)

    /** 처음 부를 때 한 번 백그라운드로 불러온다 */
    fun ensure() {
        if (started) return
        started = true
        CompletableFuture.runAsync {
            val online = enabled()
            val merged = LinkedHashMap<String, Entry>()
            for (format in FORMATS) {
                val text = runCatching { fetch(format, online) }.onFailure { TestLog.log("sets", "$format 불러오기 실패: $it") }.getOrNull() ?: continue
                runCatching { parse(JsonParser.parseString(text).asJsonObject) }.getOrNull()?.forEach { (k, v) -> merged.putIfAbsent(k, v) }
            }
            data = merged
            CobbleSynergy.LOGGER.info("CobbleSynergy: AI 트레이너 세트 자료 {}종 (온라인 {})", merged.size, if (online) "켬" else "끔")
        }
    }

    private fun fetch(format: String, online: Boolean): String? {
        val cache = dir.resolve("sets").resolve("$format.json")
        val fresh = Files.exists(cache) && Files.getLastModifiedTime(cache).toInstant().isAfter(java.time.Instant.now().minus(MAX_AGE))
        if (fresh || !online) return if (Files.exists(cache)) Files.readString(cache) else null
        val body = runCatching {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
            val res = client.send(HttpRequest.newBuilder(URI(URL.format(format))).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "CobbleSynergy").GET().build(), HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200) res.body() else null
        }.getOrNull()
        if (body != null && body.trimStart().startsWith("{")) {
            Files.createDirectories(cache.parent); Files.writeString(cache, body)
            return body
        }
        return if (Files.exists(cache)) Files.readString(cache) else null // 받지 못하면 오래된 보관본
    }

    fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }
    /** Showdown 아이템 이름 → Cobblemon 아이템 id (Loaded Dice → cobblemon:loaded_dice) */
    fun itemId(name: String) = "cobblemon:" + name.lowercase().replace("'", "").replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun parse(root: JsonObject): Map<String, Entry> = root.entrySet().associate { (species, v) ->
        val o = v.asJsonObject
        val roles = o.getAsJsonObject("roles")?.entrySet()?.map { (name, r) ->
            val ro = r.asJsonObject
            RoleSet(name, ro.getAsJsonArray("moves")?.map { key(it.asString) }?.toSet().orEmpty(),
                ro.getAsJsonArray("items")?.map { itemId(it.asString) }?.toSet().orEmpty())
        }.orEmpty()
        key(species) to Entry(roles, o.getAsJsonArray("items")?.map { itemId(it.asString) }?.toSet().orEmpty())
    }

    private fun entry(mon: Pokemon): Entry? {
        val base = key(mon.species.name)
        val form = mon.form.name.takeIf { !it.equals("normal", true) && it.isNotBlank() }?.let { base + key(it) }
        return form?.let { data[it] } ?: data[base]
    }

    /** 지금 기술과 가장 많이 겹치는 자료 역할 (기술이 하나도 안 겹치면 첫 역할) */
    fun roleSet(mon: Pokemon): RoleSet? {
        val roles = entry(mon)?.roles ?: return null
        val known = mon.moveSet.getMoves().map { key(it.template.name) }.toSet()
        return roles.maxByOrNull { r -> r.moves.count { it in known } } ?: roles.firstOrNull()
    }

    /** 자료 역할 이름 → 우리 역할 */
    fun role(mon: Pokemon, bulky: Boolean): BotBrain.Role? {
        val name = roleSet(mon)?.name ?: return null
        return when {
            name.contains("Support") -> if (name.startsWith("Bulky") && bulky) BotBrain.Role.TANK else BotBrain.Role.SUPPORT
            name.contains("Staller") || name == "Bulky Setup" || name == "AV Pivot" || name == "Bulky Attacker" -> BotBrain.Role.TANK
            else -> BotBrain.Role.DEALER
        }
    }

    /** 자료에서 이 종족이 이 기술을 쓰면 가산: 지금 역할 세트면 크게, 다른 역할 세트면 조금 */
    fun moveBonus(mon: Pokemon, move: String): Double {
        val e = entry(mon) ?: return 0.0
        val k = key(move)
        return when {
            roleSet(mon)?.moves?.contains(k) == true -> 35.0
            e.roles.any { k in it.moves } -> 12.0
            else -> 0.0
        }
    }

    fun itemBonus(mon: Pokemon, item: String): Double {
        val e = entry(mon) ?: return 0.0
        return when {
            roleSet(mon)?.items?.contains(item) == true -> 35.0
            item in e.items -> 20.0
            else -> 0.0
        }
    }
}
