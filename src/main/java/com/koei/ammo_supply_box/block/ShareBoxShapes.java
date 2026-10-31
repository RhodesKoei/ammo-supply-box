package com.koei.ammo_supply_box.block;

import com.koei.ammo_supply_box.util.ShareBoxData;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 五种「分享装」弹药盒的<b>按剩余次数变化的选框/碰撞箱</b>。
 *
 * <h2>形状 = 托盘底座 ∪ 剩余的箱堆</h2>
 * 分享装模型的元素都是「底座 + 四个箱堆」：
 * <ul>
 *   <li><b>底座</b>：模型的前 12 个元素，和 {@code wooden_tray.json} <b>逐条一致</b>
 *       （脚本核对过，五个类型都是同序相同）。所以底座形状<b>直接复用</b>
 *       {@link WoodenTrayBlock#shapeFor(Direction)}，这里一条底座坐标都不存——
 *       以后改托盘模型，托盘方块和所有分享装的底座会一起变。</li>
 *   <li><b>箱堆</b>：四个箱堆按"保留顺序"排在下面的表里，每堆元素数见各表注释。
 *       <b>带旋转的元素不进选框</b>（MC 的选框只能是轴对齐盒子，硬收进包围盒会明显膨胀）。
 *       目前只有 type1 有旋转元素（每堆 3 个 22.5° 倾斜件，其中包含最高的斜盖），
 *       所以 type1 每堆只保留 5 条，其余四个类型没有旋转、全部保留。</li>
 * </ul>
 * 每用掉一次补给少一堆；最后一堆用完后方块本身被换成托盘，形状因此连续退化成托盘。
 *
 * <h2>数据从哪来</h2>
 * 全部由脚本从 {@code models/block/ammo_box_N_share_M.json} 生成，并核对过三件事：
 * <ol>
 *   <li>每个状态的元素数 = 12 + N × 每堆元素数（20/28/36/44、21/30/39/48…）；</li>
 *   <li>每个状态的前 12 个元素与 {@code wooden_tray.json} 逐条相同；</li>
 *   <li>每个状态恰好是「底座 + 最前面 N 堆」，即"剩余 N 次 = 保留前 N 堆"。</li>
 * </ol>
 * 坐标已<b>归一化</b>（type4 有 4 个元素在 Blockbench 里被存成 {@code from > to}，
 * 直接拿去 {@code Block.box} 会得到空形状，所以统一按 min/max 取值）。
 *
 * <h2>薄片过滤</h2>
 * 生成形状时会丢掉<b>任一轴厚度小于 {@value #MIN_BOX_THICKNESS} 模型单位</b>的盒子。
 * 目前只有 type5 命中：它每堆有 6 个厚度 0.002 的"薄片"（{@code 8.998~9}、{@code 14.498~14.5} 之类），
 * 4 堆共 24 条。它们在画面上本来就看不见（0.002/16 格），放进选框只会给描边添几条
 * 亚像素细线、并多占几个 AABB，所以统一滤掉；表里仍保留原始坐标，
 * 保证"表 == 模型"的校验依然成立。
 *
 * <h2>与代码的关系</h2>
 * {@link ShareAmmoBoxBlock#getShape} 按状态里的 {@code uses} 与 {@code facing} 取形状；
 * 碰撞箱自动跟随（{@code BlockBehaviour#getCollisionShape} 默认就是 {@code state.getShape}）。
 */
public final class ShareBoxShapes {

    /** 每种分享装都有 4 个箱堆（每用掉一次少一堆）。 */
    private static final int CRATE_COUNT = 4;

    /**
     * 状态值 → 保留几堆。
     *
     * <p>分享装的 {@code uses} 是 {@code 1..4}（1 = 还能用最后一次，不存在 0），
     * 分享装自动补货的 {@code charges} 是 {@code 0..4}（0 = 4 个计时器都在跑，方块还在）。
     * 两边都满足"值 = 剩余箱堆数"，所以同一张表可以服务两种变体：
     * {@code 0} 堆就是只剩托盘底座。
     */
    private static int cratesForState(int value) {
        return Math.max(0, Math.min(value, CRATE_COUNT));
    }

    /**
     * 盒子任一轴的厚度小于这个值（模型单位，1 单位 = 1/16 格）就不进选框。
     *
     * <p>0.05 单位 = 1/320 格，肉眼绝无可能看见；正常墙板最薄也有 1 单位（type5 的薄片是 0.002）。
     */
    private static final double MIN_BOX_THICKNESS = 0.05D;

    /** type1：每堆 8 个元素、保留 5 条（模型元素数 20/28/36/44）。 */
    private static final double[][] TYPE_1_CRATE_BOXES = {
            // 第 1 堆
            {9.0D, 3.0D, 1.0D, 14.0D, 12.0D, 2.0D},
            {10.0D, 3.0D, 2.0D, 13.0D, 8.0D, 6.0D},
            {13.0D, 3.0D, 1.0D, 14.0D, 12.0D, 7.0D},
            {9.0D, 3.0D, 1.0D, 10.0D, 12.0D, 7.0D},
            {9.0D, 3.0D, 6.0D, 14.0D, 12.0D, 7.0D},
            // 第 2 堆
            {2.0D, 3.0D, 1.0D, 7.0D, 12.0D, 2.0D},
            {3.0D, 3.0D, 2.0D, 6.0D, 8.0D, 6.0D},
            {6.0D, 3.0D, 1.0D, 7.0D, 12.0D, 7.0D},
            {2.0D, 3.0D, 1.0D, 3.0D, 12.0D, 7.0D},
            {2.0D, 3.0D, 6.0D, 7.0D, 12.0D, 7.0D},
            // 第 3 堆
            {9.0D, 3.0D, 9.0D, 14.0D, 12.0D, 10.0D},
            {10.0D, 3.0D, 10.0D, 13.0D, 8.0D, 14.0D},
            {13.0D, 3.0D, 9.0D, 14.0D, 12.0D, 15.0D},
            {9.0D, 3.0D, 9.0D, 10.0D, 12.0D, 15.0D},
            {9.0D, 3.0D, 14.0D, 14.0D, 12.0D, 15.0D},
            // 第 4 堆
            {2.0D, 3.0D, 9.0D, 7.0D, 12.0D, 10.0D},
            {3.0D, 3.0D, 10.0D, 6.0D, 8.0D, 14.0D},
            {6.0D, 3.0D, 9.0D, 7.0D, 12.0D, 15.0D},
            {2.0D, 3.0D, 9.0D, 3.0D, 12.0D, 15.0D},
            {2.0D, 3.0D, 14.0D, 7.0D, 12.0D, 15.0D}
    };

    /** type2：每堆 8 个元素、全部保留（模型元素数 20/28/36/44）。 */
    private static final double[][] TYPE_2_CRATE_BOXES = {
            // 第 1 堆
            {9.0D, 3.0D, 5.0D, 16.0D, 7.0D, 6.0D},
            {9.0D, 3.0D, 1.0D, 16.0D, 7.0D, 2.0D},
            {9.0D, 3.0D, 6.0D, 11.0D, 7.0D, 7.0D},
            {14.0D, 3.0D, 6.0D, 16.0D, 7.0D, 7.0D},
            {9.0D, 3.0D, 0.0D, 11.0D, 7.0D, 1.0D},
            {14.0D, 3.0D, 0.0D, 16.0D, 7.0D, 1.0D},
            {9.0D, 7.0D, 0.0D, 16.0D, 9.0D, 7.0D},
            {10.0D, 4.0D, 1.0D, 15.0D, 10.0D, 6.0D},
            // 第 2 堆
            {0.0D, 3.0D, 5.0D, 7.0D, 7.0D, 6.0D},
            {0.0D, 3.0D, 1.0D, 7.0D, 7.0D, 2.0D},
            {0.0D, 3.0D, 6.0D, 2.0D, 7.0D, 7.0D},
            {5.0D, 3.0D, 6.0D, 7.0D, 7.0D, 7.0D},
            {0.0D, 3.0D, 0.0D, 2.0D, 7.0D, 1.0D},
            {5.0D, 3.0D, 0.0D, 7.0D, 7.0D, 1.0D},
            {0.0D, 7.0D, 0.0D, 7.0D, 9.0D, 7.0D},
            {1.0D, 4.0D, 1.0D, 6.0D, 10.0D, 6.0D},
            // 第 3 堆
            {9.0D, 3.0D, 14.0D, 16.0D, 7.0D, 15.0D},
            {9.0D, 3.0D, 10.0D, 16.0D, 7.0D, 11.0D},
            {9.0D, 3.0D, 15.0D, 11.0D, 7.0D, 16.0D},
            {14.0D, 3.0D, 15.0D, 16.0D, 7.0D, 16.0D},
            {9.0D, 3.0D, 9.0D, 11.0D, 7.0D, 10.0D},
            {14.0D, 3.0D, 9.0D, 16.0D, 7.0D, 10.0D},
            {9.0D, 7.0D, 9.0D, 16.0D, 9.0D, 16.0D},
            {10.0D, 4.0D, 10.0D, 15.0D, 10.0D, 15.0D},
            // 第 4 堆
            {0.0D, 3.0D, 14.0D, 7.0D, 7.0D, 15.0D},
            {0.0D, 3.0D, 10.0D, 7.0D, 7.0D, 11.0D},
            {0.0D, 3.0D, 15.0D, 2.0D, 7.0D, 16.0D},
            {5.0D, 3.0D, 15.0D, 7.0D, 7.0D, 16.0D},
            {0.0D, 3.0D, 9.0D, 2.0D, 7.0D, 10.0D},
            {5.0D, 3.0D, 9.0D, 7.0D, 7.0D, 10.0D},
            {0.0D, 7.0D, 9.0D, 7.0D, 9.0D, 16.0D},
            {1.0D, 4.0D, 10.0D, 6.0D, 10.0D, 15.0D}
    };

    /** type3：每堆 9 个元素、全部保留（模型元素数 21/30/39/48；后两堆在 Y 9~15）。 */
    private static final double[][] TYPE_3_CRATE_BOXES = {
            // 第 1 堆
            {2.0D, 3.0D, 8.5D, 4.0D, 6.0D, 14.5D},
            {1.0D, 3.0D, 8.5D, 2.0D, 6.0D, 10.5D},
            {1.0D, 3.0D, 12.5D, 2.0D, 6.0D, 14.5D},
            {14.0D, 3.0D, 8.5D, 15.0D, 6.0D, 10.5D},
            {14.0D, 3.0D, 12.5D, 15.0D, 6.0D, 14.5D},
            {4.0D, 3.0D, 9.5D, 12.0D, 6.0D, 13.5D},
            {5.0D, 3.0D, 8.5D, 11.0D, 6.0D, 14.5D},
            {1.0D, 6.0D, 8.5D, 15.0D, 9.0D, 14.5D},
            {12.0D, 3.0D, 8.5D, 14.0D, 6.0D, 14.5D},
            // 第 2 堆
            {2.0D, 3.0D, 1.5D, 4.0D, 6.0D, 7.5D},
            {1.0D, 3.0D, 1.5D, 2.0D, 6.0D, 3.5D},
            {1.0D, 3.0D, 5.5D, 2.0D, 6.0D, 7.5D},
            {14.0D, 3.0D, 1.5D, 15.0D, 6.0D, 3.5D},
            {14.0D, 3.0D, 5.5D, 15.0D, 6.0D, 7.5D},
            {4.0D, 3.0D, 2.5D, 12.0D, 6.0D, 6.5D},
            {5.0D, 3.0D, 1.5D, 11.0D, 6.0D, 7.5D},
            {1.0D, 6.0D, 1.5D, 15.0D, 9.0D, 7.5D},
            {12.0D, 3.0D, 1.5D, 14.0D, 6.0D, 7.5D},
            // 第 3 堆
            {1.5D, 9.0D, 12.0D, 7.5D, 12.0D, 14.0D},
            {1.5D, 9.0D, 14.0D, 3.5D, 12.0D, 15.0D},
            {5.5D, 9.0D, 14.0D, 7.5D, 12.0D, 15.0D},
            {1.5D, 9.0D, 1.0D, 3.5D, 12.0D, 2.0D},
            {5.5D, 9.0D, 1.0D, 7.5D, 12.0D, 2.0D},
            {2.5D, 9.0D, 4.0D, 6.5D, 12.0D, 12.0D},
            {1.5D, 9.0D, 5.0D, 7.5D, 12.0D, 11.0D},
            {1.5D, 12.0D, 1.0D, 7.5D, 15.0D, 15.0D},
            {1.5D, 9.0D, 2.0D, 7.5D, 12.0D, 4.0D},
            // 第 4 堆
            {8.5D, 9.0D, 12.0D, 14.5D, 12.0D, 14.0D},
            {8.5D, 9.0D, 14.0D, 10.5D, 12.0D, 15.0D},
            {12.5D, 9.0D, 14.0D, 14.5D, 12.0D, 15.0D},
            {8.5D, 9.0D, 1.0D, 10.5D, 12.0D, 2.0D},
            {12.5D, 9.0D, 1.0D, 14.5D, 12.0D, 2.0D},
            {9.5D, 9.0D, 4.0D, 13.5D, 12.0D, 12.0D},
            {8.5D, 9.0D, 5.0D, 14.5D, 12.0D, 11.0D},
            {8.5D, 12.0D, 1.0D, 14.5D, 15.0D, 15.0D},
            {8.5D, 9.0D, 2.0D, 14.5D, 12.0D, 4.0D}
    };

    /** type4：每堆 3 个元素、全部保留（模型元素数 15/18/21/24；有 4 个元素是反向坐标，已归一化）。 */
    private static final double[][] TYPE_4_CRATE_BOXES = {
            // 第 1 堆
            {8.5D, 3.0D, 0.5D, 15.5D, 15.0D, 7.5D},
            {10.5D, 9.0D, 0.5D, 12.5D, 10.0D, 1.5D},
            {9.5D, 4.0D, 0.5D, 14.5D, 14.0D, 1.5D},
            // 第 2 堆
            {0.5D, 3.0D, 0.5D, 7.5D, 15.0D, 7.5D},
            {2.5D, 9.0D, 0.5D, 4.5D, 10.0D, 1.5D},
            {1.5D, 4.0D, 0.5D, 6.5D, 14.0D, 1.5D},
            // 第 3 堆
            {8.5D, 3.0D, 8.5D, 15.5D, 15.0D, 15.5D},
            {10.5D, 9.0D, 8.5D, 12.5D, 10.0D, 9.5D},
            {9.5D, 4.0D, 8.5D, 14.5D, 14.0D, 9.5D},
            // 第 4 堆
            {0.5D, 3.0D, 8.5D, 7.5D, 15.0D, 15.5D},
            {2.5D, 9.0D, 8.5D, 4.5D, 10.0D, 9.5D},
            {1.5D, 4.0D, 8.5D, 6.5D, 14.0D, 9.5D}
    };

    /** type5：每堆 10 个元素、全部保留（模型元素数 22/32/42/52；后两堆是叠在上面的箱子，Y 9~15）。 */
    private static final double[][] TYPE_5_CRATE_BOXES = {
            // 第 1 堆
            {9.5D, 3.0D, 1.0D, 13.5D, 9.0D, 15.0D},
            {8.5D, 3.0D, 0.0D, 14.5D, 5.0D, 5.0D},
            {8.5D, 5.0D, 0.0D, 14.5D, 7.0D, 16.0D},
            {8.5D, 8.998D, 12.0D, 14.5D, 9.0D, 14.0D},
            {14.498D, 7.0D, 12.0D, 14.5D, 9.0D, 14.0D},
            {8.5D, 7.0D, 12.0D, 8.502D, 9.0D, 14.0D},
            {8.5D, 8.998D, 2.0D, 14.5D, 9.0D, 4.0D},
            {8.5D, 7.0D, 2.0D, 8.502D, 9.0D, 4.0D},
            {14.498D, 7.0D, 2.0D, 14.5D, 9.0D, 4.0D},
            {8.5D, 3.0D, 11.0D, 14.5D, 5.0D, 16.0D},
            // 第 2 堆
            {2.5D, 3.0D, 1.0D, 6.5D, 9.0D, 15.0D},
            {1.5D, 3.0D, 0.0D, 7.5D, 5.0D, 5.0D},
            {1.5D, 5.0D, 0.0D, 7.5D, 7.0D, 16.0D},
            {1.5D, 8.998D, 12.0D, 7.5D, 9.0D, 14.0D},
            {7.498D, 7.0D, 12.0D, 7.5D, 9.0D, 14.0D},
            {1.5D, 7.0D, 12.0D, 1.502D, 9.0D, 14.0D},
            {1.5D, 8.998D, 2.0D, 7.5D, 9.0D, 4.0D},
            {1.5D, 7.0D, 2.0D, 1.502D, 9.0D, 4.0D},
            {7.498D, 7.0D, 2.0D, 7.5D, 9.0D, 4.0D},
            {1.5D, 3.0D, 11.0D, 7.5D, 5.0D, 16.0D},
            // 第 3 堆
            {1.0D, 9.0D, 9.5D, 15.0D, 15.0D, 13.5D},
            {11.0D, 9.0D, 8.5D, 16.0D, 11.0D, 14.5D},
            {0.0D, 11.0D, 8.5D, 16.0D, 13.0D, 14.5D},
            {2.0D, 14.998D, 8.5D, 4.0D, 15.0D, 14.5D},
            {2.0D, 13.0D, 14.498D, 4.0D, 15.0D, 14.5D},
            {2.0D, 13.0D, 8.5D, 4.0D, 15.0D, 8.502D},
            {12.0D, 14.998D, 8.5D, 14.0D, 15.0D, 14.5D},
            {12.0D, 13.0D, 8.5D, 14.0D, 15.0D, 8.502D},
            {12.0D, 13.0D, 14.498D, 14.0D, 15.0D, 14.5D},
            {0.0D, 9.0D, 8.5D, 5.0D, 11.0D, 14.5D},
            // 第 4 堆
            {1.0D, 9.0D, 2.5D, 15.0D, 15.0D, 6.5D},
            {11.0D, 9.0D, 1.5D, 16.0D, 11.0D, 7.5D},
            {0.0D, 11.0D, 1.5D, 16.0D, 13.0D, 7.5D},
            {2.0D, 14.998D, 1.5D, 4.0D, 15.0D, 7.5D},
            {2.0D, 13.0D, 7.498D, 4.0D, 15.0D, 7.5D},
            {2.0D, 13.0D, 1.5D, 4.0D, 15.0D, 1.502D},
            {12.0D, 14.998D, 1.5D, 14.0D, 15.0D, 7.5D},
            {12.0D, 13.0D, 1.5D, 14.0D, 15.0D, 1.502D},
            {12.0D, 13.0D, 7.498D, 14.0D, 15.0D, 7.5D},
            {0.0D, 9.0D, 1.5D, 5.0D, 11.0D, 7.5D}
    };

    /** 已算好的形状表，按类型缓存（第一次用到时才计算）。 */
    private static final Map<String, Map<Integer, Map<Direction, VoxelShape>>> CACHE = new HashMap<>();

    /**
     * 取某个分享装类型的「按剩余次数变化的形状」。
     *
     * @param typeId 方块的注册 id，例如 {@code ammo_box_1_share}
     * @return {@code uses -> (facing -> 形状)}；未知类型返回 {@code null}，
     *         调用方（{@link ShareAmmoBoxBlock#getShape}）会退回固定的 {@link AmmoBoxShape}
     */
    public static Map<Integer, Map<Direction, VoxelShape>> forType(String typeId) {
        double[][] crateBoxes = crateBoxes(typeId);
        if (crateBoxes == null) {
            return null;
        }
        return CACHE.computeIfAbsent(typeId, id -> build(crateBoxes));
    }

    /** 各类型的箱堆坐标表；未知类型返回 {@code null}。 */
    private static double[][] crateBoxes(String typeId) {
        return switch (typeId) {
            case "ammo_box_1_share" -> TYPE_1_CRATE_BOXES;
            case "ammo_box_2_share" -> TYPE_2_CRATE_BOXES;
            case "ammo_box_3_share" -> TYPE_3_CRATE_BOXES;
            case "ammo_box_4_share" -> TYPE_4_CRATE_BOXES;
            case "ammo_box_5_share" -> TYPE_5_CRATE_BOXES;
            // 分享装自动补货复用同尺寸分享装的模型与几何，只是状态含义从 uses 变成 charges
            case "ammo_box_1_share_cd" -> TYPE_1_CRATE_BOXES;
            case "ammo_box_2_share_cd" -> TYPE_2_CRATE_BOXES;
            case "ammo_box_3_share_cd" -> TYPE_3_CRATE_BOXES;
            case "ammo_box_4_share_cd" -> TYPE_4_CRATE_BOXES;
            case "ammo_box_5_share_cd" -> TYPE_5_CRATE_BOXES;
            default -> null;
        };
    }

    /** 形状 = 托盘底座 ∪ 前 N 堆箱体，四个朝向各算一次；{@code N} 取 0~4（0 = 只剩底座）。 */
    private static Map<Integer, Map<Direction, VoxelShape>> build(double[][] crateBoxes) {
        int boxesPerCrate = crateBoxes.length / CRATE_COUNT;
        Map<Integer, Map<Direction, VoxelShape>> byState = new HashMap<>();
        for (int state = 0; state <= ShareBoxData.MAX_USES; state++) {
            int crates = cratesForState(state);
            // 取前 N 堆，并顺手滤掉看不见的薄片（见 MIN_BOX_THICKNESS）
            int crateBoxCount = Math.min(crates * boxesPerCrate, crateBoxes.length);
            double[][] subset = solidBoxes(crateBoxes, crateBoxCount);
            Map<Direction, VoxelShape> perFacing = new EnumMap<>(Direction.class);
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                VoxelShape cratesShape = ElementVoxelShapes.build(subset, facing);
                // 底座直接用托盘的形状：模型里分享装的前 12 个元素就是托盘，逐条一致
                perFacing.put(facing,
                        Shapes.or(WoodenTrayBlock.shapeFor(facing), cratesShape).optimize());
            }
            byState.put(state, perFacing);
        }
        return byState;
    }

    /**
     * 取前 {@code count} 条盒子，并丢掉任一轴厚度小于 {@link #MIN_BOX_THICKNESS} 的薄片。
     *
     * <p>过滤发生在<b>拼形状的时候</b>，而不是在数据表里删行：表仍然逐条对应该类型的模型元素
     * （"表 == 模型"的校验因此一直成立），规则也只写在这一处。
     */
    private static double[][] solidBoxes(double[][] crateBoxes, int count) {
        double[][] kept = new double[count][];
        int size = 0;
        for (int i = 0; i < count; i++) {
            double[] box = crateBoxes[i];
            if ((box[3] - box[0]) < MIN_BOX_THICKNESS
                    || (box[4] - box[1]) < MIN_BOX_THICKNESS
                    || (box[5] - box[2]) < MIN_BOX_THICKNESS) {
                continue;
            }
            kept[size++] = box;
        }
        return Arrays.copyOf(kept, size);
    }

    private ShareBoxShapes() {
    }
}
