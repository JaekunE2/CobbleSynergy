package dev.cobblesynergy

import com.cobblemon.mod.common.api.battles.model.actor.AIBattleActor
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.battles.ActiveBattlePokemon
import com.cobblemon.mod.common.battles.MoveActionResponse
import com.cobblemon.mod.common.battles.PassActionResponse
import com.cobblemon.mod.common.battles.ShowdownActionRequest
import com.cobblemon.mod.common.battles.ShowdownActionResponse
import com.cobblemon.mod.common.battles.ShowdownMoveset
import com.cobblemon.mod.common.battles.SwitchActionResponse
import com.cobblemon.mod.common.battles.ai.RandomBattleAI
import java.util.UUID

/**
 * 자동 테스트 NPC 의 행동 선택.
 * Cobblemon 의 강한 AI(StrongBattleAI)는 NPC 끼리 붙으면 NullPointerException 을 내고, 첫 요청에 너무 일찍 답해
 * "Team Preview" 오류로 거부당해 전투가 멈췄다. CobbleLeague 의 관장전 NPC 처럼 전투 처리가 비었을 때(doWhenClear)
 * 요청마다 한 번만 고르고, 고른 행동이 규칙에 맞는지 Cobblemon 의 검사(isValid)로 확인한다.
 */
object TestDriver {
    val actors = HashSet<UUID>()
    private val scheduled = HashMap<UUID, ShowdownActionRequest>()
    private val answered = HashMap<UUID, ShowdownActionRequest>()
    private val random = RandomBattleAI()
    var choices = 0
        private set

    @JvmStatic
    fun handles(actor: AIBattleActor): Boolean = TestMode.enabled && actor.uuid in actors

    @JvmStatic
    fun onRequest(actor: AIBattleActor) {
        val request = actor.request ?: return
        if (scheduled[actor.uuid] === request || answered[actor.uuid] === request) return
        scheduled[actor.uuid] = request
        actor.battle.doWhenClear {
            if (scheduled[actor.uuid] === request) scheduled.remove(actor.uuid)
            if (!actor.battle.ended && actor.request === request && answered[actor.uuid] !== request) {
                answered[actor.uuid] = request
                choose(actor, request)
            }
        }
    }

    /** 멈춤 감지에서 다시 부를 때: 이미 답한 요청이라도 응답이 비어 있으면 다시 고른다 */
    fun retry(actor: AIBattleActor) {
        val request = actor.request ?: return
        if (actor.responses.isNotEmpty()) return
        scheduled.remove(actor.uuid); answered.remove(actor.uuid)
        onRequest(actor)
    }

    fun forget(uuid: UUID) {
        actors.remove(uuid); scheduled.remove(uuid); answered.remove(uuid)
    }

    /** smart: AI 트레이너용. 상대 현재 포켓몬에게 가장 아픈 기술을 쓰고, 통하는 기술이 없으면 유리한 포켓몬으로 교체한다. */
    fun choose(actor: BattleActor, request: ShowdownActionRequest, smart: Boolean = false) {
        try {
            val responses = request.iterate(actor.activePokemon) { active, moveset, forceSwitch ->
                (if (smart) runCatching { smartPick(actor, active, moveset, forceSwitch) }.getOrNull() else null)
                    ?: pick(actor, active, moveset, forceSwitch)
            }
            actor.setActionResponses(responses)
            choices++
        } catch (e: Exception) {
            TestLog.log("driver", "${actor.showdownId} 선택 실패: $e ${e.stackTrace.take(3).joinToString(" < ")}")
            runCatching { actor.setActionResponses(request.iterate(actor.activePokemon) { _, _, _ -> PassActionResponse }) }
        } finally {
            actor.pokemonList.forEach { it.willBeSwitchedIn = false }
        }
    }

