package com.koei.ammo_supply_box.command;

import com.koei.ammo_supply_box.block.AmmoBoxTypes;
import com.koei.ammo_supply_box.config.AmmoSupplyBoxConfig;
import com.koei.ammo_supply_box.item.AirdropBeaconTypes;
import com.koei.ammo_supply_box.registry.ModBlocks;
import com.koei.ammo_supply_box.registry.ModItems;
import com.koei.ammo_supply_box.util.DurationText;
import com.mojang.brigadier.CommandDispatcher;
import java.util.Collection;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemCooldowns;

/**
 * 运行期修改弹药盒配置的服务端命令。
 *
 * <pre>
 * /ammosupplybox getconfig                       列出所有盒子的当前配置
 * /ammosupplybox budget   &lt;ammo_box_N&gt;    &lt;数值&gt; 设置某尺寸的补给预算
 * /ammosupplybox cooldown &lt;ammo_box_N&gt;    &lt;数值&gt; 设置某尺寸所有自动补货变体的冷却秒数
 * /ammosupplybox multiplier &lt;数值&gt;                设置枪包补给数量乘数
 * /ammosupplybox refreshbeacon [&lt;玩家/选择器&gt;]      立即清空（自己或选中玩家）的 5 档信标冷却（权限 3）
 * /ammosupplybox reset                            把全部配置恢复为默认值
 * </pre>
 *
 * <p>{@code multiplier} 是唯一的<b>全局</b>子命令：它没有"哪一个盒子"这个维度，
 * 所以下面直接跟数值，不像另外两个那样还要先选 id。
 *
 * <p>{@code refreshbeacon} 不是改配置，而是改<b>玩家状态</b>：它把目标身上 5 档信标的
 * 冷却直接清掉（管理/调试用），所以它单独用更高的权限等级，见 {@link #PERMISSION_LEVEL_ADMIN}。
 * 不写玩家参数时作用于执行者自己；写了就作用于所有匹配到的玩家（支持 {@code @a}、{@code @p} 等选择器，
 * 因为参数类型是 {@code EntityArgument.players()} 而不是 {@code player()}）。
 *
 * <p>根命令本身<b>没有行为</b>，必须带子命令（与 {@code /tacz} 等一致）——
 * 这样"查询"是一个明确的动作 {@code getconfig}，不会和"改配置"混在同一个入口上。
 *
 * <h2>方块 id 是命令树的字面量，不是自由参数</h2>
 * <p>每个合法的 id 都是命令树里的一个 {@code literal} 分支，所以<b>非法 id 在解析阶段就无法输入</b>——
 * 根本到不了处理函数，也就不需要"未知弹药盒"或"这个盒子没有冷却项"这类事后检查。
 * 这与原版 {@code /gamemode survival}、{@code /difficulty hard} 是同一种写法。
 *
 * <p>附带好处：补全列表天然精确（只会列出真实可用的 id）。
 *
 * <p>两个子命令接受的 id 集合由 {@link AmmoBoxTypes.Type} 决定，而且<b>都只接受尺寸的基础 id</b>
 * （{@code ammo_box_1..5}），不接受任何变体后缀：
 * <ul>
 *   <li>{@code budget}：只有 {@link AmmoBoxTypes.Type#ownsBudgetConfig()} 的普通变体
 *       {@code ammo_box_1..5}。预算是按<b>尺寸</b>设的，同尺寸的创造/自动补货变体共用同一个值，
 *       所以不单独开放它们的 id——不提供"设了但表意重复"的入口。</li>
 *   <li>{@code cooldown}：每个尺寸只有一个冷却值，由该尺寸的自动补货盒提供配置节，
 *       并被「分享装自动补货」强制复用（见 {@link AmmoBoxTypes.Type#cooldownConfigId()}）。
 *       所以参数同样是 {@code ammo_box_1..5}，<b>一次同时作用于 {@code _cd} 与 {@code _share_cd}</b>；
 *       {@code _cd} / {@code _share_cd} 这些后缀不再是合法的输入（它们在补全列表里只会制造误导项）。</li>
 * </ul>
 *
 * <p>回执里一律显示方块的<b>本地化名字</b>（如「中型弹药盒」）而不是注册 id，见 {@link #displayName}。
 * {@code cooldown} 回执显示的是该尺寸<b>提供冷却配置的那一个</b>变体（{@code 自动补货…}），
 * 与 {@code getconfig} 里列出的那一行完全对应。
 *
 * <h2>为什么用命令而不是图形界面</h2>
 * <p>本模组的配置是 {@code ModConfig.Type.SERVER}——<b>服务端权威</b>。
 * 客户端的图形界面（比如 Cloth Config 那类）在专用服务器上<b>改不动</b>服务端配置：
 * 玩家会看到"改了、看起来成功了、实际无效"。而命令本身就在服务端执行，
 * 天然跨过了这条权威边界，也不需要玩家装任何客户端模组。
 *
 * <h2>为什么没有单独的 reload 子命令</h2>
 * <p>不需要。{@code ForgeConfigSpec.ConfigValue#set} 会同时更新内部 Config 对象与缓存，
 * 而本模组所有读取方都是<b>每次使用时实时 {@code get()}</b>，所以 set 完下一条交互就生效；
 * 末尾的 {@code ForgeConfigSpec#save()} 负责落盘。详见
 * {@link AmmoSupplyBoxConfig#setDamageBudget}。
 *
 * <p>唯一的盲区是在游戏运行时<b>手改 TOML 文件</b>——Forge 不监听文件变化
 * （我在 47.4.20 的 {@code ConfigTracker} 里确认了没有 WatchService），
 * 那种情况需要重进世界。走本命令则完全没有这个问题。
 */
