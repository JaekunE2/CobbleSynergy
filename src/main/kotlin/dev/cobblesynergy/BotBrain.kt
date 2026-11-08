package dev.cobblesynergy

import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.Pokemon

/**
 * AI 트레이너(가상 플레이어)의 판단.
 *  - 파티 점수 = 시너지(높은 단계·6 시너지일수록 크게) + 종족값 + 다음 상대(또는 다른 참가자) 파티에 대한 상성
 *  - 기술 점수 = 위력 × 자속 × 공격/특공 중 맞는 쪽 × 명중 × 상대 파티에 대한 상성, 쓸 만한 변화기는 고정 점수
 *  - 전투에서는 상대 현재 포켓몬에게 가장 아픈 기술을 쓰고, 기술이 전부 통하지 않으면 유리한 포켓몬으로 교체한다.
 */
object BotBrain {
    // ------------------------------------------------------------------ 타입 상성 (6세대 이후)

    private val SUPER = mapOf(
        "normal" to "", "fire" to "grass ice bug steel", "water" to "fire ground rock", "grass" to "water ground rock",
        "electric" to "water flying", "ice" to "grass ground flying dragon", "fighting" to "normal ice rock dark steel",
        "poison" to "grass fairy", "ground" to "fire electric poison rock steel", "flying" to "grass fighting bug",
        "psychic" to "fighting poison", "bug" to "grass psychic dark", "rock" to "fire ice flying bug",
        "ghost" to "psychic ghost", "dragon" to "dragon", "dark" to "psychic ghost", "steel" to "ice rock fairy",
        "fairy" to "fighting dragon dark",
    ).mapValues { it.value.split(' ').filter(String::isNotEmpty).toSet() }
    private val WEAK = mapOf(
        "normal" to "rock steel", "fire" to "fire water rock dragon", "water" to "water grass dragon",
        "grass" to "fire grass poison flying bug dragon steel", "electric" to "electric grass dragon",
        "ice" to "fire water ice steel", "fighting" to "poison flying psychic bug fairy", "poison" to "poison ground rock ghost",
        "ground" to "grass bug", "flying" to "electric rock steel", "psychic" to "psychic steel", "bug" to "fire fighting poison flying ghost steel fairy",
        "rock" to "fighting ground steel", "ghost" to "dark", "dragon" to "steel", "dark" to "fighting dark fairy",
        "steel" to "fire water electric steel", "fairy" to "fire poison steel",
    ).mapValues { it.value.split(' ').filter(String::isNotEmpty).toSet() }
    private val IMMUNE = mapOf(
        "normal" to "ghost", "electric" to "ground", "fighting" to "ghost", "poison" to "steel", "ground" to "flying",
        "psychic" to "dark", "ghost" to "normal", "dragon" to "fairy",
    ).mapValues { it.value.split(' ').toSet() }

    fun effectiveness(attack: String, defence: Collection<String>): Double = defence.distinct().fold(1.0) { m, d ->
        m * when (d) {
            in IMMUNE[attack].orEmpty() -> 0.0
            in SUPER[attack].orEmpty() -> 2.0
            in WEAK[attack].orEmpty() -> 0.5
            else -> 1.0
        }
    }

    fun types(mon: Pokemon): List<String> = mon.types.map { it.name.lowercase() }.distinct()

    // ------------------------------------------------------------------ 파티 평가

    private fun stat(mon: Pokemon, s: Stats): Int = mon.form.baseStats[s] ?: 0
    fun bst(mon: Pokemon): Int = mon.form.baseStats.values.sum()

    /** 종족값 점수: 600 → 60, 450 → 30 */
    private fun strength(mon: Pokemon) = (bst(mon) - 300).coerceAtLeast(0) / 5.0

