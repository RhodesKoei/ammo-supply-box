package com.koei.ammo_supply_box.item;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 带一行「风味文字」的方块物品：可放置（父类负责），tooltip 里多一句仿对白的文案。
 *
 * <p>玩法上与普通 {@link BlockItem} 完全一致——放置、堆叠、当合成材料全走父类。
 * 唯一的覆写是 {@link #appendHoverText}，只加一行文案，不影响任何数值。
 *
 * <p>目前有两个使用者，文案都是"仿人物对话"，所以<b>引号写在语言文件里</b>
 * （{@code "...文字..."}），不是代码里拼的——翻译时引号要跟着一起翻：
 * <ul>
 *   <li>木制托盘 {@code wooden_tray}：{@code "它其实是个还不错的装饰，不是吗？"}</li>
 *   <li>木制步道 {@code wooden_walkway}：{@code "你居然真的想过用托盘来做建筑"}</li>
 * </ul>
 * 文案本身只放在语言文件里，本该由 {@code appendHoverText} 的决定权（颜色/斜体）留在代码里，
 * 这样两个物品的观感天然一致。
 *
 * <h2>样式</h2>
 * <p>深灰 + 斜体，与 {@link UpgradeCoreItem}（工厂核心那句「你是...全新装置？」）完全一致：
 * 原版用这一套表示「风味文字 / 描述」，和数据类提示（弹药盒的 GRAY 行）区分开，
 * 让玩家一眼看出这不是机制说明。
 *
 * <p>注意这两个方块都是与弹药盒无关的<b>普通方块</b>，所以它们<b>不</b>继承
 * {@code AmmoBoxItem}，也就没有「可放置 / 补给预算 / 用法」那三行。
 */
public class QuoteTooltipBlockItem extends BlockItem {

    /** 那行风味文字的语言键，由注册处按物品各自传入。 */
    private final String quoteKey;

    public QuoteTooltipBlockItem(Block block, Properties properties, String quoteKey) {
        super(block, properties);
        this.quoteKey = quoteKey;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable(this.quoteKey)
                .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }
}
