package com.koei.ammo_supply_box.compat.jade;

import com.koei.ammo_supply_box.util.DurationText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 客户端那半边：把服务端送来的时间戳换算成「还剩多久」，<b>每个还在跑的计时器各加一行</b>。
 *
 * <p>这些字之所以能像倒计时一样每秒都在变，是因为 Jade 的
 * {@code WailaTickHandler#tickClient} 每客户端刻都会重新收集一遍 tooltip，
 * 而我们每次都是拿「绝对时间戳 − 当前游戏时间」现算的，没有任何缓存。
 *
 * <h2>显示形态</h2>
 * <pre>
 * 补货剩余（1 / 4 可用）   ← 抬头：当前可用几格 / 总共几格
 * · 3 分 20 秒            ← 每个"还在跑"的计时器一行，按剩余时间从少到多排列
 * · 4 分 59 秒
 * · 4 分 59 秒
 * </pre>
 * 容量 1 的普通自动补货盒只会有一行；空闲的槽不显示（所以全部可领时整块提示都不出现）。
 *
 * <p>三种情况直接不显示：
 * <ul>
 *   <li>包里没有计时器数据——玩家连的服务器没装本模组，服务端数据通道根本没开；</li>
 *   <li>没有计时器在跑（全部可领）；</li>
 *   <li>算出来的剩余全部 {@code <= 0}——此时方块本身已经是可领外观，再挂「还剩 0 秒」只是噪音。</li>
 * </ul>
 */
public enum AutoRestockCooldownComponent implements IBlockComponentProvider {

    INSTANCE;

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        long now = accessor.getLevel().getGameTime();

        int capacity = data.contains(AutoRestockCooldownDataProvider.TAG_CAPACITY, Tag.TAG_INT)
                ? Math.max(1, data.getInt(AutoRestockCooldownDataProvider.TAG_CAPACITY))
                : 1;

        // 收集"还在跑"的计时器剩余刻数（数组里 0 = 空闲）；老格式只有单个时间戳时也能读
        List<Long> remaining = new ArrayList<>(capacity);
        if (data.contains(AutoRestockCooldownDataProvider.TAG_READY_ATS, Tag.TAG_LONG_ARRAY)) {
            long[] readyAts = data.getLongArray(AutoRestockCooldownDataProvider.TAG_READY_ATS);
            for (int i = 0; i < Math.min(capacity, readyAts.length); i++) {
                if (readyAts[i] <= 0L) {
                    continue;
                }
                long ticks = readyAts[i] - now;
                if (ticks > 0L) {
                    remaining.add(ticks);
                }
            }
        } else if (data.contains(AutoRestockCooldownDataProvider.TAG_READY_AT, Tag.TAG_LONG)) {
            long ticks = data.getLong(AutoRestockCooldownDataProvider.TAG_READY_AT) - now;
            if (ticks > 0L) {
                remaining.add(ticks);
            }
        }

        if (remaining.isEmpty()) {
            return;
        }

        // 抬头：可用几格 / 总共几格。按"还在跑的条数"推算空闲数，与方块状态的展示口径一致
        int available = Math.max(0, capacity - remaining.size());
        tooltip.add(Component.translatable("jade.ammo_supply_box.cooldown_header", available, capacity)
                .withStyle(ChatFormatting.GRAY));

        // 从少到多：玩家最关心的永远是"下一格还差多久"
        Collections.sort(remaining);
        for (long ticks : remaining) {
            // 换算交给 DurationText：它和聊天里的拒绝提示、物品 tooltip、命令回执共用同一份实现，
            // 所以这里显示的时间可以和那几处直接对照（向上取整的逻辑也在那里）。
            tooltip.add(DurationText.ofTicks(
                            "jade.ammo_supply_box.cooldown",
                            "jade.ammo_supply_box.cooldown_seconds",
                            ticks)
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public ResourceLocation getUid() {
        return AmmoBoxJadePlugin.UID_COOLDOWN;
    }
}
