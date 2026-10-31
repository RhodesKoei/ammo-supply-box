package com.koei.ammo_supply_box.block;

import com.koei.ammo_supply_box.util.ShareBoxData;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

/**
 * 「分享装」弹药盒：补给行为与普通弹药盒<b>完全一致</b>（同样一份补给量、同一套预算配置），
 * 唯一的区别是<b>能用 {@code 4} 次才消失</b>——每次用掉一次次数，用完变成一个空托盘。
 *
 * <h2>它和另外两种变体的关系</h2>
 * <ul>
 *   <li>普通弹药盒：用 1 次就消失；</li>
 *   <li><b>分享装</b>：用 4 次才消失，次数跟着方块走；</li>
 *   <li>创造模式：不消失也不用等待；</li>
 *   <li>自动补货：不消失，但要等冷却。</li>
 * </ul>
 *
 * <p>所以本类<b>没有重写任何补给流程</b>：{@code resolveSupply}、预算换算、逐枪覆盖、
 * 虚拟备弹、拒绝提示、粒子与音效全部走父类 {@link AmmoBoxBlock#use}。
 * 本类只在"父类确认这次补给<b>真的发放了</b>"之后做两件事：扣一次次数、扣到 0 就调用
 * {@link #onUsesExhausted} 收尾（默认换成一个<b>空托盘</b>，见 {@link WoodenTrayBlock}；
 * 子类 {@link AirdropCrateBlock} 覆写成直接破坏）。这个"成功之后再动手"的位置和
 * {@link AutoRestockAmmoBoxBlock} 启动冷却是同一处。
 *
 * <h2>次数存在方块状态里，而不是方块实体</h2>
 * <p>只需要一个小整数，不需要绝对时间戳，所以用 {@link IntegerProperty} 就够了，
 * 而且顺带避开两个坑：
 * <ul>
 *   <li>方块实体有"<b>{@code /setblock} 放置时不初始化</b>"的盲区（{@code setPlacedBy} 不会被调用），
 *       而方块状态有默认值 {@code 4}，用命令放出来的分享装能正常工作；</li>
 *   <li>方块状态会自动同步给客户端，所以"按剩余次数换模型"这件事不需要写任何网络代码——
 *       见 blockstate 里 {@code facing × uses} 的 16 个变体。</li>
 * </ul>
 *
 * <h2>次数怎么跟着物品走</h2>
 * <ul>
 *   <li>放下时：{@link #getStateForPlacement} 从手上的 ItemStack 读，见 {@link ShareBoxData}；</li>
 *   <li>挖掉时：{@link #getDrops} 直接把方块状态里的次数写进掉落物；</li>
 *   <li>创造模式中键时：{@link #getCloneItemStack} 同上，否则会凭空刷出满次物品。</li>
 * </ul>
 * 三条路径都经过 {@link ShareBoxData}，而它在写「满 4 次」时会把 NBT 删掉，
 * 所以满次的分享装无论来自合成、创造物品栏还是挖掘，都是同一个可堆叠的物品。
 * 注意 {@code getDrops} 一旦覆写，这个方块的<b>战利品表就被完全绕过了</b>，
 * 所以 {@code loot_tables/blocks/ammo_box_1_share.json} 是刻意不创建的
 * （建了也不会被读取，只会让人误以为它在生效）。
 */
public class ShareAmmoBoxBlock extends AmmoBoxBlock {

    /**
     * 剩余可用次数，范围 {@code 1 ~ 4}。
     *
     * <p>{@code 4} = 全新，{@code 1} = 还能用最后一次。不存在 {@code 0}：
     * 用到 0 时方块会被替换成空托盘，掉落物也不会带上这个值。
     *
     * <p>模型按这个值切换贴图（{@code ammo_box_1_share_1..4}），所以玩家不用打开
     * tooltip 就能看出还剩几次。
     */
    public static final IntegerProperty USES = IntegerProperty.create("uses", 1, ShareBoxData.MAX_USES);

