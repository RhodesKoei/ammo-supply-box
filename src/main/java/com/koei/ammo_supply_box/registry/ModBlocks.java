package com.koei.ammo_supply_box.registry;

import com.koei.ammo_supply_box.AmmoSupplyBox;
import com.koei.ammo_supply_box.block.AirdropCrateBlock;
import com.koei.ammo_supply_box.block.AmmoBoxBlock;
import com.koei.ammo_supply_box.block.AmmoBoxShape;
import com.koei.ammo_supply_box.block.AmmoBoxTypes;
import com.koei.ammo_supply_box.block.AutoRestockAmmoBoxBlock;
import com.koei.ammo_supply_box.block.ShareAmmoBoxBlock;
import com.koei.ammo_supply_box.block.ShareBoxShapes;
import com.koei.ammo_supply_box.block.ShareRestockAmmoBoxBlock;
import com.koei.ammo_supply_box.block.WoodenTrayBlock;
import com.koei.ammo_supply_box.block.WoodenWalkwayBlock;
import com.koei.ammo_supply_box.config.AmmoSupplyBoxConfig;
import com.koei.ammo_supply_box.item.AirdropBeaconTypes;
import com.koei.ammo_supply_box.util.SupplyEffects;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.joml.Vector3f;

public final class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, AmmoSupplyBox.MODID);

    /**
     * 木制托盘：<b>独立方块</b>，不是弹药盒，也不在 {@link AmmoBoxTypes#ALL} 里。
     *
     * <p>它只有一个来源：分享装弹药盒把 4 次用完时，原地换成空托盘
     * （见 {@link ShareAmmoBoxBlock#use}）。玩家也可以自己合成、捡起来、换地方放。
     *
     * <p>属性刻意和弹药盒保持同一套口径——硬度 1.0、{@code SoundType.WOOD}、
     * <b>不</b>调用 {@code requiresCorrectToolForDrops()}（徒手也能挖），
     * 「斧头加速」由数据包方块标签 {@code minecraft:mineable/axe} 提供
     * （见 {@code data/minecraft/tags/blocks/mineable/axe.json}），
     * 与弹药盒完全一致。木制物品当然也可以考虑加可燃性，但弹药盒本身也没加，
     * 这里保持一致、避免"弹药盒烧不着、托盘却烧没了"的割裂感。
     *
     * <p><b>踩踏声还要额外登记一个标签</b>：托盘只有 3 像素高，低于原版
     * 「挑脚步声来源」用的 0.2 格阈值，光有 {@code SoundType.WOOD} 是不会响的。
     * 所以它还进了 {@code minecraft:inside_step_sound_blocks}
     * （见 {@code data/minecraft/tags/blocks/inside_step_sound_blocks.json}），
     * 原因写在 {@link WoodenTrayBlock} 的类注释里。
     */
    public static final RegistryObject<Block> WOODEN_TRAY = BLOCKS.register("wooden_tray",
            () -> new WoodenTrayBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(1.0F)
                    .sound(SoundType.WOOD)
                    // 高度只有 3 像素，不是满方块：不做遮挡面剔除
                    .noOcclusion()));

    /**
     * 木制步道：和托盘同一族的<b>独立装饰方块</b>，也不在 {@link AmmoBoxTypes#ALL} 里。
     *
     * <p>与托盘的关系：两者可以在工作台里 1:1 互相转换
     * （见 {@code recipes/wooden_walkway.json} 与 {@code recipes/wooden_tray_from_walkway.json}），
     * 所以它们共用同一套方块属性口径——硬度 1.0、{@code SoundType.WOOD}、徒手可挖、
     * 登记在 {@code minecraft:mineable/axe} 标签里。
     *
     * <p>和托盘唯一不同的是<b>脚步声不需要额外标签</b>：步道的表面在 Y=16（整格高），
     * 原版那套「脚底减 0.2」的判定仍然会落到它自己身上（托盘因为只有 3 像素高，
     * 才不得不登记 {@code minecraft:inside_step_sound_blocks}）。原因见
     * {@link WoodenWalkwayBlock} 的类注释。
     */
    public static final RegistryObject<Block> WOODEN_WALKWAY = BLOCKS.register("wooden_walkway",
            () -> new WoodenWalkwayBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(1.0F)
                    .sound(SoundType.WOOD)
                    // 模型只有上半截有几何体，更不是满方块：不做遮挡面剔除
                    .noOcclusion()));

    /** 弹药盒 id -> 方块，顺序与 {@link AmmoBoxTypes#ALL} 一致。 */
    private static final Map<String, RegistryObject<Block>> AMMO_BOXES = new LinkedHashMap<>();

    static {
        // 遍历清单自动注册，新增弹药盒不需要在这里写任何东西
        for (AmmoBoxTypes.Type type : AmmoBoxTypes.ALL) {
            AMMO_BOXES.put(type.id(), BLOCKS.register(type.id(), () -> createBlock(type)));
        }
    }

    /** 空投箱档号 -> 方块，顺序与 {@link AirdropBeaconTypes#ALL} 一致。 */
    private static final Map<Integer, RegistryObject<Block>> AIRDROP_CRATES = new LinkedHashMap<>();

    static {
        for (AirdropBeaconTypes.Type type : AirdropBeaconTypes.ALL) {
            AIRDROP_CRATES.put(type.tier(), BLOCKS.register(type.crateId(), () -> createAirdropCrate(type)));
        }
    }

    /**
     * 造出一个空投箱方块。
     *
     * <p>它是一个<b>带 4 次使用次数</b>的补给点（见 {@link AirdropCrateBlock}），所以补给预算与
     * 逐枪覆盖键都直接挂到<b>本档的基础弹药盒</b>（{@code ammo_box_N}）上——
     * 与分享装的处理方式一致：配置里不新开一节，{@code /ammosupplybox budget ammo_box_3}
     * 一改，普通盒 / 分享装 / 空投箱同时生效。
     *
     * <p>选框取自空投箱模型的并集（X/Z 各缩进 1 像素、Y 满格）。模型对 X/Z 对称，
     * 所以四个朝向用的是同一个形状，{@link AmmoBoxShape#buildShapes()} 的旋转结果与原始值一致。
     */
    private static Block createAirdropCrate(AirdropBeaconTypes.Type type) {
        AmmoBoxTypes.Type base = airdropBaseBox(type.tier());
        return new AirdropCrateBlock(
                BlockBehaviour.Properties.of()
                        .mapColor(MapColor.WOOD)
                        // 与弹药盒同一套挖矿手感：硬度 1.0、徒手可挖，斧头加速由 mineable/axe 标签提供
                        .strength(1.0F)
                        .sound(SoundType.WOOD)
                        // 模型四边各缩进 1 像素，不是满方块：不做遮挡面剔除
                        .noOcclusion(),
                base.gunSupplyConfigId(),
                AmmoSupplyBoxConfig.damageBudget(base.budgetId(), base.defaultDamageBudget()),
                AIRDROP_CRATE_SHAPE,
                SupplyEffects.airdropColor(type.tier()));
    }

    /**
     * 空投箱模型的包围范围：X/Z 1~15，Y 0~16。
     *
     * <p><b>这是按 20 个模型元素并集算出来的</b>（5 档 × 4 个剩余次数状态，
     * 见 {@code models/block/ammo_box_airdrop_<档>_<次数>.json}）：
     * <ul>
     *   <li>朴素并集（忽略元件自身旋转）= X 1~15、Y 0~16、Z 1~15；</li>
     *   <li>把带 {@code rotation} 的元件的 8 个角点真的转过去再取并集，结果完全相同
     *       ——也就是没有哪个倾斜元件凸出到这个范围之外；</li>
     *   <li>20 个模型两两不同（剩余次数越少、元件越少），但包围范围完全一致，
     *       所以一份形状就能覆盖全部状态，不需要按 {@code uses} 切换。</li>
     * </ul>
     * 模型对 X/Z 对称，因此 {@link AmmoBoxShape#buildShapes()} 的旋转结果与原始值相同，
     * 四个朝向共用同一份选框。
     */
    public static final AmmoBoxShape AIRDROP_CRATE_SHAPE =
            new AmmoBoxShape(1.0D, 0.0D, 1.0D, 15.0D, 16.0D, 15.0D);

    /**
     * 第 {@code tier} 档空投箱对应的「基础弹药盒」类型记录（{@code ammo_box_1..5}）。
     *
     * <p>空投箱不在 {@link AmmoBoxTypes#ALL} 里，但它需要那一行记录来拿默认预算与逐枪覆盖键，
     * 所以按 id 查一次。查表失败说明档位数量与弹药盒档位不匹配，属于编码错误，直接抛异常。
     */
    public static AmmoBoxTypes.Type airdropBaseBox(int tier) {
        String boxId = "ammo_box_" + tier;
        return AmmoBoxTypes.byId(boxId).orElseThrow(() -> new IllegalStateException(
                "第 " + tier + " 档空投箱找不到对应的基础弹药盒 \"" + boxId + "\"，请检查 AmmoBoxTypes.ALL。"));
    }

    /**
     * 按档号取空投箱方块。
     *
     * @throws IllegalArgumentException 档号不在 {@link AirdropBeaconTypes#ALL} 里
     */
    public static RegistryObject<Block> airdropCrate(int tier) {
        RegistryObject<Block> block = AIRDROP_CRATES.get(tier);
        if (block == null) {
            throw new IllegalArgumentException("未注册的空投箱档号: " + tier + "，请确认它在 AirdropBeaconTypes.ALL 范围内。");
        }
        return block;
    }

    /**
     * 按清单里的定义造出方块实例。
     *
     * <p>带冷却的（见 {@link AmmoBoxTypes.Type#hasCooldown()}）用
     * {@link AutoRestockAmmoBoxBlock}，其余一律用 {@link AmmoBoxBlock}。
     * 分支写在这里而不是拆成两份清单，是为了让 {@code AmmoBoxTypes.ALL} 保持单一来源。
     */
    private static Block createBlock(AmmoBoxTypes.Type type) {
        Supplier<Integer> damageBudget =
                AmmoSupplyBoxConfig.damageBudget(type.id(), type.defaultDamageBudget());
        // 交互粒子的颜色由变体决定：普通=绿、创造=紫、自动补货=红、分享装=金
        Vector3f particleColor = SupplyEffects.interactionColor(type.variant());
        BlockBehaviour.Properties properties = ammoBoxProperties();
        // 查逐枪覆盖值用的是「本档基础 id」，同尺寸的所有变体共用同一个键，
        // 所以这里传 gunSupplyConfigId() 而不是 id()，见 AmmoBoxTypes.Type#gunSupplyConfigId()
        String supplyConfigId = type.gunSupplyConfigId();
        if (type.variant() == AmmoBoxTypes.Variant.SHARE) {
            // 分享装：可以用 4 次，用完后原地变成木制托盘（朝向继承自己），
            // 次数存在方块状态里（所以不需要方块实体，也因此不需要推不开活塞——
            // 活塞搬运方块状态不会丢次数）。
            // 这里传 lambda 而不是直接传 WOODEN_TRAY.get()，读取发生在游戏内，
            // 与 ModBlocks 自身的静态初始化顺序无关。
            return new ShareAmmoBoxBlock(properties, supplyConfigId, damageBudget,
                    type.shape(), particleColor,
                    facing -> WOODEN_TRAY.get().defaultBlockState()
                            .setValue(WoodenTrayBlock.FACING, facing),
                    // 按剩余次数变化的选框/碰撞箱（托盘底座 ∪ 剩余箱堆）。
                    // 还没拆分模型的类型返回 null，此时沿用 type.shape() 那份固定选框。
                    ShareBoxShapes.forType(type.id()));
        }
        if (type.variant() == AmmoBoxTypes.Variant.SHARE_RESTOCK) {
            // 分享装自动补货：一个方块并联 4 个独立计时器（所以需要方块实体 → 不让活塞搬动，
            // 理由同下面的 _cd 分支），用掉一次占一个槽、到点各自归还，永不消失也不变托盘。
            // 外观与选框复用同尺寸分享装的模型/形状，只是状态值从 uses 变成 charges(0~4)。
            properties.pushReaction(PushReaction.BLOCK);
            return new ShareRestockAmmoBoxBlock(properties, supplyConfigId, damageBudget, type.shape(),
                    AmmoSupplyBoxConfig.cooldownSeconds(type.cooldownConfigId(), type.cooldownSeconds()),
                    particleColor,
                    ShareBoxShapes.forType(type.id()));
        }
        if (type.hasCooldown()) {
            // 带冷却的盒子不允许活塞搬动：搬运会重建方块实体、丢掉冷却时间戳，
            // 等于留了个绕过冷却的后门。这与箱子/熔炉等带方块的容器行为一致。
            properties.pushReaction(PushReaction.BLOCK);
            return new AutoRestockAmmoBoxBlock(properties, supplyConfigId, damageBudget, type.shape(),
                    // cooldownConfigId() 默认就是自己的 id；分享装会自动指向同尺寸 _cd 那一节
                    AmmoSupplyBoxConfig.cooldownSeconds(type.cooldownConfigId(), type.cooldownSeconds()),
                    particleColor);
        }
        return new AmmoBoxBlock(properties, supplyConfigId, damageBudget, type.shape(),
                type.consumable(), particleColor);
    }

    /**
     * 按 id 取弹药盒方块。
     *
     * @throws IllegalArgumentException 该 id 不在 {@link AmmoBoxTypes#ALL} 里
     */
    public static RegistryObject<Block> ammoBox(String id) {
        RegistryObject<Block> block = AMMO_BOXES.get(id);
        if (block == null) {
            throw new IllegalArgumentException("未注册的弹药盒 id: \"" + id + "\"，请确认它已登记在 AmmoBoxTypes.ALL 里。");
        }
        return block;
    }

    /** 所有弹药盒方块，按清单顺序。 */
    public static Collection<RegistryObject<Block>> allAmmoBoxes() {
        return Collections.unmodifiableCollection(AMMO_BOXES.values());
    }

    /** 所有弹药盒共用的方块属性。 */
    private static BlockBehaviour.Properties ammoBoxProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GREEN)
                // 硬度 1.0，徒手可挖（不调用 requiresCorrectToolForDrops）
                .strength(1.0F)
                .sound(SoundType.WOOD)
                // 碰撞箱：不调用 noCollission()，于是 hasCollision 保持 true，
                // BlockBehaviour.getCollisionShape 的默认实现会直接返回 getShape 的选框，
                // 因此碰撞箱与描边选框天然一致（每种盒子各自的形状）。
                //
                // 模型不是满方块，不做遮挡面剔除，否则贴着它的方块面会被错误剔除。
                // 这里必须显式调用 noOcclusion()：noCollission() 其实会顺带把 canOcclude 也置为 false，
                // 只删 noCollission() 而漏掉 noOcclusion() 会让面剔除行为跟着变。
                .noOcclusion();
    }

    private ModBlocks() {
    }
}
