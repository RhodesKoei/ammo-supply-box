package com.koei.ammo_supply_box.block;

import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 把一张「模型元素坐标表」变成按水平朝向旋转好的 {@link VoxelShape}。
 *
 * <p>本模组里所有"贴合模型"的方块都走这一套：
 * <ul>
 *   <li>{@link WoodenTrayBlock}（木制托盘，12 条元素）；</li>
 *   <li>{@link WoodenWalkwayBlock}（木制步道，5 条元素）；</li>
 *   <li>分享装弹药盒（{@link ShareAmmoBoxBlock}）：<b>托盘形状 ∪ 剩余箱堆</b>，
 *       见 {@link ShareBoxShapes}。</li>
 * </ul>
 * 抽成一个工具类是为了让"坐标表 → 形状"只有一份实现，三处不会各自维护一套旋转代码。
 *
 * <h2>坐标约定</h2>
 * 元素用<b>模型空间</b>坐标（0~16，和模型 JSON 的 {@code from}/{@code to} 同一套），
 * 每个条目是 {@code {minX, minY, minZ, maxX, maxY, maxZ}}。模型里的 rotation 必须先由调用方处理掉
 * （本模组约定：带旋转的元素不进入选框，见 {@code ShareBoxShapes}）。
 *
 * <h2>旋转方向为什么不用纠结</h2>
 * 一次 90° 对应 {@code (x, z) -> (16 - z, x)}，与 blockstate 里 {@code "y": 90} 一致。
 * 需要说明的是：如果某个形状在 180° 下是对称的（托盘、步道、分享装都满足——
 * 横梁、支脚、箱堆左右镜像后仍是自己），那么"顺时针 90°"与"逆时针 90°"作用在它上面
 * 得到的是同一个形状，所以这份实现不依赖那个约定；只有将来做了 180° 不对称的模型，
 * 才需要重新确认方向。
 */
public final class ElementVoxelShapes {

    /** 单个朝向：把元素逐条转成盒子并合并、优化。 */
    public static VoxelShape build(double[][] elements, Direction facing) {
        int turns = quarterTurns(facing);
        VoxelShape shape = Shapes.empty();
        for (double[] element : elements) {
            double[] box = rotate(element, turns);
            shape = Shapes.or(shape, Block.box(box[0], box[1], box[2], box[3], box[4], box[5]));
        }
        // optimize()：把贴在一起的方块合并成尽量少的矩形，省掉逐帧描边/碰撞的开销
        return shape.optimize();
    }

    /** 四个水平朝向各来一次。竖直朝向会抛异常——本模组的这些方块都只有水平朝向。 */
    public static Map<Direction, VoxelShape> buildAll(double[][] elements) {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            shapes.put(facing, build(elements, facing));
        }
        return shapes;
    }

    /** {@code facing} 相对 {@code north}（不旋转，对应 blockstate 里没有 {@code y}）转了几个 90°。 */
    public static int quarterTurns(Direction facing) {
        return switch (facing) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalArgumentException("这些方块只有水平朝向，收到: " + facing);
        };
    }

    /** 按 90° 的整数倍旋转一个模型空间盒子的水平坐标。 */
    private static double[] rotate(double[] box, int turns) {
        double minX = box[0];
        double minY = box[1];
        double minZ = box[2];
        double maxX = box[3];
        double maxY = box[4];
        double maxZ = box[5];
        for (int i = 0; i < turns; i++) {
            double nextMinX = 16.0D - maxZ;
            double nextMaxX = 16.0D - minZ;
            double nextMinZ = minX;
            double nextMaxZ = maxX;
            minX = nextMinX;
            maxX = nextMaxX;
            minZ = nextMinZ;
            maxZ = nextMaxZ;
        }
        return new double[] {minX, minY, minZ, maxX, maxY, maxZ};
    }

    private ElementVoxelShapes() {
    }
}
