package com.koei.ammo_supply_box.block;

import java.util.List;
import net.minecraft.world.item.Rarity;

/**
 * 弹药盒清单：本模组所有弹药盒的<b>唯一定义来源</b>。
 *
 * <p>这一份清单同时驱动四处登记，所以新增弹药盒时 Java 侧只需要在 {@link #ALL} 里加一行，
 * 下面这些都会自动完成，不存在漏改的可能：
 * <ul>
 *   <li>{@code AmmoSupplyBoxConfig}——生成配置节并建立预算共用映射；</li>
 *   <li>{@code ModBlocks}——注册方块（带冷却的走 {@link AutoRestockAmmoBoxBlock}），
 *       并按变体决定交互粒子的颜色；</li>
 *   <li>{@code ModBlockEntities}——给带冷却的方块挂方块实体；</li>
 *   <li>{@code ModItems} / {@code ModCreativeTabs}——注册物品（含稀有度）并填进创造模式标签页。</li>
 * </ul>
 *
 * <p>仍然需要手动补的只有资源文件：blockstate、模型、贴图、战利品表、语言文件。
 *
 * <h2>三种变体的关系</h2>
 * <p>同一个尺寸有最多三种变体，它们<b>共用同一个补给预算</b>（改一个数字三个一起变）：
 * <pre>
 *   ammo_box_1           普通（领一次就消失）
 *   ammo_box_1_creative  创造模式（不消失，无冷却）
 *   ammo_box_1_cd        自动补货（不消失，有冷却，冷却时长独立可配）
 * </pre>
 * 也就是说预算算在「尺寸」上，而不是算在「方块」上；只有冷却时长是自动补货变体独有的。
 */
public final class AmmoBoxTypes {

    /**
     * 弹药盒变体。决定「交互后是否摧毁方块」与「交互粒子的颜色」。
     *
     * <p>把变体做成显式枚举而不是散落的布尔字段，是为了让
     * {@code SupplyEffects.interactionColor} 能用 {@code switch} 穷举——
     * 以后新增变体时编译期就会报错提醒补齐配色，不会静默退回某个默认色。
     */
    public enum Variant {
        /** 普通弹药盒：领一次就消失。 */
        NORMAL,
        /** 创造模式变体：不消失、无冷却，可无限次领取。 */
        CREATIVE,
        /** 自动补货变体：不消失，但要等冷却结束才能再领。 */
        AUTO_RESTOCK,
        /** 分享装变体：补给量同普通盒，能用 4 次才变成托盘。 */
        SHARE,
        /**
         * 分享装自动补货变体：<b>一个方块里并联 4 个独立计时器</b>，
         * 相当于 4 个自动补货盒叠在一起——短时间内最多能连续领 4 次，
         * 领完不消失、只是等各自的冷却转好。
         *
         * <p>补给量是<b>完整一份</b>（与 {@link #SHARE} 和普通盒都相同）；
         * 冷却时长强制复用同尺寸 {@code _cd} 那一节配置。
         * 外观与选框复用分享装的 4 个模型/形状（按"剩余可用格数"切换）。
         */
        SHARE_RESTOCK
    }

