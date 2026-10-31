package com.koei.ammo_supply_box.config;

import com.koei.ammo_supply_box.block.AmmoBoxTypes;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 模组配置。
 *
 * <p>注册为 {@code ModConfig.Type.SERVER}，文件位于
 * {@code saves/<世界名>/serverconfig/ammo_supply_box-server.toml}，<b>每个世界一份</b>。
 *
 * <p>之所以用 SERVER 而不是 COMMON：tooltip 需要显示总伤害预算，而那是<b>客户端</b>渲染的。
 * 我核对过 Forge 的 {@code ConfigSync.syncConfigs}，它只会同步
 * {@code ModConfig.Type.SERVER} 这一种（字节码里就是 {@code configSets().get(Type.SERVER)}），
 * COMMON 与 CLIENT 都不同步。用 COMMON 的话，多人服务器上客户端读到的是自己本地的值，
 * tooltip 会显示错误数字。
 *
 * <p>整包作者如果想让所有新世界都用同一份默认值，把配置文件放到游戏目录的
 * {@code defaultconfigs/ammo_supply_box-server.toml}，Forge 会在创建新世界时复制过去。
 *
 * <h2>配置结构</h2>
 * <p>根层有 1 个全局项（不属于任何节），下面 10 个节分两类：
 * <pre>
 * # 全局项：枪包自定义补给数量的乘数。只影响「枪包在 data 里显式写了数量」的枪。
 * gunPackAmmoMultiplier = 1.0
 *
 * [ammoBoxes]
 *
 *   # 一类：每个「尺寸」一节，只有 damageBudget。
 *   # 同一尺寸的普通 / 创造模式 / 自动补货三个变种共用这一个数字。
 *   [ammoBoxes.ammo_box_1]
 *     damageBudget = 300
 *   [ammoBoxes.ammo_box_2]
 *     damageBudget = 1000
 *   ...
 *
 *   # 二类：每个「自动补货」变种一节，只有 cooldown，互不影响。
 *   [ammoBoxes.ammo_box_1_cd]
 *     cooldown = 10
 *   [ammoBoxes.ammo_box_2_cd]
 *     cooldown = 10
 *   ...
 * </pre>
 *
 * <p>各节由 {@link AmmoBoxTypes#ALL} 自动生成，<b>不需要在这里逐一手写</b>。
 * 新增弹药盒请改 {@code AmmoBoxTypes.ALL}。生成规则见
 * {@link AmmoBoxTypes.Type#needsConfigSection()}：提供预算的盒子，以及带冷却的盒子，才各有一节；
 * 「创造模式」变体两者都不是，所以配置里完全不会出现，它们的预算由同尺寸的普通变体提供。
 *
 * <h2>翻译键</h2>
 * <p>{@code damageBudget} 与 {@code cooldown} 各自共用一个翻译键
 *（{@link #DAMAGE_BUDGET_TRANSLATION_KEY} / {@link #COOLDOWN_TRANSLATION_KEY}），
 * 而不是「每个盒子一个键」。数值确实每种盒子各有一份，但它们的<b>名字完全一样</b>，
 * 为每个盒子在语言文件里重复写一行既冗余，也容易让第三方开发者误以为这些是彼此不同的选项。
 *
 * <p>只有当某个盒子以后需要<b>语义不同</b>的独有配置项时，才应该为它取一个单独的键。
 */
public final class AmmoSupplyBoxConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    /**
     * 「总伤害预算」这一配置项共用的翻译键。
     *
     * <p>刻意不带盒子 id——所有盒子的这一项名字相同，语言文件里只写一行即可。
     */
    private static final String DAMAGE_BUDGET_TRANSLATION_KEY = "ammo_supply_box.config.damageBudget";

    /** 「补货冷却」这一配置项共用的翻译键。 */
    private static final String COOLDOWN_TRANSLATION_KEY = "ammo_supply_box.config.cooldown";

    /**
     * 补给预算允许的取值范围。
     *
     * <p>命令的校验和 {@code defineInRange} 共用这两个常量，避免两处数字漂移——
     * 我查过 {@code ForgeConfigSpec}，它没有方便的公开 API 能按路径反查某个配置项的
     * {@code ValueSpec} 范围（{@code getSpec()} 返回的是结构 config，不是
     * {@code Map<List<String>, ValueSpec>}），所以范围只能自己维护一份。
     */
    public static final int DAMAGE_BUDGET_MIN = 1;
    public static final int DAMAGE_BUDGET_MAX = 100000;

    /** 补货冷却允许的取值范围（秒），同样与 {@code defineInRange} 共用。 */
    public static final int COOLDOWN_MIN = 0;
    public static final int COOLDOWN_MAX = 86400;

    /**
     * 「枪包补给数量乘数」的配置项翻译键。
     *
     * <p>它是一项<b>全局</b>配置（不属于任何弹药盒），所以单独一个键，
     * 不走 {@link #DAMAGE_BUDGET_TRANSLATION_KEY} 那种「所有盒子共用一个键」的模式。
     */
    private static final String GUN_PACK_MULTIPLIER_TRANSLATION_KEY =
            "ammo_supply_box.config.gunPackAmmoMultiplier";

    /**
     * 枪包补给数量乘数的取值范围。
     *
     * <p>下界写 0 是刻意的：真正生效的条件是「&gt; 0」，那是一条开区间，
     * {@code defineInRange} 表达不了。所以范围放开到 0，由
     * {@link #gunPackAmmoMultiplier()} 在读取时把 0 一并回退成默认值 1。
     */
    public static final double GUN_PACK_MULTIPLIER_MIN = 0.0D;
    public static final double GUN_PACK_MULTIPLIER_MAX = 1000.0D;

    /** 乘数的默认值，同时也是<b>任何无效值的回落值</b>。 */
    public static final double GUN_PACK_MULTIPLIER_DEFAULT = 1.0D;

    /**
     * 弹药盒 id -> 它的总伤害预算配置项。
     *
     * <p>共用预算的变体这里存的是与普通变体<b>同一个</b> {@code IntValue} 对象，
     * 所以两边永远读到相同的值，不存在同步问题。
     */
    private static final Map<String, ForgeConfigSpec.IntValue> DAMAGE_BUDGETS = new LinkedHashMap<>();

    /** 弹药盒 id -> 它的补货冷却（秒）。只有自动补货变体才会有条目，且各自独立。 */
    private static final Map<String, ForgeConfigSpec.IntValue> COOLDOWNS = new LinkedHashMap<>();

    /**
     * 枪包补给数量乘数。全局唯一一项，定义在配置文件的<b>根层</b>而不是 {@code [ammoBoxes]} 下。
     *
     * <p>读取入口是 {@link #gunPackAmmoMultiplier()}——它负责把无效值回退成默认值。
     */
    private static final ForgeConfigSpec.DoubleValue GUN_PACK_MULTIPLIER;

    public static final ForgeConfigSpec SPEC;

    static {
        // 全局项必须写在 push("ammoBoxes") 之前，否则会落进 [ammoBoxes] 里面，
        // 变成"每种盒子各有一份"——而它本来就只有一份。
        GUN_PACK_MULTIPLIER = BUILDER
                .comment("第三方枪包自定义补给数量的乘数，默认 1.0。",
                        "只影响「枪包在自己的 data 文件里显式写了补给数量」的那些枪",
                        "（见枪械 data 里的 ammo_supply_box.ammo_box 字段）。",
                        "例如枪包给 1 级盒写了 100：乘数 1.0 时实得 100，乘数 0.2 时实得 20。",
                        "没写数量的枪走「总伤害预算」算法，不受本项影响。",
                        "给整合包作者/服主用：改过各盒子的补给预算后，用本乘数统一缩放枪包的绝对值。",
                        "必须大于 0；写成 0 或负数视为无效，按 1.0 处理。结果向下取整。",
                        "",
                        "Multiplier applied to ammo counts written by gun packs, default 1.0.",
                        "It only affects guns whose data file explicitly sets a supply amount",
                        "(the ammo_supply_box.ammo_box field in the gun's data JSON).",
                        "Example: a pack sets 100 for the tier-1 box; a multiplier of 1.0 yields 100,",
                        "while 0.2 yields 20.",
                        "Guns without an explicit amount use the damage-budget formula and ignore this.",
                        "Intended for modpack authors/server owners: after changing the per-box supply",
                        "budgets, use this to scale the gun pack's absolute numbers uniformly.",
                        "Must be greater than 0; 0 or negative values are invalid and fall back to 1.0.",
                        "The result is rounded down.")
                .translation(GUN_PACK_MULTIPLIER_TRANSLATION_KEY)
                .defineInRange("gunPackAmmoMultiplier", GUN_PACK_MULTIPLIER_DEFAULT,
                        GUN_PACK_MULTIPLIER_MIN, GUN_PACK_MULTIPLIER_MAX);

        BUILDER.comment("每种弹药盒各自的参数。",
                        "Per-ammo-box settings.")
                .push("ammoBoxes");

        // 只有「提供预算」或「自己提供冷却」的盒子才会生成一节
        for (AmmoBoxTypes.Type type : AmmoBoxTypes.ALL) {
            if (type.needsConfigSection()) {
                defineAmmoBox(type);
            }
        }

        BUILDER.pop();

        SPEC = BUILDER.build();

        // 为共用预算的变体登记别名（创造模式 + 自动补货 + 分享装自动补货）。
        // 必须放在 SPEC 构建之后：IntValue 是 defineAmmoBox 里创建出来的，
        // 而且 ForgeConfigSpec 的取值器只有 build 之后才可用。
        for (AmmoBoxTypes.Type type : AmmoBoxTypes.ALL) {
            if (!type.ownsBudgetConfig()) {
                ForgeConfigSpec.IntValue shared = DAMAGE_BUDGETS.get(type.budgetId());
                if (shared == null) {
                    throw new IllegalStateException("弹药盒 \"" + type.id() + "\" 的预算来源 \""
                            + type.budgetId() + "\" 不存在，请检查 AmmoBoxTypes.ALL 里的 budgetId。");
                }
                DAMAGE_BUDGETS.put(type.id(), shared);
            }
        }

        // 为"借用别人冷却配置"的变体登记别名（目前只有分享装自动补货：
        // 它强制复用同尺寸 _cd 那一节的 cooldown，见 AmmoBoxTypes.Type#cooldownConfigId）。
        // 做法与上面的预算别名完全一致，所以配置里不会为它多出一节。
        for (AmmoBoxTypes.Type type : AmmoBoxTypes.ALL) {
            if (type.hasCooldown() && !type.ownsCooldownConfig()) {
                ForgeConfigSpec.IntValue shared = COOLDOWNS.get(type.cooldownConfigId());
                if (shared == null) {
                    throw new IllegalStateException("弹药盒 \"" + type.id() + "\" 的冷却来源 \""
                            + type.cooldownConfigId() + "\" 不存在，请检查 AmmoBoxTypes.ALL 里的 budgetId。");
                }
                COOLDOWNS.put(type.id(), shared);
            }
        }
    }

    /**
     * 声明一种弹药盒的配置节。
     *
     * <p>配置项必须在 {@link #SPEC} 构建之前全部声明完，所以这个方法只应由上面的静态块调用。
     *
     * <p>同一个节里放哪些项由清单决定：
     * <ul>
     *   <li>{@link AmmoBoxTypes.Type#ownsBudgetConfig()}：写 {@code damageBudget}；</li>
     *   <li>{@link AmmoBoxTypes.Type#ownsCooldownConfig()}：写 {@code cooldown}。</li>
     * </ul>
     * 普通变体只有前者，自动补货变体只有后者；
     * 「分享装自动补货」两者都是借用别人的，所以本节根本不会为它创建
     * （它靠 {@code needsConfigSection()} 为 false 被跳过）。
     */
    private static void defineAmmoBox(AmmoBoxTypes.Type type) {
        String boxId = type.id();
        BUILDER.push(boxId);

        if (type.ownsBudgetConfig()) {
            DAMAGE_BUDGETS.put(boxId, BUILDER
                    .comment("单次交互提供的总伤害预算。",
                            "发放子弹数 = ceil(总伤害预算 / 枪械单发伤害)，所以数值越大玩家拿到的子弹越多。",
                            "例：预算 300 时，单发伤害 5 的 cz75 给 60 发，单发伤害 75 的 m95 给 4 发。",
                            "若手持枪械的单发伤害高于本值，交互会被拒绝，且不会消耗弹药盒。",
                            "上限设得很大时请留意：单发伤害极低的枪可能一次给出上百发弹药。",
                            "本值由同一尺寸的三个变种共用：",
                            "  ammo_box_N（普通）/ ammo_box_N_creative（创造模式）/ ammo_box_N_cd（自动补货）。",
                            "改动这里会同时影响这三个变种。",
                            "",
                            "Total damage budget granted per interaction.",
                            "Ammo count = ceil(damage budget / per-shot damage), so a higher value gives more ammo.",
                            "Example: with a budget of 300, a cz75 (5 damage per shot) yields 60 rounds,",
                            "while an m95 (75 damage per shot) yields only 4.",
                            "If the held gun's per-shot damage exceeds this value, the interaction is refused",
                            "and the box is not consumed.",
                            "Be careful with very large values: a low-damage gun may yield hundreds of rounds at once.",
                            "This value is shared by the three variants of the same size:",
                            "  ammo_box_N (normal) / ammo_box_N_creative (creative) / ammo_box_N_cd (auto-restock).",
                            "Changing it affects all three.")
                    .translation(DAMAGE_BUDGET_TRANSLATION_KEY)
                    .defineInRange("damageBudget", type.defaultDamageBudget(),
                            DAMAGE_BUDGET_MIN, DAMAGE_BUDGET_MAX));
        }

        if (type.ownsCooldownConfig()) {
            COOLDOWNS.put(boxId, BUILDER
                    .comment("领取一次后需要等待多久才能再次领取，单位秒。",
                            "改成 0 表示取消冷却，此时该盒子的行为等同于同尺寸的「创造模式」变体。",
                            "冷却按方块位置记录，所以同一个方块对所有玩家共享同一个冷却；",
                            "方块被挖掉再放回去会重置冷却。",
                            "本项只作用于这一档补给盒；同尺寸的「分享装自动补货」强制复用本项，",
                            "  也就是改这里会同时影响 ammo_box_N_cd 与 ammo_box_N_share_cd。",
                            "",
                            "Seconds to wait before the same box can be used again.",
                            "Set to 0 to disable the cooldown; the box then behaves like the creative variant of the same size.",
                            "The cooldown is tracked per block position, so all players share the cooldown of one block;",
                            "breaking and replacing the block resets it.",
                            "This setting applies to this tier; the team edition (ammo_box_N_share_cd) reuses it,",
                            "so changing it affects both ammo_box_N_cd and ammo_box_N_share_cd.")
                    .translation(COOLDOWN_TRANSLATION_KEY)
                    .defineInRange("cooldown", type.cooldownSeconds(), COOLDOWN_MIN, COOLDOWN_MAX));
        }

        // 这个弹药盒以后若需要独有配置项，继续写在 push/pop 之间

        BUILDER.pop();
    }

    /**
     * 取某个弹药盒总伤害预算的取值器，供方块和物品实时读取。
     *
     * <p>对「创造模式」与「自动补货」变体的 id 同样有效——它们和同尺寸的普通变体返回同一份配置值。
     *
     * @param fallback 配置尚未加载时的兜底值，通常传 {@link AmmoBoxTypes.Type#defaultDamageBudget()}
     * @throws IllegalArgumentException 该 id 不在 {@link AmmoBoxTypes#ALL} 里——通常是拼写错误
     */
    public static Supplier<Integer> damageBudget(String boxId, int fallback) {
        ForgeConfigSpec.IntValue value = DAMAGE_BUDGETS.get(boxId);
        if (value == null) {
            throw new IllegalArgumentException("弹药盒 \"" + boxId
                    + "\" 没有预算配置项，请确认它已登记在 AmmoBoxTypes.ALL 里。");
        }
        // 为什么要兜一层：ConfigValue.get() 在开发环境下若配置尚未加载会直接抛异常
        // （生产环境只是静默回落默认值）。而 tooltip 由客户端渲染，
        // 客户端在还没进入世界、SERVER 配置尚未同步/加载时也可能取到这个值，所以统一在这里保护。
        return () -> SPEC.isLoaded() ? value.get() : fallback;
    }

    /**
     * 取某个自动补货弹药盒补货冷却（秒）的取值器。
     *
     * @param fallback 配置尚未加载时的兜底值，通常传 {@link AmmoBoxTypes.Type#cooldownSeconds()}
     * @throws IllegalArgumentException 该 id 没有冷却配置项——只有 {@code cooldownSeconds > 0}
     *                                  的盒子才会有
     */
    public static Supplier<Integer> cooldownSeconds(String boxId, int fallback) {
        ForgeConfigSpec.IntValue value = COOLDOWNS.get(boxId);
        if (value == null) {
            throw new IllegalArgumentException("弹药盒 \"" + boxId
                    + "\" 没有冷却配置项，请确认它在 AmmoBoxTypes.ALL 里的 cooldownSeconds 大于 0。");
        }
        return () -> SPEC.isLoaded() ? value.get() : fallback;
    }

    /**
     * 第三方枪包自定义补给数量的乘数。
     *
     * <p><b>只作用于「枪包在 data 文件里显式写了数量」这条路径</b>
     * （见 {@code GunSupplyOverride}）。没写数量的枪走总伤害预算算法，不受本乘数影响
     * ——那套算法本身已经由管理员通过 {@code damageBudget} 调节了。
     *
     * <p><b>它存在的理由</b>：枪包作者写的数量是<b>绝对值</b>（例如"1 级盒给 100 个弹药物品"），
     * 而整合包作者/服主可能改过每个盒子的补给预算。绝对值不会跟着预算一起变，
     * 本乘数就是给管理员统一缩放它的旋钮：
     * <pre>
     * 枪包写 100，乘数 1.0 → 实得 100
     * 枪包写 100，乘数 0.2 → 实得  20
     * </pre>
     *
     * <p><b>无效值一律回退为默认值 1</b>：{@link #GUN_PACK_MULTIPLIER_MIN} 之所以是 0 而不是
     * 一个正数，是因为 {@code defineInRange} 只能表达闭区间，没法要求「&gt; 0」。
     * 所以真正下界由这里兜：读到的值不大于 0（含 0 与负数）就按 1.0 处理。
     * 配置尚未加载时同样返回 1.0——SERVER 配置在客户端进世界之前读不到，
     * 和 {@link #damageBudget} 的处理保持一致。
     */
    public static double gunPackAmmoMultiplier() {
        if (!SPEC.isLoaded()) {
            return GUN_PACK_MULTIPLIER_DEFAULT;
        }
        double value = GUN_PACK_MULTIPLIER.get();
        return value > 0.0D ? value : GUN_PACK_MULTIPLIER_DEFAULT;
    }

    // ============================ 运行时修改（供命令使用） ============================

    /**
     * 某个 id 是否解析得到一个补给预算配置项。
     *
     * <p>因为共用预算的变体都登记了别名，所以同尺寸的三个变体 id 都会返回 {@code true}，
     * 指向的其实是同一个值。
     */
    public static boolean hasDamageBudget(String boxId) {
        return DAMAGE_BUDGETS.containsKey(boxId);
    }

    /** 某个 id 是否解析得到一个补货冷却配置项。只有自动补货变体为 {@code true}。 */
    public static boolean hasCooldown(String boxId) {
        return COOLDOWNS.containsKey(boxId);
    }

    /**
     * 修改某个弹药盒的补给预算，并<b>立刻写回配置文件</b>。
     *
     * <p><b>为什么不需要额外的「重载」命令</b>：{@code ForgeConfigSpec.ConfigValue#set} 会
     * 同时更新内部的 Config 对象与缓存（字节码里是 {@code childConfig.set(path, value)} 紧接着
     * {@code cachedValue = value}），而本模组所有读取方（方块交互、物品 tooltip）都是
     * <b>每次使用时实时调 {@code get()}</b>，所以命令执行完下一条交互就会用新值。
     * 末尾的 {@link ForgeConfigSpec#save()} 再把改动落盘，重启世界后依然有效。
     *
     * <p>调用方应先用 {@link #hasDamageBudget} 与 {@link #DAMAGE_BUDGET_MIN}/{@link #DAMAGE_BUDGET_MAX}
     * 校验，这样可以给出本地化的错误提示；本方法只做最后一道防线。
     *
     * @throws IllegalArgumentException 该 id 没有预算配置项，或数值超出范围
     * @throws IllegalStateException    配置尚未加载（正常情况下命令只会在世界内执行）
     */
    public static void setDamageBudget(String boxId, int value) {
        checkRange(value, DAMAGE_BUDGET_MIN, DAMAGE_BUDGET_MAX, "补给预算");
        set(DAMAGE_BUDGETS, boxId, value);
    }

    /**
     * 修改某个自动补货弹药盒的冷却秒数，并立刻写回配置文件。
     *
     * @see #setDamageBudget(String, int) 关于「为什么不需要重载」的说明
     * @throws IllegalArgumentException 该 id 没有冷却配置项，或数值超出范围
     * @throws IllegalStateException    配置尚未加载
     */
    public static void setCooldownSeconds(String boxId, int value) {
        checkRange(value, COOLDOWN_MIN, COOLDOWN_MAX, "补货冷却");
        set(COOLDOWNS, boxId, value);
    }

    /**
     * 修改枪包补给数量乘数，并立刻写回配置文件。
     *
     * <p>和 {@link #gunPackAmmoMultiplier()} 的<b>回落</b>语义刻意不同：
     * 那个方法负责把配置文件里的无效值"纠正"成默认值，而本方法是被命令直接调用的，
     * 收到无效值应当<b>报错</b>而不是偷偷改成 1.0——否则管理员敲了
     * {@code multiplier 0} 会得到一个"成功"的回执，实际值却是 1.0。
     *
     * @throws IllegalArgumentException 值不大于 0、超过上限，或是 {@code NaN}
     * @throws IllegalStateException    配置尚未加载（正常情况下命令只会在世界内执行）
     * @see #setDamageBudget(String, int) 关于「为什么不需要重载」的说明
     */
    public static void setGunPackAmmoMultiplier(double value) {
        // 写成 !(value > 0) 而不是 value <= 0，是为了把 NaN 也挡在外面
        if (!(value > 0.0D) || value > GUN_PACK_MULTIPLIER_MAX) {
            throw new IllegalArgumentException("枪包补给数量乘数必须大于 0 且不超过 "
                    + GUN_PACK_MULTIPLIER_MAX + "，收到 " + value);
        }
        if (!SPEC.isLoaded()) {
            throw new IllegalStateException("配置尚未加载，无法修改");
        }
        GUN_PACK_MULTIPLIER.set(value);
        SPEC.save();
    }

    /**
     * 把本模组的<b>全部</b>配置项恢复成默认值，并立刻写回配置文件。
     *
     * <p>"全部"指三类共 11 项：
     * <ul>
     *   <li>5 个补给预算（按尺寸，由普通变体那一节提供）；</li>
     *   <li>5 个补货冷却（按自动补货变体，各自独立）；</li>
     *   <li>1 个枪包补给数量乘数（根层全局项）。</li>
     * </ul>
     *
     * <p>共用预算的变体在 {@link #DAMAGE_BUDGETS} 里存的是<b>同一个</b> {@code IntValue} 对象，
     * 所以这里先丢进 {@link Set} 去重——否则同一个值会被重复设 3 次（结果一样，
     * 但统计出来的项数会虚高）。{@code ConfigValue} 没有覆写 {@code equals}，
     * 天然按对象身份比较，正好是这里想要的行为。
     *
     * @return 实际被重置的配置项个数
     * @throws IllegalStateException 配置尚未加载（正常情况下命令只会在世界内执行）
     * @see #setDamageBudget(String, int) 关于「为什么不需要重载」的说明
     */
    public static int resetAllToDefaults() {
        if (!SPEC.isLoaded()) {
            throw new IllegalStateException("配置尚未加载，无法重置");
        }

        Set<ForgeConfigSpec.IntValue> budgets = new LinkedHashSet<>(DAMAGE_BUDGETS.values());
        Set<ForgeConfigSpec.IntValue> cooldowns = new LinkedHashSet<>(COOLDOWNS.values());

        for (ForgeConfigSpec.IntValue value : budgets) {
            value.set(value.getDefault());
        }
        for (ForgeConfigSpec.IntValue value : cooldowns) {
            value.set(value.getDefault());
        }
        GUN_PACK_MULTIPLIER.set(GUN_PACK_MULTIPLIER.getDefault());

        // 只落盘一次：上面每次 set() 只更新内存，save() 才写文件
        SPEC.save();

        return budgets.size() + cooldowns.size() + 1;
    }

    private static void checkRange(int value, int min, int max, String optionName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    optionName + " 必须在 " + min + " ~ " + max + " 之间，收到 " + value);
        }
    }

    private static void set(Map<String, ForgeConfigSpec.IntValue> values, String boxId, int value) {
        ForgeConfigSpec.IntValue configValue = values.get(boxId);
        if (configValue == null) {
            throw new IllegalArgumentException("弹药盒 \"" + boxId + "\" 没有这个配置项");
        }
        // set() 内部有 Preconditions.checkNotNull(childConfig)，配置未加载时会抛异常，先兜住
        if (!SPEC.isLoaded()) {
            throw new IllegalStateException("配置尚未加载，无法修改");
        }
        configValue.set(value);
        SPEC.save();
    }

    private AmmoSupplyBoxConfig() {
    }
}
