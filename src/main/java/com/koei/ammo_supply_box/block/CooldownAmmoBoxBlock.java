package com.koei.ammo_supply_box.block;

import com.koei.ammo_supply_box.registry.ModBlockEntities;
import com.koei.ammo_supply_box.util.DurationText;
import com.koei.ammo_supply_box.util.SupplyEffects;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Vector3f;

/**
 * 「带冷却的弹药盒」的公共父类：把"计时器 / 拦截 / 到期归还 / 补货完成反馈"这一整套
 * 从具体变体里抽出来。
 *
 * <h2>谁继承它</h2>
 * <ul>
 *   <li>{@link AutoRestockAmmoBoxBlock}——容量 <b>1</b>：领一次，等冷却，再领一次（原有行为，
 *       用 {@code ready} 布尔状态表达"有没有格"）；</li>
 *   <li>{@link ShareRestockAmmoBoxBlock}——容量 <b>4</b>：一个方块里并联 4 个计时器，
 *       短时间内最多连领 4 次，领完不消失、等各自的冷却转好（用 {@code charges} 0~4 表达）。</li>
 * </ul>
 * 两种变体的差别只有"容量"和"怎么把剩余格数写进方块状态"，补给流程本身
 * （预算换算、逐枪覆盖、虚拟备弹、聊天提示、音效）仍然全部走 {@link AmmoBoxBlock#use}。
 *
 * <h2>为什么抽成父类</h2>
 * <p>Java 单继承：{@link ShareAmmoBoxBlock} 与 {@link AutoRestockAmmoBoxBlock} 都直接继承
 * {@link AmmoBoxBlock}，而"分享装自动补货"要同时具备两者的特性。抽一个中间层之后，
 * 组合变体只需要再叠几十行，而 {@code _cd} 那 5 个方块的行为一行不变。
 * 顺带一个好处：Jade 联动可以只针对本类注册，两种变体自动都有那条冷却提示。
 *
 * <h2>子类要实现的三个钩子</h2>
 * <ul>
 *   <li>{@link #maxCharges()}：容量（几个计时器）；</li>
 *   <li>{@link #chargesOf(BlockState)}：从状态读出"当前有几格可用"；</li>
 *   <li>{@link #withCharges(BlockState, int)}：把格数写回状态（外观随之变化，且自动同步给客户端）。</li>
 * </ul>
 *
 * <h2>时序上的三条不变式</h2>
 * <ol>
 *   <li><b>失败不扣格</b>：父类在「非枪 / 枪数据缺失 / 被禁用 / 预算不足 / 虚拟备弹装不下」时
 *       返回 {@code PASS}，本类只在 {@code consumesAction()} 之后才占格并开始计时；</li>
 *   <li><b>不会卡死</b>：{@link #use} 在发现"状态说 0 格"但时间戳其实早已到期时，会先归还再照常补给；</li>
 *   <li><b>拆放不刷新</b>：{@link #getStateForPlacement} 给 0 格，{@link #setPlacedBy} 再一次性占满所有槽，
 *       所以"挖掉再放"不能跳过等待。{@code /setblock} 这类不经过 {@code BlockItem} 的放置走默认状态
 *       （满格、没有时间戳），首次交互时自愈——对管理员操作可以接受，也不是玩家能利用的路径。</li>
 * </ol>
 */
public abstract class CooldownAmmoBoxBlock extends AmmoBoxBlock implements EntityBlock {

    /** 冷却时长（秒）。实时读配置，所以改配置后对下一次领取立即生效。 */
    private final Supplier<Integer> cooldownSeconds;

    protected CooldownAmmoBoxBlock(Properties properties, String supplyConfigId, Supplier<Integer> damageBudget,
                                   AmmoBoxShape shape, Supplier<Integer> cooldownSeconds,
                                   Vector3f supplyParticleColor) {
        // 带冷却的盒子永不摧毁方块（consumable = false），冷却才是它的限制手段。
        //
        // supplyParticleColor 是「领取成功」时用的 dust 颜色；而「补货完成」用的是另一套固定蓝色，
        // 见 finishRestock —— 两者刻意区分：前者 = 你拿到了补给，后者 = 计时器已经转好。
        super(properties, supplyConfigId, damageBudget, shape, false, supplyParticleColor);
        this.cooldownSeconds = cooldownSeconds;
    }