    /**
     * 一种弹药盒的定义。
     *
     * @param id                  注册 id，同时用作方块名、物品名和资源文件名
     * @param budgetId            补给预算取自<b>哪个配置节</b>的 id。
     *                            同尺寸的「普通」变体填自己（于是由它提供该尺寸的预算配置）；
     *                            「创造模式」与「自动补货」变体填对应普通变体的 id，
     *                            从而与普通变体<b>共用</b>同一个配置项。
     * @param defaultDamageBudget 配置文件中该项的默认值。
     *                            仅当 {@code budgetId} 等于 {@code id} 时才会真的写进配置；
     *                            共用预算的变体只把它当作配置尚未加载时的兜底值。
     * @param shape               选框尺寸（模型空间 0-16，可直接照抄模型元素的 from/to）
     * @param variant             变体种类，见 {@link Variant}。
     *                            它同时决定了交互后是否摧毁方块（只有普通变体会），
     *                            以及交互粒子用什么颜色。
     * @param cooldownSeconds     领取后的冷却秒数，{@code 0} 表示没有冷却。
     *                            大于 {@code 0} 时这个盒子会注册成 {@link AutoRestockAmmoBoxBlock}
     *                            （带方块实体与 {@code ready} 方块属性），否则是普通方块。
     *                            冷却时长是<b>每个自动补货盒独有</b>的配置项，
     *                            会写在以该盒子 id 命名的配置节里。
     * @param rarity              物品稀有度，<b>只影响物品名称那一行的颜色</b>，不影响任何玩法数值。
     *                            给的是<b>最终值</b>而不是「基准值 + 推导」——这样
     *                            {@link #ALL} 这张表本身就是最终结果，改动时不用在脑子里再过一层换算。
     *                            四档颜色见下：
     *                            {@code COMMON} 白 / {@code UNCOMMON} 黄 /
     *                            {@code RARE} 青 / {@code EPIC} 淡紫。
     */
    public record Type(String id, String budgetId, int defaultDamageBudget, AmmoBoxShape shape,
                       Variant variant, int cooldownSeconds, Rarity rarity) {

        /**
         * 本盒子是否<b>提供</b>该尺寸的补给预算配置节。
         *
         * <p>为 {@code false} 时预算取自 {@link #budgetId()} 那一节（由普通变体提供）。
         */
        public boolean ownsBudgetConfig() {
            return this.id.equals(this.budgetId);
        }

        /**
         * 枪包 data 文件里用来指定本盒子补给数量的键。
         *
         * <p>取的是<b>本档的基础 id</b>（也就是 {@link #budgetId()}），而<b>不是</b> {@link #id()}。
         * 同尺寸的普通 / 创造模式 / 自动补货三个变体共用同一个键——这和它们共用补给预算是同一个道理：
         * 枪包作者写的是"这一档给多少"，不该因为玩家手上是哪种变体就失效。
         *
         * <p>所以枪包里的写法永远是 {@code "ammo_box_1": 100}，
         * 不会出现 {@code "ammo_box_1_creative"} 或 {@code "ammo_box_1_cd"}。
         */
        public String gunSupplyConfigId() {
            return this.budgetId;
        }

        /** 是否是「领取后有冷却」的自动补货型。 */
        public boolean hasCooldown() {
            return this.cooldownSeconds > 0;
        }

        /**
         * 冷却时长取自<b>哪一节配置</b>。
         *
         * <p>默认就是自己（{@link #id()}）。唯一的例外是
         * {@link Variant#SHARE_RESTOCK}（一个方块里并联 4 个计时器的分享装版）：
         * 它<b>强制复用同尺寸自动补货盒</b>那一节，也就是 {@code ammo_box_N_cd} 的 {@code cooldown}，
         * 所以配置里不会为它多出一节，调一处两个变体一起变。
         *
         * <p>为什么需要一个"去别处读"的入口而不是不管：配置项是按节名索引的
         * （见 {@code AmmoSupplyBoxConfig} 的 {@code COOLDOWNS} 表），
         * 如果不告诉代码去读哪一节，它就只会为自己新建一节——正是我们不想要的。
         * 这里用<b>派生方法</b>而不是新增 record 字段：规则只有一条，靠约定算得出来。
         */
        public String cooldownConfigId() {
            return this.variant == Variant.SHARE_RESTOCK && this.hasCooldown()
                    ? this.budgetId + "_cd"
                    : this.id();
        }

        /** 本盒子是否<b>自己</b>提供冷却配置节（即 {@link #cooldownConfigId()} 指向自己）。 */
        public boolean ownsCooldownConfig() {
            return this.hasCooldown() && this.cooldownConfigId().equals(this.id);
        }

        /**
         * 冷却命令里用的参数名：该尺寸的<b>基础 id</b>（例如 {@code ammo_box_1}）。
         *
         * <p>一个尺寸只有一个冷却值——由 {@code _cd} 提供配置节，分享装自动补货复用同一份，
         * 所以让玩家输入基础 id，一次同时作用于两者，补全列表里也不会出现
         * {@code _cd} / {@code _share_cd} 这些只会制造误导的后缀。
         *
         * <p>仅对 {@link #ownsCooldownConfig()} 的类型有意义（它就是那个提供配置节的变体，
         * 其 {@link #budgetId()} 正是尺寸的基础 id）。
         */
        public String cooldownCommandId() {
            return this.budgetId;
        }

        /**
         * 是否需要一份以自己 id 命名的配置节。
         *
         * <p>两种情况下需要：提供预算（普通变体），或者自己提供冷却（自动补货变体）。
         * 「创造模式」变体两者都不是，「分享装自动补货」则两者都借用别人，所以配置里不会出现它们的名字。
         */
        public boolean needsConfigSection() {
            return this.ownsBudgetConfig() || this.ownsCooldownConfig();
        }

        /**
         * 交互成功后是否摧毁方块。
         *
         * <p>由变体派生而非单独存一个字段——避免出现「变体是创造模式但 consumable 写成 true」
         * 这种自相矛盾的数据。
         */
        public boolean consumable() {
            return this.variant == Variant.NORMAL;
        }
    }

