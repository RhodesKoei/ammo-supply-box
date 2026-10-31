package com.koei.ammo_supply_box.compat.jade;

import com.koei.ammo_supply_box.block.AutoRestockAmmoBoxBlockEntity;
import com.koei.ammo_supply_box.block.CooldownAmmoBoxBlock;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * 服务端那半边：玩家把准星对准带冷却的弹药盒时，把「每个计时器结束的绝对游戏刻」放进 Jade 的包。
 *
 * <p>这里刻意只传时间戳、不传「还剩几秒」，因为两点：
 * <ul>
 *   <li>剩余时间是会一直变的，传过去就立刻过期；传绝对时间戳，
 *       客户端每刻拿自己的游戏时间一减就是实时值，不需要服务端反复发包；</li>
 *   <li>客户端的 {@code level.getGameTime()} 和服务端是同一根时间轴
 *       （原版每 20 刻同步一次），所以两边算出来的数字一致，暂停时也会一起停住。</li>
 * </ul>
 *
 * <h2>为什么传整个数组</h2>
 * <p>「分享装自动补货盒」一个方块里并联 4 个独立计时器，玩家想知道的是<b>4 条冷却各自还剩多久</b>，
 * 只传最早的那一个会丢掉另外三条。数组里 {@code 0} 表示该槽空闲，
 * 客户端按「只显示还在跑的槽」过滤，所以容量 1 的普通自动补货盒自然只显示一行。
 * 容量（总共几个槽）一起传过去，客户端才能写出「可用 1 / 4」。
 *
 * <p>用 {@code enum} 单例而不是每次 new 一个：Jade 在注册时就要求给一个固定实例，
 * 而且每个方块每 4 刻就会被问一次，单例省掉分配。
 */
public enum AutoRestockCooldownDataProvider implements IServerDataProvider<BlockAccessor> {

    INSTANCE;

    /** 每个槽的到期时刻（{@code 0} = 空闲）。 */
    static final String TAG_READY_ATS = "ammo_supply_box:ready_ats";

    /** 总槽位数（容量）。 */
    static final String TAG_CAPACITY = "ammo_supply_box:timer_capacity";

    /** 最早到期时刻（单值），留给"只关心下一格"的读取方。 */
    static final String TAG_READY_AT = "ammo_supply_box:ready_at";

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        // 注册时已经限定过方块实体类型，这里再判一次是因为 Jade 的泛型擦除后
        // 实际调用点不做强类型检查，保持显式判断更安全。
        if (!(accessor.getBlockEntity() instanceof AutoRestockAmmoBoxBlockEntity box)) {
            return;
        }
        // 容量只有方块自己知道（方块实体是按容量无关的方式存的），所以问方块
        int capacity = accessor.getBlockState().getBlock() instanceof CooldownAmmoBoxBlock block
                ? block.maxCharges()
                : 1;
        data.putLongArray(TAG_READY_ATS, box.getReadyAts());
        data.putInt(TAG_CAPACITY, capacity);
        data.putLong(TAG_READY_AT, box.getReadyAt());
    }

    @Override
    public ResourceLocation getUid() {
        return AmmoBoxJadePlugin.UID_COOLDOWN;
    }
}