    /**
     * 容量：同时最多能存几次补给（并联的计时器数量）。
     *
     * <p>{@code public} 是为了让 Jade 联动能按方块类型读到它（显示"可用几 / 共几"）。
     */
    public abstract int maxCharges();

    /** 当前状态里有几格可用。 */
    protected abstract int chargesOf(BlockState state);

    /** 把"几格可用"写回方块状态。实现必须把入参夹到 {@code 0..maxCharges()}。 */
    protected abstract BlockState withCharges(BlockState state, int charges);

    /** 冷却秒数（已夹到非负）。 */
    protected int cooldownSeconds() {
        return Math.max(0, this.cooldownSeconds.get());
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 刚放下的盒子不是立即可用的：先给 0 格，时间戳随后由 setPlacedBy 写入
        return this.withCharges(super.getStateForPlacement(context), 0);
    }

    /**
     * 放置完成时立刻把所有计时器占满。
     *
     * <p><b>为什么用 {@code setPlacedBy} 而不是 {@code onPlace}</b>：{@code LevelChunk#setBlockState}
     * 里 {@code onPlace} 在 L257 调用，而方块实体到 L263 才由 {@code newBlockEntity} 创建，
     * 所以在 {@code onPlace} 里<b>拿不到方块实体</b>。更要紧的是 {@code onPlace} 在<b>属性变化</b>时
     * 也会触发（L253 只比较方块种类），如果自己的格数翻转也走它，冷却会被无限重置。
     *
     * <p>{@code setPlacedBy} 由 {@code BlockItem#place} 在 {@code setBlock} 之后调用，
     * 此时方块实体一定存在，是唯一能安全写时间戳的时机。
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) {
            return;
        }

        int seconds = this.cooldownSeconds();
        if (seconds <= 0) {
            // 冷却被配置成 0：等同于创造模式变体，立刻可用。
            // 这里必须显式翻回满格，因为 getStateForPlacement 给的是 0 格。
            level.setBlock(pos, this.withCharges(state, this.maxCharges()), Block.UPDATE_ALL);
            return;
        }

        AutoRestockAmmoBoxBlockEntity be = blockEntity(level, pos);
        if (be != null) {
            be.startAllCooldowns(level.getGameTime(), seconds, this.maxCharges());
        }
        level.setBlock(pos, this.withCharges(state, 0), Block.UPDATE_ALL);
    }

    /** 取本方块位置的方块实体；取不到时返回 {@code null}，调用方一律按「没有冷却」处理（失败即放行）。 */
    @Nullable
    private static AutoRestockAmmoBoxBlockEntity blockEntity(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof AutoRestockAmmoBoxBlockEntity be ? be : null;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AutoRestockAmmoBoxBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide) {
            // 客户端只做摆动预测，交给父类的客户端分支，不碰任何状态
            return super.use(state, level, pos, player, hand, hitResult);
        }

        // 只有「这一次交互确实会尝试补给」时本方块才做出反应：判定与父类共用 resolveSupply，
        // 覆盖非枪械 / 枪械数据缺失 / 枪包 active=false 三种情况。任一命中就直接交还父类
        //（它会静默返回 PASS），于是冷却提示、自愈、占格统统不会发生。
        // 这一步必须在冷却闸门之前，否则空手点一下盒子也会被告知"正在补货"。
        if (resolveSupply(player, hand) == null) {
            return super.use(state, level, pos, player, hand, hitResult);
        }

        ServerLevel serverLevel = (ServerLevel) level;
        long now = level.getGameTime();
        AutoRestockAmmoBoxBlockEntity be = blockEntity(level, pos);

        int charges = this.chargesOf(state);
        if (charges <= 0) {
            // 先自愈：状态说没格，但时间戳可能早就到期了（ticker 没跑、方块被活塞挪过等）
            if (be != null && be.releaseExpired(now) > 0) {
                charges = Math.min(this.maxCharges(), be.chargeCount(now, this.maxCharges()));
                state = this.withCharges(state, charges);
                level.setBlock(pos, state, Block.UPDATE_ALL);
                finishRestockFeedback(serverLevel, pos, state);
            }
            if (charges <= 0) {
                long remaining = be == null ? 0L : be.getRemainingTicks(now);
                if (remaining > 0L) {
                    // 换算交给 DurationText，与 Jade、物品 tooltip、命令回执共用同一份实现，
                    // 四处显示的数字可以直接互相对照
                    player.displayClientMessage(DurationText.ofTicks(
                                    "message.ammo_supply_box.cooldown",
                                    "message.ammo_supply_box.cooldown_seconds",
                                    remaining)
                            .withStyle(ChatFormatting.RED), false);
                    return InteractionResult.PASS;
                }
                // 没有时间戳（例如 /setblock 放出来的）→ 按可用处理，让玩家不会被卡住
                charges = this.maxCharges();
                state = this.withCharges(state, charges);
                level.setBlock(pos, state, Block.UPDATE_ALL);
            }
        }