    /** 시너지 점수. 높은 단계, 6마리 단계는 크게. 다음 단계까지 1마리 남은 시너지는 파티가 덜 찼을 때 조금 더한다. */
    fun synergyScore(party: List<Pokemon>): Double {
        val traits = SynergyEngine.traits(SynergyEngine.countTypes(party))
        var score = 0.0
        for (t in traits) {
            val tier = t.tier
            if (tier != null) {
                score += if (tier.isExact) 10.0 else tier.count * (t.activeTier + 1) * 5.0
                if (!tier.isExact && tier.count >= 6) score += 45.0
            }
            if (party.size < 6) {
                val next = t.def.tiers.filter { !it.isExact && it.count > t.count }.minByOrNull { it.count }
                if (next != null && next.count - t.count == 1) score += next.count * 1.5
            }
        }
        return score
    }

    /** 상대 파티에 대한 상성: 상대 포켓몬마다 우리 쪽에 자속으로 약점을 찌를 포켓몬이 있으면 +, 우리 포켓몬이 상대 자속에 약하면 - */
    fun matchupScore(party: List<Pokemon>, threats: List<Pokemon>): Double {
        if (threats.isEmpty()) return 0.0
        var score = 0.0
        for (t in threats) {
            val dt = types(t)
            val best = party.maxOfOrNull { m -> types(m).maxOf { effectiveness(it, dt) } } ?: 1.0
            score += when { best >= 4 -> 6.0; best >= 2 -> 4.0; best < 1 -> -2.0; else -> 0.0 }
        }
        for (m in party) {
            val mt = types(m)
            val danger = threats.count { t -> types(t).any { effectiveness(it, mt) >= 2 } }
            score -= danger * 1.5
        }
        return score * 6.0 / threats.size.coerceAtLeast(6) // 상대 파티 크기와 상관없이 같은 크기로
    }

    fun partyScore(party: List<Pokemon>, threats: List<Pokemon>, owner: java.util.UUID? = null): Double =
        synergyScore(party) + party.sumOf { strength(it) } + matchupScore(party, threats) + counterScore(owner, party)

    /** 후보를 넣을 때 가장 좋은 결과: (점수, 바꿀 칸 또는 -1) */
    fun bestPlacement(party: List<Pokemon>, candidate: Pokemon, threats: List<Pokemon>, owner: java.util.UUID? = null): Pair<Double, Int> {
        if (party.size < 6) return partyScore(party + candidate, threats, owner) to -1
        return party.indices.map { slot -> partyScore(party.toMutableList().also { it[slot] = candidate }, threats, owner) to slot }
            .maxBy { it.first }
    }

    // ------------------------------------------------------------------ 기술

    private val GOOD_STATUS = mapOf(
        "swordsdance" to 75.0, "nastyplot" to 75.0, "dragondance" to 80.0, "quiverdance" to 85.0, "shellsmash" to 85.0,
        "calmmind" to 65.0, "bulkup" to 65.0, "irondefense" to 35.0, "agility" to 45.0, "coil" to 60.0, "victorydance" to 85.0,
        "recover" to 60.0, "roost" to 60.0, "softboiled" to 60.0, "slackoff" to 60.0, "moonlight" to 50.0, "synthesis" to 50.0,
        "morningsun" to 50.0, "shoreup" to 60.0, "willowisp" to 55.0, "toxic" to 55.0, "thunderwave" to 50.0,
        "spore" to 80.0, "sleeppowder" to 55.0, "stealthrock" to 50.0, "spikes" to 40.0, "toxicspikes" to 35.0,
        "protect" to 40.0, "substitute" to 40.0, "leechseed" to 45.0, "taunt" to 35.0, "trickroom" to 30.0, "tailwind" to 40.0,
        "reflect" to 35.0, "lightscreen" to 35.0, "auroraveil" to 45.0, "encore" to 40.0, "uturn" to 0.0,
    )

    private fun isStatus(m: MoveTemplate) = m.damageCategory.name.equals("status", true)
    private fun physical(m: MoveTemplate) = m.damageCategory.name.equals("physical", true)

