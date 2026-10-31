package com.koei.ammo_supply_box.item;

import java.util.List;
import net.minecraft.world.item.Rarity;

/**
 * 「随机弹药盒」的类型清单。
 *
 * <p>和 {@code AmmoBoxTypes} 是同一个思路：把「一种盒子的参数」抽成一条记录放进清单，
 * 新增同类盒子（更肥的、更瘦的、更高稀有度的）只需要在 {@link #ALL} 里加一行，
 * {@link RandomAmmoBoxItem} 的逻辑完全复用，不需要新类。
 *
 * <p>它和方块弹药盒那套清单<b>刻意分开</b>：那边的 {@code Type} 背着伤害预算、冷却、
 * 碰撞箱、变体这些方块专属字段，这里一条都用不上；硬塞进同一个枚举只会让两边都变成
 * 一堆"对本类型无意义"的字段。
 */
public final class RandomAmmoBoxTypes {

    /**
     * 一种随机弹药盒的参数。
     *
     * @param id            物品 id，同时也是语言文件 key 的后缀
     * @param minKinds      一次右键最少抽几种弹药（含）
     * @param maxKinds      一次右键最多抽几种弹药（含）；实际种类数会再钳到「服务器有多少种弹药」
     * @param stackFraction 每种弹药的数量上限 = 该口径堆叠上限 × 本系数。
     *                      1.0 表示"最多一整叠"；想让某种盒子更瘦就写 0.5（最多半叠）
     * @param rarity        只影响物品名那一行的颜色，不影响任何玩法数值
     */
    public record Type(String id, int minKinds, int maxKinds, double stackFraction, Rarity rarity) {

        /**
         * 单种弹药本次的数量上限。
         *
         * <p>堆叠上限来自 TACZ 的弹药索引，各口径不同（默认包里 60 / 36 / 30 / 16 / 6 五种），
         * 所以取不到或异常值（≤0）时统一按 1 处理，保证 {@code nextInt} 的参数一定为正。
         *
         * <p>先按系数缩放再钳回上限，避免 {@code stackFraction > 1} 的配置造出"超过一叠"的区间
         * （那没有意义：{@code giveAmmo} 会拆成多叠，直接写更大的系数也不会有任何额外效果）。
         */
        public int maxAmountPerKind(int stackSize) {
            int cap = Math.max(1, stackSize);
            int scaled = (int) Math.round(cap * this.stackFraction);
            return Math.min(cap, Math.max(1, scaled));
        }
    }

    /**
     * 随机盒 #1：1~3 种、每种 1 发起、最多<b>半叠</b>、不套伤害预算（纯随机）。
     *
     * <p>「半叠」是按各口径<b>自己的</b>堆叠上限算的（{@code stackFraction = 0.5}）：
     * 默认包里 60 发的口径最多给 30 发、{@code 12g}（36）最多 18、
     * {@code 50bmg}（30）最多 15、{@code 40mm}（16）最多 8、
     * {@code rpg_rocket}（6）最多 3。这样"半组"在任何口径下都是同一种手感，
     * 不会出现"火箭弹一次给 32 发"这种离谱结果。
     *
     * <p>稀有度取 {@code UNCOMMON}（黄名）：它比普通弹药盒稀有一些，但也不是
     * {@code upgrade_core} 那种顶级材料。不抗火——掉落物掉进岩浆照样烧掉，
     * 和普通弹药盒一致（普通盒是消耗品，保持"别乱丢进岩浆"的紧张感）。
     */
    public static final Type TYPE_1 = new Type("ammo_box_random_1", 1, 3, 0.5D, Rarity.UNCOMMON);

    /** 全部随机盒，顺序即创造页签里的顺序。 */
    public static final List<Type> ALL = List.of(TYPE_1);

    private RandomAmmoBoxTypes() {
    }
}
