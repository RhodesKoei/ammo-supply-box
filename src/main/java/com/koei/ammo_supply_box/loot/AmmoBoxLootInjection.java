package com.koei.ammo_supply_box.loot;

import com.koei.ammo_supply_box.registry.ModItems;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraftforge.event.LootTableLoadEvent;

/**
 * 把普通弹药盒投放到原版箱子战利品表里。
 *
 * <p>只处理 <b>普通</b>弹药盒（{@code ammo_box_1..5}）：「创造模式」变体永远不出现在任何
 * 玩家可获得的来源里；「自动补货」变体只能通过合成获得。
 *
 * <p>投放规则见 {@link #createRules()}——那是<b>唯一生效的来源</b>，
 * 工作区里 {@code 战利品表/投放.md} 只是同一份内容的人读版本。
 *
 * <h2>投放模型：本档必放，低档越级递减</h2>
 * <p>每张表除了投放<b>本档</b>盒子，还会投放所有<b>更低档</b>的盒子。原因有两个：
 * <ul>
 *   <li>升级链需要大量低级盒（合成 1 个 5 级盒要 4⁴ = 256 个 1 级盒），如果 1 级盒只出现在
 *       早期结构里，后期玩家会被卡死；</li>
 *   <li>后期结构里只出一种尺寸的盒子，观感也很单调。</li>
 * </ul>
 *
 * <p>概率这样算：
 * <ul>
 *   <li><b>本档</b>：用该表原本设定的概率（见 {@link #createRules()}），完全不变；</li>
 *   <li><b>低 k 档</b>：{@code 该档基础概率 × 衰减^k}，衰减系数见 {@link #CROSS_TIER_DECAY}。</li>
 * </ul>
 * 以 5 级表（堡垒宝箱室 / 远古城市 / 末地城）为例，衰减 0.5 时各档出现概率约为
 * 1 级 4.4% / 2 级 8.1% / 3 级 15% / 4 级 27.5% / 5 级 50%
 * ——本档仍是主角，低档只是"偶尔翻到"。
 *
 * <h2>为什么不是"每档都保持原概率"</h2>
 * <p>那也是一个可行方案，但代价很大：各档独立 100% 保留原概率（1 级 70%、2 级 65%……）时，
 * 一张 5 级表的期望产出是 3 个盒子、99% 至少出 1 个，全表总产量是逐级递减方案的约 2.6 倍。
 * 用"越级衰减"可以在保住<b>本档</b>概率的前提下压住总产量。
 *
 * <h2>实现要点</h2>
 * <ul>
 *   <li>用 {@link LootTableLoadEvent} 而不是覆写原版 json：前者是<b>追加</b>，不会和
 *       其他模组或数据包冲突；后者会整个替换原版表。</li>
 *   <li>每个池 {@code setRolls(ConstantValue.exactly(1))} 且只有一个条目，所以<b>每个池最多产出 1 个</b>。
 *       但一张表会挂多个池（本档 + 各低档 + 工厂核心），因此<b>一个箱子里可能同时出现几个弹药盒</b>。</li>
 *   <li>概率判断挂在<b>池</b>上而不是条目上：不通过时整个池不参与生成，
 *       语义更直观，也不会出现"roll 到了但权重被抽空"的情况。</li>
 *   <li>池必须用 {@code name(...)} 起名：Forge 用池名避免与原版已有池冲突，
 *       同名会直接报错。一张表里有多个池，所以池名必须带上档次，见 {@link #poolName(int)}。</li>
 * </ul>
 */
public final class AmmoBoxLootInjection {

    /** 原版箱子表的路径前缀。 */
    private static final String CHEST_PREFIX = "chests/";

    /**
     * 弹药盒注入池的名字前缀。
     *
     * <p>实际池名是 {@code 前缀_t档次}，例如 {@code ammo_supply_box_inject_t3}。
     * 必须带档次：一张表里会为每个档位各挂一个池，池名重复会被 Forge 直接拒绝，
     * 而且下面「已有同名池就不再注入」的守卫也会把后来的挡掉（那会让低档盒静默消失）。
     */
    private static final String POOL_NAME_PREFIX = "ammo_supply_box_inject";

    /** 工厂核心的注入池名字，和上面那些必须不同。 */
    private static final String POOL_NAME_UPGRADE_CORE = "ammo_supply_box_inject_core";

    /** 某个档位的弹药盒池名。 */
    private static String poolName(int tier) {
        return POOL_NAME_PREFIX + "_t" + tier;
    }

    /**
     * 各档位盒子的<b>基础概率</b>，也就是它在自己那一档表里的概率。下标即档次，0 位空着。
     *
     * <p>这是「越级投放」的唯一数据来源：低档盒子进入高档表时，用它乘以衰减系数即可，
     * 不需要在 {@link #createRules()} 里逐表再写一遍。{@link #createRules()} 反过来也引用
     * 这个数组，所以调档位概率只需要改这一处。
     *
     * <p>唯一的例外是出生点奖励箱（1 级、100% 必出），它在 {@code createRules()} 里显式写死。
     */
    private static final float[] TIER_CHANCE = {0.0F, 0.70F, 0.65F, 0.60F, 0.55F, 0.50F};

