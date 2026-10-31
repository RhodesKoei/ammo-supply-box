package com.koei.ammo_supply_box.item;

import com.koei.ammo_supply_box.util.DurationText;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 弹药盒物品，所有弹药盒共用这一个类。
 *
 * <p>tooltip 的行数随盒子类型变化：
 * <ol>
 *   <li>「可放置」——说明这是个可放置的方块物品；</li>
 *   <li>本弹药盒的总伤害预算，数值由构造时注入的取值器实时读取，所以改配置后不需要重新注册物品；</li>
 *   <li><b>仅自动补货变体</b>：补货冷却时长，紧跟在预算下面；</li>
 *   <li>用法提示，所有弹药盒共用同一句话。</li>
 * </ol>
 *
 * <p>冷却那行的取舍：创建物品时如果这个盒子没有冷却（{@code cooldownSeconds} 传 {@code null}），
 * 就<b>完全不显示</b>这一行——普通与创造模式变体因此保持原有的三行。
 * 自动补货变体在冷却被配置成 0 时也不显示，因为「补给冷却：0 秒」对玩家没有意义，
 * 那种状态下它的行为本来就等同于创造模式变体。
 *
 * <p>用法提示里的按键用 {@link Component#keybind(String)} 而不是写死 "O"：
 * 客户端启动时 {@code Minecraft} 会通过 {@code KeybindResolver.setKeyResolver} 装上解析器，
 * 于是它会渲染成玩家<b>当前实际绑定</b>的键，玩家改键后 tooltip 会自动跟着变。
 * 用的键名 {@code key.tacz.interact.desc} 是 TACZ 注册交互键时使用的名字
 * （从 {@code InteractKey} 的静态初始化字节码确认）。
 *
 * <p>注意预算与冷却这两个数值都只有在 {@code ModConfig.Type.SERVER} 下才是正确的——
 * tooltip 由客户端渲染，而 Forge 只同步 SERVER 类型的配置。
 * 详见 {@link com.koei.ammo_supply_box.config.AmmoSupplyBoxConfig}。
 */
public class AmmoBoxItem extends BlockItem {

    /**
     * TACZ 交互键的注册名。
     *
     * <p>取自 TACZ {@code com.tacz.guns.client.input.InteractKey} 的静态初始化，
     * 那里用这个字符串构造了 {@code KeyMapping}。
     */
    private static final String TACZ_INTERACT_KEYBIND = "key.tacz.interact.desc";

    /** 本弹药盒的总伤害预算取值器，见 {@code AmmoSupplyBoxConfig#damageBudget}。 */
    private final Supplier<Integer> damageBudget;

    /**
     * 本弹药盒的补货冷却（秒）取值器，见 {@code AmmoSupplyBoxConfig#cooldownSeconds}。
     *
     * <p><b>可为 {@code null}</b>：只有「自动补货」变体会传值，普通与创造模式变体传 {@code null}
     * 表示"本盒子没有冷却这个概念"，此时 tooltip 不显示冷却行。
     */
    private final Supplier<Integer> cooldownSeconds;

    public AmmoBoxItem(Block block, Properties properties, Supplier<Integer> damageBudget,
                       Supplier<Integer> cooldownSeconds) {
        super(block, properties);
        this.damageBudget = damageBudget;
        this.cooldownSeconds = cooldownSeconds;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);

        // 第一行：说明这是个可放置的方块物品
        tooltip.add(Component.translatable("tooltip.ammo_supply_box.placeable")
                .withStyle(ChatFormatting.GRAY));

        // 第二行：数值本身，由构造时注入的取值器实时读取
        tooltip.add(Component.translatable("tooltip.ammo_supply_box.damage_budget", this.damageBudget.get())
                .withStyle(ChatFormatting.GRAY));

        // 第三行（仅自动补货变体）：冷却时长，按要求紧跟在预算下面。
        // 走 DurationText 而不是直接拼秒数，是为了和聊天提示、Jade 那一行保持同一种写法（分 + 秒）。
        if (this.cooldownSeconds != null) {
            int seconds = Math.max(0, this.cooldownSeconds.get());
            if (seconds > 0) {
                tooltip.add(DurationText.ofSeconds(
                                "tooltip.ammo_supply_box.cooldown",
                                "tooltip.ammo_supply_box.cooldown_seconds",
                                seconds)
                        .withStyle(ChatFormatting.GRAY));
            }
        }

        // 变体自己的附加行（目前只有分享装用它显示剩余次数）。
        // 刻意插在"用法提示"之前：用法是固定的一句话，应该永远在最后一行。
        this.appendExtraHoverText(stack, tooltip);

        // 最后一行：用法提示，所有盒子共用；用 DARK_GRAY 让它在视觉上从属于上面几行
        tooltip.add(Component.translatable("tooltip.ammo_supply_box.usage",
                        Component.keybind(TACZ_INTERACT_KEYBIND))
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /**
     * 供子类插入自己的提示行，默认什么都不做。
     *
     * <p>存在的意义是把"变体自己的数据"和"所有盒子共用的固定行"分开：
     * 分享装需要读<b>手上这一个物品</b>的 NBT（同一种盒子剩 4 次和剩 1 次是不同的东西），
     * 而父类只认识配置值。
     *
     * @param stack   正在渲染的物品，需要读物品自身状态时用它
     * @param tooltip 提示行列表，直接往里 {@code add}
     */
    protected void appendExtraHoverText(ItemStack stack, List<Component> tooltip) {
    }
}
