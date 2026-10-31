package com.koei.ammo_supply_box.item;

import java.util.List;
import net.minecraft.world.item.Rarity;

/**
 * 空投系统的档位清单：<b>一个投掷信标 ↔ 一档空投补给箱</b>。
 *
 * <p>5 档的宽高比例与现有弹药盒的 5 个尺寸一一对应（小型 / 中型 / 大型 / 保险库 / 补给库），
 * 模型几何完全相同、只换贴图，所以这里只关心「档号」这一件事，几何细节都在资源文件里。
 *
 * <p><b>冷却按族计算</b>：使用任意一档信标，5 档会<b>一起</b>进入冷却
 * （见 {@link AirdropBeaconItem}）。理由是它们本质上是同一个「空投能力」的 5 个入口，
 * 如果各算各的冷却，玩家换一档就能绕过去连续空投。
 */
public final class AirdropBeaconTypes {

    /**
     * 一档空投。
     *
     * @param tier   档号 1~5，同时决定光柱颜色、物品名与两边的注册 id
     * @param rarity 只影响信标物品名那一行的颜色；档位越高越稀有
     */
    public record Type(int tier, Rarity rarity) {

        /** 投掷信标的物品 id，如 {@code airdrop_beacon_3}。 */
        public String beaconId() {
            return "airdrop_beacon_" + this.tier;
        }

        /** 对应空投补给箱的方块 id，如 {@code ammo_box_airdrop_3}。 */
        public String crateId() {
            return "ammo_box_airdrop_" + this.tier;
        }
    }

    public static final Type TIER_1 = new Type(1, Rarity.COMMON);
    public static final Type TIER_2 = new Type(2, Rarity.COMMON);
    public static final Type TIER_3 = new Type(3, Rarity.UNCOMMON);
    public static final Type TIER_4 = new Type(4, Rarity.RARE);
    public static final Type TIER_5 = new Type(5, Rarity.EPIC);

    /** 全部档位，顺序即创造页签里的顺序。 */
    public static final List<Type> ALL = List.of(TIER_1, TIER_2, TIER_3, TIER_4, TIER_5);

    /** 档位总数。投掷物与光柱实体里用它做范围钳制。 */
    public static final int TIER_COUNT = ALL.size();

    /**
     * 按档号取类型，越界时钳到 1~5。
     *
     * <p>会被实体从 NBT / 同步数据里读到的档号调用，所以必须容错：
     * 存档被改坏或将来档位数量变化时，宁可退化到第一档，也不要抛异常把整个实体弄崩。
     */
    public static Type byTier(int tier) {
        int clamped = Math.max(1, Math.min(TIER_COUNT, tier));
        return ALL.get(clamped - 1);
    }

    private AirdropBeaconTypes() {
    }
}
