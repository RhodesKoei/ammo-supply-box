package com.koei.ammo_supply_box.block;

import com.koei.ammo_supply_box.util.ShareBoxData;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

/**
 * 「分享装自动补货」弹药盒：<b>一个方块里并联 4 个独立计时器</b>，相当于把 4 个自动补货盒叠在一起。
 *
 * <h2>玩法</h2>
 * <ul>
 *   <li>最多能<b>连续领 4 次</b>——每领一次就占用一个计时器并开始它自己的冷却；</li>
 *   <li>领完不消失，也不变成托盘：4 个计时器各自到点归还一格（{@code ready} 反馈 + 蓝色粒子）；</li>
 *   <li>每次补给量是<b>完整一份</b>（与 {@link AutoRestockAmmoBoxBlock} 相同，<b>不</b>像
 *       与 {@link ShareAmmoBoxBlock} 和普通盒都一样），不做任何数量折算；</li>
 *   <li>冷却时长强制复用同尺寸 {@code _cd} 那一节配置（见 {@code AmmoBoxTypes.Type#cooldownConfigId}）。</li>
 * </ul>
 *
 * <h2>外观与选框</h2>
 * <p>模型是<b>一套几何 + 5 张贴图</b>（{@code ammo_box_1_share_cd_0..4}，后缀 = 当前可用格数），
 * 所以只需要 5 个只差贴图的模型文件；选框与碰撞箱固定用<b>满次数</b>那一档
 * （托盘底座 ∪ 4 堆箱体，数据来自 {@link ShareBoxShapes}，与分享装共用同一张表），
 * 不随格数变化——格数只改贴图，轮廓始终是那 4 堆箱子。
 *
 * <p><b>为什么用 {@code charges} 而不是继承 {@code ready} 布尔</b>：这里的外观随格数连续变化
 * （4→1 一个模型一档），布尔只能表达"有/没有"；而 {@code _cd} 那 5 个方块的外观只有两态，
 * 用布尔最贴切。两者由各自的子类决定，{@link CooldownAmmoBoxBlock} 只要求提供
 * "读格数 / 写格数"这两个钩子。
 */
public class ShareRestockAmmoBoxBlock extends CooldownAmmoBoxBlock {

    /**
     * 当前可用格数，范围 {@code 0 ~ 4}。
     *
     * <p>与分享装的 {@code uses} 不同：那边 {@code 1} 是"还能用最后一次"、{@code 0} 不存在（用完成托盘）；
     * 这边 {@code 0} 是正常状态（4 个计时器都在跑），方块留在原地等冷却。
     */
    public static final IntegerProperty CHARGES =
            IntegerProperty.create("charges", 0, ShareBoxData.MAX_USES);

    /**
     * 按格数变化的选框；{@code null} 表示该类型还没提供形状（此时退回整块固定选框）。
     * 数据来自 {@link ShareBoxShapes}，与分享装是同一张表。
     *
     * <p><b>实际只用满格那一档</b>：本变体的 5 个状态共用同一套几何（只有贴图不同），
     * 所以选框也固定取"满次数"那一个，不随格数变化。见 {@link #getShape}。
     */
    private final Map<Integer, Map<Direction, VoxelShape>> shapesByCharges;

    public ShareRestockAmmoBoxBlock(Properties properties, String supplyConfigId, Supplier<Integer> damageBudget,
                                    AmmoBoxShape shape, Supplier<Integer> cooldownSeconds,
                                    Vector3f supplyParticleColor,
                                    Map<Integer, Map<Direction, VoxelShape>> shapesByCharges) {
        super(properties, supplyConfigId, damageBudget, shape, cooldownSeconds, supplyParticleColor);
        this.shapesByCharges = shapesByCharges;
        // 父类构造器已经注册过一次默认状态（只含 facing），这里覆盖成连同 charges 一起。
        // 默认给满格：/setblock 之类不经过 BlockItem 的放置会拿到这个状态，满格最不容易让人困惑。
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(CHARGES, ShareBoxData.MAX_USES));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CHARGES);
    }

    @Override
    public int maxCharges() {
        return ShareBoxData.MAX_USES;
    }

    @Override
    protected int chargesOf(BlockState state) {
        return state.getValue(CHARGES);
    }

    @Override
    protected BlockState withCharges(BlockState state, int charges) {
        return state.setValue(CHARGES, Mth.clamp(charges, 0, ShareBoxData.MAX_USES));
    }

    /**
     * 选框/碰撞箱：所有格数都用同一个形状（满次数那一档，= 托盘底座 ∪ 4 堆箱体）。
     *
     * <p>因为这个变体的模型只有<b>一套几何</b>，5 个状态只是贴图不同——格数变了方块外观
     * 靠贴图表达，而"轮廓"始终是那 4 堆箱体，所以选框不应该跟着动，否则会出现
     * "画面上箱子还在、却已经点不到了"的错位。
     *
     * <p>父类 {@code getCollisionShape} 的默认实现就是 {@code state.getShape(...)}
     * （{@code hasCollision} 为 true），所以碰撞箱自动跟随；客户端也拿得到 {@code charges}
     * （方块状态会同步），两端一致。
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (this.shapesByCharges == null) {
            return super.getShape(state, level, pos, context);
        }
        Map<Direction, VoxelShape> perFacing = this.shapesByCharges.get(this.maxCharges());
        if (perFacing == null) {
            return super.getShape(state, level, pos, context);
        }
        return perFacing.get(state.getValue(FACING));
    }
}
