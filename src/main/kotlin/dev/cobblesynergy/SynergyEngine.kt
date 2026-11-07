package dev.cobblesynergy

import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.math.roundToInt

/** 한 플레이어 파티에서 계산된 시너지 하나의 상태 */
data class TraitState(
    val def: SynergyDef,
    val count: Int,
    /** 켜진 단계 index (없으면 -1) */
    val activeTier: Int,
) {
    val active get() = activeTier >= 0
    val tier get() = if (active) def.tiers[activeTier] else null
}

object SynergyEngine {
    val TYPE_KO = mapOf(
        "normal" to "노말", "fire" to "불꽃", "water" to "물", "grass" to "풀", "electric" to "전기",
        "ice" to "얼음", "fighting" to "격투", "poison" to "독", "ground" to "땅", "flying" to "비행",
        "psychic" to "에스퍼", "bug" to "벌레", "rock" to "바위", "ghost" to "고스트", "dragon" to "드래곤",
        "dark" to "악", "steel" to "강철", "fairy" to "페어리",
    )
    private val STAT_KO = mapOf("atk" to "공격", "def" to "방어", "spa" to "특공", "spd" to "특방", "spe" to "스피드")
    private val STATUS_KO = mapOf("brn" to "화상", "par" to "마비", "psn" to "독", "tox" to "맹독", "slp" to "잠듦", "frz" to "얼음")

    /** 타입별 마릿수. 복합 타입은 두 타입 모두에 1씩 센다. */
    fun countTypes(party: Iterable<Pokemon?>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (pokemon in party) {
            if (pokemon == null) continue
            for (type in pokemon.types.map { it.name.lowercase() }.distinct()) {
                counts.merge(type, 1, Int::plus)
            }
        }
        return counts
    }

    fun traits(counts: Map<String, Int>): List<TraitState> =
        SynergyConfig.current.defs.map { def ->
            val count = counts[def.type] ?: 0
            var best = -1
            def.tiers.forEachIndexed { index, tier ->
                val ok = if (tier.isExact) count == tier.count else count >= tier.count
                if (ok) best = index
            }
            TraitState(def, count, best)
        }

    // ---------------------------------------------------------------- 설명 문구

    private fun pct(mult: Double) = ((mult - 1.0) * 100).roundToInt()
    private fun pctOf(fraction: Double) = (fraction * 100).roundToInt()

