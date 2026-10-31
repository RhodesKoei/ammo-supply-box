package com.koei.ammo_supply_box.item;

import com.koei.ammo_supply_box.registry.ModSounds;
import com.koei.ammo_supply_box.util.TaczAmmoHelper;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.resource.index.CommonAmmoIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * 随机弹药盒：<b>纯物品</b>，没有方块形态。右键一次性打开，从服务器上<b>全部</b>弹药类型里
 * 随机抽几种、每种随机给一些。
 *
 * <p>和方块版弹药盒的三点本质区别（都是刻意的）：
 * <ol>
 *   <li><b>不需要手持枪械</b>：方块盒的补给对象由手持的枪决定，这里完全不看手，
 *       空手也能用；</li>
 *   <li><b>不套伤害预算</b>：方块盒按"{@code TaczAmmoHelper#getGrantAmount} 的总伤害预算"
 *       换算数量，随机盒是纯随机（数量上限只受各口径的堆叠上限约束）；</li>
 *   <li><b>不管虚拟备弹</b>：它不知道玩家要拿这些子弹喂哪把枪，所以一律发弹药物品，
 *       不会去写枪械自带的 {@code DummyAmmo} 池。</li>
 * </ol>
 *
 * <h2>弹药清单从哪来</h2>
 * {@link TimelessAPI#getAllCommonAmmoIndex()}。这是 TACZ 的<b>弹药索引</b>，不是物品注册表——
 * TACZ 整个模组只有<b>一个</b>弹药物品 {@code tacz:ammo}，具体口径存在堆叠 NBT 里
 * （{@code AmmoItemDataAccessor.AMMO_ID_TAG}），所以遍历 {@code ForgeRegistries.ITEMS}
 * 根本枚举不出"有多少种子弹"。
 *
 * <p>索引的键是完整的 {@code ResourceLocation}，<b>命名空间就是枪包的数据命名空间</b>
 * （默认包在 {@code data/tacz/index/ammo/*.json} → {@code tacz:9mm}），因此第三方枪包里的
 * 弹药会自动出现在这里，不需要我们认识那个包。
 *
 * <p>索引是数据包 reload 的产物：模组构造期取到的是空集合，{@code /reload} 后会重建。
 * 所以本类每次右键都<b>现场取一次</b>，绝不缓存到静态字段。
 */
public class RandomAmmoBoxItem extends Item {

    private static final String MESSAGE_PREFIX = "message.ammo_supply_box.";

    private static final String TOOLTIP_HINT = "tooltip.ammo_supply_box.random_hint";

    /** 本物品对应的类型参数，见 {@link RandomAmmoBoxTypes}。 */
    private final RandomAmmoBoxTypes.Type type;

    public RandomAmmoBoxItem(RandomAmmoBoxTypes.Type type, Properties properties) {
        super(properties);
        this.type = type;
    }

    /**
     * 右键：抽几种弹药发给玩家，然后消耗掉这一个物品。
     *
     * <p><b>随机只在服务端跑</b>。客户端里 {@code Item#use} 同样会被调用一次
     * （{@code MultiPlayerGameMode#useItem} 会先本地预测一遍），如果两边都抽，
     * 客户端显示的和服务器真正发的必然对不上，所以客户端一律直接返回。
     *
     * <p>消耗方式就是在服务端 {@code shrink(1)}，创造模式不用另外判断：
     * {@code ServerPlayerGameMode#useItem} 会在 {@code use} 返回后对创造模式玩家
     * 执行 {@code itemstack.setCount(原数量)}，物品会自己变回去。
     *
     * <p>对准会吞掉右键的方块（箱子、工作台等）时不会触发——这是原版行为，
     * 客户端在 {@code useItemOn} 返回"已消耗"后就不再走 {@code useItem} 这条路径了，
     * 和扔雪球/末影珍珠完全一样，不需要特殊处理。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide) {
            // 只让客户端摆一下手，不做任何随机
            return InteractionResultHolder.success(stack);
        }

        List<Map.Entry<ResourceLocation, CommonAmmoIndex>> pool =
                new ArrayList<>(TimelessAPI.getAllCommonAmmoIndex());
        if (pool.isEmpty()) {
            // 索引拿不到（TACZ 资源未加载完 / 枪包索引全被替换掉）。
            // 这种情况绝不能消耗物品：玩家会白白损失一个盒子。
            player.displayClientMessage(
                    Component.translatable(MESSAGE_PREFIX + "random_unavailable")
                            .withStyle(ChatFormatting.RED),
                    false);
            return InteractionResultHolder.success(stack);
        }

        RandomSource random = level.getRandom();

        // 种类数：minKinds ~ maxKinds 之间的闭区间随机，再钳到服务器实际有多少种弹药
        int kinds = Math.min(pool.size(),
                this.type.minKinds() + random.nextInt(this.type.maxKinds() - this.type.minKinds() + 1));
        List<Map.Entry<ResourceLocation, CommonAmmoIndex>> picked = pickRandom(pool, kinds, random);

        // 汇总成一条消息，而不是每种弹药一条：一次给好几种时逐条刷屏很吵
        MutableComponent summary = Component.empty();
        for (int i = 0; i < picked.size(); i++) {
            Map.Entry<ResourceLocation, CommonAmmoIndex> entry = picked.get(i);

            // 数量：1 ~ 该口径的堆叠上限（闭区间）。堆叠上限取不到时由 maxAmountPerKind 兜底成 1。
            int upper = this.type.maxAmountPerKind(entry.getValue().getStackSize());
            int amount = 1 + random.nextInt(upper);

            // giveAmmo 已经处理了"按堆叠上限拆成多叠"和"背包塞不下的余量掉在脚下"
            TaczAmmoHelper.giveAmmo(player, entry.getKey(), amount);

            if (i > 0) {
                summary.append(Component.translatable(MESSAGE_PREFIX + "random_separator"));
            }
            summary.append(Component.translatable(MESSAGE_PREFIX + "random_entry", ammoName(entry), amount));
        }

        // 只发给操作者本人（displayClientMessage 的第二个参数 false = 走聊天框；
        // 它只往这名玩家的连接发包，附近其他玩家看不到）。
        //
        // 用 WHITE 而不是绿色：这行是"开包结果"的陈述，不是"补给成功"这种状态提示，
        // 白色更中性。注意弹药名自身可能带颜色——枪包可以在翻译值里内嵌 §a/§e 这类格式码
        //（默认包的 9mm 就是 §a），而内嵌格式码的优先级高于这里设的父样式，
        // 所以那些名字仍会显示成枪包作者指定的颜色，"随机弹药盒："与数量则是白色。
        player.displayClientMessage(
                Component.translatable(MESSAGE_PREFIX + "random_supplied", summary)
                        .withStyle(ChatFormatting.WHITE),
                false);

        // 与方块盒同款补给音效，同样只发给操作者（Player#playNotifySound 在 ServerPlayer 里
        // 被覆写成"只发给该玩家自己"）
        player.playNotifySound(ModSounds.AMMO_BOX_SUPPLY.get(), SoundSource.PLAYERS, 1.0F, 1.0F);

        // 一次性：用掉这一个。创造模式由原版自动回填，见方法注释。
        stack.shrink(1);
        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);

        // 刻意不提"几种""一次性"这些数字：文案一句话说清怎么用即可，
        // 具体给多少是随机盒自己的事，写进 tooltip 反而要跟着参数改动而失效。
        tooltip.add(Component.translatable(TOOLTIP_HINT)
                .withStyle(ChatFormatting.GRAY));
    }

    /**
     * 从候选池里等概率、<b>不重复</b>地取 {@code count} 项。
     *
     * <p>用部分 Fisher-Yates 洗牌：只把前 {@code count} 个位置确定下来，
     * 后面的元素不需要处理。这样比"整体洗牌再取前 n 个"少做无用功，
     * 而且每个子集的概率完全相等。不用 {@link Collections#shuffle} 是因为它只接受
     * {@link java.util.Random}，而 Minecraft 统一用 {@link RandomSource}。
     */
    private static <T> List<T> pickRandom(List<T> pool, int count, RandomSource random) {
        List<T> candidates = new ArrayList<>(pool);
        int picked = Math.min(count, candidates.size());
        for (int i = 0; i < picked; i++) {
            // 在 [i, size) 里等概率挑一个换到位置 i
            int j = i + random.nextInt(candidates.size() - i);
            Collections.swap(candidates, i, j);
        }
        return candidates.subList(0, picked);
    }

    /**
     * 弹药在聊天提示里的显示名。
     *
     * <p>TACZ 索引里的 {@code name} 字段默认包里给的是翻译键（{@code tacz.ammo.9mm.name}），
     * 但第三方枪包也可能直接写纯文本。统一交给 {@link Component#translatable} 处理：
     * 是翻译键就正常本地化，是纯文本则原样显示（找不到 key 时就是把 key 本身显示出来），
     * 两种情况都得到可读的结果，不需要我们猜。
     *
     * <p>取不到名字时回落到 id 的 path（不显示命名空间，聊天里太长了）。
     */
    private static Component ammoName(Map.Entry<ResourceLocation, CommonAmmoIndex> entry) {
        CommonAmmoIndex index = entry.getValue();
        String name = index == null || index.getPojo() == null ? null : index.getPojo().getName();
        if (name == null || name.isBlank()) {
            return Component.literal(entry.getKey().getPath());
        }
        return Component.translatable(name);
    }
}
