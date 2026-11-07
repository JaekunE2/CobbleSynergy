package dev.cobblesynergy.client

import dev.cobblesynergy.SynergyEngine
import dev.cobblesynergy.SynergyTogglePayload
import dev.cobblesynergy.TestBattlePayload
import dev.cobblesynergy.TestLog
import dev.cobblesynergy.TestMode
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * TFT 특성 도감 같은 상세 화면 (기본 키 Y).
 * 왼쪽: 18개 타입 시너지 목록 (켜진 것 먼저), 오른쪽: 마우스를 올리거나 클릭한 시너지의 단계별 효과.
 */
class SynergyScreen(private val parent: Screen? = null) : Screen(Component.literal(if (TestMode.enabled) "타입 시너지 (테스트 빌드)" else "타입 시너지")) {
    companion object {
        private val TEST_TYPES = SynergyEngine.TYPE_KO.keys.toList() + "random"
        private var testType = 0
        private var testCount = 3
    }

    private val rowH = SynergyDraw.BADGE + 3
    private val listW = 150
    private var scroll = 0
    private var selected: String? = null

    private var left = 0
    private var top = 0
    private var panelH = 0

    private lateinit var hudButton: Button
    private var summary = true   // 처음에는 "현재 효과 / 아직 발동 안 된 효과" 요약을 보여준다

    override fun init() {
        val totalW = listW + 12 + 210
        left = (width - totalW) / 2
        top = 28
        panelH = height - top - if (TestMode.enabled) 58 else 34
        if (TestMode.enabled) addTestRow(totalW)
        hudButton = addRenderableWidget(
            Button.builder(hudLabel()) { ClientConfig.cycleMode(); it.message = hudLabel() }
                .bounds(left, height - 26, 110, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("HUD 위치: 좌/우")) { ClientConfig.toggleSide() }
                .bounds(left + 114, height - 26, 100, 20).build(),
        )
        addRenderableWidget(
            Button.builder(switchLabel()) {
                if (ClientPlayNetworking.canSend(SynergyTogglePayload.TYPE)) ClientPlayNetworking.send(SynergyTogglePayload(!SynergyClientState.enabled))
            }.bounds(left + 218, height - 26, 90, 20).build(),
        ).also { switchButton = it }
        addRenderableWidget(
            Button.builder(Component.literal("요약")) { summary = true; selected = null }
                .bounds(left + 312, height - 26, 40, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal(if (parent != null) "뒤로" else "닫기")) { onClose() }
                .bounds(left + totalW - 50, height - 26, 50, 20).build(),
        )
    }

    // ------------------------------------------------------------ 테스트 빌드 전용

    private fun testTypeLabel() = Component.literal("테스트 타입: " + (SynergyEngine.TYPE_KO[TEST_TYPES[testType]] ?: "무작위"))
    private fun testCountLabel() = Component.literal("${testCount}마리")

    private fun addTestRow(totalW: Int) {
        val y = height - 50
        addRenderableWidget(Button.builder(testTypeLabel()) {
            testType = (testType + 1) % TEST_TYPES.size; it.message = testTypeLabel()
        }.bounds(left, y, 120, 20).build())
        addRenderableWidget(Button.builder(testCountLabel()) {
            testCount = testCount % 6 + 1; it.message = testCountLabel()
        }.bounds(left + 124, y, 50, 20).build())
        addRenderableWidget(Button.builder(Component.literal("테스트 전투 시작")) {
            val type = TEST_TYPES[testType]
            TestLog.log("ui", "테스트 전투 버튼: type=$type count=$testCount")
            ClientPlayNetworking.send(TestBattlePayload(type, testCount))
            onClose()
        }.bounds(left + 178, y, 110, 20).build())
        addRenderableWidget(Button.builder(Component.literal("로그 위치")) {
            minecraft?.player?.displayClientMessage(Component.literal(TestLog.path), false)
        }.bounds(left + totalW - 60, y, 60, 20).build())
    }

    private var switchButton: Button? = null
    private fun switchLabel() = Component.literal("시너지: " + if (SynergyClientState.enabled) "켜짐" else "꺼짐")

    override fun tick() { switchButton?.message = switchLabel() }
    override fun onClose() { minecraft?.setScreen(parent) }

    private fun hudLabel() = Component.literal("HUD: ${ClientConfig.mode.label}")

    private val traits get() = SynergyClientState.traits

