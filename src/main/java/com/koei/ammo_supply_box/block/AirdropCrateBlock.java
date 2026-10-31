package com.koei.ammo_supply_box.block;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import org.joml.Vector3f;

/**
 * 空投补给箱：由 {@link com.koei.ammo_supply_box.entity.SupplyBeamEntity} 从高空落下生成的补给点。
 *
 * <h2>功能与「分享装」完全一致，只有收尾不同</h2>
 * <p>补给行为、每次的补给量、4 次可用的次数、外观随剩余次数变化——全部继承自
 * {@link ShareAmmoBoxBlock}。唯一的区别是用完最后一次之后：
 * <ul>
 *   <li>分享装：原地换成一个<b>木制托盘</b>（玩家的"说法"是盒子被用空了）；</li>
 *   <li><b>空投箱</b>：<b>直接被破坏</b>，什么都不留下（空投物资本来就是一次性的）。</li>
 * </ul>
 * 这个区别只体现在 {@link #onUsesExhausted} 这一个钩子上，所以构造时不需要残留物，
 * {@code leftover} 传 {@code null}。
 *
 * <h2>挖掉不会掉落任何东西</h2>
 * 空投箱是<b>只能靠空投获得</b>的据点，不能被打包带走——否则玩家一发信标就能刷出一个
 * 可搬运的补给点，搬回基地反复使用。所以 {@link #getDrops} 返回空：
 * 挖掘、爆炸、被别的方块替换，全部得不到物品。
 *
 * <h2>但创造模式中键可以取到</h2>
 * <p>{@link #getCloneItemStack} <b>不再</b>覆盖，沿用 {@link ShareAmmoBoxBlock} 的实现：
 * 取到的物品带着当前剩余次数。
 *
 * <p>这不会破坏上面那条规则。我核对过 1.20.1 的 {@code Minecraft#pickBlock}：
 * 是否凭空生成物品由 {@code this.player.getAbilities().instabuild} 决定——
 * <b>只有创造模式</b>才走 {@code inventory.setPickedItem(...)}；
 * 生存模式走的是「在背包里找到同款才切换快捷栏槽位」的分支，一个物品都不会多出来。
 * 而生存真正能"打包带走"的三条路径（挖掘、爆炸、方块替换）全部经过 {@code getDrops}，
 * 那条仍然返回空。所以中键放行的实际后果只是：创造模式玩家不用翻创造物品栏。
 *
 * <p>顺带一个细节：{@code Minecraft#pickBlock} 在拿到空栈时只打一行
 * {@code Picking on … gave null item} 警告就静默返回，玩家侧表现为"中键毫无反应"
 * ——这正是之前把它覆盖成空栈后会出现的情况。
 *
 * <p>另外这也意味着 {@link ShareAmmoBoxBlock} 里那套「次数跟着物品走」的逻辑，
 * 对空投箱在<b>创造模式</b>下是完整可用的（中键、创造物品栏、{@code /give}）。
 *
 * <h2>补给预算复用本档基础弹药盒</h2>
 * <p>和分享装一样不新开配置节：{@code /ammosupplybox budget ammo_box_3} 会同时影响
 * 普通盒、分享装和空投箱；枪包作者写的逐枪覆盖键也是 {@code ammo_box_3}。
 */
public class AirdropCrateBlock extends ShareAmmoBoxBlock {

    public AirdropCrateBlock(Properties properties, String supplyConfigId, Supplier<Integer> damageBudget,
                             AmmoBoxShape shape, Vector3f supplyParticleColor) {
        // leftover 传 null：本类用完直接破坏，收尾逻辑在 onUsesExhausted 里
        // shapesByUses 也传 null：空投箱只有一种几何，选框不随剩余次数变化（换的只是贴图）
        super(properties, supplyConfigId, damageBudget, shape, supplyParticleColor, null, null);
    }

    /**
     * 用完最后一次：直接破坏，不掉落任何东西。
     *
     * <p>{@code removeBlock(pos, false)} 与普通弹药盒补给后被消耗用的是同一条路径：
     * 它不走 {@code Block.dropResources}，也不会触发原版的破坏粒子与音效
     * ——这一点很重要，因为父类刚刚已经播过补给粒子和补给音了，再叠一套会显得很吵。
     *
     * <p>第二个参数是 {@code isMoving}，传 {@code false} 表示"不是被活塞推动导致的移除"。
     */
    @Override
    protected void onUsesExhausted(Level level, BlockPos pos, BlockState state) {
        level.removeBlock(pos, false);
    }

    /**
     * 挖掉不掉任何东西。
     *
     * <p>覆写 {@code getDrops} 会<b>完全绕过战利品表</b>（所以本方块没有
     * {@code loot_tables/blocks/*.json}），覆盖玩家挖掘、爆炸、被方块替换等所有掉落路径。
     * 空投箱是空投专属据点，不允许被打包搬走，见类注释。
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return List.of();
    }
}