    /**
     * 越级衰减系数：低 k 档的盒子出现在本档表里的概率 = 该档基础概率 × 本系数^k。
     *
     * <p>取 0.5 时，一张 5 级表里各档的出现概率约为
     * 1 级 4.4% / 2 级 8.1% / 3 级 15% / 4 级 27.5% / 5 级 50%，
     * 全 21 张表横扫的期望产出是原来的约 1.5 倍（5 级盒数量完全不变）。
     *
     * <p>这是唯一需要调的旋钮：调大 → 低档盒更常见、总产量上升；调小 → 低档盒更罕见。
     * 取 1.0 等于"每档都保持原概率"，总产量会变成约 2.6 倍。
     */
    private static final float CROSS_TIER_DECAY = 0.5F;

    /**
     * 一条投放规则：这张表属于哪个盒子档次、本档盒子以什么概率出现。
     *
     * <p>用它同时表达「这张表是几级表」和「本档概率」。低档盒子要投放到哪些表、以什么概率
     * 投放，全部由 {@link #onLootTableLoad} 从这两个值派生，不需要再写第二份清单。
     *
     * @param tier   本档盒子的档次（1~5）
     * @param chance 本档盒子出现的概率（0~1）
     */
    private record Rule(int tier, float chance) {

        /** 本档盒子的注册 id，例如 {@code tier = 3} → {@code ammo_box_3}。 */
        String boxId() {
            return "ammo_box_" + this.tier;
        }
    }

    /**
     * 箱子表路径（去掉 {@code chests/} 前缀）-> 投放规则。
     *
     * <p>注意村庄那几张表的真实路径带 {@code village/} 前缀，
     * 例如 {@code minecraft:chests/village/village_armorer}。
     */
    private static final Map<String, Rule> RULES = createRules();

    /**
     * 哪些档次的箱子里<b>额外</b>投放工厂核心。
     *
     * <p>只挂在 4 级（保险库）和 5 级（补给库）上：工厂核心是合成「自动补货」变体的钥匙，
     * 而自动补货盒的价值就在于高阶尺寸，所以核心只在玩家已经开始摸高阶盒的箱子附近出现。
     */
    private static final Set<Integer> UPGRADE_CORE_SOURCE_TIERS = Set.of(4, 5);

    /**
     * 工厂核心的出现概率：{@code 0.02F} = <b>2%</b>。
     *
     * <p>开发期先设成 50% 验证过两件事——战利品表确实被注入、并且只出现在 4/5 级盒的
     * 6 张表里（3 级及以下的表开不出核心）。确认无误后定为 2%。
     *
     * <p><b>为什么 2% 是合理的稀有度</b>：工厂核心的<b>主要</b>来源是合成
     * （{@code upgrade_core.json}，需要下界合金锭等），战利品只是「省下一块下界合金锭」的
     * 幸运补给，不是必经之路。所以它应当稀有到"偶尔撞见"，而不是"逛几圈就能刷"。
     * 按 2% 算，平均要开约 50 个这类箱子才出一个核心。
     */
    private static final float UPGRADE_CORE_CHANCE = 0.02F;

    /**
     * 实际会投放工厂核心的箱子表集合。
     *
     * <p>从 {@link #RULES} <b>派生</b>而不是再手写一份表名列表：这样
     * 「工厂核心跟着 4/5 级盒走」这条规则是自动成立的——以后给某个新箱子加 4 级或 5 级盒，
     * 工厂核心会自动跟过去，不会出现两份列表打架、漏改一处的情况。
     *
     * <p>声明顺序有讲究：静态字段按文本顺序初始化，所以它必须排在
     * {@link #RULES} 和 {@link #UPGRADE_CORE_SOURCE_TIERS} 之后。
     */
    private static final Set<String> UPGRADE_CORE_TABLES = findUpgradeCoreTables();

    private static Set<String> findUpgradeCoreTables() {
        Set<String> tables = new HashSet<>();
        for (Map.Entry<String, Rule> entry : RULES.entrySet()) {
            if (UPGRADE_CORE_SOURCE_TIERS.contains(entry.getValue().tier())) {
                tables.add(entry.getKey());
            }
        }
        return Set.copyOf(tables);
    }

