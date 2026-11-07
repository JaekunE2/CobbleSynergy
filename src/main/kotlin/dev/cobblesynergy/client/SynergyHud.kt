package dev.cobblesynergy.client

import com.cobblemon.mod.common.client.gui.battle.BattleGUI
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

/**
 * TFT 특성 패널처럼 화면 옆에 시너지를 보여준다.
 *  - 평소: 켜진 시너지 최대 3줄만 크게, 나머지(켜진 것 + 모으는 중인 것)는 작은 아이콘 + 숫자 격자로 접는다.
 *    그래서 시너지가 많아도 세로로 길어져 다른 UI 를 덮지 않는다.
 *  - 전투 중(Cobblemon 전투 화면): 켜진 시너지만 작은 아이콘 + 숫자로 오른쪽 가장자리에 한 줄씩.
 *  - 그 밖의 화면(F4 던전 메뉴, 채팅, 인벤토리 등)이 열려 있거나 F1 이면 그리지 않는다.
 *  - 던전 시너지가 꺼져 있으면 그리지 않는다.
 */
object SynergyHud {
    private const val ROW = SynergyDraw.BADGE + 2
    private const val FULL_WIDTH = 118
    private const val MARGIN = 4
    private const val MAX_FULL_ROWS = 3
    private const val GRID_COLUMNS = 6

    fun render(g: GuiGraphics, @Suppress("UNUSED_PARAMETER") delta: DeltaTracker) {
        val mc = Minecraft.getInstance()
        if (!SynergyClientState.active || !SynergyClientState.enabled || mc.player == null || mc.options.hideGui) return
        val mode = ClientConfig.mode
        if (mode == HudMode.HIDDEN) return
        val inBattle = mc.screen is BattleGUI
        if (mc.screen != null && !inBattle) return

        val shown = SynergyClientState.traits.filter { it.count > 0 }
        if (shown.isEmpty()) return
        val font = mc.font
        val screenW = g.guiWidth()
        val screenH = g.guiHeight()

        if (inBattle || mode == HudMode.COMPACT) {
            // 작은 아이콘 + 숫자만. 전투 중에는 켜진 시너지만.
            val list = if (inBattle) shown.filter { it.active } else shown
            if (list.isEmpty()) return
            val step = SynergyDraw.SMALL + 2
            val x = if (ClientConfig.left && !inBattle) MARGIN else screenW - SynergyDraw.SMALL - 2
            val maxRows = ((screenH - 80) / step).coerceAtLeast(1)
            var y = (screenH - minOf(list.size, maxRows) * step) / 2 + if (inBattle) 0 else ClientConfig.yOffset
            for (t in list.take(maxRows)) { SynergyDraw.smallBadge(g, font, x, y, t); y += step }
            return
        }

        val full = shown.filter { it.active }.take(MAX_FULL_ROWS)
        val rest = shown.filter { it !in full }
        val step = SynergyDraw.SMALL + 2
        val gridRows = (rest.size + GRID_COLUMNS - 1) / GRID_COLUMNS
        val height = full.size * ROW + if (gridRows > 0) gridRows * step + 2 else 0
        val x = if (ClientConfig.left) MARGIN else screenW - FULL_WIDTH - MARGIN
        var y = (screenH - height) / 2 + ClientConfig.yOffset

        for (trait in full) {
            g.fill(x, y, x + FULL_WIDTH, y + SynergyDraw.BADGE, 0x90101820.toInt())
            SynergyDraw.badge(g, x, y, trait)
            SynergyDraw.countBox(g, font, x + SynergyDraw.BADGE - 8, y + SynergyDraw.BADGE - 9, trait)
            val tx = x + SynergyDraw.BADGE + 4
            g.drawString(font, trait.name, tx, y + 2, 0xFFFFFFFF.toInt(), true)
            SynergyDraw.progress(g, font, tx, y + 12, trait)
            y += ROW
        }
        if (rest.isNotEmpty()) {
            y += 2
            // 오른쪽 정렬 격자 (켜진 것 먼저, 그다음 모으는 중)
            rest.forEachIndexed { i, t ->
                val col = i % GRID_COLUMNS
                val row = i / GRID_COLUMNS
                val gx = if (ClientConfig.left) x + col * step else x + FULL_WIDTH - (col + 1) * step + 2
                SynergyDraw.smallBadge(g, font, gx, y + row * step, t)
            }
        }
    }
}
