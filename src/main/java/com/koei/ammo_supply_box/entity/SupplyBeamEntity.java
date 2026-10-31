package com.koei.ammo_supply_box.entity;

import com.koei.ammo_supply_box.item.AirdropBeaconTypes;
import com.koei.ammo_supply_box.registry.ModBlocks;
import com.koei.ammo_supply_box.registry.ModEntities;
import com.koei.ammo_supply_box.util.SupplyEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 空投光柱：信号弹命中方块顶面后原地生成，等一段时间后在该位置落下空投箱，到点必然消失。
 *
 * <p>它同时是<b>视觉</b>和<b>定时器</b>：
 * <ul>
 *   <li>客户端看到它的存在就画一根信标光柱（见 {@code SupplyBeamRenderer}），
 *       所以"光柱什么时候消失"天然等于"这个实体什么时候被移除"；</li>
 *   <li>服务端用它推进一条<b>纯定时</b>的时间线，见下。</li>
 * </ul>
 *
 * <h2>为什么改成纯定时，而不是"箱子落地才消失"</h2>
 * 早先的实现是每 tick 检查「光柱那格是否已经变成目标空投箱」，落地就收。那个判断在几种情况下
 * 永远不成立，于是光柱会一直挂着：
 * <ul>
 *   <li>落点被玩家在等待期间占了，箱子落到更高一格；</li>
 *   <li>箱子落地后立刻被挖掉（空投箱不掉东西，玩家可能顺手清掉）；</li>
 *   <li>箱子因为上方被彻底封死而没能生成。</li>
 * </ul>
 * 所以现在改成<b>只认时间</b>：光柱出现时就把"落下时刻"和"消失时刻"一起算好，
 * 到点无论如何都收掉。落箱本身是阶段一的副作用，成不成功都不影响阶段二。
 *
 * <h2>时间线（绝对游戏刻，不是自增计数器）</h2>
 * <pre>
 *   出现 ──WAIT_TICKS(5 秒)──▶ 开始下落 ──FALL_TICKS──▶ 光柱消失
 * </pre>
 * 用绝对游戏刻的好处：区块卸载时实体不 tick，但世界时间照走，重新加载后一 tick 就会
 * 把"该落的落、该收的收"补上，不会出现"回来以后光柱从头再等 5 秒"。
 */
public class SupplyBeamEntity extends Entity {

    /** 本光柱要落哪一档空投；同步到客户端用于决定光柱颜色。 */
    private static final EntityDataAccessor<Integer> DATA_TIER =
            SynchedEntityData.defineId(SupplyBeamEntity.class, EntityDataSerializers.INT);

    /** 光柱消失的绝对游戏刻；同步到客户端，用于在光柱上方显示剩余秒数倒计时。 */
    private static final EntityDataAccessor<Long> DATA_DISCARD_AT =
            SynchedEntityData.defineId(SupplyBeamEntity.class, EntityDataSerializers.LONG);

    /**
     * 光柱出现到开始下落的等待时长：<b>5 秒</b>。
     *
     * <p>原先写的是 10 秒，后来整体压短 5 秒——{@link #LIFETIME_TICKS} 是
     * {@code WAIT_TICKS + FALL_TICKS} 推出来的，所以改这一个常量会同时让
     * "等待时间"和"光柱总寿命"各少 5 秒，两者始终相差一个 {@link #FALL_TICKS}。
     */
    public static final int WAIT_TICKS = 5 * 20;

    /**
     * 开始下落到光柱消失的时长。
     *
     * <p>按 {@link AirdropCrateEntity} 的<b>两倍重力</b>模拟得出（每 tick -0.08、末尾乘 0.98）：
     * 160 格需要 80 tick，落点被占而抬高的最坏情况（165 格）需要 81 tick。
     * 取 <b>86</b> 留 5 tick 余量，保证"光柱消失"不会早于"箱子落地"。
     */
    private static final int FALL_TICKS = 86;

    /** 光柱总存在时长（从生成到消失）。 */
    public static final int LIFETIME_TICKS = WAIT_TICKS + FALL_TICKS;