    /**
     * 用完（第 4 次补给成功）之后留在原地的方块状态，按本方块当时的朝向生成。
     *
     * <p>写成 {@code Function<Direction, BlockState>} 而不是直接引用某个方块，有两个原因：
     * <ul>
     *   <li>{@code block} 包不用反向依赖 {@code registry} 包，也就不存在静态初始化顺序问题；</li>
     *   <li>以后要让不同尺寸的分享装留下不同残留物时，只要在 {@code ModBlocks} 里换个实现。</li>
     * </ul>
     * 约定：返回的状态必须能放在本方块原来的位置上（同格替换），朝向取参数给的那个。
     *
     * <p><b>可以为 {@code null}</b>：覆写了 {@link #onUsesExhausted} 的子类（例如空投箱，
     * 它用完后直接把自己破坏掉）不需要残留物，传 {@code null} 即可；
     * 默认实现遇到 {@code null} 时会退化成「移除方块」。
     */
    private final Function<Direction, BlockState> leftover;

    /**
     * 按剩余次数变化的选框/碰撞箱；{@code null} 表示这个类型还没做分状态几何。
     *
     * <p>由注册处按类型注入，见 {@link ShareBoxShapes#forType(String)}。
     * 形状 = <b>托盘底座 ∪ 剩余箱堆</b>：每用掉一次少一堆，用完时正好退化成托盘，
     * 所以"变成托盘"那一刻底座部分的形状是连续的，不会有跳变。
     */
    private final Map<Integer, Map<Direction, VoxelShape>> shapesByUses;

