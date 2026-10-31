package com.koei.ammo_supply_box.registry;

import com.koei.ammo_supply_box.AmmoSupplyBox;
import com.koei.ammo_supply_box.entity.AirdropCrateEntity;
import com.koei.ammo_supply_box.entity.AirdropProjectile;
import com.koei.ammo_supply_box.entity.AirdropShakeEntity;
import com.koei.ammo_supply_box.entity.SupplyBeamEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 空投系统的实体类型。
 *
 * <h2>三个实体的分工</h2>
 * <ul>
 *   <li>{@link #AIRDROP_PROJECTILE}：信号弹本体。碰撞箱小、跟踪范围按普通投掷物给
 *       （8 区块足够，它飞行时间很短）；</li>
 *   <li>{@link #SUPPLY_BEAM}：光柱 + 计时器。<b>跟踪范围必须给大</b>——
 *       实体的同步范围由这里决定，给 16 区块（约 256 格）才和原版信标
 *       （{@code BeaconRenderer#getViewDistance() = 256}）的观感一致，
 *       否则光柱会在 100 格外突然消失；</li>
 *   <li>{@link #AIRDROP_CRATE}：下落中的空投箱，原版下落方块的子类（双倍重力）。</li>
 * </ul>
 *
 * <p>光柱实体设了 {@code noSummon()}：它只由代码生成，不应该被刷怪蛋或
 * {@code /summon} 半吊子地创建出来（那样计时字段全是 0，会立刻触发空投）。
 */
public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, AmmoSupplyBox.MODID);

    /** 信号弹。 */
    public static final RegistryObject<EntityType<AirdropProjectile>> AIRDROP_PROJECTILE =
            ENTITY_TYPES.register("airdrop_projectile",
                    () -> EntityType.Builder.<AirdropProjectile>of(AirdropProjectile::new, MobCategory.MISC)
                            .sized(0.25F, 0.25F)
                            .clientTrackingRange(8)
                            .updateInterval(10)
                            .fireImmune()
                            .build("airdrop_projectile"));

    /** 空投光柱（同时是空投计时器）。 */
    public static final RegistryObject<EntityType<SupplyBeamEntity>> SUPPLY_BEAM =
            ENTITY_TYPES.register("supply_beam",
                    () -> EntityType.Builder.<SupplyBeamEntity>of(SupplyBeamEntity::new, MobCategory.MISC)
                            .sized(0.5F, 0.5F)
                            .clientTrackingRange(16)
                            .updateInterval(20)
                            .noSummon()
                            .fireImmune()
                            .build("supply_beam"));

    /**
     * 下落中的空投箱。
     *
     * <p>尺寸 / 跟踪范围 / 更新间隔都照抄原版 {@code EntityType.FALLING_BLOCK}
     * （{@code 0.98 × 0.98}、10 区块、20 tick），只有重力不同——见
     * {@link AirdropCrateEntity}，它比原版下落方块快得多（重力 8 倍），落地会发声，
     * 并且像铁砧一样砸伤落点上的生物。
     */
    public static final RegistryObject<EntityType<AirdropCrateEntity>> AIRDROP_CRATE =
            ENTITY_TYPES.register("airdrop_crate",
                    () -> EntityType.Builder.<AirdropCrateEntity>of(AirdropCrateEntity::new, MobCategory.MISC)
                            .sized(0.98F, 0.98F)
                            .clientTrackingRange(10)
                            .updateInterval(20)
                            .noSummon()
                            .build("airdrop_crate"));

    /**
     * 摄像机震颤标记：空投箱落地时生成一个，让范围内玩家的视角抖一下。
     *
     * <p>它是<b>纯参数载体</b>，没有任何几何体，所以尺寸给 {@code 0 × 0}、更新间隔给 20 tick
     * （它从不移动，没必要每 tick 同步位置）。跟踪范围 8 区块（128 格）覆盖了 24 格的影响半径，
     * 保证范围内的玩家都能收到这个实体。
     *
     * <p>客户端必须给它注册一个渲染器（用原版 {@code NoopRenderer}，什么都不画），
     * 否则会因为没有渲染器而报警告。
     */
    public static final RegistryObject<EntityType<AirdropShakeEntity>> AIRDROP_SHAKE =
            ENTITY_TYPES.register("airdrop_shake",
                    () -> EntityType.Builder.<AirdropShakeEntity>of(AirdropShakeEntity::new, MobCategory.MISC)
                            .sized(0.0F, 0.0F)
                            .clientTrackingRange(8)
                            .updateInterval(20)
                            .noSummon()
                            .fireImmune()
                            .build("airdrop_shake"));

    private ModEntities() {
    }
}