    fun describe(def: SynergyDef, effect: Effect): String {
        val ko = TYPE_KO[def.type] ?: def.type
        val who = if (effect.isTeam) "팀 전체" else "$ko 포켓몬"
        val moveKo = effect.moveType?.let { "${TYPE_KO[it] ?: it} 기술" }
        return when (effect.kind) {
            "power" -> {
                val what = when {
                    effect.onlyNonStab -> "자속이 아닌 기술"
                    moveKo != null -> moveKo
                    else -> "모든 기술"
                }
                "$who $what 위력 +${pct(effect.v)}%"
            }
            "stat" -> {
                val stats = effect.stat.orEmpty().split(',').joinToString("·") { STAT_KO[it.trim()] ?: it }
                "$who $stats +${pct(effect.v)}%"
            }
            "heal" -> "$who 매 턴 종료 시 최대 HP ${"%.1f".format(effect.v * 100)}% 회복"
            "drain" -> "$who 준 피해의 ${pctOf(effect.v)}% 회복"
            "onhit" -> {
                val what = moveKo ?: "공격"
                val result = when {
                    effect.volatile == "flinch" -> "풀죽음 (상대보다 먼저 공격했을 때)"
                    effect.volatile != null -> effect.volatile
                    effect.status != null -> STATUS_KO[effect.status] ?: effect.status
                    effect.boosts != null -> effect.boosts.entries.joinToString(", ") { (s, n) ->
                        "${STAT_KO[s] ?: s} ${kotlin.math.abs(n)}랭크 ${if (n < 0) "하락" else "상승"}"
                    }
                    else -> "효과"
                }
                "$who $what 적중 시 ${pctOf(effect.c)}% 확률로 상대 $result"
            }
            "crit" -> "$who 급소 랭크 +${effect.v.roundToInt()}"
            "execute" -> "$who 이(가) HP ${pctOf(effect.hp)}% 이하인 상대에게 주는 피해 +${pct(effect.v)}%"
            "vsstatus" -> "$who 이(가) 상태이상인 상대에게 주는 피해 +${pct(effect.v)}%"
            "taken" -> "$who 받는 피해 -${-pct(effect.v)}%"
            "charm" -> "$who 공격받으면 ${pctOf(effect.c)}% 확률로 상대 공격(물리)/특공(특수) 1랭크 하락"
            "first" -> "$who 교체해 나온 뒤 첫 기술 위력 +${pct(effect.v)}%"
            "grudge" -> "$who 기절하면 쓰러뜨린 상대에게 그 상대 최대 HP ${pctOf(effect.v)}% 피해"
            "ppgrudge" -> "$who 기절하면 쓰러뜨린 기술의 PP를 모두 없앰 (원념)"
            "sidecond" -> {
                val name = MOVE_KO[effect.id] ?: effect.id
                if (effect.onFoe) "전투 시작 시 상대 진영에 $name${if (effect.n > 1) " ${effect.n}겹" else ""}"
                else "전투 시작 시 우리 진영에 $name ${effect.turns}턴"
            }
            "room", "terrain", "weather" -> "전투 시작 시 ${MOVE_KO[effect.id] ?: effect.id} ${effect.turns}턴"
            "foevolatile" -> "전투 시작 시 상대 선두 포켓몬에게 ${MOVE_KO[effect.id] ?: effect.id}" +
                if (effect.turns > 0) " ${effect.turns}턴" else ""
            "entry" -> {
                val parts = ArrayList<String>()
                effect.volatile?.let { parts += "${MOVE_KO[it] ?: it} 상태" }
                effect.boosts.orEmpty().forEach { (s, n) ->
                    parts += "${if (s == "best") "공격·특공 중 높은 쪽" else STAT_KO[s] ?: s} ${if (n > 0) "+" else ""}${n}랭크"
                }
                (if (effect.firstOnly == true) "처음 나온 첫 번째 ${TYPE_KO[def.type] ?: def.type} 포켓몬만 " else "$who 처음 나올 때 ") +
                    parts.joinToString(", ") + if (effect.turns > 0 && effect.boosts != null) " (${effect.turns}턴)" else ""
            }
            "endure" -> "$who HP가 가득할 때 쓰러질 공격을 한 번 HP 1로 버팀 (포켓몬마다 1회)"
            "priority" -> "$who 교체해 나온 뒤 첫 공격 기술 우선도 +${effect.v.roundToInt()}"
            "odds" -> "매 라운드 시작 시 무료 확률 강화: " +
                (if (effect.id?.trim() == "all") "전설·준전설·환상·패러독스·메가 전부"
                else effect.id.orEmpty().split(',').joinToString(", ") { ODDS_KO[it.trim()] ?: it.trim() }) +
                " (상점 강화 1회분씩, 던전 동안 누적)"
            "oddsbuy" -> "상점의 확률 강화를 라운드당 ${1 + effect.v.roundToInt()}번까지 구매 가능"
            "reroll" -> "매 라운드 리롤 횟수 +${effect.v.roundToInt()}회"
            "laststand" -> "살아남은 $ko 포켓몬이 마지막 한 마리가 되면 (필드에 있을 때) " +
                effect.boosts.orEmpty().entries.joinToString(", ") { (st, n) ->
                    "${if (st == "best") "공격·특공 중 높은 쪽" else STAT_KO[st] ?: st} +${n}랭크"
                } + if (effect.turns > 0) " (${effect.turns}턴, 1회)" else " (1회)"
            else -> effect.kind
        }
    }

    private val MOVE_KO = mapOf(
        "reflect" to "리플렉터", "lightscreen" to "빛의장막", "auroraveil" to "오로라베일", "safeguard" to "신비의부적",
        "tailwind" to "순풍", "mist" to "흰안개", "stealthrock" to "스텔스록", "spikes" to "압정뿌리기",
        "toxicspikes" to "독압정", "stickyweb" to "끈적끈적네트", "trickroom" to "트릭룸", "magicroom" to "매직룸",
        "wonderroom" to "원더룸", "gravity" to "중력", "electricterrain" to "일렉트릭필드", "grassyterrain" to "그래스필드",
        "mistyterrain" to "미스트필드", "psychicterrain" to "사이코필드", "sunnyday" to "쾌청", "raindance" to "비",
        "sandstorm" to "모래바람", "snow" to "설경",
        "aquaring" to "아쿠아링", "ingrain" to "뿌리박기", "focusenergy" to "기합", "leechseed" to "씨뿌리기",
        "taunt" to "도발", "encore" to "앵콜",
    )
    private val ODDS_KO = mapOf(
        "mythical" to "환상", "legendary" to "전설", "sublegendary" to "준전설", "paradox" to "패러독스", "mega" to "메가",
    )

    fun tierLabel(tier: Tier) = if (tier.isExact) "정확히 ${tier.count}" else "${tier.count}"

    // ---------------------------------------------------------------- 클라이언트 표시용

