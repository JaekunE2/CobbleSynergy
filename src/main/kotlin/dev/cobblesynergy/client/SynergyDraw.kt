package dev.cobblesynergy.client

import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemon.mod.common.client.gui.TypeIcon
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics

/** HUD 와 상세 화면이 같이 쓰는 그리기 도구. 아이콘은 Cobblemon 기본 타입 아이콘을 그대로 사용한다. */
object SynergyDraw {
    const val ICON = 18           // Cobblemon TypeIcon: 36px 텍스처 × 0.5
    const val BADGE = ICON + 4

    /** 등급 색 테두리 + 어두운 바탕 + Cobblemon 타입 아이콘 */
    fun badge(g: GuiGraphics, x: Int, y: Int, trait: ClientTrait) {
        g.fill(x, y, x + BADGE, y + BADGE, trait.color)
        g.fill(x + 1, y + 1, x + BADGE - 1, y + BADGE - 1, 0xE0101418.toInt())
        val type = ElementalTypes.get(trait.type) ?: return
        TypeIcon(x = x + 2, y = y + 2, type = type, opacity = if (trait.count > 0) 1f else 0.35f).render(g)
    }

    /** "2 › 4 › 6" 형태의 단계 표시. 켜진 단계는 등급색, 달성 못한 단계는 회색. 끝 x 좌표를 돌려준다. */
    fun progress(g: GuiGraphics, font: Font, x: Int, y: Int, trait: ClientTrait): Int {
        var cx = x
        trait.tiers.forEachIndexed { i, tier ->
            if (i > 0) cx = g.drawString(font, " › ", cx, y, 0xFF6A6A6A.toInt(), false)
            val color = when {
                i == trait.activeTier -> trait.color or 0xFF000000.toInt()
                else -> 0xFF8A8A8A.toInt()
            }
            cx = g.drawString(font, tier.label, cx, y, color, i == trait.activeTier)
        }
        return cx
    }

    const val SMALL = 13          // 작은 배지 (작은 타입 아이콘 9px + 테두리)

    /** 전투 중·넘친 줄에 쓰는 작은 배지 + 마릿수 */
    fun smallBadge(g: GuiGraphics, font: Font, x: Int, y: Int, trait: ClientTrait) {
        g.fill(x, y, x + SMALL, y + SMALL, trait.color)
        g.fill(x + 1, y + 1, x + SMALL - 1, y + SMALL - 1, 0xE0101418.toInt())
        ElementalTypes.get(trait.type)?.let { TypeIcon(x = x + 2, y = y + 2, type = it, small = true, opacity = if (trait.count > 0) 1f else 0.35f).render(g) }
        val text = trait.count.toString()
        g.pose().pushPose()
        g.pose().translate((x + SMALL - font.width(text) * 0.75f).toDouble(), (y + SMALL - 6).toDouble(), 200.0)
        g.pose().scale(0.75f, 0.75f, 1f)
        g.drawString(font, text, 0, 0, if (trait.active) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt(), true)
        g.pose().popPose()
    }

    fun countBox(g: GuiGraphics, font: Font, x: Int, y: Int, trait: ClientTrait) {
        val text = trait.count.toString()
        val w = font.width(text) + 4
        g.fill(x, y, x + w, y + 10, 0xF0000000.toInt())
        g.drawString(font, text, x + 2, y + 1, if (trait.active) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt(), false)
    }
}
