package dev.cobblesynergy
import java.util.UUID

/** A player's prior battle must never lock switches in the next battle. */
internal class BotSwitchCooldown {
    private data class Entry(val battle:UUID,val turn:Int)
    private val entries=mutableMapOf<UUID,Entry>()
    fun record(actor:UUID,battle:UUID,turn:Int){entries[actor]=Entry(battle,turn)}
    fun recent(actor:UUID,battle:UUID,turn:Int):Boolean {
        val last=entries[actor]?:return false
        return last.battle==battle && turn>=last.turn && turn-last.turn<2
    }
    fun forget(actor:UUID){entries.remove(actor)}
}
