package dev.cobblesynergy

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.net.messages.client.battle.BattleEndPacket
import com.cobblemon.mod.common.net.messages.client.battle.BattleInitializePacket
import com.cobblemon.mod.common.net.messages.client.battle.BattleMessagePacket
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * 던전 관전.
 *  - 참가자가 3명 이상이면, 지금 전투하지 않는 참가자(순환 대기, 차례 대기, 이미 경기를 마친 사람)는
 *    진행 중인 다른 참가자의 던전 전투를 관전할 수 있다. (4명 이상이면 자기 경기를 마친 뒤에도 남은 경기를 본다)
 *  - 전투가 시작되면 관전할 수 있는 참가자에게 채팅으로 [관전] 버튼을 보낸다. /synergy watch 로도 연다.
 *  - 관전하면 그 경기장 옆으로 이동하고 Cobblemon 전투 화면이 관전 모드로 열린다.
 *    관전을 끝내거나 그 경기가 끝나면 원래 자리(자기 선택방 등)로 돌아온다. CobbleLeague 는 자기 대전을 시작할 때
 *    선택방 안에 있어야 하므로 반드시 되돌린다. 자기 전투가 시작되면 관전만 끝낸다 (CobbleLeague 가 이미 옮겼으므로 이동 없음).
 */
object Spectate {
    const val MIN_PLAYERS = 3
    private val watching = HashMap<UUID, UUID>() // 관전자 → 전투
    private class Origin(val level: net.minecraft.server.level.ServerLevel, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)
    private val origins = HashMap<UUID, Origin>() // 관전 시작 전 위치

    private fun participants(): Set<UUID> = LeagueBridge.runs().map { it.player }.toSet()

    /** 던전 참가자가 들어 있는 진행 중 전투 */
    private fun dungeonBattles(server: MinecraftServer, people: Set<UUID>): List<PokemonBattle> =
        people.mapNotNull { id -> server.playerList.getPlayer(id)?.let { BattleRegistry.getBattleByParticipatingPlayer(it) } }
            .distinctBy { it.battleId }.filter { !it.ended }

    private fun label(b: PokemonBattle) = b.actors.joinToString(" vs ") { it.getName().string }

