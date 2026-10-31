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
 * 木制步道：与弹药盒无关的<b>独立装饰方块</b>，和 {@link WoodenTrayBlock} 是同一族
 * （两者可以在工作台里 1:1 互相转换）。踩在上面是木头脚步声，也不是满方块。
 *
 * <h2>模型与选框：逐元素贴合</h2>
 * 模型只有 5 个元素，而且全部堆在<b>方块顶部</b>（模型里的 rotation 角度全是 0，
 * 所以就是原始坐标）：
 * <ul>
 *   <li><b>Y 15~16</b>：三块板，X 0~16，Z 分别 0~4、5~11、12~16
 *       → 缝在 {@code Z 4~5} 与 {@code Z 11~12}；</li>
 *   <li><b>Y 14~15</b>：两道横撑，X 2~5 与 X 11~14，Z 全通。</li>
 * </ul>
 * 选框与碰撞箱就是这 5 个元素的并集（{@link #ELEMENTS}），所以：
 * <ul>
 *   <li>脚下可站的面在 {@code Y = 16}，也就是<b>整格高度</b>——这一点和托盘不同，
 *       详见下面「脚步声」；</li>
 *   <li>方块下半部分（{@code Y 0~14}）是<b>完全空的</b>，箭、子弹、掉落物都可以从下面穿过去，
 *       而玩家/生物因为身体会顶到 {@code Y 14~16} 的几何体，走过去时仍然会被挡住
 *       （行为上接近"只有上半截的墙"）。</li>
 * </ul>
 *
 * <h2>脚步声为什么不需要额外登记标签</h2>
 * 原版判定「踩在哪个方块上」用的是 {@code Entity#getOnPos(0.2F)}：
 * 把脚底高度减 0.2 再向下取整。托盘只有 3 像素高（0.1875 &lt; 0.2），
 * 会被这条线"漏掉"，所以它必须登记 {@code minecraft:inside_step_sound_blocks} 标签
 * （原因见 {@link WoodenTrayBlock}）。
 * 步道的表面在 {@code Y = 16}（整格高），脚下高度减 0.2 之后仍落在<b>本方块</b>这一层，
 * 因此它的 {@code SoundType.WOOD} 会被正常采用，不需要任何标签。
 *
 * <h2>朝向</h2>
 * 模型不是 90° 对称的（板的缝隙和两道横撑会跟着转），所以像托盘一样按水平朝向存 4 个状态、
 * 由 blockstate 做 {@code y} 旋转，选框也跟着转。
 * 该形状在 180° 下是对称的（板与横撑左右镜像后仍是自己），所以 east/west、south/north
 * 各自算出来完全一样，也与"顺时针/逆时针 90°"的方向约定无关。
 */
public class WoodenWalkwayBlock extends HorizontalDirectionalBlock {

    /**
     * 模型元素的并集，<b>模型空间</b>坐标（0~16，和模型 JSON 的 {@code from}/{@code to} 同一套），
     * 每个条目是 {@code {minX, minY, minZ, maxX, maxY, maxZ}}。
     *
     * <p>顺序与 {@code models/block/wooden_walkway.json} 的 {@code elements} 完全一致，
     * 改模型时照着改这张表即可。元素之间两两不重叠（只贴面）。
     */
    private static final double[][] ELEMENTS = {
            // 顶部三块板（Y 15~16）
            {0.0D, 15.0D, 0.0D, 16.0D, 16.0D, 4.0D},
            {0.0D, 15.0D, 12.0D, 16.0D, 16.0D, 16.0D},
            {0.0D, 15.0D, 5.0D, 16.0D, 16.0D, 11.0D},
            // 板下两道横撑（Y 14~15）
            {11.0D, 14.0D, 0.0D, 14.0D, 15.0D, 16.0D},
            {2.0D, 14.0D, 0.0D, 5.0D, 15.0D, 16.0D}
    };

    /** {@code facing -> 选框}，类初始化时算一次（{@link #ELEMENTS} 是常量）。 */
    private static final Map<Direction, VoxelShape> SHAPES = ElementVoxelShapes.buildAll(ELEMENTS);

    public WoodenWalkwayBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 正面朝向玩家，和托盘、弹药盒、箱子一致
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

}
