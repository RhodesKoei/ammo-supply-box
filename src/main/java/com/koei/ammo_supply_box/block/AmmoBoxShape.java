package com.koei.ammo_supply_box.block;

import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 一种弹药盒的选框尺寸。
 *
 * <p>数值使用<b>模型空间</b>（0-16，1 单位 = 1/16 格），和模型文件里元素的 {@code from} / {@code to} 同一套单位，
 * 可以直接从模型元素的并集抄过来，不需要自己换算成 0-1。
 *
 * <p>{@link #buildShapes()} 据此生成四个水平朝向各自的 VoxelShape。这一步不能省：
 * 方块模型在 {@code facing=east/west} 时会被 blockstate 整体旋转 90°，选框必须跟着转，
 * 否则换朝向时描边会错位。
 */
public record AmmoBoxShape(double minX, double minY, double minZ,
                           double maxX, double maxY, double maxZ) {

    /**
     * 生成 {@code facing -> VoxelShape} 的映射。
     *
     * <p>blockstate 里的 {@code "y": 90} 等价于绕方块中心俯视顺时针转 90°，
     * 在 XZ 平面上即 (x, z) -&gt; (16 - z, x)，所以 east / west 用旋转后的坐标、
     * north / south 用原始坐标。这个换算对任何尺寸都成立，
     * 模型在 X 还是 Z 上更长都不影响。
     */
    public Map<Direction, VoxelShape> buildShapes() {
        VoxelShape northSouth = Block.box(minX, minY, minZ, maxX, maxY, maxZ);
        VoxelShape eastWest = Block.box(16.0D - maxZ, minY, minX, 16.0D - minZ, maxY, maxX);
        return Map.of(
                Direction.NORTH, northSouth,
                Direction.SOUTH, northSouth,
                Direction.EAST, eastWest,
                Direction.WEST, eastWest);
    }
}
