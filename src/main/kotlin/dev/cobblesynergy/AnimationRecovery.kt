package dev.cobblesynergy

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.entity.pokemon.effects.IllusionEffect
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.activestate.InactivePokemonState
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * 테스트 빌드 전용: 멈춘 내보내기·회수 연출만 마무리한다. (CobbleLeague 0.7.2 의 BattleAnimationRecovery 를 옮긴 것, MIT)
 * Cobblemon 은 포켓몬 내보내기·회수 연출이 끝날 때까지 전투 처리를 기다리는데, 가끔 연출이 끝나지 않아 전투가 영원히 멈춘다.
 * 설치된 CobbleLeague 0.7.1 에는 이 복구가 없어서, 자동 테스트 전투와 던전 전투 모두 120틱(6초) 넘게 끝나지 않으면 마무리해 준다.
 * 엔진의 행동 선택이나 턴 대기는 건드리지 않는다.
 */
object AnimationRecovery {
    private class Pending(val battle: UUID, val mon: Pokemon, val started: Int, val future: CompletableFuture<*>, val recall: Boolean, val repair: () -> Unit)
    private val pending = ArrayList<Pending>()
    private const val GRACE = 120
    private var now = 0
    var recovered = 0
        private set

    /**
     * 테스트 빌드: 모든 전투. 배포 빌드: CobbleLeague 0.7.1 (자체 복구가 없는 버전)일 때 리그 차원 전투만.
     * CobbleLeague 0.7.2 이상은 같은 복구를 직접 하므로 배포 빌드에서는 아무것도 하지 않는다.
     */
    private val releaseOn: Boolean by lazy {
        net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("cobbleleague")
            .map { it.metadata.version.friendlyString.startsWith("0.7.1") }.orElse(false)
    }
    private fun watched(world: ServerLevel?): Boolean =
        TestMode.enabled || (releaseOn && world?.dimension()?.location()?.toString() == "cobbleleague:league")

    @JvmStatic
    fun watchSendOut(mon: Pokemon, world: ServerLevel, pos: Vec3, id: UUID?, illusion: IllusionEffect?,
                     mutation: (PokemonEntity) -> Unit, future: CompletableFuture<PokemonEntity>) {
        if (id == null || !watched(world)) return
        pending += Pending(id, mon, now, future, false) {
            val entity = mon.entity?.takeUnless { it.isRemoved } ?: run {
                mon.state = InactivePokemonState()
                requireNotNull(mon.sendOut(world, pos, illusion) { mutation(it); it.battleId = id }) { "복구 소환 거절" }
            }
            entity.phasingTargetId = -1; entity.beamMode = 0
            future.complete(entity)
        }
    }

    @JvmStatic
    fun watchRecall(entity: PokemonEntity, future: CompletableFuture<Pokemon>) {
        if (!watched(entity.level() as? ServerLevel)) return
        val id = entity.battleId ?: return
        val mon = entity.pokemon
        pending += Pending(id, mon, now, future, true) {
            if (mon.entity === entity) mon.recall()
            if (!entity.isRemoved) entity.discard()
            future.complete(mon)
        }
    }

    fun tick(tick: Int) {
        now = tick
        if (pending.isEmpty()) return
        val it = pending.iterator()
        val due = ArrayList<Pending>()
        while (it.hasNext()) {
            val op = it.next()
            if (op.future.isDone) { it.remove(); continue }
            if (tick - op.started >= GRACE) { it.remove(); due += op }
        }
        // 회수를 마무리하면 바로 다음 내보내기가 등록될 수 있어 목록에서 뺀 뒤 실행한다
        for (op in due) runCatching { op.repair() }
            .onSuccess { recovered++; TestLog.log("recovery", "멈춘 ${if (op.recall) "회수" else "내보내기"} 연출 마무리 battle=${op.battle.toString().take(8)} ${op.mon.species.name}") }
            .onFailure { e -> TestLog.log("recovery", "연출 마무리 실패 battle=${op.battle.toString().take(8)}: $e") }
    }
}