        InteractionResult result = super.use(state, level, pos, player, hand, hitResult);
        if (!result.consumesAction()) {
            // 非枪、枪数据异常、被枪包禁用、预算不足、虚拟备弹空间不够……都不是成功领取，
            // 因此不占格、不开始计时
            return result;
        }

        int seconds = this.cooldownSeconds();
        if (seconds <= 0) {
            // 冷却被配置成 0：等同于创造模式弹药盒，不做任何状态切换，避免出现一帧的闪烁
            return result;
        }

        // 先切外观（方块状态会自动同步给客户端），再读方块实体写时间戳。
        // 顺序这样安排是因为 setBlock 之后取到的一定是当前这份方块实体。
        level.setBlock(pos, this.withCharges(state, charges - 1), Block.UPDATE_ALL);
        AutoRestockAmmoBoxBlockEntity afterSet = blockEntity(level, pos);
        if (afterSet != null) {
            afterSet.startCooldown(now, seconds, this.maxCharges());
        }
        return result;
    }

    /**
     * 方块实体 ticker：每 tick 把到期的计时器归还，格数变了就刷新状态并播一次反馈。
     *
     * <p>客户端直接返回 {@code null}——外观由方块状态同步过来，客户端不需要自己再算一遍，
     * 也就不会出现两边判断不一致。
     *
     * <p><b>为什么用 ticker 而不是计划刻</b>（{@code level.scheduleTick}）：早先的实现用的就是计划刻，
     * 而 {@code LevelTicks#schedule} 在区块容器取不到时<b>只 return、既不抛出也不记日志</b>，
     * 属于静默丢刻（实测 3 秒冷却跑完没有回调）。ticker 只要区块加载着就每 tick 都会被调用，
     * 到期判断只有一个 {@code long} 比较；而且它只依赖方块实体里持久化的时间戳，
     * 区块重新加载后的第一个 tick 就会发现自己早已到期并立刻归还，不需要任何补偿。
     *
     * <p>类型判断是手写的，因为 1.20.1 里没有 {@code createTickerHelper}（那是更高版本才有的）。
     */
    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        if (type != ModBlockEntities.AUTO_RESTOCK_AMMO_BOX.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<AutoRestockAmmoBoxBlockEntity>)
                CooldownAmmoBoxBlock::tickCooldowns;
    }

    /** ticker 的实际逻辑。<b>幂等</b>：没有到期槽就直接返回，所以反馈只播一次。 */
    private static void tickCooldowns(Level level, BlockPos pos, BlockState state,
                                      AutoRestockAmmoBoxBlockEntity box) {
        if (!(state.getBlock() instanceof CooldownAmmoBoxBlock block)) {
            return;
        }
        long now = level.getGameTime();
        int released = box.releaseExpired(now);
        if (released <= 0) {
            return;
        }
        BlockState updated = block.withCharges(state, Math.min(block.maxCharges(),
                box.chargeCount(now, block.maxCharges())));
        level.setBlock(pos, updated, Block.UPDATE_ALL);
        // getTicker 只在服务端返回非 null，所以走到这里 level 一定是 ServerLevel
        finishRestockFeedback((ServerLevel) level, pos, updated);
    }

    /**
     * 补货完成的反馈：原版玩家升级音效 + 蓝色颗粒/白色烟雾粒子。
     *
     * <p>都是<b>世界事件</b>——附近所有玩家都能听到、看到，因为"计时器转好了"不是某位玩家的行为。
     * 领取时的补给音效则相反，只发给交互者。
     */
    private static void finishRestockFeedback(ServerLevel level, BlockPos pos, BlockState state) {
        level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
        SupplyEffects.spawn(level, pos, state, SupplyEffects.COLOR_RESTOCK_READY);
    }
}
