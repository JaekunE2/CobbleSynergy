package dev.cobblesynergy

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * battle.js 가 남긴 "|t:|cobblesynergy:{...}" 표시를 읽어 Cobblemon 전투 로그(전투 화면 아래 메시지 창)에
 * 시너지 발동을 한국어로 보여준다. (t: 줄은 Cobblemon 이 무시하는 시간 표시 줄이라 화면에 깨진 글자가 나오지 않는다)
 * 테스트 빌드에서는 "피해 증감" 이 실제로 빠진 HP 와 맞는지 확인해 로그에 남긴다.
 */
object SynergyFeed {
    private const val MARK = "cobblesynergy:"

    private class DamageCheck(val battle: String, val label: String, val side: String, val uuid: String, val hpBefore: Int, val expected: Int, val text: String)

    private class Watch(val battle: PokemonBattle) {
        var raw = 0
        val damage = ArrayList<DamageCheck>()
        val kinds = HashMap<String, Int>()
    }

    private val watches = HashMap<UUID, Watch>()

    /** 테스트 스위트가 검사 결과를 집계할 수 있도록 */
    var onCheck: ((String, Boolean, String) -> Unit)? = null
    var labelOf: ((PokemonBattle) -> String?)? = null

    fun watch(battle: PokemonBattle) {
        watches.getOrPut(battle.battleId) { Watch(battle) }
    }

    /** 이 전투에서 발동한 이벤트 종류별 횟수 (테스트용) */
    fun kinds(battle: PokemonBattle): Map<String, Int> = watches[battle.battleId]?.kinds.orEmpty()

    fun tick() {
        val it = watches.values.iterator()
        while (it.hasNext()) {
            val w = it.next()
            runCatching { scan(w) }.onFailure { e -> TestLog.log("feed", "읽기 오류: $e") }
            if (w.battle.ended) {
                w.damage.forEach { d -> TestLog.log("check", "INFO ${d.label} ${d.text} — 결과 HP 를 받기 전에 전투 종료") }
                it.remove()
            }
        }
    }

