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

    private fun smartPick(actor: BattleActor, active: ActiveBattlePokemon, moveset: ShowdownMoveset?, forceSwitch: Boolean): ShowdownActionResponse? {
        val foeMon = actor.battle.sides.first { s -> actor !in s.actors }.activePokemon.firstNotNullOfOrNull { it.battlePokemon?.takeIf { b -> b.health > 0 } }
        val foe = foeMon?.let { BotBrain.types(it.effectedPokemon) }.orEmpty()
        val activeIds = actor.activePokemon.mapNotNull { it.battlePokemon?.uuid }.toSet()
        val bench = actor.pokemonList.filter { it.health > 0 && !it.gone && it.uuid !in activeIds && !it.willBeSwitchedIn }
        fun switchTo(b: com.cobblemon.mod.common.battles.pokemon.BattlePokemon): ShowdownActionResponse {
            b.willBeSwitchedIn = true
            return SwitchActionResponse(b.uuid)
        }
        if (forceSwitch || moveset == null) {
            val best = bench.maxByOrNull { BotBrain.switchScore(it.effectedPokemon, foe) } ?: return null
            return switchTo(best)
        }
        val self = active.battlePokemon ?: return null
        val hp = self.health.toDouble() / self.maxHealth.coerceAtLeast(1)
        val usable = moveset.moves.filter { !it.disabled && it.pp > 0 }
        if (usable.isEmpty()) return null
        val scored = usable.map { it to BotBrain.battleMoveScore(self.effectedPokemon, it.id, foe, hp) }
        val (move, score) = scored.maxBy { it.second }
        // 쓸 기술이 거의 안 통하면(무효·반감뿐) 더 유리한 포켓몬으로 교체 (갇힘 상태면 Showdown 이 거절하므로 기술로 대체)
        if (score < 35 && !moveset.trapped && bench.isNotEmpty()) {
            val best = bench.maxBy { BotBrain.switchScore(it.effectedPokemon, foe) }
            if (BotBrain.switchScore(best.effectedPokemon, foe) > BotBrain.switchScore(self.effectedPokemon, foe) + 1.0) return switchTo(best)
        }
        val chosen = if (Math.random() < 0.1) usable.random() else move // 10% 는 다른 기술로 예측을 피한다
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