    // ========================= 新增弹药盒：在下面加一行 =========================
    //
    // 稀有度配色方案（只影响物品名称颜色，不影响玩法）：
    //   尺寸         普通       自动补货（升一档）   创造模式
    //   小型 300     白 COMMON  黄 UNCOMMON          淡紫 EPIC
    //   中型 1000    白 COMMON  黄 UNCOMMON          淡紫 EPIC
    //   大型 2000    白 COMMON  黄 UNCOMMON          淡紫 EPIC
    //   保险库 4000  黄 UNCOMMON 青 RARE             淡紫 EPIC
    //   补给库 6000  青 RARE    淡紫 EPIC            淡紫 EPIC
    //
    public static final List<Type> ALL = List.of(
            // ==================== 普通弹药盒（领一次就消失，提供各尺寸的预算配置） ====================
            // 【小型弹药盒】选框取自 ammo_box_1 全部元素 from/to 的并集 x 4..12、y 0..14、z 2..14
            // （忽略元件自身旋转——箱盖倾斜会把 y 抬到 16.77、z 推到 14.38），再做两处收紧：
            //   X 4→5、12→11：贴住箱体（箱体元素是 x 5..11，x=4/12 是箱盖多出来的宽度）
            //   Y 14→12：与 1/2/7/8 号元素（都是 y 7..12）的顶面齐平，排除箱盖上方的把手/卡扣
            new Type("ammo_box_1", "ammo_box_1", 300,
                    new AmmoBoxShape(5.0D, 0.0D, 2.0D, 11.0D, 12.0D, 14.0D),
                    Variant.NORMAL, 0, Rarity.COMMON),

            // 【中型弹药盒】ammo_box_2 全部 11 个元素都没有旋转，并集是 x 2..14、y 0..11、z 2..14：
            //   两侧不收窄——盖板 el9 横跨整个 x/z（2..14），没有 type1 那种"盖子比箱体宽"的悬挑
            //   顶部由 11 降到 10——与上沿墙 el5/el8 和中央板 el10 的顶面齐平，
            //     只切掉内箱 el11 高出 1 单位的那一小截
            new Type("ammo_box_2", "ammo_box_2", 1000,
                    new AmmoBoxShape(2.0D, 0.0D, 2.0D, 14.0D, 10.0D, 14.0D),
                    Variant.NORMAL, 0, Rarity.COMMON),

            // 【大型弹药盒】ammo_box_3 的 14 个元素都没有旋转，并集正好是整格 0..16，
            // 所以选框直接用整格，不做任何收紧——描边就是标准方块轮廓。
            new Type("ammo_box_3", "ammo_box_3", 2000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D),
                    Variant.NORMAL, 0, Rarity.COMMON),

