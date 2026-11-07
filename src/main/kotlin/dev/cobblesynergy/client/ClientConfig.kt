package dev.cobblesynergy.client

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

enum class HudMode(val label: String) { FULL("전체"), COMPACT("아이콘만"), HIDDEN("숨김") }

/** config/cobblesynergy-client.json (각자 PC 설정) */
object ClientConfig {
    private data class Data(var mode: String? = "FULL", var yOffset: Int? = 0, var left: Boolean? = false)

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file = FabricLoader.getInstance().configDir.resolve("cobblesynergy-client.json")
    private var data = Data()

    val mode: HudMode get() = runCatching { HudMode.valueOf(data.mode ?: "FULL") }.getOrDefault(HudMode.FULL)
    val yOffset: Int get() = data.yOffset ?: 0
    val left: Boolean get() = data.left == true

    fun load() {
        data = runCatching {
            if (Files.exists(file)) gson.fromJson(Files.readString(file), Data::class.java) else null
        }.getOrNull() ?: Data()
        save()
    }

    fun cycleMode(): HudMode {
        val next = HudMode.entries[(mode.ordinal + 1) % HudMode.entries.size]
        data.mode = next.name
        save()
        return next
    }

    fun toggleSide() { data.left = !left; save() }

    private fun save() {
        runCatching { Files.writeString(file, gson.toJson(data)) }
    }
}