    /** 이번 전투에서 상대 포켓몬이 쓴 기술 이름 (Showdown 로그의 |move| 줄) */
    private fun usedMoves(actor: BattleActor, foe: com.cobblemon.mod.common.battles.pokemon.BattlePokemon): List<String> {
        val foeSide = actor.battle.actors.firstOrNull { foe in it.pokemonList }?.showdownId ?: return emptyList()
        val keys = listOf(foe.uuid.toString(), foe.effectedPokemon.getDisplayName().string, foe.effectedPokemon.species.name)
        return runCatching {
            actor.battle.showdownMessages.flatMap { it.split('\n') }.filter { it.startsWith("|move|$foeSide") }
                .mapNotNull { line -> val parts = line.split('|'); if (parts.size > 3 && keys.any { k -> parts[2].contains(k, true) }) parts[3] else null }
        }.getOrDefault(emptyList())
    }

    /** 마지막으로 스스로 교체한 턴 (연달아 교체하지 않게) */
    private val lastSwitch = HashMap<UUID, Int>()

    private fun smartPick(actor: BattleActor, active: ActiveBattlePokemon, moveset: ShowdownMoveset?, forceSwitch: Boolean): ShowdownActionResponse? {
        val foeBp = actor.battle.sides.first { s -> actor !in s.actors }.activePokemon.firstNotNullOfOrNull { it.battlePokemon?.takeIf { b -> b.health > 0 } }
        val foe = foeBp?.let { BotBrain.types(it.effectedPokemon) }.orEmpty()
        val attacks = foeBp?.let { BotBrain.predictAttacks(it.effectedPokemon, usedMoves(actor, it)) }.orEmpty()
        val activeIds = actor.activePokemon.mapNotNull { it.battlePokemon?.uuid }.toSet()
        val bench = actor.pokemonList.filter { it.health > 0 && !it.gone && it.uuid !in activeIds && !it.willBeSwitchedIn }
        fun hpOf(b: com.cobblemon.mod.common.battles.pokemon.BattlePokemon) = b.health.toDouble() / b.maxHealth.coerceAtLeast(1)
        /** 상대 예상 공격을 얼마나 버티고 얼마나 아프게 때리는지 */
        fun counter(b: com.cobblemon.mod.common.battles.pokemon.BattlePokemon): Double {
            val mon = b.effectedPokemon
            val taken = if (attacks.isEmpty()) 1.0 else BotBrain.danger(mon, attacks)
            return BotBrain.offense(mon, foe) * 2 - taken * 2 + hpOf(b) + BotBrain.bst(mon) / 600.0
        }
        fun switchTo(b: com.cobblemon.mod.common.battles.pokemon.BattlePokemon): ShowdownActionResponse {
            b.willBeSwitchedIn = true
            lastSwitch[actor.uuid] = actor.battle.turn
            return SwitchActionResponse(b.uuid)
        }
        if (forceSwitch || moveset == null) {
            val best = bench.maxByOrNull { counter(it) } ?: return null
            // 내보낼 포켓몬마저 상대 예상 공격에 약하면 이 상대를 기억해 다음 영입에서 대항할 포켓몬을 찾는다
            if (foeBp != null && attacks.isNotEmpty() && BotBrain.danger(best.effectedPokemon, attacks) >= 2) BotBrain.noteProblem(actor.uuid, foeBp.effectedPokemon, 1.0)
            return best.let(::switchTo)
        }
        val self = active.battlePokemon ?: return null
        val hp = hpOf(self)
        val usable = moveset.moves.filter { !it.disabled && it.pp > 0 }
        if (usable.isEmpty()) return null
        // 이 포켓몬이 이번 전투에서 쓴 기술 (능력 올리기·장판 같은 변화기를 몇 번 썼는지)
        val mine = usedMoves(actor, self).map { n -> n.lowercase().filter { c -> c.isLetterOrDigit() } }
        val foeStatused = foeBp?.effectedPokemon?.status != null
        val (move, score) = usable.map {
            it to BotBrain.battleMoveScore(self.effectedPokemon, it.id, foe, hp, foeStatused, mine.count { n -> n == it.id })
        }.maxBy { it.second }

        // 교체는 확실히 이득일 때만: (1) 지금 포켓몬이 상대 예상 공격에 약점이고 약점을 찌를 기술도 없거나,
        // (2) 쓸 기술이 전부 거의 안 통할 때. 그리고 들어갈 포켓몬이 그 공격을 반감 이하로 받고, 체력이 절반 이상이며,
        // 상대에게 지금 포켓몬보다 분명히 더 아프게 때릴 수 있어야 한다. 2턴 안에 다시 교체하지 않는다.
        val recent = actor.battle.turn - (lastSwitch[actor.uuid] ?: -99) < 2
        if (foeBp != null && !moveset.trapped && !recent && bench.isNotEmpty()) {
            val inDanger = attacks.isNotEmpty() && BotBrain.danger(self.effectedPokemon, attacks) >= 2 && BotBrain.offense(self.effectedPokemon, foe) < 2
            val useless = score < 35
            if (inDanger || useless) {
                val safe = bench.filter { b -> hpOf(b) >= 0.5 && (attacks.isEmpty() || BotBrain.danger(b.effectedPokemon, attacks) <= 0.5) }
                val best = safe.maxByOrNull { counter(it) }
                if (best != null && counter(best) >= counter(self) + 2.0 && BotBrain.offense(best.effectedPokemon, foe) >= 1.0) {
                    TestLog.log("driver", "${actor.showdownId} 교체: ${self.effectedPokemon.species.name} → ${best.effectedPokemon.species.name} (상대 예상 공격 ${attacks.joinToString("/")})")
                    return switchTo(best)
                }
                // 대항할 포켓몬이 없으면 그대로 싸우고, 이 상대를 기억한다
                BotBrain.noteProblem(actor.uuid, foeBp.effectedPokemon, if (inDanger) 0.5 else 0.3)
            }
        }
        // 10% 는 다른 기술로 예측을 피한다 (단, 최고 점수의 절반 이상인 기술 중에서만)
        val alternatives = usable.filter { BotBrain.battleMoveScore(self.effectedPokemon, it.id, foe, hp, foeStatused, mine.count { n -> n == it.id }) >= score * 0.5 }
        val chosen = if (Math.random() < 0.1 && alternatives.isNotEmpty()) alternatives.random() else move
        val target = runCatching { chosen.getTargets(active)?.firstOrNull()?.getPNX() }.getOrNull()
        val r = MoveActionResponse(chosen.id, target, null)
        return if (runCatching { r.isValid(active, moveset, false) }.getOrDefault(true)) r else null
    }