    private fun scan(w: Watch) {
        val msgs = w.battle.showdownMessages
        while (w.raw < msgs.size) {
            val chunk = msgs[w.raw++].toString()
            for (line in chunk.split('\n')) {
                val at = line.indexOf(MARK)
                if (at >= 0) {
                    runCatching { JsonParser.parseString(line.substring(at + MARK.length)).asJsonObject }
                        .getOrNull()?.let { handle(w, it) }
                } else if (line.startsWith("|-damage|") && w.damage.isNotEmpty()) {
                    resolveDamage(w, line)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 표시

    private fun str(o: JsonObject, k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun speciesKo(name: String): String =
        runCatching { PokemonSpecies.getByName(name.lowercase().replace(Regex("[^a-z0-9]"), ""))?.translatedName?.string }
            .getOrNull() ?: name

    private fun moveKo(name: String): String =
        runCatching { Moves.getByName(name.lowercase().replace(Regex("[^a-z0-9]"), ""))?.displayName?.string }.getOrNull() ?: name

    private fun mon(o: JsonObject, k: String) = o.getAsJsonObject(k)?.let { speciesKo(str(it, "species")) } ?: "?"

    private fun sideName(b: PokemonBattle, side: String) =
        runCatching { b.actors.firstOrNull { it.showdownId == side }?.getName()?.string }.getOrNull() ?: side

    private val STAT_KO = mapOf("atk" to "공격", "def" to "방어", "spa" to "특공", "spd" to "특방", "spe" to "스피드")
    private val STATUS_KO = mapOf("brn" to "화상", "par" to "마비", "psn" to "독", "tox" to "맹독", "slp" to "잠듦", "frz" to "얼음")
    private val ID_KO = mapOf(
        "reflect" to "리플렉터", "lightscreen" to "빛의장막", "auroraveil" to "오로라베일", "safeguard" to "신비의부적",
        "tailwind" to "순풍", "mist" to "흰안개", "stealthrock" to "스텔스록", "spikes" to "압정뿌리기", "toxicspikes" to "독압정",
        "stickyweb" to "끈적끈적네트", "trickroom" to "트릭룸", "electricterrain" to "일렉트릭필드", "grassyterrain" to "그래스필드",
        "mistyterrain" to "미스트필드", "psychicterrain" to "사이코필드", "sunnyday" to "쾌청", "raindance" to "비",
        "sandstorm" to "모래바람", "snow" to "설경", "taunt" to "도발", "leechseed" to "씨뿌리기", "aquaring" to "아쿠아링",
        "flinch" to "풀죽음",
    )

    private fun boosts(o: JsonObject?, k: String = "b"): String =
        o?.getAsJsonObject(k)?.entrySet()?.joinToString(", ") { (s, v) ->
            val n = v.asInt
            "${STAT_KO[s] ?: s} ${if (n > 0) "+" else ""}$n"
        }.orEmpty()

    private fun handle(w: Watch, o: JsonObject) {
        val b = w.battle
        val kind = str(o, "k")
        w.kinds.merge(kind, 1, Int::plus)
        val nm = str(o, "nm")
        val id = ID_KO[str(o, "id")] ?: str(o, "id")
        val d = o.get("d")?.asInt ?: 0
        val turns = if (d > 0) " ${d}턴" else ""
        val text = when (kind) {
            "start" -> {
                val who = sideName(b, str(o, "side"))
                val where = if (o.get("foe")?.asBoolean == true && str(o, "k") == "start" && o.get("mon") == null) "상대 진영에 " else ""
                when (str(o, "r")) {
                    "ok" -> if (o.get("mon") != null) "$nm — ${mon(o, "mon")}에게 $id$turns" else "$nm — $where$id$turns"
                    "late" -> "$nm — 대기하던 $id 발동$turns"
                    "queued" -> "$nm — $id 대기 (다른 날씨·필드가 끝나면 발동)"
                    "merged" -> "$nm — 같은 효과가 이미 있어 하나로 합침"
                    "expired" -> "$nm — 대기하던 $id 취소"
                    "fail" -> "$nm — ${mon(o, "mon")}에게 $id 실패"
                    else -> "$nm — $id"
                }.let { "[$who] $it" }
            }
            "entry" -> {
                val vols = o.getAsJsonArray("v")?.joinToString(", ") { ID_KO[it.asString] ?: it.asString }.orEmpty()
                "$nm — ${mon(o, "mon")} 첫 등장: " + listOf(boosts(o), vols).filter { it.isNotEmpty() }.joinToString(", ")
            }
            "power" -> {
                val m = o.get("m").asDouble
                val bp = o.get("bp").asInt
                "$nm — ${mon(o, "mon")}의 ${moveKo(str(o, "move"))} 위력 ×${"%.2f".format(m)} ($bp → ${(bp * m).roundToInt()})"
            }
            "dmg" -> {
                val m = o.get("m").asDouble
                val before = o.get("before").asInt
                val expected = maxOf(1, (before * m).roundToInt())
                val t = "$nm — ${mon(o, "mon")} 받는 피해 ${if (m < 1) "감소" else "증가"} ×${"%.2f".format(m)} ($before → $expected)"
                o.getAsJsonObject("mon")?.let { mo ->
                    w.damage += DamageCheck(b.battleId.toString(), labelOf?.invoke(b) ?: b.battleId.toString().take(8),
                        str(mo, "side"), str(mo, "uuid"), o.get("hp")?.asInt ?: 0, expected, t)
                }
                t
            }
            "endure" -> "$nm — ${mon(o, "mon")} 버티기! HP 1로 견딤"
            "laststand" -> "$nm — 마지막으로 남은 ${mon(o, "mon")}: ${boosts(o)}" + (o.get("d")?.asInt?.takeIf { it > 0 }?.let { " (${it}턴)" } ?: "")
            "expire" -> "$nm — ${mon(o, "mon")}의 랭크 효과 끝" + boosts(o).let { if (it.isEmpty()) "" else " ($it)" }
            "onhit" -> {
                val what = listOf(STATUS_KO[str(o, "st")].orEmpty(), ID_KO[str(o, "v")].orEmpty(), boosts(o)).filter { it.isNotEmpty() }.joinToString(", ")
                "$nm — ${mon(o, "atk")}의 공격으로 ${mon(o, "mon")} $what"
            }
            "charm" -> "$nm — ${mon(o, "mon")} 매혹: ${mon(o, "atk")} ${boosts(o)}"
            "grudge" -> "$nm — ${mon(o, "mon")}의 원한: ${mon(o, "atk")}에게 ${o.get("dmg")?.asInt} 피해"
            "ppgrudge" -> "$nm — 원념: ${mon(o, "atk")}의 ${moveKo(str(o, "move"))} PP 0"
            else -> return
        }
        runCatching { b.broadcastChatMessage(Component.literal("[시너지] $text").withStyle(ChatFormatting.LIGHT_PURPLE)) }
        TestLog.log("feed", "battle=${b.battleId.toString().take(8)} $text")
    }

    // ------------------------------------------------------------------ 피해 검증

    private val HP = Regex("""^\|-damage\|(p\d)[a-z]?: ([^|]+)\|(\d+)/(\d+)""")

    private fun resolveDamage(w: Watch, line: String) {
        val m = HP.find(line) ?: return
        val (side, uuid, hpText, maxText) = m.destructured
        if (maxText == "100" && w.damage.none { it.uuid == uuid && it.hpBefore <= 100 }) return // 공개용 % 줄은 건너뜀
        val check = w.damage.firstOrNull { it.side == side && it.uuid == uuid } ?: return
        w.damage.remove(check)
        val hp = hpText.toInt()
        val lost = check.hpBefore - hp
        val name = "${check.label} 실제 피해 확인: ${check.text}"
        when {
            hp == 0 && lost < check.expected -> TestLog.log("check", "INFO $name — 쓰러져서 비교 불가 (남은 HP ${check.hpBefore})")
            abs(lost - check.expected) <= 1 -> { TestLog.result(name, true, "실제로 ${lost} 감소"); onCheck?.invoke(name, true, "실제 $lost") }
            else -> {
                // 리플렉터·생명의구슬 같은 다른 보정이 같이 붙으면 숫자가 달라진다
                TestLog.log("check", "INFO $name — 실제 ${lost} (다른 보정이 함께 적용된 것으로 보임)")
            }
        }
    }
}
