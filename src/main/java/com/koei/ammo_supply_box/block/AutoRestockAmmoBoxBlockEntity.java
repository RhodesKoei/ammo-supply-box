package com.koei.ammo_supply_box.block;

import com.koei.ammo_supply_box.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 带冷却的弹药盒的方块实体：记住「每个计时器什么时候结束」。
 *
 * <p>为什么需要方块实体：冷却要按<b>方块位置</b>记录，而且要能<b>算出剩余秒数</b>给玩家提示。
 * 这两个需求里任何一条都排除了「只用方块状态」的做法——方块状态只能存枚举/布尔，
 * 存不了绝对时间。所以时间戳放在这里，而「现在有几格可用」这个<b>外观</b>问题
 * 交给方块的方块属性（方块状态会自动同步到客户端，方块实体不做任何网络同步）。
 *
 * <p>存的是<b>绝对游戏刻</b>而不是「剩余刻数」，这样：
 * <ul>
 *   <li>存读档天然正确——世界里时间在走，读回来直接比较即可，不需要额外补偿；</li>
 *   <li>区块卸载期间也不算错——回来时时间早已走过，直接就是可用状态。</li>
 * </ul>
 *
 * <h2>为什么是"一组计时器"而不是一个时间戳</h2>
 * <p>两种变体共用这一个方块实体：
 * <ul>
 *   <li><b>自动补货盒</b>（{@link AutoRestockAmmoBoxBlock}）：容量 1，只用 {@code readyAt[0]}，
 *       行为与改造前完全一致；</li>
 *   <li><b>分享装自动补货盒</b>（{@link ShareRestockAmmoBoxBlock}）：容量 4，四个槽各自独立计时——
 *       用掉一次就占一个槽，该槽到点自动归还。这就是"一个方块里并联 4 个自动补货盒"。</li>
 * </ul>
 * 槽位用"到期时刻"表示：{@code <= 当前时间} 即空闲。<b>不排序、不搬移</b>——
 * 顺序对玩法毫无意义，搬移只会多出出错面。
 *
 * <h2>容量从哪来</h2>
 * <p>方块实体自己不知道容量（{@link #MAX_TIMERS} 只是数组上限），每次操作都由调用方
 * 传进来（来自方块的 {@code maxCharges()}）。这样同一个方块实体类可以服务不同容量的方块。
 */
public class AutoRestockAmmoBoxBlockEntity extends BlockEntity {

    /** 计时器槽位上限（分享装自动补货的 4 连发就是 4）。 */
    public static final int MAX_TIMERS = 4;

    /** 旧格式：单个到期时刻。仅用于读档兼容，见 {@link #load}。 */
    private static final String TAG_READY_AT_LEGACY = "ReadyAt";

    /** 新格式：每个槽的到期时刻。 */
    private static final String TAG_READY_ATS = "ReadyAts";

    /** 每个槽的冷却结束时刻（绝对游戏刻）。{@code <= 当前时间} 表示该槽空闲。 */
    private final long[] readyAt = new long[MAX_TIMERS];

    public AutoRestockAmmoBoxBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AUTO_RESTOCK_AMMO_BOX.get(), pos, state);
    }

    /**
     * <b>最早</b>一个仍在计时的槽的到期时刻；全部空闲时返回 {@code 0}。
     *
     * <p>容量 1 时它就是那唯一的时间戳；容量 4 时它代表"下一格什么时候回来"。
     */
    public long getReadyAt() {
        long earliest = 0L;
        for (long at : this.readyAt) {
            if (at > 0L && (earliest == 0L || at < earliest)) {
                earliest = at;
            }
        }
        return earliest;
    }

    /**
     * 所有槽的到期时刻快照（{@code 0} = 该槽空闲）。
     *
     * <p>给 Jade 用：它要把"4 个计时器各自还剩多久"一起送到客户端。
     * 返回副本，调用方改不动内部状态。
     */
    public long[] getReadyAts() {
        return this.readyAt.clone();
    }

    /**
     * 距离<b>下一次</b>有格子可用还剩多少刻。{@code <= 0} 表示现在就至少有一格可用。
     *
     * <p>刻意用「传入当前时间」而不是「内部读时间」：这样这个方法不依赖
     * {@code level} 是否已挂上，逻辑纯粹，也方便各个调用方各自持有一个 {@code now}。
     */
    public long getRemainingTicks(long gameTime) {
        long earliest = this.getReadyAt();
        return earliest == 0L ? 0L : earliest - gameTime;
    }

    /** 当前有几格可用（容量内的空闲槽数）。 */
    public int chargeCount(long gameTime, int capacity) {
        int limit = Math.min(capacity, MAX_TIMERS);
        int free = 0;
        for (int i = 0; i < limit; i++) {
            if (this.readyAt[i] <= gameTime) {
                free++;
            }
        }
        return free;
    }

    /**
     * 占用一个空闲槽开始计时，返回是否真的占到了。
     *
     * <p>容量 1（自动补货盒）时等价于改造前的 {@code startCooldown}。
     * 全部槽都在计时中（调用方本应先判断可用格数）时返回 {@code false}。
     */
    public boolean startCooldown(long gameTime, int seconds, int capacity) {
        int limit = Math.min(capacity, MAX_TIMERS);
        for (int i = 0; i < limit; i++) {
            if (this.readyAt[i] <= gameTime) {
                this.readyAt[i] = gameTime + seconds * 20L;
                // 触发方块实体数据标记为脏，否则退出世界时不会写进区块 NBT
                this.setChanged();
                return true;
            }
        }
        return false;
    }

    /** 一次性占满所有槽（放置时用：堵住"挖了再放"刷新冷却）。 */
    public void startAllCooldowns(long gameTime, int seconds, int capacity) {
        int limit = Math.min(capacity, MAX_TIMERS);
        long until = gameTime + seconds * 20L;
        for (int i = 0; i < limit; i++) {
            this.readyAt[i] = until;
        }
        this.setChanged();
    }

    /**
     * 把已经到期的槽清零（归还给玩家），返回本次归还了几格。
     *
     * <p>返回值只用来决定"要不要刷新方块状态 + 播一次补货完成反馈"，
     * 所以是"本次新归还的数量"而不是"当前空闲总数"。
     */
    public int releaseExpired(long gameTime) {
        int released = 0;
        for (int i = 0; i < MAX_TIMERS; i++) {
            if (this.readyAt[i] != 0L && this.readyAt[i] <= gameTime) {
                this.readyAt[i] = 0L;
                released++;
            }
        }
        if (released > 0) {
            this.setChanged();
        }
        return released;
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        for (int i = 0; i < MAX_TIMERS; i++) {
            this.readyAt[i] = 0L;
        }
        if (tag.contains(TAG_READY_ATS, LongArrayTag.TAG_LONG_ARRAY)) {
            long[] saved = tag.getLongArray(TAG_READY_ATS);
            for (int i = 0; i < Math.min(saved.length, MAX_TIMERS); i++) {
                this.readyAt[i] = saved[i];
            }
        } else if (tag.contains(TAG_READY_AT_LEGACY)) {
            // 旧存档只有一个时间戳：放进 0 号槽，容量 1 的盒子读出来与原行为完全一致
            this.readyAt[0] = tag.getLong(TAG_READY_AT_LEGACY);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLongArray(TAG_READY_ATS, this.readyAt.clone());
    }
}