    /**
     * 从光柱上方多少格开始下落：160 格。
     *
     * <p>配合两倍重力大约落 4 秒——能完整看清坠落轨迹，又被原版重力（约 6.3 秒）快。
     * 注意会被建造高度上限钳制：光柱位置太高时实际下落距离会短于这个值，
     * 光柱仍然按 {@link #LIFETIME_TICKS} 定时收掉，不会卡住。
     */
    private static final int FALL_HEIGHT = 160;

    /**
     * 落点那圈粒子隔多少 tick 刷一次。
     *
     * <p>1 = 每 tick 都刷（每秒 20 次）。每个点是「1 蓝 dust + 1 白 dust」两颗，
     * 所以每 tick 会有 {@code RING_POINTS × 2 = 4} 颗，整条光柱生命周期内约 1000 颗。
     * 调大这个值就变稀疏、省性能；1 是最密集的取值（不存在"半 tick"）。
     */
    private static final int RING_INTERVAL_TICKS = 1;

    /** 预定高度不可放置时，最多再往上找几格。 */
    private static final int FALL_HEIGHT_SEARCH = 5;

    /** 客户端渲染距离（格），与信标方块实体的渲染距离一致。 */
    public static final double RENDER_DISTANCE = 256.0D;

    /** 绝对游戏刻：到点开始下落。只在服务端用，不需要同步。 */
    private long dropAt;

    /** 是否已经尝试过下落（只触发一次）。 */
    private boolean fallen;

    public SupplyBeamEntity(EntityType<? extends SupplyBeamEntity> type, Level level) {
        super(type, level);
        // 完全不可交互：没有物理、不受重力、不可见（视觉由渲染器画）、打不掉也烧不掉
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setInvisible(true);
        this.setInvulnerable(true);
    }