    private fun visibleRows() = (panelH / rowH).coerceAtLeast(1)

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(g, mouseX, mouseY, partialTick)
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF.toInt())

        if (!ClientPlayNetworking.canSend(SynergyTogglePayload.TYPE)) {
            // 접속한 서버에 CobbleSynergy 가 없다 (서버장이 서버 mods 에 넣어야 함)
            g.drawCenteredString(font, "이 서버에는 CobbleSynergy가 설치되어 있지 않아 시너지가 적용되지 않습니다.", width / 2, height / 2 - 6, 0xFFFF8080.toInt())
            g.drawCenteredString(font, "서버장에게 같은 버전의 CobbleSynergy를 서버 mods 폴더에 넣어 달라고 요청하세요.", width / 2, height / 2 + 6, 0xFFAAAAAA.toInt())
            return
        }
        if (!SynergyClientState.active) {
            g.drawCenteredString(font, "던전 도전 중에만 시너지가 표시됩니다. (U 도감은 던전 중에 열어 보세요)", width / 2, height / 2, 0xFFAAAAAA.toInt())
            return
        }
        if (!SynergyClientState.enabled) {
            g.drawCenteredString(font, "던전 시너지가 꺼져 있습니다. 아래 버튼으로 켤 수 있습니다.", width / 2, 20, 0xFFFF8080.toInt())
        }

        // 목록
        g.fill(left - 2, top - 2, left + listW + 2, top + panelH + 2, 0xC0000000.toInt())
        var hovered: ClientTrait? = null
        val rows = traits.drop(scroll).take(visibleRows())
        rows.forEachIndexed { i, trait ->
            val y = top + i * rowH
            val over = mouseX in left..(left + listW) && mouseY in y..(y + rowH - 1)
            if (over) hovered = trait
            if (over || trait.type == selected) g.fill(left, y, left + listW, y + rowH - 1, 0x40FFFFFF)
            SynergyDraw.badge(g, left, y, trait)
            SynergyDraw.countBox(g, font, left + SynergyDraw.BADGE - 8, y + SynergyDraw.BADGE - 9, trait)
            val tx = left + SynergyDraw.BADGE + 4
            g.drawString(font, trait.name, tx, y + 2, if (trait.active) 0xFFFFFFFF.toInt() else 0xFF9A9A9A.toInt(), true)
            SynergyDraw.progress(g, font, tx, y + 12, trait)
        }

        // 상세
        val dx = left + listW + 12
        val dw = 210
        if (hovered == null && selected == null && summary) { drawSummary(g, dx, dw); return }
        val detail = hovered ?: traits.firstOrNull { it.type == selected } ?: traits.firstOrNull() ?: return
        g.fill(dx - 2, top - 2, dx + dw + 2, top + panelH + 2, 0xC0000000.toInt())
        SynergyDraw.badge(g, dx, top, detail)
        g.drawString(font, "${detail.name}  (${detail.count}마리)", dx + SynergyDraw.BADGE + 4, top + 2, detail.color or 0xFF000000.toInt(), true)
        val next = detail.nextTier
        val hint = when {
            next != null -> "다음 단계까지 ${next.count - detail.count}마리"
            detail.active -> "최고 단계"
            else -> ""
        }
        g.drawString(font, hint, dx + SynergyDraw.BADGE + 4, top + 12, 0xFFAAAAAA.toInt(), false)

        var y = top + SynergyDraw.BADGE + 8
        detail.tiers.forEachIndexed { i, tier ->
            val on = i == detail.activeTier
            val head = if (tier.exact) "정확히 ${tier.count}마리" else "${tier.count}마리 이상"
            g.drawString(font, head, dx, y, if (on) detail.color or 0xFF000000.toInt() else 0xFF8A8A8A.toInt(), on)
            y += 11
            for (line in tier.desc) {
                for (seq in font.split(Component.literal("• $line"), dw - 6)) {
                    g.drawString(font, seq, dx + 6, y, if (on) 0xFFFFFFFF.toInt() else 0xFF8A8A8A.toInt(), false)
                    y += 10
                }
            }
            y += 4
        }
        if (detail.tiers.size > 1) {
            g.drawString(font, "단계는 누적되지 않고 가장 높은 단계 하나만 적용됩니다.", dx, top + panelH - 10, 0xFF6A6A6A.toInt(), false)
        }
    }

    /** 현재 적용 중인 효과와, 모으는 중이라 아직 발동하지 않은 다음 단계 */
    private fun drawSummary(g: GuiGraphics, dx: Int, dw: Int) {
        g.fill(dx - 2, top - 2, dx + dw + 2, top + panelH + 2, 0xC0000000.toInt())
        var y = top
        val bottom = top + panelH - 10
        fun line(text: String, color: Int, indent: Int = 0) {
            for (seq in font.split(Component.literal(text), dw - indent)) {
                if (y > bottom) return
                g.drawString(font, seq, dx + indent, y, color, false); y += 10
            }
        }
        val on = traits.filter { it.active }
        line("현재 적용 중 (${on.size})", 0xFFE8B730.toInt())
        if (on.isEmpty()) line("없음", 0xFF8A8A8A.toInt(), 6)
        for (t in on) {
            line("${t.name} ${t.count}마리 · ${t.tiers[t.activeTier].label}단계", t.color or 0xFF000000.toInt(), 2)
            t.tiers[t.activeTier].desc.forEach { line("• $it", 0xFFFFFFFF.toInt(), 8) }
        }
        y += 4
        val waiting = traits.filter { it.count > 0 && it.nextTier != null }
        line("아직 발동 안 됨 (${waiting.size})", 0xFF9AB8FF.toInt())
        if (waiting.isEmpty()) line("없음", 0xFF8A8A8A.toInt(), 6)
        for (t in waiting) {
            val next = t.nextTier!!
            line("${t.name} ${t.count}/${next.count} — ${next.count - t.count}마리 더", 0xFFB8C4CC.toInt(), 2)
            next.desc.forEach { line("• $it", 0xFF8A8A8A.toInt(), 8) }
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val max = (traits.size - visibleRows()).coerceAtLeast(0)
        scroll = (scroll - scrollY.toInt()).coerceIn(0, max)
        return true
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (mouseX >= left && mouseX <= left + listW && mouseY >= top) {
            val index = ((mouseY - top) / rowH).toInt() + scroll
            traits.getOrNull(index)?.let { selected = it.type; return true }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun isPauseScreen() = false
}
