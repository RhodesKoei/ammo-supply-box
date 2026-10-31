package com.koei.ammo_supply_box.registry;

import com.koei.ammo_supply_box.AmmoSupplyBox;
import com.koei.ammo_supply_box.block.AmmoBoxTypes;
import com.koei.ammo_supply_box.config.AmmoSupplyBoxConfig;
import com.koei.ammo_supply_box.item.AirdropBeaconItem;
import com.koei.ammo_supply_box.item.AirdropBeaconTypes;
import com.koei.ammo_supply_box.item.AmmoBoxItem;
import com.koei.ammo_supply_box.item.QuoteTooltipBlockItem;
import com.koei.ammo_supply_box.item.RandomAmmoBoxItem;
import com.koei.ammo_supply_box.item.RandomAmmoBoxTypes;
import com.koei.ammo_supply_box.item.ShareAmmoBoxItem;
import com.koei.ammo_supply_box.item.UpgradeCoreItem;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, AmmoSupplyBox.MODID);

    /** 弹药盒 id -> 物品，顺序与 {@link AmmoBoxTypes#ALL} 一致。 */
    private static final Map<String, RegistryObject<Item>> AMMO_BOX_ITEMS = new LinkedHashMap<>();

    static {
        // 遍历清单自动注册，新增弹药盒不需要在这里写任何东西
        for (AmmoBoxTypes.Type type : AmmoBoxTypes.ALL) {
            AMMO_BOX_ITEMS.put(type.id(), ITEMS.register(type.id(), () -> createItem(type)));
        }
    }

    /**
     * 随机弹药盒 id -&gt; 物品，顺序与 {@link RandomAmmoBoxTypes#ALL} 一致。
     *
     * <p>它们和方块弹药盒分开存：随机盒是<b>纯物品</b>，没有方块、没有方块物品、
     * 不参与 {@code AmmoBoxTypes} 那套预算/冷却/变体逻辑，混在一起只会让两边的
     * 遍历代码到处都要判断"这个 id 到底是不是方块"。
     */
    private static final Map<String, RegistryObject<Item>> RANDOM_AMMO_BOX_ITEMS = new LinkedHashMap<>();

    static {
        // 同样是遍历清单自动注册：以后加"更肥的随机盒"只需要在 RandomAmmoBoxTypes 里加一行
        for (RandomAmmoBoxTypes.Type type : RandomAmmoBoxTypes.ALL) {
            RANDOM_AMMO_BOX_ITEMS.put(type.id(), ITEMS.register(type.id(),
                    () -> new RandomAmmoBoxItem(type, new Item.Properties().rarity(type.rarity()))));
        }
    }

    /** 空投箱档号 -&gt; 方块物品；空投信标档号 -&gt; 投掷物品。两者按同一档号配对。 */
    private static final Map<Integer, RegistryObject<Item>> AIRDROP_CRATES = new LinkedHashMap<>();
    private static final Map<Integer, RegistryObject<Item>> AIRDROP_BEACONS = new LinkedHashMap<>();

    static {
        for (AirdropBeaconTypes.Type type : AirdropBeaconTypes.ALL) {
            // 空投箱：<b>带 4 次使用次数</b>的补给点，所以用分享装那套物品类——
            // tooltip 会多一行「剩余补给次数：N / 4」，读数来自物品自身 NBT（挖掉/中键都会带上）。
            // 补给预算与普通盒同源，见 ModBlocks#createAirdropCrate。
            AmmoBoxTypes.Type base = ModBlocks.airdropBaseBox(type.tier());
            AIRDROP_CRATES.put(type.tier(), ITEMS.register(type.crateId(),
                    () -> new ShareAmmoBoxItem(ModBlocks.airdropCrate(type.tier()).get(),
                            new Item.Properties(),
                            AmmoSupplyBoxConfig.damageBudget(base.budgetId(), base.defaultDamageBudget()))));
            // 信标：可反复使用、不消耗，堆叠上限 1（每档一件道具，不堆叠）；
            // 注意 Item.Properties 的默认堆叠上限是 64，所以这里必须显式 stacksTo(1)。
            // 稀有度随档位升高
            AIRDROP_BEACONS.put(type.tier(), ITEMS.register(type.beaconId(),
                    () -> new AirdropBeaconItem(type, new Item.Properties()
                            .stacksTo(1)
                            .rarity(type.rarity()))));
        }
    }

    /**
     * 工厂核心：纯<b>合成中间材料</b>，没有任何功能。
     *
     * <p>不能使用、不能放置、不参与任何玩法逻辑——{@link UpgradeCoreItem} 只覆写了
     * {@code appendHoverText}（加一行《明日方舟》彩蛋文字），其余行为完全由原版
     * {@code Item} 决定。唯一的玩法用途是作为合成「自动补货」弹药盒的原料。
     *
     * <p>三个属性都是刻意的：
     * <ul>
     *   <li><b>堆叠上限 1</b>：它是合成链条上最高级的一环，一次只能拿一个，不适合囤积；</li>
     *   <li><b>稀有度 {@code EPIC}（淡紫）</b>：与「自动补货」变体的最高档同色，
     *       玩家一眼就能看出这是高级材料。只影响名称颜色，不影响玩法。</li>
     *   <li><b>不怕火/岩浆</b>（{@code fireResistant()}）：掉落物掉进岩浆或火里不会被烧掉，
     *       与下界合金系列一致。判定机制见下方说明。</li>
     * </ul>
     *
     * <h2>为什么 {@code fireResistant()} 就能免岩浆</h2>
     * 原版下界合金物品（{@code Items.NETHERITE_INGOT} 等）注册时就是
     * {@code new Item(new Item.Properties().fireResistant())}，本方法照抄同一写法。
     * 这个标记在 1.20.1 里只影响掉落物实体 {@code ItemEntity} 的两处判断：
     * <ol>
     *   <li>{@code ItemEntity#fireImmune()} → {@code getItem().isFireResistant() || super.fireImmune()}：
     *       掉落物<b>根本不会着火</b>，所以踩进岩浆也不会被点燃；</li>
     *   <li>{@code Item#canBeHurtBy(DamageSource)} → {@code !isFireResistant || !source.is(IS_FIRE)}：
     *       即使被火源伤害也<b>免伤</b>。这一条同样重要——掉落物血量只有 5，
     *       没有它就是"点着了但烧不死"的半吊状态。</li>
     * </ol>
     * 注意它<b>不</b>保护其他死法：仙人掌、虚空、掉出世界、{@code /kill} 照样毁掉它。
     * 原版下界合金物品也是这样，所以这里不会出现"比原版更强"的意外行为。
     */
    public static final RegistryObject<Item> UPGRADE_CORE = ITEMS.register("upgrade_core",
            () -> new UpgradeCoreItem(new Item.Properties()
                    .stacksTo(1)
                    .rarity(Rarity.EPIC)
                    .fireResistant()));

    /**
     * 木制托盘：普通方块物品 + 一行风味文字（{@link QuoteTooltipBlockItem}），
     * 可放置、可堆叠（64）、可当合成材料。
     *
     * <p>它既是「分享装」用完后的残留物，也是合成分享装的原料之一
     * （4 个同级弹药盒 + 1 个托盘 → 1 个分享装），还能和木制步道 1:1 互换，
     * 所以必须能捡起来、能放进工作台。
     * 没有特殊的稀有度 / 抗火属性：它就是一块木板做的东西。
     */
    public static final RegistryObject<Item> WOODEN_TRAY = ITEMS.register("wooden_tray",
            () -> new QuoteTooltipBlockItem(ModBlocks.WOODEN_TRAY.get(), new Item.Properties(),
                    "tooltip.ammo_supply_box.wooden_tray_quote"));

    /**
     * 木制步道：同样是一个带风味文字的方块物品（{@link QuoteTooltipBlockItem}）。
     *
     * <p>与托盘 1:1 互换（两个方向的配方都在 {@code recipes/} 下），其余属性与托盘一致：
     * 可放置、可堆叠 64、COMMON、不抗火。
     */
    public static final RegistryObject<Item> WOODEN_WALKWAY = ITEMS.register("wooden_walkway",
            () -> new QuoteTooltipBlockItem(ModBlocks.WOODEN_WALKWAY.get(), new Item.Properties(),
                    "tooltip.ammo_supply_box.wooden_walkway_quote"));

    /**
     * 造出物品实例。
     *
     * <p>只有「自动补货」变体才会有冷却取值器（tooltip 因此多显示一行冷却时长），
     * 普通与创造模式变体传 {@code null}，tooltip 保持三行。
     *
     * <p>{@code rarity(...)} 只影响<b>物品名称那一行的颜色</b>（白/黄/青/淡紫），
     * 不影响任何玩法数值；具体配色见 {@code AmmoBoxTypes.ALL} 上方的说明。
     *
     * <p><b>不怕火/岩浆</b>只给「自动补货」变体，和 {@code upgrade_core} 一致——
     * 这一族是整条合成链上最贵的产物（要 8 个同级普通盒 + 1 个工厂核心，或者走升级链），
     * 掉进岩浆里烧掉太惩罚人。普通与创造模式弹药盒刻意<b>不给</b>：
     * 普通盒是消耗品、获取门槛低，保持"别把它乱丢进岩浆"的紧张感。
     *
     * <p>判定用的是 {@link AmmoBoxTypes.Type#variant()} 而不是
     * {@link AmmoBoxTypes.Type#hasCooldown()}：<b>变体身份</b>决定它是不是自动补货盒，
     * 与冷却配置值无关。用 {@code hasCooldown()} 的话，万一以后把某个自动补货盒的默认冷却
     * 改成 0（那样它会退化成普通 {@code AmmoBoxBlock}，见 {@code ModBlocks#createBlock}），
     * 抗火属性就会跟着莫名其妙消失。机制说明见 {@link #UPGRADE_CORE}。
     */
    private static AmmoBoxItem createItem(AmmoBoxTypes.Type type) {
        Item.Properties properties = new Item.Properties().rarity(type.rarity());
        if (type.variant() == AmmoBoxTypes.Variant.AUTO_RESTOCK
                || type.variant() == AmmoBoxTypes.Variant.SHARE_RESTOCK) {
            // 分享装自动补货归 _cd 那一档，同样抗火（它是共享用的贵重补给点，被岩浆烧掉太惩罚人）
            properties.fireResistant();
        }
        if (type.variant() == AmmoBoxTypes.Variant.SHARE) {
            // 分享装没有冷却（父类的冷却行永远不渲染）；「剩余次数」那一行由
            // ShareAmmoBoxItem 自己读物品 NBT 渲染，不走配置取值器
            return new ShareAmmoBoxItem(ModBlocks.ammoBox(type.id()).get(), properties,
                    AmmoSupplyBoxConfig.damageBudget(type.id(), type.defaultDamageBudget()));
        }
        // 分享装自动补货走这里：tooltip 显示「补给预算 + 冷却」，没有次数行
        // （4 个计时器活在方块实体里，物品上没有任何次数信息可显示）
        return new AmmoBoxItem(ModBlocks.ammoBox(type.id()).get(),
                properties,
                AmmoSupplyBoxConfig.damageBudget(type.id(), type.defaultDamageBudget()),
                type.hasCooldown()
                        ? AmmoSupplyBoxConfig.cooldownSeconds(type.cooldownConfigId(), type.cooldownSeconds())
                        : null);
    }

    /**
     * 按 id 取弹药盒对应的 BlockItem。
     *
     * @throws IllegalArgumentException 该 id 不在 {@link AmmoBoxTypes#ALL} 里
     */
    public static RegistryObject<Item> ammoBox(String id) {
        RegistryObject<Item> item = AMMO_BOX_ITEMS.get(id);
        if (item == null) {
            throw new IllegalArgumentException("未注册的弹药盒物品 id: \"" + id + "\"，请确认它已登记在 AmmoBoxTypes.ALL 里。");
        }
        return item;
    }

    /** 所有弹药盒物品，按清单顺序。 */
    public static Collection<RegistryObject<Item>> allAmmoBoxItems() {
        return Collections.unmodifiableCollection(AMMO_BOX_ITEMS.values());
    }

    /**
     * 按 id 取随机弹药盒物品。
     *
     * @throws IllegalArgumentException 该 id 不在 {@link RandomAmmoBoxTypes#ALL} 里
     */
    public static RegistryObject<Item> randomAmmoBox(String id) {
        RegistryObject<Item> item = RANDOM_AMMO_BOX_ITEMS.get(id);
        if (item == null) {
            throw new IllegalArgumentException(
                    "未注册的随机弹药盒物品 id: \"" + id + "\"，请确认它已登记在 RandomAmmoBoxTypes.ALL 里。");
        }
        return item;
    }

    /** 所有随机弹药盒物品，按清单顺序。 */
    public static Collection<RegistryObject<Item>> allRandomAmmoBoxItems() {
        return Collections.unmodifiableCollection(RANDOM_AMMO_BOX_ITEMS.values());
    }

    /**
     * 按档号取空投箱的方块物品。
     *
     * @throws IllegalArgumentException 档号超出 {@link AirdropBeaconTypes#ALL} 范围
     */
    public static RegistryObject<Item> airdropCrate(int tier) {
        RegistryObject<Item> item = AIRDROP_CRATES.get(tier);
        if (item == null) {
            throw new IllegalArgumentException("未注册的空投箱物品档号: " + tier + "。");
        }
        return item;
    }

    /**
     * 按档号取空投信标物品。
     *
     * @throws IllegalArgumentException 档号超出 {@link AirdropBeaconTypes#ALL} 范围
     */
    public static RegistryObject<Item> airdropBeacon(int tier) {
        RegistryObject<Item> item = AIRDROP_BEACONS.get(tier);
        if (item == null) {
            throw new IllegalArgumentException("未注册的空投信标物品档号: " + tier + "。");
        }
        return item;
    }

    private ModItems() {
    }
}
