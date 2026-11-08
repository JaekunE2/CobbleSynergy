package dev.cobblesynergy

import kotlin.test.Test
import kotlin.test.assertEquals

class BotBrainTest {
    @Test
    fun typeChart() {
        assertEquals(4.0, BotBrain.effectiveness("fire", listOf("grass", "steel")))
        assertEquals(0.0, BotBrain.effectiveness("electric", listOf("water", "ground")))
        assertEquals(0.25, BotBrain.effectiveness("grass", listOf("fire", "flying")))
        assertEquals(2.0, BotBrain.effectiveness("fairy", listOf("dragon")))
        assertEquals(0.0, BotBrain.effectiveness("dragon", listOf("fairy")))
        assertEquals(1.0, BotBrain.effectiveness("normal", listOf("water")))
        assertEquals(0.0, BotBrain.effectiveness("ghost", listOf("normal")))
        assertEquals(2.0, BotBrain.effectiveness("ice", listOf("dragon")))
    }
}

class SetDataNameTest {
    @Test
    fun showdownNamesMapToCobblemonIds() {
        assertEquals("cobblemon:heavy_duty_boots", SetData.itemId("Heavy-Duty Boots"))
        assertEquals("cobblemon:loaded_dice", SetData.itemId("Loaded Dice"))
        assertEquals("cobblemon:kings_rock", SetData.itemId("King's Rock"))
        assertEquals("softboiled", SetData.key("Soft-Boiled"))
        assertEquals("taurospaldeaaqua", SetData.key("Tauros-Paldea-Aqua"))
    }
}
