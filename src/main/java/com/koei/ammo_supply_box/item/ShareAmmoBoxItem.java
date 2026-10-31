package com.koei.ammo_supply_box.item;

import com.koei.ammo_supply_box.util.ShareBoxData;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * 「分享装」弹药盒物品：在普通弹药盒的 tooltip 上只多一行「剩余次数」。
 *
 * <p>继承 {@link AmmoBoxItem} 而不是另写一个：可放置 / 补给预算 / 用法提示这三行
 * 逻辑一模一样（分享装的单次补给量与一次性盒相同，所以预算那一行也是原样的总量），
 * 只有冷却那一行不适用（分享装没有冷却，所以给父类传 {@code null}）。
 *
 * <p>「剩余次数」这一行必须读<b>手上这一个物品</b>的 NBT，所以不能放在父类里用配置值渲染——
 * 同一种分享装，剩 4 次和剩 1 次是完全不同的两个东西。
 */
public class ShareAmmoBoxItem extends AmmoBoxItem {

    public ShareAmmoBoxItem(Block block, Properties properties, Supplier<Integer> damageBudget) {
        // 第四个参数传 null：分享装没有冷却，父类因此不会渲染冷却那一行
        super(block, properties, damageBudget, null);
    }

    @Override
    protected void appendExtraHoverText(ItemStack stack, List<Component> tooltip) {
        tooltip.add(Component.translatable("tooltip.ammo_supply_box.share_uses",
                        ShareBoxData.getRemainingUses(stack), ShareBoxData.MAX_USES)
                .withStyle(ChatFormatting.GRAY));
    }
}