    /** 기술 하나의 가치 (상대 파티 기준) */
    fun moveValue(mon: Pokemon, m: MoveTemplate, threats: List<Pokemon>): Double {
        if (isStatus(m)) return GOOD_STATUS[m.name] ?: 8.0
        val power = if (m.power > 0) m.power.coerceAtMost(150.0) else 60.0
        val type = m.elementalType.name.lowercase()
        val stab = if (type in types(mon)) 1.5 else 1.0
        val atk = stat(mon, Stats.ATTACK).toDouble(); val spa = stat(mon, Stats.SPECIAL_ATTACK).toDouble()
        val ratio = (if (physical(m)) atk else spa) / maxOf(atk, spa, 1.0)
        val acc = if (m.accuracy <= 0 || m.accuracy > 100) 1.0 else m.accuracy / 100.0
        val coverage = if (threats.isEmpty()) 1.0 else threats.map { effectiveness(type, types(it)).coerceIn(0.25, 2.0) }.average()
        return power * stab * ratio * acc * coverage
    }

    /** 이 기술 세트에 더할 가치: 변화기는 하나만 높게 치고, 같은 타입 공격기가 겹치면 낮춘다 */
    fun setValue(mon: Pokemon, moves: List<MoveTemplate>, threats: List<Pokemon>): Double {
        var total = 0.0
        val seenTypes = HashSet<String>()
        var status = 0
        for (m in moves.sortedByDescending { moveValue(mon, it, threats) }) {
            val v = moveValue(mon, m, threats)
            total += when {
                isStatus(m) -> if (status++ == 0) v else v * 0.3
                !seenTypes.add(m.elementalType.name.lowercase()) -> v * 0.35
                else -> v
            }
        }
        if (moves.none { !isStatus(it) }) total -= 100 // 공격기가 하나도 없으면 크게 깎는다
        return total
    }

    /** 배울 수 있는 기술 중 가장 이득인 교체: (기술, 칸, 이득) */
    fun bestLesson(mon: Pokemon, learnable: Collection<MoveTemplate>, threats: List<Pokemon>): Triple<MoveTemplate, Int, Double>? {
        val current = (0..3).map { mon.moveSet[it]?.template }
        val known = current.filterNotNull()
        val base = setValue(mon, known, threats)
        var best: Triple<MoveTemplate, Int, Double>? = null
        for (m in learnable) {
            if (known.any { it.name == m.name }) continue
            for (slot in 0..3) {
                val next = current.toMutableList().also { it[slot] = m }.filterNotNull()
                val gain = setValue(mon, next, threats) - base
                if (gain > (best?.third ?: 0.0)) best = Triple(m, slot, gain)
            }
        }
        return best
    }

    // ------------------------------------------------------------------ 기억: 대항하지 못한 상대

    /** AI 트레이너별로 고전한 상대 포켓몬의 타입 조합과 무게. 새 전투마다 조금씩 잊는다. */
    private val problems = HashMap<java.util.UUID, HashMap<String, Double>>()

    /** 대항할 포켓몬이 없었거나 우리 포켓몬을 쓰러뜨린 상대를 기억한다 */
    fun noteProblem(owner: java.util.UUID, foe: Pokemon, weight: Double) {
        val key = types(foe).sorted().joinToString("/")
        val m = problems.getOrPut(owner) { HashMap() }
        m[key] = ((m[key] ?: 0.0) + weight).coerceAtMost(6.0)
    }

    fun newBattle(owner: java.util.UUID) {
        problems[owner]?.let { m -> m.replaceAll { _, w -> w * 0.75 }; m.values.removeIf { it < 0.3 } }
    }

    fun forget(owner: java.util.UUID) { problems.remove(owner) }

    fun problemsText(owner: java.util.UUID): String = problems[owner].orEmpty().entries.sortedByDescending { it.value }.take(4)
        .joinToString(", ") { (k, w) -> k.split('/').joinToString("/") { SynergyEngine.TYPE_KO[it] ?: it } + " ×" + "%.1f".format(w) }

