package com.koei.ammo_supply_box.block;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 木制托盘：<b>独立的装饰/残留方块</b>，不是弹药盒，也没有任何交互逻辑。
 *
 * <p>它只有一个来源——「分享装」弹药盒把 4 次用完时，不在原地消失，而是把自己换成一个托盘
 * （见 {@link ShareAmmoBoxBlock#use}）。所以它本质上是"弹药盒拆开后的空托盘"。
 * 玩家也可以自己合成、捡起来、换个地方放，因此它同样是一个正常的可放置方块，
 * 挖掉掉自己（战利品表 {@code loot_tables/blocks/wooden_tray.json}）。
 *
 * <h2>为什么它有自己的 {@code facing}</h2>
 * 托盘模型不是中心对称的（中间有一道隔条、四角有支脚），旋转 90° 外观会变，
 * 所以要像弹药盒一样按水平朝向存 4 个状态、由 blockstate 做 {@code y} 旋转。
 * 分享装用完时托盘会<b>继承那个弹药盒的朝向</b>，见 {@code ShareAmmoBoxBlock#use}。
 *
 * <h2>选框与碰撞箱：逐元素贴合模型</h2>
 * 托盘<b>不是</b>整格方块，也不是"整格长宽 + 3 像素高"的实心板：边框和碰撞箱
 * <b>完全按模型元素</b>拼出来（{@link #ELEMENTS}），连顶板上那两条 1 像素宽的缝都保留。
 * 用模型自己的话描述（模型里的 rotation 角度全是 0，所以就是原始坐标）：
 * <ul>
 *   <li><b>Y 2~3（顶板）</b>：X 0~16 的三块板，Z 分别 0~4、5~11、12~16
 *       → 缝在 {@code Z 4~5} 与 {@code Z 11~12}；</li>
 *   <li><b>Y 1~2</b>：左右横梁（X 0~2、X 14~16）+ 中间隔条（X 7~9），Z 全通；</li>
 *   <li><b>Y 0~1</b>：中间隔条继续 + 6 个 2x1x2 的支脚（X 0~2 与 14~16，Z 0~2 / 7~9 / 14~16）。</li>
 * </ul>
 * 直接在游戏里能观察到的两个后果，都是这个选择的自然结果，不是 bug：
 * <ul>
 *   <li>侧面看过去，梁与梁之间（例如 X 2~7 的 Y 1~2 层）是<b>空的</b>，
 *       箭、TACZ 的子弹可以从这些空隙里穿过去；顶板那两条缝同理；</li>
 *   <li>描边会把梁和支脚的轮廓也画出来（原版对合并形状只画外轮廓，
 *       内部互相贴合的面不会重复画）。</li>
 * </ul>
 * 形状必须跟着 {@code facing} 旋转：这个形状在 180° 下是对称的（支脚与横梁左右镜像后仍是自己），
 * 但 90° 不是（横梁会从"沿 Z"变成"沿 X"），所以按朝向生成 4 份，
 * 变换与 blockstate 里 {@code "y": 90/180/270} 一一对应。
 *
 * <p>本类不调用 {@code noCollission()}，于是 {@code BlockBehaviour#getCollisionShape}
 * 的默认实现直接返回 {@link #getShape}，碰撞箱与描边选框天然一致（和弹药盒同一套约定）。
 * 因为不是满方块，属性里必须调用 {@code noOcclusion()}，否则贴着它的方块面会被错误剔除
 * （见 {@code ModBlocks.WOODEN_TRAY}）。
 *
 * <h2>为什么它还要进 {@code minecraft:inside_step_sound_blocks} 标签</h2>
 * <p>托盘只有 3 像素高（{@code 0.1875}），而原版判定「玩家踩在哪个方块上」用的不是脚下那个方块，
 * 而是 {@code Entity#getOnPos(0.2F)}：把玩家脚底高度减去 {@code 0.2} 再向下取整
 * （见 1.20.1 {@code Entity#move} 里 {@code walkDist > nextStep} 之后的那段）。
 * 也就是说<b>顶面不到 0.2 格高的方块永远算不到自己头上</b>，脚步声会取到它下方的方块。
 * 托盘正好卡在这条线下面（{@code 0.1875 < 0.2}），所以默认是「没有自己的脚步声」。
 *
 * <p>原版给这类薄方块留了后门：只要方块在
 * {@code minecraft:inside_step_sound_blocks} 或 {@code minecraft:combination_step_sound_blocks}
 * 标签里，{@code Player#playStepSound} 就会改用它<b>上方</b>那一格（也就是托盘自己）的
 * {@code SoundType}。原版这两个标签装的就是全部薄方块——地毯、苔藓毯、雪、睡莲、
 * 粉末雪、绯红菌索…… 本方块因此登记进前者（只听托盘的木头声），
 * 见 {@code data/minecraft/tags/blocks/inside_step_sound_blocks.json}。
 * 如果想让下方地面的声音也保留一点点，把它改登记到 {@code combination_step_sound_blocks} 即可
 * （那条路径是「上方 0.15 音量 + 下方 0.05 音量」叠加）。
 */
public class WoodenTrayBlock extends HorizontalDirectionalBlock {

    /**
     * 模型元素的并集，<b>模型空间</b>坐标（0~16，和模型 JSON 里的 {@code from}/{@code to} 同一套），
     * 每个条目是 {@code {minX, minY, minZ, maxX, maxY, maxZ}}。
     *
     * <p>顺序与 {@code models/block/wooden_tray.json} 的 {@code elements} 完全一致，
     * 改模型时照着改这张表即可。元素之间两两不重叠（只贴面），所以直接并起来就是精确轮廓。
     */
    private static final double[][] ELEMENTS = {
            // 顶板三块
            {0.0D, 2.0D, 0.0D, 16.0D, 3.0D, 4.0D},
            {0.0D, 2.0D, 12.0D, 16.0D, 3.0D, 16.0D},
            {0.0D, 2.0D, 5.0D, 16.0D, 3.0D, 11.0D},
            // 中间隔条（上下两层贯穿）
            {7.0D, 0.0D, 0.0D, 9.0D, 2.0D, 16.0D},
            // 左右横梁
            {14.0D, 1.0D, 0.0D, 16.0D, 2.0D, 16.0D},
            {0.0D, 1.0D, 0.0D, 2.0D, 2.0D, 16.0D},
            // 六个支脚
            {14.0D, 0.0D, 14.0D, 16.0D, 1.0D, 16.0D},
            {14.0D, 0.0D, 0.0D, 16.0D, 1.0D, 2.0D},
            {14.0D, 0.0D, 7.0D, 16.0D, 1.0D, 9.0D},
            {0.0D, 0.0D, 0.0D, 2.0D, 1.0D, 2.0D},
            {0.0D, 0.0D, 7.0D, 2.0D, 1.0D, 9.0D},
            {0.0D, 0.0D, 14.0D, 2.0D, 1.0D, 16.0D}
    };

    /** {@code facing -> 选框}，类初始化时算一次（{@link #ELEMENTS} 是常量）。 */
    private static final Map<Direction, VoxelShape> SHAPES = ElementVoxelShapes.buildAll(ELEMENTS);

    /**
     * 托盘在指定朝向上的选框/碰撞箱外形。
     *
     * <p>对外公开是因为<b>分享装弹药盒把这 12 条元素当作自己的底座</b>：
     * 五个分享装模型的前 12 个元素就是这个托盘（逐条一致），所以它们的底座形状直接复用这里，
     * 不再另存一份坐标。见 {@link ShareBoxShapes}。
     */
    public static VoxelShape shapeFor(Direction facing) {
        return SHAPES.get(facing);
    }

    public WoodenTrayBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 正面朝向玩家，和弹药盒、箱子、熔炉一致
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

}
