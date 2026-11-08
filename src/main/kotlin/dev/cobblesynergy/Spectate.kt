package dev.cobblesynergy

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import dev.cobbleleague.LeagueMod
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** The league owns spectator access, teleportation, return positions and battle lifecycle. */
object Spectate {
    fun onBattleStart(server:MinecraftServer,battle:PokemonBattle) {
        if(battle.ended)return
        val league=LeagueMod.service?:return
        for(id in league.spectatorParticipants()){
            val p=server.playerList.getPlayer(id)?:continue
            if(DungeonBot.isBot(id)||!league.canSpectate(id))continue
            p.sendSystemMessage(Component.literal("[시너지] 관전 가능한 경기가 있습니다. ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("[경기 선택]").withStyle{it.withColor(ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND,"/synergy watch"))}))
        }
    }
    fun watch(p:ServerPlayer):String {
        val league=LeagueMod.service?:return "코블리그를 사용할 수 없습니다."
        league.openWatchMenu(p)
        return "관전할 경기를 선택하세요. 자기 전투 중에는 관전할 수 없습니다."
    }
    fun stop(p:ServerPlayer,silent:Boolean=false):String=LeagueMod.service?.stopWatching(p)?:"관전 중이 아닙니다."
    fun onBattleEnd(battle:PokemonBattle) { /* Core tick and stopBattle restore each observer. */ }
}