    /** 기억한 상대마다 파티 안에서 가장 잘 대항하는 포켓몬(그 상대의 자속을 반감 이하로 받고, 자속으로 약점을 찌름)의 점수 */
    fun counterScore(owner: java.util.UUID?, party: List<Pokemon>): Double {
        val m = owner?.let { problems[it] } ?: return 0.0
        var score = 0.0
        for ((key, w) in m) {
            val foe = key.split('/')
            val best = party.maxOfOrNull { mon ->
                val mt = types(mon)
                val taken = foe.maxOf { effectiveness(it, mt) }
                val dealt = mt.maxOf { effectiveness(it, foe) }
                (if (taken <= 0.5) 3.0 else if (taken < 2) 1.0 else -1.0) + (if (dealt >= 2) 3.0 else 0.0)
            } ?: 0.0
            score += best * w * 1.5
        }
        return score.coerceIn(-20.0, 40.0)
    }

    // ------------------------------------------------------------------ 상대 공격 예상

    private fun moveId(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    /** 상대가 쓸 공격 타입 예상: 자속 타입 + 이번 전투에서 실제로 쓴 공격기의 타입 */
    fun predictAttacks(foe: Pokemon, usedMoves: Collection<String>): Set<String> {
        val seen = usedMoves.mapNotNull { Moves.getByName(moveId(it)) }.filter { !isStatus(it) }.map { it.elementalType.name.lowercase() }
        return (types(foe) + seen).toSet()
    }

    /** 예상 공격 중 가장 아픈 배율 */
    fun danger(mon: Pokemon, attacks: Set<String>): Double = attacks.maxOfOrNull { effectiveness(it, types(mon)) } ?: 1.0

    /** 우리 포켓몬이 가진 공격기로 상대에게 줄 수 있는 가장 큰 배율 (자속 1.5 포함) */
    fun offense(mon: Pokemon, foe: List<String>): Double = mon.moveSet.getMoves().filter { !isStatus(it.template) }
        .maxOfOrNull { m -> val t = m.template.elementalType.name.lowercase(); effectiveness(t, foe) * (if (t in types(mon)) 1.5 else 1.0) } ?: 0.0

    // ------------------------------------------------------------------ 전투

    /** 전투 중 기술 점수 (상대 현재 포켓몬 기준). 상태 기술은 HP 가 많고 상대에게 큰 피해를 못 줄 때만 의미 있게 본다. */
    fun battleMoveScore(mon: Pokemon, moveId: String, foe: List<String>, hpRatio: Double): Double {
        val m = Moves.getByName(moveId) ?: return 10.0
        if (isStatus(m)) return (GOOD_STATUS[m.name] ?: 5.0) * (if (hpRatio > 0.7) 1.0 else 0.3)
        val type = m.elementalType.name.lowercase()
        val eff = if (foe.isEmpty()) 1.0 else effectiveness(type, foe)
        val power = if (m.power > 0) m.power.coerceAtMost(150.0) else 60.0
        val stab = if (type in types(mon)) 1.5 else 1.0
        val atk = stat(mon, Stats.ATTACK).toDouble(); val spa = stat(mon, Stats.SPECIAL_ATTACK).toDouble()
        val ratio = (if (physical(m)) atk else spa) / maxOf(atk, spa, 1.0)
        val acc = if (m.accuracy <= 0 || m.accuracy > 100) 1.0 else m.accuracy / 100.0
        return power * stab * ratio * acc * eff
    }

    /** 상대 현재 포켓몬을 상대로 한 우리 포켓몬의 유리함 (교체 대상 고르기) */
    fun switchScore(mon: Pokemon, foe: List<String>): Double {
        if (foe.isEmpty()) return bst(mon) / 100.0
        val attack = mon.moveSet.getMoves().filter { !isStatus(it.template) }
            .maxOfOrNull { effectiveness(it.template.elementalType.name.lowercase(), foe) * (if (it.template.elementalType.name.lowercase() in types(mon)) 1.5 else 1.0) } ?: 0.5
        val danger = foe.maxOf { effectiveness(it, types(mon)) }
        return attack * 2 - danger + bst(mon) / 300.0
    }
}