public final class AmmoSupplyBoxCommand {

    /** 命令根字面量。 */
    public static final String ROOT = "ammosupplybox";

    /** 列出当前配置的子命令。 */
    public static final String SUB_GET_CONFIG = "getconfig";
    /** 设置补给预算的子命令。 */
    public static final String SUB_BUDGET = "budget";
    /** 设置补货冷却的子命令。 */
    public static final String SUB_COOLDOWN = "cooldown";
    /** 设置枪包补给数量乘数的子命令（全局项，不需要 id）。 */
    public static final String SUB_MULTIPLIER = "multiplier";
    /** 清空执行者全部空投信标冷却的子命令（不需要参数，权限更高）。 */
    public static final String SUB_REFRESH_BEACON = "refreshbeacon";
    /** 把全部配置恢复为默认值的子命令（不需要 id，也不需要参数）。 */
    public static final String SUB_RESET = "reset";

    private static final String ARG_VALUE = "value";

    /** {@code refreshbeacon} 的可选玩家参数名。 */
    private static final String ARG_PLAYER = "player";

    /**
     * 乘数不大于 0 时抛出的错误。
     *
     * <p>为什么在命令这一层<b>报错</b>、而不是像配置文件那样静默回退成 1.0：
     * 配置文件只能被"纠正"，而命令是管理员主动下的指令，把 0 悄悄改成 1.0
     * 会给出一个"成功"的假象。见 {@code AmmoSupplyBoxConfig#setGunPackAmmoMultiplier}。
     */
    private static final SimpleCommandExceptionType ERROR_MULTIPLIER_NOT_POSITIVE =
            new SimpleCommandExceptionType(Component.translatable(
                    "command.ammo_supply_box.multiplier_invalid",
                    AmmoSupplyBoxConfig.GUN_PACK_MULTIPLIER_MAX));

    /** 权限等级 2 = OP / 命令方块，与 TACZ 的 {@code /tacz dummy} 一致。 */
    private static final int PERMISSION_LEVEL = 2;

