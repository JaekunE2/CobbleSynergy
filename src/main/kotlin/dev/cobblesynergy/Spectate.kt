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
 *  - 위치는 그대로이고 Cobblemon 전투 화면만 관전 모드로 열린다. 자기 전투가 시작되면 자동으로 관전을 끝낸다.
 */
object Spectate {
    const val MIN_PLAYERS = 3
    private val watching = HashMap<UUID, UUID>() // 관전자 → 전투

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
        fighters.forEach { id -> server.playerList.getPlayer(id)?.let { stop(it, silent = true) } }
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
        stop(p, silent = true)
        next.spectators.add(p.uuid)
        BattleInitializePacket(next, next.side1).sendToPlayer(p)
        BattleMessagePacket(next.chatLog).sendToPlayer(p)
        watching[p.uuid] = next.battleId
        return "관전: ${label(next)}" + if (list.size > 1) " (다시 /synergy watch 하면 다음 경기, 진행 중 ${list.size}경기)" else ""
    }

    fun stop(p: ServerPlayer, silent: Boolean = false): String {
        val id = watching.remove(p.uuid) ?: return "관전 중이 아닙니다."
        BattleRegistry.getBattle(id)?.spectators?.remove(p.uuid)
        if (!silent) runCatching { BattleEndPacket().sendToPlayer(p) }
        return "관전을 끝냈습니다."
    }

    /** 전투가 끝나면 그 전투를 보던 관전자 기록을 지운다 (화면은 Cobblemon 이 닫는다) */
    fun onBattleEnd(battle: PokemonBattle) {
        watching.entries.removeIf { it.value == battle.battleId }
    }
}