    private fun pick(actor: BattleActor, active: ActiveBattlePokemon, moveset: ShowdownMoveset?, forceSwitch: Boolean): ShowdownActionResponse {
        val fromAi = runCatching { random.choose(active, actor.battle, actor.battle.sides.first { s -> actor in s.actors }, moveset ?: return@runCatching null, forceSwitch) }.getOrNull()
        val activeNow = actor.activePokemon.mapNotNull { it.battlePokemon?.uuid }.toSet()
        val badSwitch = fromAi is SwitchActionResponse && actor.pokemonList.firstOrNull { it.uuid == fromAi.newPokemonId }
            .let { it == null || it.uuid in activeNow || it.health <= 0 || it.gone || it.willBeSwitchedIn }
        if (fromAi != null && !badSwitch && runCatching { fromAi.isValid(active, moveset, forceSwitch) }.getOrDefault(false)) {
            if (fromAi is SwitchActionResponse) actor.pokemonList.firstOrNull { it.uuid == fromAi.newPokemonId }?.willBeSwitchedIn = true
            return fromAi
        }
        // 직접 고르기: 기술이 있으면 쓸 수 있는 기술 중 하나, 아니면 남은 포켓몬으로 교체
        if (!forceSwitch && moveset != null) {
            val usable = moveset.moves.filter { !it.disabled && it.pp > 0 }
            val move = usable.randomOrNull() ?: moveset.moves.firstOrNull()
            if (move != null) {
                val target = runCatching { move.getTargets(active)?.randomOrNull()?.getPNX() }.getOrNull()
                val r = MoveActionResponse(move.id, target, null)
                if (runCatching { r.isValid(active, moveset, false) }.getOrDefault(true)) return r
            }
        }
        val activeIds = actor.activePokemon.mapNotNull { it.battlePokemon?.uuid }.toSet()
        val bench = actor.pokemonList.firstOrNull { it.health > 0 && !it.gone && it.uuid !in activeIds && !it.willBeSwitchedIn }
        if (bench != null) {
            bench.willBeSwitchedIn = true
            return SwitchActionResponse(bench.uuid)
        }
        return PassActionResponse
    }
}
