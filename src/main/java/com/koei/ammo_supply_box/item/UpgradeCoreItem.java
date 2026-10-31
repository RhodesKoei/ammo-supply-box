package com.koei.ammo_supply_box.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * 工厂核心（{@code upgrade_core}）：合成「自动补货」弹药盒的中间材料，外加一行风味文字。
 *
 * <p>玩法上和原版 {@link Item} 完全一样——不能使用、不能放置、不参与任何逻辑。
 * 唯一的覆写是 {@link #appendHoverText}，纯粹加一行提示，不影响任何数值。
 *
 * <h2>那行提示是什么</h2>
 * <p>物品模型是照着《明日方舟》里的升级材料「<b>全新装置</b>」做的，所以提示写成一句
 * 认出了它的对白：{@code "......你是...全新装置？"}。纯彩蛋，没有玩法含义。
 * 开头的两个省略号和句首句尾的引号都是文案本身的一部分。
 *
 * <p>「全新装置」的官方英文名是 <b>Integrated Device</b>（装置系 T3 材料，
 * T4 的「改良装置」才是 Optimized Device），所以 {@code en_us} 里用的是这个官方译名，
 * 而不是自己意译成 "brand new device" 之类。
 *
 * <h2>样式</h2>
 * <p>深灰 + 斜体：原版里「风味文字 / 描述」就是用这一套（例如唱片、回响碎片的说明），
 * 和数据类提示（弹药盒的 GRAY 行）区分开，让玩家一眼看出这不是机制说明。
 */
public class UpgradeCoreItem extends Item {

    /** 那行风味文字的语言键。 */
    private static final String QUOTE_KEY = "tooltip.ammo_supply_box.upgrade_core_quote";

    public UpgradeCoreItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable(QUOTE_KEY)
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}
