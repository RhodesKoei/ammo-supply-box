package com.koei.ammo_supply_box.block;

import java.util.function.Supplier;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.joml.Vector3f;

/**
 * 自动补货弹药盒：补给规则与普通弹药盒<b>完全一致</b>，唯一的区别是领取之后要等冷却结束才能再领。
 *
 * <p>三种弹药盒的关系：
 * <ul>
 *   <li>普通弹药盒：领一次，方块消失（{@code consumable = true}）；</li>
 *   <li>创造模式弹药盒：领了不消失，可无限次领（{@code consumable = false}，无冷却）；</li>
 *   <li><b>自动补货弹药盒</b>：领了不消失，但要等冷却（{@code consumable = false}，有冷却）。</li>
 * </ul>
 *
 * <h2>现在它只是"容量 1 的带冷却盒"</h2>
 * <p>冷却的全套机制（方块实体、闸门、ticker、到期归还、补货完成反馈、放置即冷却）
 * 已经上移到 {@link CooldownAmmoBoxBlock}，因为「分享装自动补货」（{@link ShareRestockAmmoBoxBlock}）
 * 需要同一套机制但容量为 4。本类现在只负责把"容量 1"映射到 {@link #READY} 这个布尔方块属性：
 * 有格可领 = {@code ready=true}（用 {@code _ready} 模型），计时中 = {@code ready=false}（用 {@code _refuse} 模型）。
 *
 * <p>方块状态一变化就自动同步给客户端，所以"切换外观"不需要写任何网络同步代码。
 */
public class AutoRestockAmmoBoxBlock extends CooldownAmmoBoxBlock {

    /**
     * 是否已经补货完毕、可以领取。
     *
     * <p>{@code true} = 可领取（用 {@code _ready} 模型），
     * {@code false} = 冷却中（用 {@code _refuse} 模型）。
     *
     * <p>注意 {@link BooleanProperty} 的属性默认值是 {@code false}，所以构造器里必须显式把
     * 方块的默认状态设成 {@code true}，否则用 {@code /setblock} 之类的途径放出来的盒子
     * 会一上来就是冷却态。
     */
    public static final BooleanProperty READY = BooleanProperty.create("ready");

    public AutoRestockAmmoBoxBlock(Properties properties, String supplyConfigId, Supplier<Integer> damageBudget,
                                   AmmoBoxShape shape, Supplier<Integer> cooldownSeconds,
                                   Vector3f supplyParticleColor) {
        super(properties, supplyConfigId, damageBudget, shape, cooldownSeconds, supplyParticleColor);
        // 父类构造器已经注册过一次默认状态（只含 facing），这里覆盖成连同 ready 一起
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(READY, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(READY);
    }

    @Override
    public int maxCharges() {
        return 1;
    }

    @Override
    protected int chargesOf(BlockState state) {
        return state.getValue(READY) ? 1 : 0;
    }

    @Override
    protected BlockState withCharges(BlockState state, int charges) {
        return state.setValue(READY, charges > 0);
    }
}
