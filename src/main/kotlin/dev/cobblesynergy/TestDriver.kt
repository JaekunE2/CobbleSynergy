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

    fun choose(actor: BattleActor, request: ShowdownActionRequest) {
        try {
            val responses = request.iterate(actor.activePokemon) { active, moveset, forceSwitch -> pick(actor, active, moveset, forceSwitch) }
            actor.setActionResponses(responses)
            choices++
        } catch (e: Exception) {
            TestLog.log("driver", "${actor.showdownId} 선택 실패: $e ${e.stackTrace.take(3).joinToString(" < ")}")
            runCatching { actor.setActionResponses(request.iterate(actor.activePokemon) { _, _, _ -> PassActionResponse }) }
        } finally {
            actor.pokemonList.forEach { it.willBeSwitchedIn = false }
        }
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