            // 【弹药保险库】ammo_box_4 的 6 个元素都没有旋转，并集正好是整格 0..16，
            // 同样直接用整格选框，不做收紧。
            new Type("ammo_box_4", "ammo_box_4", 4000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D),
                    Variant.NORMAL, 0, Rarity.UNCOMMON),

            // 【弹药补给库】ammo_box_5 的 12 个元素都没有旋转，并集正好是整格 0..16，
            // 同样直接用整格选框。
            new Type("ammo_box_5", "ammo_box_5", 6000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D),
                    Variant.NORMAL, 0, Rarity.RARE),

            // ==================== 创造模式变体（不摧毁方块，无冷却，共用本尺寸预算） ====================
            // 几何与对应普通变体完全一致（模型的 elements 逐字节相同），所以选框直接沿用。
            // budgetId 指向普通变体 → 配置里不会为它们单开一节。
            // 全部用 EPIC：这类物品在生存里拿不到，最显眼的颜色正好提示"这不是正常获得的物品"。
            new Type("ammo_box_1_creative", "ammo_box_1", 300,
                    new AmmoBoxShape(5.0D, 0.0D, 2.0D, 11.0D, 12.0D, 14.0D),
                    Variant.CREATIVE, 0, Rarity.EPIC),
            new Type("ammo_box_2_creative", "ammo_box_2", 1000,
                    new AmmoBoxShape(2.0D, 0.0D, 2.0D, 14.0D, 10.0D, 14.0D),
                    Variant.CREATIVE, 0, Rarity.EPIC),
            new Type("ammo_box_3_creative", "ammo_box_3", 2000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D),
                    Variant.CREATIVE, 0, Rarity.EPIC),
            new Type("ammo_box_4_creative", "ammo_box_4", 4000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D),
                    Variant.CREATIVE, 0, Rarity.EPIC),
            new Type("ammo_box_5_creative", "ammo_box_5", 6000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D),
                    Variant.CREATIVE, 0, Rarity.EPIC),

            // ============ 自动补货变体（不摧毁方块，有冷却，共用预算但冷却独立） ============
            // 与创造模式变体的区别：cooldownSeconds > 0
            //   → 注册成 AutoRestockAmmoBoxBlock，带方块实体（记时间戳）与 ready 方块属性（切外观）。
            //
            // 预算仍指向普通变体（budgetId = ammo_box_N），所以「改一个数字三个变种一起变」；
            // 而冷却时长写在以自己 id 命名的配置节里，五个补货盒各自独立可调。
            // 稀有度比同尺寸普通变体高一级（到 EPIC 封顶）。
            new Type("ammo_box_1_cd", "ammo_box_1", 300,
                    new AmmoBoxShape(5.0D, 0.0D, 2.0D, 11.0D, 12.0D, 14.0D),
                    Variant.AUTO_RESTOCK, 300, Rarity.UNCOMMON),
            new Type("ammo_box_2_cd", "ammo_box_2", 1000,
                    new AmmoBoxShape(2.0D, 0.0D, 2.0D, 14.0D, 10.0D, 14.0D),
                    Variant.AUTO_RESTOCK, 600, Rarity.UNCOMMON),
            new Type("ammo_box_3_cd", "ammo_box_3", 2000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D),
                    Variant.AUTO_RESTOCK, 900, Rarity.UNCOMMON),
            new Type("ammo_box_4_cd", "ammo_box_4", 4000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D),
                    Variant.AUTO_RESTOCK, 1200, Rarity.RARE),
            new Type("ammo_box_5_cd", "ammo_box_5", 6000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D),
                    Variant.AUTO_RESTOCK, 1500, Rarity.EPIC),

            // ==================== 分享装（4 次用完后才消失） ====================
            // 补给行为与同尺寸普通弹药盒完全一致，所以 budgetId 指向它：
            //   预算共用同一节配置；枪包作者的逐枪覆盖也共用同一个键（见 gunSupplyConfigId）。
            // cooldownSeconds 传 0：分享装没有冷却，剩余次数由 ShareAmmoBoxBlock 自己管，
            //   次数上限是 ShareBoxData.MAX_USES（锁死 4），不进配置、不需要新字段。
            // 五个尺寸的分享装到这里就齐了（ammo_box_1_share ~ ammo_box_5_share），
            // 以后再加尺寸照这个形状往下加即可（id 用 ammo_box_N_share）。
            // 选框：分享装的模型都是「铺满 16x16 底面的板 + 若干小盒子」，最外圈正好顶到 0/16，
            //   所以长宽一律取满格（0~16），高度取到箱体上沿（比模型最高点略低）。
            new Type("ammo_box_1_share", "ammo_box_1", 300,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 12.0D, 16.0D),
                    Variant.SHARE, 0, Rarity.COMMON),
            new Type("ammo_box_2_share", "ammo_box_2", 1000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 9.0D, 16.0D),
                    Variant.SHARE, 0, Rarity.COMMON),
            // type3 的模型并集正好是 0..16 / 0..15 / 0..16，所以选框直接取整格长宽 + 15 高
            new Type("ammo_box_3_share", "ammo_box_3", 2000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 15.0D, 16.0D),
                    Variant.SHARE, 0, Rarity.COMMON),
            // type4 的模型并集同样是 0..16 / 0..15 / 0..16，选框与 type3 分享装一致
            new Type("ammo_box_4_share", "ammo_box_4", 4000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 15.0D, 16.0D),
                    Variant.SHARE, 0, Rarity.COMMON),
            // type5 的模型并集同上（0..16 / 0..15 / 0..16），选框沿用
            new Type("ammo_box_5_share", "ammo_box_5", 6000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 15.0D, 16.0D),
                    Variant.SHARE, 0, Rarity.COMMON),

            // ============ 分享装自动补货（一个方块并联 4 个计时器） ============
            // 补给量与其它变体相同（完整一份），不做任何数量折算。
            // 冷却时长强制复用同尺寸 _cd 那一节（见 cooldownConfigId），
            //   所以配置里不会为它们多出一节；这里写的秒数只是配置尚未加载时的兜底值。
            // 外观与选框复用同尺寸分享装的几何（每个充能档一张贴图，5 个模型只差贴图），
            // 选框固定取满次数那一档（见 ShareRestockAmmoBoxBlock#getShape）。
            // 其余尺寸照这两行往下加即可（id 用 ammo_box_N_share_cd）。
            new Type("ammo_box_1_share_cd", "ammo_box_1", 300,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 12.0D, 16.0D),
                    Variant.SHARE_RESTOCK, 300, Rarity.UNCOMMON),
            new Type("ammo_box_2_share_cd", "ammo_box_2", 1000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 9.0D, 16.0D),
                    Variant.SHARE_RESTOCK, 600, Rarity.UNCOMMON),
            new Type("ammo_box_3_share_cd", "ammo_box_3", 2000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 15.0D, 16.0D),
                    Variant.SHARE_RESTOCK, 900, Rarity.UNCOMMON),
            new Type("ammo_box_4_share_cd", "ammo_box_4", 4000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 15.0D, 16.0D),
                    Variant.SHARE_RESTOCK, 1200, Rarity.RARE),
            new Type("ammo_box_5_share_cd", "ammo_box_5", 6000,
                    new AmmoBoxShape(0.0D, 0.0D, 0.0D, 16.0D, 15.0D, 16.0D),
                    Variant.SHARE_RESTOCK, 1500, Rarity.EPIC)
    );
    // ==========================================================================

    /**
     * 按注册 id 查类型（含所有变体，例如 {@code ammo_box_3_share} 也查得到）。
     *
     * <p>存在的原因：空投箱这类<b>不在本清单里</b>的方块也要按档位挂到同一套预算上，
     * 它们需要拿到「本档基础弹药盒」那一行记录（默认预算、逐枪覆盖用的键）。
     * 用查表而不是直接按下标取 {@link #ALL}，是因为清单顺序随时可能因为新增变体而变，
     * 而下标一旦错位就会静默地把第 3 档接到第 4 档的预算上——那种 bug 极难发现。
     *
     * @return 找不到时返回 {@link java.util.Optional#empty()}，由调用方决定是抛异常还是兜底
     */
    public static java.util.Optional<Type> byId(String id) {
        return ALL.stream().filter(type -> type.id().equals(id)).findFirst();
    }

    private AmmoBoxTypes() {
    }
}
