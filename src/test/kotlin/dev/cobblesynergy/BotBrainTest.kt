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
