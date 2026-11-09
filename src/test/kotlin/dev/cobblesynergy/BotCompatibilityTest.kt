package dev.cobblesynergy
import kotlin.test.*
import java.util.UUID
class BotCompatibilityTest {
    @Test fun `switch cooldown is scoped to a battle and expires after two turns`(){
        val c=BotSwitchCooldown();val actor=UUID.randomUUID();val old=UUID.randomUUID();val next=UUID.randomUUID()
        c.record(actor,old,40)
        assertTrue(c.recent(actor,old,41));assertFalse(c.recent(actor,old,42))
        assertFalse(c.recent(actor,next,1));assertFalse(c.recent(actor,old,1))
        c.record(actor,next,2);assertTrue(c.recent(actor,next,3));c.forget(actor);assertFalse(c.recent(actor,next,3))
    }
    @Test fun `glove values punching moves without requiring iron fist`(){
        assertEquals(40.0,BotBrain.punchingGloveValue(listOf("surgingstrikes","closecombat")))
        assertEquals(40.0,BotBrain.punchingGloveValue(listOf("ragefist")))
        assertEquals(0.0,BotBrain.punchingGloveValue(listOf("flamethrower","protect")))
    }
}