    fun uiJson(traits: List<TraitState>): JsonObject {
        val root = JsonObject()
        root.addProperty("active", true)
        val array = JsonArray()
        // 0마리인 타입도 보내서 상세 화면에서 전체 시너지 목록을 볼 수 있게 한다 (HUD 는 1마리 이상만 표시).
        for (trait in traits) {
            val obj = JsonObject()
            obj.addProperty("type", trait.def.type)
            obj.addProperty("name", trait.def.name)
            obj.addProperty("count", trait.count)
            obj.addProperty("activeTier", trait.activeTier)
            val tiers = JsonArray()
            for (tier in trait.def.tiers) {
                val t = JsonObject()
                t.addProperty("count", tier.count)
                t.addProperty("exact", tier.isExact)
                val desc = JsonArray()
                tier.effectList.forEach { desc.add(describe(trait.def, it)) }
                t.add("desc", desc)
                tiers.add(t)
            }
            obj.add("tiers", tiers)
            array.add(obj)
        }
        root.add("traits", array)
        return root
    }

    /** 채팅용 한 줄 요약 */
    fun summary(traits: List<TraitState>): List<String> =
        traits.filter { it.active }.sortedByDescending { it.activeTier }.map { trait ->
            val tier = trait.tier!!
            "${TYPE_KO[trait.def.type]} ${trait.def.name} (${trait.count}) — " +
                tier.effectList.joinToString(" / ") { describe(trait.def, it) }
        }

    // ---------------------------------------------------------------- 전투(Showdown) 주입용

    private fun showdownType(type: String?) = type?.replaceFirstChar { it.uppercase() }

    /**
     * 켜진 시너지를 Showdown 스크립트가 읽는 형식으로 변환한다.
     * { power:[{t,mt,m,ns}], stat:[{t,s,m}], heal:[{t,f}], ... }
     * t = 효과를 받는 포켓몬 타입 (null = 팀 전체)
     */
    fun battleConfig(traits: List<TraitState>): JsonObject? {
        val root = JsonObject()
        fun list(key: String): JsonArray = root.getAsJsonArray(key) ?: JsonArray().also { root.add(key, it) }
        for (trait in traits) {
            val tier = trait.tier ?: continue
            for (effect in tier.effectList) {
                val e = JsonObject()
                e.addProperty("nm", trait.def.name)
                if (!effect.isTeam) e.addProperty("t", showdownType(trait.def.type))
                when (effect.kind) {
                    "power" -> {
                        effect.moveType?.let { e.addProperty("mt", showdownType(it)) }
                        e.addProperty("ns", effect.onlyNonStab); e.addProperty("m", effect.v)
                    }
                    "stat" -> effect.stat.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { s ->
                        val copy = e.deepCopy(); copy.addProperty("s", s); copy.addProperty("m", effect.v)
                        list("stat").add(copy)
                    }
                    "heal", "drain", "grudge" -> e.addProperty("f", effect.v)
                    "onhit" -> {
                        effect.moveType?.let { e.addProperty("mt", showdownType(it)) }
                        e.addProperty("c", effect.c)
                        effect.status?.let { e.addProperty("st", it) }
                        effect.volatile?.let { e.addProperty("v", it) }
                        effect.boosts?.let { b -> e.add("b", JsonObject().apply { b.forEach { (k, v) -> addProperty(k, v) } }) }
                    }
                    "laststand" -> {
                        effect.boosts?.let { b -> e.add("b", JsonObject().apply { b.forEach { (k, v) -> addProperty(k, v) } }) }
                        if (effect.turns > 0) e.addProperty("d", effect.turns)
                    }
                    "entry" -> {
                        effect.volatile?.let { e.addProperty("v", it) }
                        if (effect.firstOnly == true) e.addProperty("fo", true)
                        if (effect.turns > 0) e.addProperty("d", effect.turns)
                        effect.boosts?.let { b -> e.add("b", JsonObject().apply { b.forEach { (k, v) -> addProperty(k, v) } }) }
                    }
                    "endure", "ppgrudge" -> Unit
                    "priority" -> e.addProperty("n", effect.v.roundToInt())
                    // 전투 시작 시 한 번 적용되는 효과는 모두 "start" 목록으로 모은다.
                    "sidecond", "room", "terrain", "weather", "foevolatile" -> {
                        val s = JsonObject()
                        s.addProperty("k", effect.kind)
                        s.addProperty("nm", trait.def.name)
                        effect.id?.let { s.addProperty("id", it) }
                        s.addProperty("d", effect.turns)
                        s.addProperty("foe", effect.onFoe)
                        s.addProperty("n", effect.n)
                        // 날씨·필드 충돌 시 우선순위: 그 시너지 타입의 마릿수가 많은 쪽 (같으면 무작위)
                        s.addProperty("p", trait.count)
                        list("start").add(s)
                        continue
                    }
                    "crit" -> e.addProperty("n", effect.v.roundToInt())
                    "execute" -> { e.addProperty("m", effect.v); e.addProperty("hp", effect.hp) }
                    "vsstatus", "taken", "first" -> e.addProperty("m", effect.v)
                    "charm" -> e.addProperty("c", effect.c)
                    else -> continue
                }
                if (effect.kind != "stat") list(effect.kind).add(e)
            }
        }
        return if (root.size() == 0) null else root
    }
}
