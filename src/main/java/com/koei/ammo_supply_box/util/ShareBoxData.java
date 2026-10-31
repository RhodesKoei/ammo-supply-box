package com.koei.ammo_supply_box.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 「分享装」弹药盒的剩余使用次数。
 *
 * <p>次数要在<b>方块和物品之间来回搬运</b>，这是分享装唯一新增的状态：
 * <ul>
 *   <li>物品 → 方块：{@code ShareAmmoBoxBlock#getStateForPlacement} 从手上的 ItemStack 读；</li>
 *   <li>方块 → 物品：{@code ShareAmmoBoxBlock#getDrops} 把方块状态里的次数写进掉落物。</li>
 * </ul>
 * 其余一切（补给量、枪包配置、拒绝提示、粒子音效）都复用普通弹药盒的现成实现。
 *
 * <h2>为什么次数锁死 4</h2>
 * <p>按需求固定为 4 次，<b>不做成配置项</b>。所以这里是一个常量而不是配置读取，
 * 以后新增 type2~5 的分享装也全部共享这个值，不需要在
 * {@code AmmoBoxTypes.Type} 里多加字段。
 *
 * <h2>「满次数」的规范形式是：没有标签</h2>
 * <p>1.20.1 的合成配方<b>无法给产物写 NBT</b>，所以刚合成出来、以及创造物品栏 / {@code /give}
 * 出来的分享装都是不带标签的。因此「没有这个标签」必须当成正常路径（= 满 4 次），而不是异常路径。
 *
 * <p>这条规则是<b>双向</b>的：{@link #getRemainingUses} 读到没有标签就返回满次数，
 * {@link #setRemainingUses} 写满次数时会把标签删掉。两个方向都统一到"满次数 = 无标签"，
 * 满次的分享装才不会因为来源不同而变成两种堆不到一起的物品（详见 {@link #setRemainingUses}）。
 */
public final class ShareBoxData {

    /** 分享装的次数上限。所有尺寸共用，不做成配置项。 */
    public static final int MAX_USES = 4;

    /** ItemStack 上存剩余次数的 NBT 键。 */
    private static final String TAG_USES = "Uses";

    /**
     * 读出手上这个分享装还剩几次。
     *
     * <p>没有标签时返回 {@link #MAX_USES}（见类注释：合成产物本来就不带 NBT）。
     * 读到的值会被夹到 {@code [1, MAX_USES]}——0 次的状态不存在，因为用完就直接移除方块了。
     */
    public static int getRemainingUses(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_USES, Tag.TAG_INT)) {
            return MAX_USES;
        }
        return clamp(tag.getInt(TAG_USES));
    }

    /**
     * 把剩余次数写进物品。挖掉方块、创造模式中键取方块时用。
     *
     * <p><b>满次数不写标签，而是把标签删掉</b>（见类注释「满次数 = 没有标签」）。
     * 这是为了堆叠：本模组里同一个 ItemStack 能否堆叠，看的是物品 + NBT 是否完全相同，
     * 所以「不带 NBT」和「带着 {@code Uses:4}」是<b>两种不同的东西，堆不到一起</b>。
     * 如果这里给满次数的物品也写上标签，玩家就会发现明明是同一件东西，却分成了两堆：
     * 创造物品栏 / 合成产物（本来就没有 NBT）一堆，挖下来的（带着 {@code Uses:4}）另一堆。
     * 把满次数规范成"没有标签"之后，四个来源（合成、创造物品栏、{@code /give}、挖下来）
     * 产出的满次分享装就都是同一个物品了。
     *
     * <p>只有真的用掉过（剩 1~3 次）才写标签——这种本来就不该和满次的堆叠，
     * 而剩同样次数的两件之间仍然能堆。
     */
    public static void setRemainingUses(ItemStack stack, int uses) {
        int clamped = clamp(uses);
        if (clamped >= MAX_USES) {
            // 只在确实有标签时动手：避免给物品凭空造出一个空 CompoundTag
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                tag.remove(TAG_USES);
                // 删空之后连空 CompoundTag 也要去掉，否则「空标签」和「没有标签」依旧堆不起来
                if (tag.getAllKeys().isEmpty()) {
                    stack.setTag(null);
                }
            }
            return;
        }
        stack.getOrCreateTag().putInt(TAG_USES, clamped);
    }

    /** 夹到合法区间：{@code 1} 是能存下来的最小值（0 次意味着方块已经被移除了）。 */
    private static int clamp(int uses) {
        return Math.max(1, Math.min(MAX_USES, uses));
    }

    private ShareBoxData() {
    }
}