    public ShareAmmoBoxBlock(Properties properties, String supplyConfigId, Supplier<Integer> damageBudget,
                             AmmoBoxShape shape, Vector3f supplyParticleColor,
                             Function<Direction, BlockState> leftover,
                             Map<Integer, Map<Direction, VoxelShape>> shapesByUses) {
        // consumable = false：父类不会在补给成功后移除方块，扣次数与替换残留物由本类自己负责
        super(properties, supplyConfigId, damageBudget, shape, false, supplyParticleColor);
        this.leftover = leftover;
        this.shapesByUses = shapesByUses;
        // 父类构造器已经注册过一次默认状态（只含 facing），这里覆盖成连同 uses 一起
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(USES, ShareBoxData.MAX_USES));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(USES);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 挖掉再放回来要保持剩余次数；合成/NBT 缺失时 ShareBoxData 会返回满次数
        return super.getStateForPlacement(context)
                .setValue(USES, ShareBoxData.getRemainingUses(context.getItemInHand()));
    }

    /**
     * 选框/碰撞箱跟着 {@code uses} 变：用完一堆少一堆，剩下的就是"托盘 + 剩余箱堆"。
     *
     * <p>父类 {@code getCollisionShape} 的默认实现就是 {@code state.getShape(...)}
     * （{@code hasCollision} 为 true），所以碰撞箱自动跟着这里走，不需要另外覆写。
     * 客户端也拿得到 {@code uses}（方块状态会同步），所以描边与碰撞在两端一致。
     *
     * <p>没提供分状态几何的类型（{@code shapesByUses == null}）退回父类那份固定的
     * {@link AmmoBoxShape}，行为与改造前完全一样。
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (this.shapesByUses == null) {
            return super.getShape(state, level, pos, context);
        }
        Map<Direction, VoxelShape> perFacing = this.shapesByUses.get(state.getValue(USES));
        if (perFacing == null) {
            return super.getShape(state, level, pos, context);
        }
        return perFacing.get(state.getValue(FACING));
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide) {
            // 客户端只做摆动预测，交给父类的客户端分支，不碰任何状态
            return super.use(state, level, pos, player, hand, hitResult);
        }

        InteractionResult result = super.use(state, level, pos, player, hand, hitResult);
        if (!result.consumesAction()) {
            // 非枪 / 枪数据异常 / 被枪包禁用 / 预算不足 / 虚拟备弹装不下
            // 这些情况父类都返回 PASS，也就是"这次没有真的发放"，所以不能扣次数
            return result;
        }

        int remaining = state.getValue(USES) - 1;
        if (remaining > 0) {
            level.setBlock(pos, state.setValue(USES, remaining), Block.UPDATE_ALL);
            return result;
        }

        // 第 4 次用完了：交给钩子决定原地留下什么（分享装留托盘，空投箱直接破坏）。
        this.onUsesExhausted(level, pos, state);
        return result;
    }

    /**
     * 次数用尽（最后一次补给成功）之后，对原地做最终处理。
     *
     * <p>默认实现是「换成 {@link #leftover}」——也就是分享装留下空托盘。
     * 子类可以覆写成别的收尾：
     * <ul>
     *   <li>分享装：保留现状（留托盘）；</li>
     *   <li>空投箱：{@code level.removeBlock(pos, false)}，直接破坏且不掉落任何东西。</li>
     * </ul>
     *
     * <p>调用时机在父类补给流程<b>真正发放成功</b>之后（粒子与音效都播过了），
     * 所以这里的实现不需要再补任何反馈。
     *
     * @param state 被使用掉的那一次交互发生时的方块状态（此时 {@code uses} 仍等于 1）
     */
    protected void onUsesExhausted(Level level, BlockPos pos, BlockState state) {
        if (this.leftover == null) {
            // 没给残留物的子类又不覆写本方法：退化成"直接破坏"，总比抛 NPE 把服务端搞崩好
            level.removeBlock(pos, false);
            return;
        }

        // 原地留下残留物（分享装 → 空托盘），朝向继承本方块。
        //
        // 这里用 setBlock 一次写完，而不是 removeBlock 之后再放托盘：
        // 不会出现"中间有一 tick 是空气"的状态，也不需要额外的放置音效/粒子
        //（父类刚播过补给粒子与音效，再叠一层就太吵了）。
        // setBlock 不走 Block.dropResources，所以这个弹药盒本身不会掉出来。
        BlockState leftoverState = this.leftover.apply(state.getValue(FACING));
        if (!level.setBlock(pos, leftoverState, Block.UPDATE_ALL)
                && level.getBlockState(pos).getBlock() == this) {
            // setBlock 理论上不该失败。万一失败了，方块会带着"剩 1 次"留在原地，
            // 玩家就能无限续杯——所以这里补一次移除兜底。
            // 先确认原地仍然是本方块，避免误删别人同一 tick 放下的东西。
            level.removeBlock(pos, false);
        }
    }

    /**
     * 掉落物：带着剩余次数。
     *
     * <p>覆写这个方法意味着<b>不再走战利品表</b>——所以本方块没有
     * {@code loot_tables/blocks/*.json}。这样做覆盖了所有掉落路径
     * （玩家挖掘、爆炸、方块被替换等），而不只是玩家挖掘。
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        ItemStack dropped = new ItemStack(this.asItem());
        ShareBoxData.setRemainingUses(dropped, state.getValue(USES));
        return List.of(dropped);
    }

    /**
     * 创造模式中键取方块（pick block）时，也带上当前剩余次数。
     *
     * <p>原版默认实现是 {@code new ItemStack(this)}，也就是一个不带 NBT 的物品——
     * 而按约定「不带 NBT」等于「满 4 次」，于是对着一个只剩 1 次的分享装按中键
     * 会凭空刷出一个满次的。这里补上同样的搬运逻辑，让中键和挖掉的结果一致。
     *
     * <p>生存模式不经过这个方法（那里走 {@link #getDrops}），所以两者互不影响。
     */
    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        ItemStack stack = new ItemStack(this.asItem());
        ShareBoxData.setRemainingUses(stack, state.getValue(USES));
        return stack;
    }
}
