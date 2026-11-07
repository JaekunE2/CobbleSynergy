package dev.cobblesynergy

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/**
 * 테스트 빌드 전용: CobbleLeague 배틀 맵 바닥을 깐다.
 * CobbleLeague 는 cobbleleague:league 차원(데이터팩)과 운영자의 /function cobbleleague:install 로 만든 맵을 요구하는데,
 * 받은 저장소에는 그 데이터팩이 없다. 차원은 datapack/cobbleleague-league-map 으로 넣고, 맵은 CobbleLeague 코드가 쓰는 좌표에 맞춰 여기서 만든다.
 *  - (0,64,0) 바다랜턴: CobbleLeague 가 "맵이 설치됨" 으로 확인하는 표시
 *  - 로비: x -20..20, z -113..-85, 바닥 y64 (입장 위치 0.5, -100.5)
 *  - PvP 리그장: 중심 (0, 38), 두 참가자는 x ±20.5 에 선다
 *  - 개인 선택방(관장전 포함)은 CobbleLeague 가 직접 짓는다
 */
object LeagueMapBuilder {
    private val KEY = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("cobbleleague", "league"))

    fun ensure(server: MinecraftServer) {
        val level = server.getLevel(KEY)
        if (level == null) {
            TestLog.log("map", "cobbleleague:league 차원이 없습니다. 월드의 datapacks 폴더에 cobbleleague-league-map 이 있는지 확인하세요.")
            return
        }
        if (level.getBlockState(BlockPos(0, 64, 0)).`is`(Blocks.SEA_LANTERN)) {
            TestLog.log("map", "배틀 맵이 이미 있습니다")
            return
        }
        val floor = Blocks.SMOOTH_QUARTZ.defaultBlockState()
        val light = Blocks.SEA_LANTERN.defaultBlockState()
        val wall = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState()
        var placed = 0
        fun set(x: Int, y: Int, z: Int, s: BlockState) { level.setBlock(BlockPos(x, y, z), s, 2); placed++ }
        fun area(x0: Int, x1: Int, z0: Int, z1: Int, walls: Boolean) {
            for (x in x0..x1) for (z in z0..z1) set(x, 64, z, if ((x - x0) % 6 == 0 && (z - z0) % 6 == 0) light else floor)
            if (!walls) return
            for (y in 65..67) {
                for (x in x0..x1) { set(x, y, z0, wall); set(x, y, z1, wall) }
                for (z in z0..z1) { set(x0, y, z, wall); set(x1, y, z, wall) }
            }
        }
        area(-3, 3, -3, 3, false)            // 설치 표시 주변
        area(-22, 22, -115, -83, true)       // 로비
        area(-34, 34, 16, 60, true)          // PvP 리그장
        set(0, 64, 0, light)                  // 설치 표시 (마지막에)
        TestLog.log("map", "배틀 맵 바닥을 만들었습니다 (블록 $placed 개)")
    }
}