    /**
     * 清空信标冷却所需的权限等级：<b>3（管理员）</b>。
     *
     * <p>比本命令其余子命令高一级，因为它改的不是配置而是<b>玩家状态</b>——
     * 谁拿到它就能无视空投冷却反复起空投，属于管理/调试权限。
     *
     * <p>为什么是 3 而不是 4：原版权限等级里 3 就是「管理员」（{@code permissions.level.3}），
     * 4 是「服主/所有者」。但要注意 <b>原版 {@code /op} 默认给的就是 4</b>，
     * 所以实际上所有 OP 都能用；写 3 只是避免了"3 级管理员反而用不了"这种别扭情况。
     * 想收紧成只有服主可用，把这个常量改成 4 即可。
     *
     * <p>另外注意 Brigadier 的 {@code requires} 是<b>逐层叠加</b>的：根命令已经要求
     * {@link #PERMISSION_LEVEL}，子命令再要求 3，两者的交集生效，不可能靠子命令放宽父命令。
     */
    private static final int PERMISSION_LEVEL_ADMIN = 3;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT)
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                // 根命令刻意为空：查询与修改各走各的子命令，入口不歧义
                .then(Commands.literal(SUB_GET_CONFIG)
                        .executes(ctx -> getConfig(ctx.getSource())));

        LiteralArgumentBuilder<CommandSourceStack> budget = Commands.literal(SUB_BUDGET);
        LiteralArgumentBuilder<CommandSourceStack> cooldown = Commands.literal(SUB_COOLDOWN);

        // 把每个合法的 id 挂成一个字面量分支。非法的 id 不存在于树里，
        // 所以处理函数拿到的 type 一定是有效的，不需要任何 null 检查。
        for (AmmoBoxTypes.Type type : AmmoBoxTypes.ALL) {
            if (type.ownsBudgetConfig()) {
                budget.then(Commands.literal(type.id())
                        .then(Commands.argument(ARG_VALUE, IntegerArgumentType.integer(
                                        AmmoSupplyBoxConfig.DAMAGE_BUDGET_MIN,
                                        AmmoSupplyBoxConfig.DAMAGE_BUDGET_MAX))
                                .executes(ctx -> setBudget(ctx, type))));
            }
            if (type.ownsCooldownConfig()) {
                // 字面量用<b>尺寸的基础 id</b>（ammo_box_N），而不是 _cd 自己的 id：
                // 一个尺寸只有一个冷却值，分享装自动补货也复用它，
                // 所以让玩家输入 ammo_box_N 一次设好两处，补全列表里也不会出现 _cd / _share_cd 这类后缀。
                cooldown.then(Commands.literal(type.cooldownCommandId())
                        .then(Commands.argument(ARG_VALUE, IntegerArgumentType.integer(
                                        AmmoSupplyBoxConfig.COOLDOWN_MIN,
                                        AmmoSupplyBoxConfig.COOLDOWN_MAX))
                                .executes(ctx -> setCooldown(ctx, type))));
            }
        }

        root.then(budget).then(cooldown);

        // 全局项：没有 id 这一层，数值直接挂在 multiplier 下面。
        // 下界取配置常量（0），所以 0 能通过 Brigadier 的解析，
        // 由处理函数再挡一次并给出明确错误——理由见 ERROR_MULTIPLIER_NOT_POSITIVE。
        root.then(Commands.literal(SUB_MULTIPLIER)
                .then(Commands.argument(ARG_VALUE, DoubleArgumentType.doubleArg(
                                AmmoSupplyBoxConfig.GUN_PACK_MULTIPLIER_MIN,
                                AmmoSupplyBoxConfig.GUN_PACK_MULTIPLIER_MAX))
                        .executes(AmmoSupplyBoxCommand::setGunPackMultiplier)));

        // 重置：不需要 id、也不需要参数——它作用于整份配置
        root.then(Commands.literal(SUB_RESET)
                .executes(ctx -> resetAll(ctx.getSource())));

        // 清空信标冷却：需要更高权限（父命令的 requires 依旧生效，两层叠加）。
        // 无参 = 清自己；带参数 = 清所有匹配到的玩家（用 players() 而不是 player()，
        //   这样 /ammosupplybox refreshbeacon @a 这类多目标选择器才不会被原版挡掉）。
        root.then(Commands.literal(SUB_REFRESH_BEACON)
                .requires(source -> source.hasPermission(PERMISSION_LEVEL_ADMIN))
                .executes(ctx -> refreshBeaconsSelf(ctx.getSource()))
                .then(Commands.argument(ARG_PLAYER, EntityArgument.players())
                        .executes(ctx -> refreshBeaconsFor(ctx.getSource(),
                                EntityArgument.getPlayers(ctx, ARG_PLAYER)))));

        dispatcher.register(root);
    }

    /** 列出所有盒子的当前配置。预算按尺寸、冷却按补货盒，分两组显示。 */
    private static int getConfig(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("command.ammo_supply_box.list_header"), false);

        // 全局项排在最前面：它不属于任何盒子，混进下面两组里会让人误以为是某个盒子的参数
        source.sendSuccess(() -> Component.translatable(
                "command.ammo_supply_box.list_multiplier",
                AmmoSupplyBoxConfig.gunPackAmmoMultiplier()), false);

        for (AmmoBoxTypes.Type type : AmmoBoxTypes.ALL) {
            if (type.ownsBudgetConfig()) {
                int budget = AmmoSupplyBoxConfig.damageBudget(type.id(), type.defaultDamageBudget()).get();
                // 名字取<b>配置节的主人</b>：预算节由普通盒提供，所以显示「小型弹药盒」；
                // 加上语言里的「系列」，读出来就是"这一档的所有变体"。
                Component name = displayName(type.id());
                source.sendSuccess(() -> Component.translatable(
                        "command.ammo_supply_box.list_budget", name, budget), false);
            }
        }
        for (AmmoBoxTypes.Type type : AmmoBoxTypes.ALL) {
            // 只列"自己提供冷却配置"的盒子。分享装自动补货强制复用同尺寸 _cd 那一节
            //（见 AmmoBoxTypes.Type#cooldownConfigId），在这里再列一遍只会让人以为它是独立可调的。
            if (type.ownsCooldownConfig()) {
                int seconds = AmmoSupplyBoxConfig.cooldownSeconds(type.id(), type.cooldownSeconds()).get();
                // 冷却节的主人是<b>自动补货盒</b>，所以这里显示「自动补货小型弹药盒」而不是
                //「小型弹药盒」——后者根本没有冷却机制，写成它会让玩家以为普通盒也带冷却。
                // 配合语言里的「系列」，读出来是"自动补货这一族（含分享装版）"。
                Component name = displayName(type.id());
                // 名字作为前缀参数接在时长前面，由 DurationText 决定这一行是「分 + 秒」还是只有秒
                source.sendSuccess(() -> DurationText.ofSeconds(
                        "command.ammo_supply_box.list_cooldown",
                        "command.ammo_supply_box.list_cooldown_seconds",
                        seconds, name), false);
            }
        }
        return 1;
    }

    private static int setBudget(CommandContext<CommandSourceStack> ctx, AmmoBoxTypes.Type type) {
        int value = IntegerArgumentType.getInteger(ctx, ARG_VALUE);
        AmmoSupplyBoxConfig.setDamageBudget(type.id(), value);

        // 名字用配置节的主人（普通盒 = 这一档），配合「系列」表示同尺寸所有变体一起变
        Component name = displayName(type.id());
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "command.ammo_supply_box.budget_set", name, value), true);
        return 1;
    }

    private static int setCooldown(CommandContext<CommandSourceStack> ctx, AmmoBoxTypes.Type type) {
        int value = IntegerArgumentType.getInteger(ctx, ARG_VALUE);
        AmmoSupplyBoxConfig.setCooldownSeconds(type.id(), value);

        // 冷却节的主人是自动补货盒，所以回执里也显示它（与 getconfig 那行一致）
        Component name = displayName(type.id());
        ctx.getSource().sendSuccess(() -> DurationText.ofSeconds(
                "command.ammo_supply_box.cooldown_set",
                "command.ammo_supply_box.cooldown_set_seconds",
                value, name), true);
        return 1;
    }

    /**
     * 设置枪包补给数量乘数。
     *
     * <p>唯一一个<b>不需要 id</b> 的子命令：它作用于整个存档，没有"哪一个盒子"这个维度。
     *
     * @throws CommandSyntaxException 数值不大于 0（{@code defineInRange} 的下界是 0，
     *                                所以 0 能通过参数解析，必须在这里补一道检查）
     */
    private static int setGunPackMultiplier(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        double value = DoubleArgumentType.getDouble(ctx, ARG_VALUE);
        // 写成 !(value > 0) 而不是 value <= 0，是为了把 NaN 也挡在外面
        if (!(value > 0.0D)) {
            throw ERROR_MULTIPLIER_NOT_POSITIVE.create();
        }
        AmmoSupplyBoxConfig.setGunPackAmmoMultiplier(value);

        ctx.getSource().sendSuccess(() -> Component.translatable(
                "command.ammo_supply_box.multiplier_set", value), true);
        return 1;
    }

    /**
     * 清空<b>执行者自己</b>全部 5 档空投信标的冷却（无参路径）。
     *
     * @return 实际清掉的档位数
     * @throws CommandSyntaxException 控制台执行时抛出原版的「需要玩家」错误
     */
    private static int refreshBeaconsSelf(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer self = source.getPlayerOrException();
        int cleared = clearBeaconCooldowns(self);
        source.sendSuccess(() -> Component.translatable(
                "command.ammo_supply_box.beacon_refresh_done", cleared), true);
        return cleared;
    }

    /**
     * 清空<b>参数选中的玩家</b>的 5 档信标冷却（可一次多人）。
     *
     * <p>回执按目标数量分三种：
     * <ul>
     *   <li>只选中自己（例如 {@code @s}）→ 用「你」的说法；</li>
     *   <li>只选中一名别人 → 用对方名字；</li>
     *   <li>选中多名 → 报"N 名玩家 / 共 M 项"，不逐个列名字。</li>
     * </ul>
     * 这样管理员给别人清完冷却后，聊天框里不会出现让人误以为清了自己的措辞。
     *
     * @return 所有目标上实际清掉的档位数<b>之和</b>
     */
    private static int refreshBeaconsFor(CommandSourceStack source, Collection<ServerPlayer> targets) {
        int totalCleared = 0;
        for (ServerPlayer target : targets) {
            totalCleared += clearBeaconCooldowns(target);
        }
        int cleared = totalCleared;

        if (targets.size() == 1) {
            ServerPlayer only = targets.iterator().next();
            if (only == source.getEntity()) {
                source.sendSuccess(() -> Component.translatable(
                        "command.ammo_supply_box.beacon_refresh_done", cleared), true);
            } else {
                source.sendSuccess(() -> Component.translatable(
                        "command.ammo_supply_box.beacon_refresh_done_other",
                        only.getDisplayName(), cleared), true);
            }
        } else {
            int playerCount = targets.size();
            source.sendSuccess(() -> Component.translatable(
                    "command.ammo_supply_box.beacon_refresh_done_multi", playerCount, cleared), true);
        }
        return cleared;
    }

    /**
     * 清掉一名玩家身上全部 5 档信标冷却，返回清掉的档位数。
     *
     * <p><b>为什么能真的清掉</b>：{@code ItemCooldowns#removeCooldown} 在移除记录之后会调用
     * {@code onCooldownEnded}，而服务端的实现是 {@code ServerItemCooldowns}，
     * 它在那里补发一个 {@code ClientboundCooldownPacket(item, 0)}——所以客户端的冷却遮罩
     * 也会跟着消失。只改服务端记录的话，玩家会看到"物品还灰着但能扔"，那就是两套状态不同步。
     *
     * <p>只对<b>正在冷却中</b>的档位调用移除：对没有冷却的档位调用会白发一个 0 时长包，
     * 而且回执里的计数也就没意义了。
     */
    private static int clearBeaconCooldowns(ServerPlayer target) {
        ItemCooldowns cooldowns = target.getCooldowns();
        int cleared = 0;
        for (AirdropBeaconTypes.Type type : AirdropBeaconTypes.ALL) {
            Item beacon = ModItems.airdropBeacon(type.tier()).get();
            if (cooldowns.isOnCooldown(beacon)) {
                cooldowns.removeCooldown(beacon);
                cleared++;
            }
        }
        return cleared;
    }

    /**
     * 把本模组的全部配置项恢复为默认值。
     *
     * <p>和另外几个子命令不同，它<b>不需要任何参数</b>，也无法撤销。回执只说"已恢复默认值"，
     * 具体数值请用 {@code getconfig} 复核；返回值仍是受影响的项数（命令的惯例），
     * 方便 {@code /execute store result} 之类拿到具体数字。
     */
    private static int resetAll(CommandSourceStack source) {
        int count = AmmoSupplyBoxConfig.resetAllToDefaults();
        // 文案里不再引用项数（那条 %s 已被移除），所以这里不传参数。
        // count 仍然作为命令的返回值，不参与本地化。
        source.sendSuccess(() -> Component.translatable(
                "command.ammo_supply_box.reset_done"), true);
        return count;
    }

    /**
     * 取某个盒子 id 的本地化显示名，例如「中型弹药盒」。
     *
     * <p>用方块自己的 {@code Block#getName()} 而不是手拼 {@code "block." + id}：
     * 前者返回的是注册时算出来的描述键对应的组件，和 tooltip、聊天提示里用的完全同源，
     * 以后就算改了描述键也不会出现两套名字。
     */
    private static Component displayName(String boxId) {
        return ModBlocks.ammoBox(boxId).get().getName();
    }

    private AmmoSupplyBoxCommand() {
    }
}