    fun onBattleStart(server: MinecraftServer, battle: PokemonBattle) {
        val people = participants()
        val fighters = battle.actors.flatMap { it.getPlayerUUIDs() }.toSet()
        if (fighters.none { it in people }) return
        // 자기 전투가 시작된 사람은 관전 종료
        fighters.forEach { id -> server.playerList.getPlayer(id)?.let { stop(it, silent = true, back = false) } }
        if (people.size < MIN_PLAYERS) return
        for (id in people) {
            if (id in fighters || DungeonBot.isBot(id)) continue
            val p = server.playerList.getPlayer(id) ?: continue
            if (BattleRegistry.getBattleByParticipatingPlayer(p) != null) continue
            p.sendSystemMessage(Component.literal("[시너지] ${label(battle)} 경기가 시작됐습니다. ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("[관전]").withStyle { s ->
                    s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/synergy watch"))
                        .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("클릭하면 이 경기를 관전합니다")))
                }))
        }
    }

    /** /synergy watch : 진행 중인 다른 참가자의 전투를 차례로 관전 (다시 입력하면 다음 경기) */
    fun watch(p: ServerPlayer): String {
        val people = participants()
        if (p.uuid !in people) return "던전 참가자만 관전할 수 있습니다."
        if (people.size < MIN_PLAYERS) return "참가자가 ${MIN_PLAYERS}명 이상일 때만 관전할 수 있습니다."
        if (BattleRegistry.getBattleByParticipatingPlayer(p) != null) return "자기 전투 중에는 관전할 수 없습니다."
        val list = dungeonBattles(p.server, people)
        if (list.isEmpty()) return "지금 진행 중인 경기가 없습니다."
        val current = watching[p.uuid]
        val next = list.getOrNull((list.indexOfFirst { it.battleId == current } + 1) % list.size) ?: list.first()
        val first = p.uuid !in origins
        stop(p, silent = true, back = false)
        if (first) origins[p.uuid] = Origin(p.serverLevel(), p.x, p.y, p.z, p.yRot, p.xRot)
        moveNear(p, next)
        next.spectators.add(p.uuid)
        BattleInitializePacket(next, next.side1).sendToPlayer(p)
        BattleMessagePacket(next.chatLog).sendToPlayer(p)
        watching[p.uuid] = next.battleId
        return "관전: ${label(next)}" + if (list.size > 1) " (다시 /synergy watch 하면 다음 경기, 진행 중 ${list.size}경기)" else ""
    }

    /** back=false: 자기 전투가 시작돼 CobbleLeague 가 이미 옮긴 경우, 또는 다음 경기로 넘어가는 경우 */
    fun stop(p: ServerPlayer, silent: Boolean = false, back: Boolean = true): String {
        val id = watching.remove(p.uuid)
        if (id == null) { if (back) origins.remove(p.uuid); return "관전 중이 아닙니다." }
        BattleRegistry.getBattle(id)?.spectators?.remove(p.uuid)
        if (!silent) runCatching { BattleEndPacket().sendToPlayer(p) }
        if (back) goBack(p)
        return "관전을 끝냈습니다."
    }

    /** 전투가 끝나면 그 전투를 보던 관전자를 원래 자리로 돌려보낸다 (화면은 Cobblemon 이 닫는다) */
    fun onBattleEnd(battle: PokemonBattle): List<UUID> {
        val ids = watching.filterValues { it == battle.battleId }.keys.toList()
        ids.forEach { watching.remove(it) }
        return ids
    }

    /** onBattleEnd 가 돌려준 관전자를 원래 자리로 (CobbleLeague 의 전투 뒤 처리가 끝난 뒤 부른다) */
    fun sendBack(server: MinecraftServer, ids: List<UUID>) =
        ids.forEach { id -> if (id !in watching) server.playerList.getPlayer(id)?.let { goBack(it) } }

    fun forget(id: UUID) { watching.remove(id); origins.remove(id) }

    private fun goBack(p: ServerPlayer) {
        val o = origins.remove(p.uuid) ?: return
        if (BattleRegistry.getBattleByParticipatingPlayer(p) != null) return // 자기 전투 중이면 CobbleLeague 위치를 존중
        runCatching { p.teleportTo(o.level, o.x, o.y, o.z, o.yaw, o.pitch); p.fallDistance = 0f }
    }

    /** 경기자 두 사람 사이 옆쪽, 발 밑이 단단한 곳으로 옮긴다 (경기장 바닥 위, 두 경기자를 바라보게) */
    private fun moveNear(p: ServerPlayer, b: PokemonBattle) {
        val fighters = b.actors.flatMap { it.getPlayerUUIDs() }.mapNotNull { p.server.playerList.getPlayer(it) }
        val anchor = fighters.firstOrNull() ?: return
        val level = anchor.serverLevel()
        val mx = fighters.map { it.x }.average(); val my = fighters.map { it.y }.average(); val mz = fighters.map { it.z }.average()
        val dx = (fighters.lastOrNull()?.x ?: mx) - anchor.x; val dz = (fighters.lastOrNull()?.z ?: mz) - anchor.z
        val len = kotlin.math.sqrt(dx * dx + dz * dz).takeIf { it > 0.1 } ?: 1.0
        val nx = -dz / len; val nz = dx / len // 경기자를 잇는 선의 옆 방향
        val y = kotlin.math.floor(my)
        fun solid(x: Double, z: Double): Boolean {
            val pos = net.minecraft.core.BlockPos.containing(x, y - 0.5, z)
            return level.getBlockState(pos).isSolidRender(level, pos) && level.getBlockState(pos.above()).isAir && level.getBlockState(pos.above(2)).isAir
        }
        val spot = listOf(10.0, -10.0, 7.0, -7.0, 4.0, -4.0).map { d -> mx + nx * d to mz + nz * d }.firstOrNull { (x, z) -> solid(x, z) }
            ?: (mx to mz)
        val (x, z) = spot
        val yaw = (Math.toDegrees(kotlin.math.atan2(-(mx - x), mz - z))).toFloat()
        p.teleportTo(level, x, y, z, yaw, 15f)
        p.fallDistance = 0f
    }
}