    private static Map<String, Rule> createRules() {
        Map<String, Rule> rules = new HashMap<>();

        // ---- 小型弹药盒（补给预算 300）----
        // 出生点奖励箱是必出的（100%）：它是开局一次性容器，
        // 让玩家在最初几分钟就能见到这个模组，而不是全靠探索运气。
        // 显式写 1.00F 而不是 TIER_CHANCE[1]——这是整份清单里唯一的「必出」例外。
        addAll(rules, 1.00F, 1,
                "spawn_bonus_chest");
        // 其余 1 级表用本档基础概率
        addAll(rules, TIER_CHANCE[1], 1,
                "shipwreck_supply",
                "village/village_armorer",
                "village/village_toolsmith",
                "underwater_ruin_small",
                "underwater_ruin_big",
                "stronghold_crossing");

        // ---- 中型弹药盒（补给预算 1000）----
        addAll(rules, TIER_CHANCE[2], 2,
                "ruined_portal",
                "village/village_weaponsmith",
                "pillager_outpost",
                "igloo_chest");

        // ---- 大型弹药盒（补给预算 2000）----
        addAll(rules, TIER_CHANCE[3], 3,
                "abandoned_mineshaft",
                "desert_pyramid",
                "jungle_temple",
                "simple_dungeon");

        // ---- 弹药保险库（补给预算 4000）----
        addAll(rules, TIER_CHANCE[4], 4,
                "nether_bridge",
                "woodland_mansion",
                "bastion_bridge");

        // ---- 弹药补给库（补给预算 6000）----
        addAll(rules, TIER_CHANCE[5], 5,
                "bastion_treasure",
                "ancient_city",
                "end_city_treasure");

        return Map.copyOf(rules);
    }

    /** 把一组表登记到同一个档次下。同一张表被登记两次会直接报错，避免概率被悄悄叠加。 */
    private static void addAll(Map<String, Rule> rules, float chance, int tier, String... tables) {
        Rule rule = new Rule(tier, chance);
        for (String table : tables) {
            Rule previous = rules.put(table, rule);
            if (previous != null) {
                throw new IllegalStateException("战利品表 \"" + table + "\" 被重复投放："
                        + previous.boxId() + " 与 " + rule.boxId());
            }
        }
    }

    /**
     * 战利品表加载时按 {@link #RULES} 注入。
     *
     * <p>只在服务端生效（事件本身只在服务端数据包加载时触发）。{@code /reload} 会重新触发，
     * 每次拿到的是全新的 {@code LootTable} 对象，所以不会累积。
     */
    public static void onLootTableLoad(LootTableLoadEvent event) {
        ResourceLocation name = event.getName();
        if (!"minecraft".equals(name.getNamespace())) {
            return;
        }
        String path = name.getPath();
        if (!path.startsWith(CHEST_PREFIX)) {
            return;
        }
        String tableKey = path.substring(CHEST_PREFIX.length());

        Rule rule = RULES.get(tableKey);
        if (rule != null) {
            injectAmmoBoxPools(event.getTable(), rule);
        }

        // 工厂核心是另一个独立的池，有自己的一次独立判定。
        // 所以「这张箱子出了弹药盒」和「这张箱子出了工厂核心」是互不影响的两件事。
        if (UPGRADE_CORE_TABLES.contains(tableKey)) {
            injectPool(event.getTable(), POOL_NAME_UPGRADE_CORE,
                    ModItems.UPGRADE_CORE.get(), UPGRADE_CORE_CHANCE);
        }
    }

    /**
     * 按规则往表里挂弹药盒的池：本档一个，再加上所有更低档各一个。
     *
     * <p>见类注释里的「投放模型」——本档用原概率，低档按越级衰减。
     */
    private static void injectAmmoBoxPools(LootTable table, Rule rule) {
        // 本档：这张表原本设定的概率，完全不变
        injectPool(table, poolName(rule.tier()),
                ModItems.ammoBox(rule.boxId()).get(), rule.chance());

        // 低档：越了 k 级就乘 k 次衰减系数
        for (int tier = 1; tier < rule.tier(); tier++) {
            float chance = TIER_CHANCE[tier]
                    * (float) Math.pow(CROSS_TIER_DECAY, rule.tier() - tier);
            injectPool(table, poolName(tier), ModItems.ammoBox("ammo_box_" + tier).get(), chance);
        }
    }

    /**
     * 往表里加一个「只 roll 一次、只有一个条目」的池。
     *
     * <p>同名池已经存在就直接跳过——{@code /reload} 会重新触发本事件，
     * 万一别的模组或数据包也往同名池里加过东西，这层守卫能防止概率被悄悄叠加。
     */
    private static void injectPool(LootTable table, String poolName, Item item, float chance) {
        if (table.getPool(poolName) != null) {
            return;
        }

        LootPool.Builder pool = LootPool.lootPool()
                // 只 roll 一次，且下面只有一个条目 → 这个池最多产出 1 个
                .setRolls(ConstantValue.exactly(1.0F))
                .add(LootItem.lootTableItem(item))
                .name(poolName);

        // 概率挂在池上：不通过时整个池不参与生成。
        // 概率为 100% 时干脆不加条件，让「必出」在池定义里就是显式的。
        if (chance < 1.0F) {
            pool.when(LootItemRandomChanceCondition.randomChance(chance));
        }

        table.addPool(pool.build());
    }

    private AmmoBoxLootInjection() {
    }
}