    public SupplyBeamEntity(Level level, BlockPos pos, int tier) {
        this(ModEntities.SUPPLY_BEAM.get(), level);
        // 位置取方块中心（X/Z +0.5、Y 取方块底面）：渲染器会再减掉半个格，
        // 因为原版 renderBeaconBeam 内部固定加了 (0.5, 0, 0.5)。
        this.setPos(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        this.setTier(tier);
        long now = level.getGameTime();
        this.dropAt = now + WAIT_TICKS;
        this.setDiscardAt(now + LIFETIME_TICKS);
    }

    @Override
    protected void defineSynchedData() {
        this.getEntityData().define(DATA_TIER, 1);
        this.getEntityData().define(DATA_DISCARD_AT, 0L);
    }

    /**
     * 光柱消失的绝对游戏刻。
     *
     * <p><b>同步给客户端</b>：客户端要靠它算出「剩余寿命」显示在光柱上方的倒计时里。
     * 之所以同步"绝对时刻"而不是"剩余 tick"，是因为剩余量每秒都在变、同步不起；
     * 而客户端自己的 {@code level.getGameTime()} 与服务端基本一致，两者相减就能得到剩余的刻度，
     * 后进服的玩家、区块重载后重新同步也都能立刻拿到正确值。
     */
    public long getDiscardAt() {
        return this.entityData.get(DATA_DISCARD_AT);
    }

    private void setDiscardAt(long discardAt) {
        this.entityData.set(DATA_DISCARD_AT, discardAt);
    }

    /**
     * 距离光柱消失还有多少 tick（客户端也调用：用客户端的游戏刻与同步来的消失时刻相减）。
     *
     * <p>钳到非负，避免超时那一帧算出负数。
     *
     * <p>刚 spawn 出来的那一两帧，同步数据可能还没到（{@code discardAt} 还是默认的 0），
     * 此时退回用实体自身的 {@code tickCount} 估算，免得倒计时先闪一下「0 秒」再跳回满值。
     */
    public long getRemainingTicks() {
        long discardAt = getDiscardAt();
        if (discardAt <= 0L) {
            return Math.max(0L, LIFETIME_TICKS - this.tickCount);
        }
        return Math.max(0L, discardAt - this.level().getGameTime());
    }

    public int getTier() {
        return Mth.clamp(this.entityData.get(DATA_TIER), 1, AirdropBeaconTypes.TIER_COUNT);
    }

    public void setTier(int tier) {
        this.entityData.set(DATA_TIER, Mth.clamp(tier, 1, AirdropBeaconTypes.TIER_COUNT));
    }

    /** 本档对应的空投箱方块。 */
    public Block crateBlock() {
        return ModBlocks.airdropCrate(getTier()).get();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        long now = this.level().getGameTime();

        // 落点那一圈蓝白烟雾：每 RING_INTERVAL_TICKS 刷一次，避免粒子预算爆掉。
        // 放在阶段判断之前，所以"等待中"和"下落中"两个阶段都有这圈烟。
        if (now % RING_INTERVAL_TICKS == 0L) {
            SupplyEffects.spawnAirdropRing(this.level(), this.blockPosition(), now);
        }

        // 阶段一：到点落箱。放在收尾判断之前，这样"区块卸载很久后回来"时
        // 仍然会先把箱子放下来，再让光柱消失，而不是白白吞掉一次空投。
        if (!this.fallen && now >= this.dropAt) {
            startFall();
            if (this.isRemoved()) {
                return;
            }
        }

        // 阶段二：到点必收。不看箱子有没有落成、也看那格现在是什么方块。
        if (now >= getDiscardAt()) {
            this.discard();
        }
    }

    /**
     * 生成下落实体。只会被执行一次（{@link #fallen}）。
     *
     * <p>用的是本模组自己的 {@link AirdropCrateEntity}（原版下落方块 + 双倍重力），它自己负责
     * placement，<b>不会清空目标格</b>；这里仍然先找一个可放置的格子，是为了避免实体从方块内部
     * 开始下落被卡住。找不到就放弃这次落箱——光柱仍然会按定时器正常消失。
     */
    private void startFall() {
        this.fallen = true;
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos base = this.blockPosition();
        int maxY = serverLevel.getMaxBuildHeight() - 1;
        BlockPos spawnPos = null;
        for (int offset = FALL_HEIGHT; offset <= FALL_HEIGHT + FALL_HEIGHT_SEARCH; offset++) {
            int y = Math.min(base.getY() + offset, maxY);
            if (y <= base.getY()) {
                // 已经顶到建造上限，再往上没有意义
                break;
            }
            BlockPos candidate = new BlockPos(base.getX(), y, base.getZ());
            if (serverLevel.getBlockState(candidate).canBeReplaced()) {
                spawnPos = candidate;
                break;
            }
        }
        if (spawnPos == null) {
            // 上方被完全封死：这次不出箱，光柱照常等到 discardAt 自己收掉
            return;
        }
        AirdropCrateEntity.spawn(serverLevel, spawnPos, crateBlock().defaultBlockState());
    }

    // ------------------------------------------------------------------
    // 客户端渲染相关的钩子
    // ------------------------------------------------------------------

    /**
     * 光柱要能在很远的地方看到，所以渲染距离按信标的 256 格算。
     *
     * <p>默认实现是按实体包围盒算的（0.5 格实体只有几格），不覆写的话光柱会走近才突然出现。
     */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        return distanceSqr < RENDER_DISTANCE * RENDER_DISTANCE;
    }

    /** 不可被玩家/箭矢等选中。 */
    @Override
    public boolean isPickable() {
        return false;
    }

    /** 不可被攻击。 */
    @Override
    public boolean isAttackable() {
        return false;
    }

    /** 没有碰撞体，玩家可以直接穿过光柱所在的位置。 */
    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        // 同步数据不进存档，所以档号单独存一份
        tag.putInt("Tier", getTier());
        tag.putLong("DropAt", this.dropAt);
        tag.putLong("DiscardAt", getDiscardAt());
        tag.putBoolean("Fallen", this.fallen);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setTier(tag.getInt("Tier"));
        this.dropAt = tag.getLong("DropAt");
        this.setDiscardAt(tag.getLong("DiscardAt"));
        this.fallen = tag.getBoolean("Fallen");
    }
}
