package com.koei.ammo_supply_box.entity;

import com.koei.ammo_supply_box.item.AirdropBeaconTypes;
import com.koei.ammo_supply_box.registry.ModEntities;
import com.koei.ammo_supply_box.registry.ModItems;
import com.koei.ammo_supply_box.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 空投信标投出的信号弹：像雪球一样飞出去，但会<b>弹跳</b>，撞到方块顶面才触发空投。
 *
 * <h2>为什么可以在 onHitBlock 里直接改速度</h2>
 * 父类 {@code ThrowableProjectile#tick()} 的顺序是：先沿当前速度射线检测碰撞、命中就回调
 * {@code onHit}，<b>之后</b>才读取 {@code getDeltaMovement()} 结算位移。所以在
 * {@link #onHitBlock} 里把速度取反，这一 tick 的位移就立刻朝反方向走——既不会卡进方块，
 * 也不需要手动把实体推出方块边界。
 *
 * <h2>命中规则</h2>
 * <table border="1">
 *   <caption>按被撞面的法线决定行为</caption>
 *   <tr><th>被撞的面</th><th>getDirection()</th><th>行为</th></tr>
 *   <tr><td>方块顶面</td><td>UP</td><td>消失，并在该方块上方生成 {@link SupplyBeamEntity}</td></tr>
 *   <tr><td>方块侧面</td><td>NORTH/SOUTH/EAST/WEST</td><td>反射对应水平分量 × 阻尼</td></tr>
 *   <tr><td>方块底面</td><td>DOWN</td><td>反射竖直分量 × 阻尼</td></tr>
 * </table>
 *
 * <p>整段逻辑在<b>两端都会跑</b>：客户端的实体也会自己 tick 并做同样的反弹（没有随机数，
 * 因此结果确定），这样客户端看起来才不会一路直线穿墙再被位置包拽回来。
 * 真正的副作用（生成光柱、放音效）只在服务端执行。
 */
public class AirdropProjectile extends ThrowableItemProjectile {

    /** 本弹射物属于哪一档空投；同步到客户端用于渲染对应的信标物品。 */
    private static final EntityDataAccessor<Integer> DATA_TIER =
            SynchedEntityData.defineId(AirdropProjectile.class, EntityDataSerializers.INT);

    /** 每次反弹保留的速度比例。小于 1 才会越弹越矮，最终必然落到某个顶面上。 */
    private static final double BOUNCE_DAMPING = 0.75D;

    /** 反弹次数上限：防止在狭小空间里无限对弹。 */
    private static final int MAX_BOUNCES = 12;

    /** 存活上限：防止扔进虚空或极开阔地带后永远弹下去。 */
    private static final int MAX_LIFETIME_TICKS = 200;

    private int bounces;
    private int lifetime;

    public AirdropProjectile(EntityType<? extends AirdropProjectile> type, Level level) {
        super(type, level);
    }

    public AirdropProjectile(Level level, LivingEntity shooter) {
        super(ModEntities.AIRDROP_PROJECTILE.get(), shooter, level);
    }

    /**
     * 渲染时用哪张贴图——直接取本档的信标物品。
     *
     * <p>故意不依赖 {@code setItem} 传入的物品堆：{@code ThrowableItemProjectile#setItem}
     * 在「传入的堆就是这个默认物品且没有 NBT」时<b>不会</b>存下来，5 档信标就会全部渲染成同一档。
     * 把「档号」作为唯一状态、渲染时反查物品，正好绕开这个短路。
     */
    @Override
    protected Item getDefaultItem() {
        return ModItems.airdropBeacon(getTier()).get();
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.getEntityData().define(DATA_TIER, 1);
    }

    public int getTier() {
        return Mth.clamp(this.entityData.get(DATA_TIER), 1, AirdropBeaconTypes.TIER_COUNT);
    }

    public void setTier(int tier) {
        this.entityData.set(DATA_TIER, Mth.clamp(tier, 1, AirdropBeaconTypes.TIER_COUNT));
    }

    @Override
    public void tick() {
        super.tick();
        if (this.isRemoved()) {
            return;
        }
        if (++this.lifetime > MAX_LIFETIME_TICKS) {
            this.discard();
        }
    }

    /**
     * 撞到实体时什么都不做。
     *
     * <p>返回 {@code false} 之后，{@code ProjectileUtil.getHitResultOnMoveVector} 的实体筛选
     * 会把所有实体排除掉，于是这颗球只与方块互动——它本来就不该被路人或者坐骑挡下来。
     */
    @Override
    protected boolean canHitEntity(Entity target) {
        return false;
    }

    /**
     * 撞到方块。顶面 → 触发空投；其余面 → 反弹。
     *
     * <p>刻意<b>不</b>调用 {@code super.onHitBlock}：父类会让被撞方块执行
     * {@code BlockState#onProjectileHit}（打钟、命中标靶之类），而信号弹只是路过，
     * 不应该替玩家意外触发那些机关。
     */
    @Override
    protected void onHitBlock(BlockHitResult result) {
        if (result.getDirection() == Direction.UP) {
            spawnAirdrop(result.getBlockPos());
            this.discard();
            return;
        }
        bounceOff(result.getDirection());
    }

    /**
     * 在命中的方块<b>正上方那格</b>生成光柱。
     *
     * <p>用格子中心而不是精确命中点：光柱要插在方块正上方、空投箱也要落回同一格，
     * 对齐到格子是唯一能让“落地判定只查一格”成立的做法。
     */
    private void spawnAirdrop(BlockPos hitPos) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            // 客户端不做这件事，它只等服务器把光柱实体同步过来
            return;
        }
        BlockPos beamPos = hitPos.above();
        serverLevel.addFreshEntity(new SupplyBeamEntity(serverLevel, beamPos, getTier()));
        // 光柱升起音：用本模组自己的 sounds/beam.ogg。
        // playSound(null, ...) 会广播给附近玩家，客户端不需要自己再播一遍。
        serverLevel.playSound(null, beamPos, ModSounds.AIRDROP_BEAM.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    /**
     * 按被撞面的轴向反射速度。
     *
     * <p>{@link Direction.Axis} 只有 X / Y / Z 三种，所以这个 switch 是穷尽的：
     * 侧面（X/Z 轴）反射水平分量，天花板/底面（Y 轴）反射竖直分量。
     */
    private void bounceOff(Direction face) {
        if (++this.bounces > MAX_BOUNCES) {
            // 弹太多次还没落到顶面上，直接消失，避免留下永久实体
            this.discard();
            return;
        }
        Vec3 velocity = this.getDeltaMovement();
        Vec3 bounced = switch (face.getAxis()) {
            case X -> new Vec3(-velocity.x * BOUNCE_DAMPING, velocity.y, velocity.z);
            case Y -> new Vec3(velocity.x, -velocity.y * BOUNCE_DAMPING, velocity.z);
            case Z -> new Vec3(velocity.x, velocity.y, -velocity.z * BOUNCE_DAMPING);
        };
        this.setDeltaMovement(bounced);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        // 同步数据不写进存档，所以档号要单独存一份，否则区块重载后信号弹会变回第一档
        tag.putInt("Tier", getTier());
        tag.putInt("Bounces", this.bounces);
        tag.putInt("Lifetime", this.lifetime);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setTier(tag.getInt("Tier"));
        this.bounces = tag.getInt("Bounces");
        this.lifetime = tag.getInt("Lifetime");
    }
}
