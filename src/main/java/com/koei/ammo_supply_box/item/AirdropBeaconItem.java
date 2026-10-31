package com.koei.ammo_supply_box.item;

import com.koei.ammo_supply_box.block.AmmoBoxTypes;
import com.koei.ammo_supply_box.config.AmmoSupplyBoxConfig;
import com.koei.ammo_supply_box.entity.AirdropProjectile;
import com.koei.ammo_supply_box.registry.ModItems;
import com.koei.ammo_supply_box.util.DurationText;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * 空投信标：右键投出一颗会弹跳的信号弹，命中方块<b>顶面</b>后在该处召唤空投。
 *
 * <p>这一族共 5 档（见 {@link AirdropBeaconTypes}），每档只决定「最后落下来的是哪个空投箱」，
 * 投掷行为完全一致，所以只有这一个类，档位参数由构造时注入。
 *
 * <h2>两个刻意的设计</h2>
 * <ol>
 *   <li><b>使用不消耗物品</b>：它是可反复使用的道具，成本全部由冷却承担；</li>
 *   <li><b>5 档共用一份冷却</b>：{@link #applyFamilyCooldown} 会把全部 5 档一起压进冷却。
 *       否则玩家只要换一档就能接着投，冷却形同虚设。各档的冷却时长取自
 *       <b>同档位的分享装自动补货盒</b>（{@code ammo_box_N_share_cd}），
 *       所以用 {@code /ammosupplybox cooldown ammo_box_N} 一次调整即可同时作用于两者。</li>
 * </ol>
 *
 * <p>写法上对齐原版末影珍珠（{@code EnderpearlItem}）：音效与冷却方法在
 * {@code isClientSide} 判断<b>之外</b>调用。客户端那次是为了立刻显示冷却遮罩和音效，
 * 服务端那次才是权威，二者缺一都会让手感出问题。
 */
public class AirdropBeaconItem extends Item {

    /**
     * 冷却时长<b>不在这里写死</b>：每一档取<b>同档位「分享装自动补货盒」</b>
     * （{@code ammo_box_N_share_cd}）的补货冷却。
     *
     * <p>这样管理员只要用 {@code /ammosupplybox cooldown ammo_box_N <秒>} 调一次，
     * 分享装自动补货盒和对应档位的空投信标就一起变——不需要为信标再维护一套配置。
     * 配置里本来也没有信标专属的节：{@code _share_cd} 复用同尺寸 {@code _cd} 那一节
     * （{@code AmmoBoxTypes.Type#cooldownConfigId()} 返回的就是 {@code ammo_box_N_cd}），
     * 所以这里也用 {@code cooldownConfigId()} 去查，和 {@code ModBlocks}/{@code ModItems}
     * 建立那些方块时的取法完全一致。
     *
     * <p>取值器在<b>类初始化时</b>解析一次并存进 {@link #COOLDOWNS}，之后每次 {@code get()}
     * 都是实时的——命令改完立刻生效，不需要重载。
     */
    private static final Map<Integer, Supplier<Integer>> COOLDOWNS = new LinkedHashMap<>();

    /** 一秒等于 20 刻。 */
    private static final int TICKS_PER_SECOND = 20;

    static {
        for (AirdropBeaconTypes.Type type : AirdropBeaconTypes.ALL) {
            AmmoBoxTypes.Type box = shareRestockBox(type.tier());
            COOLDOWNS.put(type.tier(), AmmoSupplyBoxConfig.cooldownSeconds(
                    box.cooldownConfigId(), box.cooldownSeconds()));
        }
    }

    /**
     * 找某个档位的「分享装自动补货盒」。
     *
     * <p>按 {@code Variant.SHARE_RESTOCK} + 同档基础 id 找，而不是拼字符串——
     * 这样以后 {@code AmmoBoxTypes} 里新增或改名变体时，这里不会静默拼出一个不存在的 id
     * （拼错了 {@code byId} 只会返回 empty，冷却会悄悄变成 0）。
     *
     * @throws IllegalStateException 找不到（说明 {@code AmmoBoxTypes.ALL} 少了这一档的变体）
     */
    private static AmmoBoxTypes.Type shareRestockBox(int tier) {
        String baseId = "ammo_box_" + tier;
        return AmmoBoxTypes.ALL.stream()
                .filter(candidate -> candidate.variant() == AmmoBoxTypes.Variant.SHARE_RESTOCK
                        && candidate.budgetId().equals(baseId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "找不到档位 " + tier + " 的分享装自动补货盒（" + baseId + "_share_cd），"
                                + "请检查 AmmoBoxTypes.ALL。"));
    }

    /** 某个档位的冷却刻数；该档冷却 ≤ 0（= 关闭）时返回 0。 */
    private static int cooldownTicksFor(int tier) {
        return cooldownSecondsFor(tier) * TICKS_PER_SECOND;
    }

    /** 某个档位的冷却秒数（配置尚未加载时是该档的默认值）；拿不到档位记录时返回 0。 */
    private static int cooldownSecondsFor(int tier) {
        Supplier<Integer> seconds = COOLDOWNS.get(tier);
        return seconds == null ? 0 : seconds.get();
    }

    /** 投掷初速度。比末影珍珠（1.5）慢一点，让弹道看得清。 */
    private static final float THROW_VELOCITY = 1.2F;

    private final AirdropBeaconTypes.Type type;

    public AirdropBeaconItem(AirdropBeaconTypes.Type type, Properties properties) {
        super(properties);
        this.type = type;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // 与雪球同款的投掷音效（SnowballItem 里就是这一组参数）
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SNOWBALL_THROW, SoundSource.NEUTRAL,
                0.5F, 0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F));

        // 5 档一起进冷却（客户端 + 服务端都要跑，理由见类注释）
        applyFamilyCooldown(player);

        if (!level.isClientSide) {
            // 实体只在服务端生成；客户端会在收到生成包时自己建一份
            AirdropProjectile projectile = new AirdropProjectile(level, player);
            projectile.setTier(this.type.tier());
            projectile.shootFromRotation(player, player.getXRot(), player.getYRot(),
                    0.0F, THROW_VELOCITY, 1.0F);
            level.addFreshEntity(projectile);
        }

        player.awardStat(Stats.ITEM_USED.get(this));
        // 不消耗物品，所以没有 shrink；sidedSuccess 负责让两端都挥手
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /**
     * 把<b>全部 5 档</b>信标一起压进冷却，每档用<b>自己</b>的冷却时长。
     *
     * <p>{@code ItemCooldowns} 是按物品实例记录的，所以这里必须逐个遍历；
     * 服务端侧 {@code ServerItemCooldowns} 会为每个物品各发一个
     * {@code ClientboundCooldownPacket}，5 个包的开销可以忽略。
     *
     * <p>为什么"投一档、5 档全进冷却"：否则玩家只要换一档就能接着投，冷却形同虚设。
     * 而此刻各档的冷却长度并不相同（各自等于同档分享装自动补货盒），
     * 所以是"一起进冷却，但各自冷却各自的时间"。
     */
    private static void applyFamilyCooldown(Player player) {
        ItemCooldowns cooldowns = player.getCooldowns();
        for (AirdropBeaconTypes.Type tierType : AirdropBeaconTypes.ALL) {
            int ticks = cooldownTicksFor(tierType.tier());
            if (ticks <= 0) {
                // 该档冷却被配置成 0（关闭）。跳过，免得白发一个已经过期的冷却包
                continue;
            }
            cooldowns.addCooldown(ModItems.airdropBeacon(tierType.tier()).get(), ticks);
        }
    }

    /**
     * 物品提示：一行用法说明 + 一行冷却时长。
     *
     * <p>冷却那一行的数值来自<b>同档位的分享装自动补货盒</b>（见 {@link #COOLDOWNS}），
     * 和实际投掷时用的是同一个取值器，所以提示里看到的数字就是真实会生效的数字。
     * 冷却被配置成 0（关闭）时<b>如实显示「冷却：0 秒」</b>——把 0 显示出来本身就是
     * "这个档位没有冷却"的明确信息，比整行消失更清楚。
     */
    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);

        tooltip.add(Component.translatable("tooltip.ammo_supply_box.airdrop_beacon")
                .withStyle(ChatFormatting.GRAY));

        tooltip.add(DurationText.ofSeconds(
                "tooltip.ammo_supply_box.beacon_cooldown",
                "tooltip.ammo_supply_box.beacon_cooldown_seconds",
                cooldownSecondsFor(this.type.tier())).withStyle(ChatFormatting.GRAY));
    }
}
