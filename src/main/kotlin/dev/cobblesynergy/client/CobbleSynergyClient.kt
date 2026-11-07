package dev.cobblesynergy.client

import com.mojang.blaze3d.platform.InputConstants
import dev.cobblesynergy.SynergyPayload
import dev.cobblesynergy.TestLog
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

class CobbleSynergyClient : ClientModInitializer {
    override fun onInitializeClient() {
        ClientConfig.load()

        // 기존 키(F4 던전, C 파티, R 대전)와 겹치지 않게 U 를 기본값으로 둔다 (Y 는 Tom's Storage 가 사용). 설정 → 조작에서 변경 가능.
        val openKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping("key.cobblesynergy.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.categories.cobblesynergy"),
        )
        val hudKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping("key.cobblesynergy.hud", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.value, "key.categories.cobblesynergy"),
        )

        ClientPlayNetworking.registerGlobalReceiver(SynergyPayload.TYPE) { payload, context ->
            context.client().execute {
                SynergyClientState.update(payload.json)
                TestLog.log("ui", "시너지 상태 수신 active=${SynergyClientState.active} 표시=" +
                    SynergyClientState.traits.filter { it.count > 0 }.joinToString { "${it.name}${it.count}(${it.activeTier})" })
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> SynergyClientState.clear() }

        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            while (openKey.consumeClick()) {
                if (mc.screen == null) mc.setScreen(SynergyScreen())
            }
            while (hudKey.consumeClick()) {
                val mode = ClientConfig.cycleMode()
                mc.player?.displayClientMessage(Component.literal("시너지 HUD: ${mode.label}"), true)
            }
        }

        HudRenderCallback.EVENT.register(SynergyHud::render)

        // CobbleLeague 던전 메뉴(F4)의 오른쪽 위에 "시너지" 버튼을 붙인다. 던전 도전 중일 때만.
        ScreenEvents.AFTER_INIT.register { client, screen, w, h ->
            val isLeague = runCatching { screen is dev.cobbleleague.client.LeaguePane }.getOrDefault(false)
            if (!isLeague) return@register
            val serverHas = ClientPlayNetworking.canSend(dev.cobblesynergy.SynergyTogglePayload.TYPE)
            if (!serverHas) return@register // 서버에 CobbleSynergy 가 없으면 아무것도 붙이지 않는다

            // 던전 로비(입장 전): 로비 창 안 오른쪽 위에 시너지 켜기/끄기
            val isLobby = runCatching { screen is dev.cobbleleague.client.LobbyScreen }.getOrDefault(false)
            if (isLobby && !SynergyClientState.active) {
                // CobbleLeague 창 위치 계산과 같은 식 (LeaguePane: 최대 500 × 250(로비))
                val pw = minOf(500, w - if (w >= 360) 84 else 16)
                val ph = minOf(250, h - 16)
                val px = if (w >= 360) maxOf(68, (w - pw) / 2) else (w - pw) / 2
                val py = (h - ph) / 2
                fun label() = Component.literal("시너지: " + if (SynergyClientState.enabled) "켜짐" else "꺼짐")
                val toggle = Button.builder(label()) {
                    ClientPlayNetworking.send(dev.cobblesynergy.SynergyTogglePayload(!SynergyClientState.enabled))
                }.bounds(px + pw - 12 - 92 - 80, py + 7, 92, 16)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                        "던전 타입 시너지를 켜고 끕니다. 서버 전체 설정이며 다음 게임부터 적용됩니다.\n게임이 진행 중일 때는 운영자만 바꿀 수 있습니다.")))
                    .build()
                Screens.getButtons(screen).add(toggle)
                ScreenEvents.afterTick(screen).register { toggle.message = label() }
                return@register
            }

            // 던전 진행 중: 화면 오른쪽 위에 [시너지] 상세와 [관전]
            if (!SynergyClientState.active) return@register
            val label = Component.literal(if (SynergyClientState.enabled) "시너지" else "시너지(꺼짐)")
            Screens.getButtons(screen).add(
                Button.builder(label) { client.setScreen(SynergyScreen(screen)) }.bounds(w - 66, 2, 64, 14).build(),
            )
            // 관전: 참가자 3명 이상일 때 진행 중인 다른 경기를 본다 (서버가 조건을 확인)
            Screens.getButtons(screen).add(
                Button.builder(Component.literal("관전")) { client.setScreen(null); client.player?.connection?.sendCommand("synergy watch") }
                    .bounds(w - 66, 18, 64, 14).build(),
            )
        }
    }
}
