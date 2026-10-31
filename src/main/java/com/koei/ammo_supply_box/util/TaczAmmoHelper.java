package com.koei.ammo_supply_box.util;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import com.tacz.guns.resource.index.CommonAmmoIndex;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.tacz.guns.resource.pojo.data.gun.ExplosionData;
import com.tacz.guns.resource.pojo.data.gun.ExtraDamage;
import com.tacz.guns.resource.pojo.data.gun.FeedType;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.resource.pojo.data.gun.GunReloadData;
import java.util.LinkedList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 与 TACZ 交互的弹药计算与发放逻辑。
 *
 * <p>发放量按<b>总伤害预算</b>换算：单发伤害越低的枪给得越多，越高的枪给得越少，
 * 从而让任何一把枪单次交互拿到的子弹总伤害都大致相同。
 *
 * <p>只依赖 TACZ 的 {@code com.tacz.guns.api} 公开包与资源 POJO 的 getter，
 * 不触碰 {@code com.tacz.guns.util.AttachmentDataUtils} 这类内部实现类。
 */
public final class TaczAmmoHelper {

    /** 取不到口径数据时的兜底堆叠上限，即原版默认值。 */
    private static final int FALLBACK_STACK_SIZE = 64;

    /**
     * fuel 枪的拒绝阈值系数：单个弹匣的总伤超过「补给预算 × 本系数」时拒绝交互。
     *
     * <p>因为发放数向上取整，最坏情况是弹匣总伤刚好逼近本系数倍预算时只给 1 个，
     * 此时给出约 2 倍预算的伤害——这个系数就是用来把最坏情况兜住的。
     */
    private static final int FUEL_REFUSE_BUDGET_MULTIPLIER = 2;

    private TaczAmmoHelper() {
    }

    /**
     * 算出这次该给多少个弹药物品。返回 0 表示拒绝交互（调用方应提示玩家且不消耗弹药盒）。
     *
     * <p>默认按<b>总伤害预算</b>换算；但枪包作者可以在枪械 data 文件里显式指定数量来覆盖它
     * （见 {@link GunSupplyOverride}），此时以显式值为准。
     *
     * <p>本方法只负责"给多少"。枪械被 {@code active: false} 禁用补给这类"什么都不做"的判断
     * 由调用方处理，见 {@code AmmoBoxBlock#use}。
     *
     * @param configuredAmount 枪包作者显式配置的弹药物品个数；{@code null} 表示没配，走预算算法。
     *                         配成 0 或负数表示明确拒绝（注意是"拒绝"，不是回落到预算算法）。
     * @param configuredMultiplier 套在 {@code configuredAmount} 上的乘数，由模组配置提供
     *                             （{@code AmmoSupplyBoxConfig#gunPackAmmoMultiplier()}）。
     *                             调用方保证它已通过有效性校验（必定 &gt; 0）。
     *
     * <h2>两种计量单位</h2>
     * <p>返回值是<b>弹药物品的个数</b>，但一个物品对不同供弹方式的枪价值不同，
     * 这是从 TACZ 的 {@code ModernKineticGunItem.defaultReloadFinishing} 里确认的：
     * <ul>
     *   <li><b>弹匣制</b>（{@code reload.type} = magazine / inventory / manual）：
     *       {@code consumeAmmoFromPlayer(N)} 然后 {@code putAmmoInMagazine(N)}，
     *       一个物品 = 一发，所以按「单发伤害」换算；</li>
     *   <li><b>fuel</b>：{@code consumeAmmoFromPlayer(1)} 然后
     *       {@code putAmmoInMagazine(neededAmmoAmount * 1)}，
     *       <b>一个物品 = 一个满弹匣</b>，所以按「整个弹匣的总伤」换算。</li>
     * </ul>
     *
     * <p>fuel 分支不套用「单发伤害 &gt; 预算就拒绝」那条：按设计它只看弹匣总伤。
     *
     * <h2>与虚拟备弹的换算一致性</h2>
     * <p>返回的这个数字同时也是<b>虚拟备弹池的「发数」</b>，两种模式可以通用，不需要另算一套：
     * <ul>
     *   <li><b>弹匣制</b>：一个弹药物品 = 一发，所以物品数天然就是发数；</li>
     *   <li><b>fuel</b>：一个弹药物品 = 一个满弹匣；而虚拟备弹模式下
     *       {@code consumeAmmoFromPlayer(1)} 只从池里扣 <b>1 发</b>，
     *       却同样执行 {@code putAmmoInMagazine(neededAmmoAmount * 1)}，
     *       也就是 1 发池弹 = 一个满弹匣。两者在这里依旧相等。</li>
     * </ul>
     * <p>所以本方法算的是「这次补给值多少发 / 多少个满弹匣」，与它最终落到背包物品
     * 还是虚拟备弹池无关，去向由调用方决定，见 {@code AmmoBoxBlock#use}。
     */
    public static int getGrantAmount(GunData gunData, Integer configuredAmount, int damageBudget,
                                     double configuredMultiplier) {
        // 枪包作者的显式配置优先，不再经过预算换算，只套一层管理员乘数
        if (configuredAmount != null) {
            return scaleConfiguredAmount(configuredAmount, configuredMultiplier);
        }

        // 没配置则按总伤害预算换算
        float damagePerShot = getDamagePerShot(gunData);

        if (isFuelFed(gunData)) {
            // fuel 枪：一个弹药物品顶一个满弹匣，按弹匣总伤换算
            float magazineDamage = damagePerShot * gunData.getAmmoAmount();
            if (magazineDamage <= 0 || magazineDamage > damageBudget * (double) FUEL_REFUSE_BUDGET_MULTIPLIER) {
                return 0;
            }
            return (int) Math.ceil(damageBudget / (double) magazineDamage);
        }

        // 弹匣制：一个弹药物品 = 一发
        if (damagePerShot <= 0 || damagePerShot > damageBudget) {
            return 0;
        }
        return (int) Math.ceil(damageBudget / (double) damagePerShot);
    }

    /**
     * 把枪包显式写的数量乘上管理员设置的乘数。
     *
     * <p><b>向下取整</b>：乘数的语义是「最多给到 配置值 × 乘数」，所以宁可少给也不多给。
     * 例：配置 100、乘数 0.2 → 20；配置 7、乘数 0.2 → 1（不是 2）。
     *
     * <p>取整后为 0（配置本身就是 0，或配置很小、被乘数压到 1 以下）时返回 0，
     * 由调用方按「拒绝交互」处理——这和枪包直接把数量写成 0 是同一个结果。
     *
     * @param multiplier 已经过有效性校验的乘数（必定 &gt; 0）
     */
    private static int scaleConfiguredAmount(int configuredAmount, double multiplier) {
        int base = Math.max(configuredAmount, 0);
        if (base == 0) {
            return 0;
        }
        double scaled = base * multiplier;
        if (scaled <= 0.0D) {
            return 0;
        }
        // 乘数上限 1000，配置值理论上也可能很大，先夹一次避免转 int 时溢出
        if (scaled >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.floor(scaled);
    }

    /** 是否是 fuel 供弹（一个弹药物品 = 一个满弹匣）。类型缺失时按弹匣制处理。 */
    private static boolean isFuelFed(GunData gunData) {
        GunReloadData reloadData = gunData.getReloadData();
        return reloadData != null && reloadData.getType() == FeedType.FUEL;
    }

    /**
     * 取「每发伤害」。
     *
     * <p>优先用距离-伤害分段常函数（{@code extra_damage.damage_adjust}）的<b>第一段</b>，
     * 也就是最近距离那一档的伤害；没有这个字段时回落到 {@code bullet.damage}。
     * 默认枪包里有 7 把枪两者不一致（例如 m107 是 damage=55 而首段=50），
     * 也有 2 把枪完全没有 damage_adjust（m320、rpg7）。
     *
     * <p>另外把爆炸伤害一并计入：rpg7 直伤只有 20，但每发还带 120 爆炸伤害，
     * 不计算的话会按 200 预算发出 10 发火箭弹，实际总伤 1400，超出预算 7 倍。
     */
    private static float getDamagePerShot(GunData gunData) {
        BulletData bulletData = gunData.getBulletData();
        if (bulletData == null) {
            return 0.0F;
        }

        float damage = bulletData.getDamageAmount();

        ExtraDamage extraDamage = bulletData.getExtraDamage();
        if (extraDamage != null) {
            LinkedList<ExtraDamage.DistanceDamagePair> damageAdjust = extraDamage.getDamageAdjust();
            if (damageAdjust != null && !damageAdjust.isEmpty()) {
                damage = damageAdjust.getFirst().getDamage();
            }
        }

        ExplosionData explosionData = bulletData.getExplosionData();
        if (explosionData != null && explosionData.isExplode()) {
            damage += explosionData.getDamage();
        }

        return damage;
    }

    /** 取该口径的堆叠上限（TACZ 里 {@code AmmoItem.getMaxStackSize} 返回的就是这个值）。 */
    public static int getAmmoStackSize(ResourceLocation ammoId) {
        return TimelessAPI.getCommonAmmoIndex(ammoId)
                .map(CommonAmmoIndex::getStackSize)
                .filter(size -> size > 0)
                .orElse(FALLBACK_STACK_SIZE);
    }

    /**
     * 把定量弹药直接塞进玩家背包，只有背包放不下的余量才在玩家脚下掉出来。
     *
     * <p>不生成掉落物可以避免"物品在眼前闪一下就被捡走"的观感问题——有空间时全程只有一次
     * {@code Inventory.add}，客户端根本不会收到实体生成包。
     *
     * <p>背包满的处理借用了 {@code Inventory.add} 自身的语义：它会<b>就地削减</b>传入的
     * {@code ItemStack}，把装不下的部分留在里面，并且"只要有剩余就返回 false"。
     * 所以下面这种"先 add，失败再丢余量"是原版标准写法，不需要自己算背包还剩多少格。
     *
     * <p>仍然按口径的堆叠上限预先拆分：{@code AmmoItem} 的堆叠上限是每口径不同的
     * （6~64 不等，见 {@code CommonAmmoIndex.getStackSize}），一次构造超量堆叠会造出非法物品。
     */
    public static void giveAmmo(Player player, ResourceLocation ammoId, int totalAmount) {
        if (totalAmount <= 0) {
            return;
        }
        int stackSize = getAmmoStackSize(ammoId);
        int remaining = totalAmount;
        while (remaining > 0) {
            int count = Math.min(remaining, stackSize);
            ItemStack ammoStack = AmmoItemBuilder.create()
                    .setId(ammoId)
                    .setCount(count)
                    .build();

            if (!player.getInventory().add(ammoStack)) {
                // 装不下的余量还留在 ammoStack 里
                dropAtFeet(player, ammoStack);
            }

            remaining -= count;
        }
    }

    /**
     * 虚拟备弹（DummyAmmo）枪械自带的备弹池还能再装多少发。
     *
     * <p><b>虚拟备弹是什么</b>：TACZ 允许把「备弹」直接存在枪械物品自己的 NBT 上，而不是让玩家
     * 背着一堆弹药物品。判据是枪械 NBT 里存不存在 int 标签 {@code DummyAmmo}——存在即开启，
     * 值为 0 也算开启。开启后 TACZ 装填时只从池里扣弹（{@code findAndExtractDummyAmmo}），
     * 完全不再碰玩家背包；并且池子为 0 时 {@code canReload} 直接返回 false，
     * 这把枪就再也装填不了了，不会回退到背包。
     *
     * <p>上限由另一个 int 标签 {@code MaxDummyAmmo} 决定。<b>标签不存在时无上限</b>，
     * 此时返回 {@link Integer#MAX_VALUE}，调用方按「空间无限」处理即可。
     *
     * <p>这两个标签都只由 TACZ 命令 {@code /tacz dummy <目标> <数量>}（需 OP 权限 2）
     * 或枪包 Lua 脚本写入，枪械 data JSON 里没有对应字段，所以只能运行时读。
     */
    public static int getDummyReserveSpace(IGun gun, ItemStack gunStack) {
        if (!gun.hasMaxDummyAmmo(gunStack)) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, gun.getMaxDummyAmmoAmount(gunStack) - gun.getDummyAmmoAmount(gunStack));
    }

    /**
     * 把定量弹药写进枪械自带的虚拟备弹池，而不是玩家背包。
     *
     * <p><b>只能对已经处于虚拟备弹模式的枪调用</b>：{@code addDummyAmmoAmount} 内部第一句就是
     * {@code if (!useDummyAmmo(stack)) return;}，对普通枪是空操作。也就是说本方法无法、
     * 也不会把一把普通枪变成虚拟备弹枪——那会不可逆地永久改变这把枪的弹药经济
     *（{@code setDummyAmmoAmount} 只写不删，一旦开启就退不出去），本模组刻意不做，
     * 见 {@code AmmoBoxBlock#use}。
     *
     * <p>写入量由 {@code addDummyAmmoAmount} 自己按 {@code MaxDummyAmmo} 截断，
     * 调用方应先自行用 {@link #getDummyReserveSpace} 判断空间够不够。
     */
    public static void supplyDummyAmmo(IGun gun, ItemStack gunStack, int amount) {
        gun.addDummyAmmoAmount(gunStack, amount);
    }

    /**
     * 在玩家脚下生成一个掉落物。
     *
     * <p>不用 {@code Player.drop}：那个方法固定在"眼睛高度 - 0.3"处生成并设 40 tick 拾取延迟，
     * 会让玩家在自己视野中央看到物品掉落。这里改成脚底，和直接入包路径的观感一致。
     *
     * <p>刻意不触发 {@code ItemTossEvent}——那是"玩家主动丢弃物品"的语义（该事件可以被取消并把
     * 物品退回背包），而这里属于机器/方块产出的溢出物，与 {@code Block.popResource} 的做法一致。
     *
     * <p>拾取延迟设为 0：背包满时原版拾取逻辑本来就会失败、物品会留在原地，
     * 等玩家腾出空间后立刻被捡走，符合"发到手上"的整体设计。
     */
    private static void dropAtFeet(Player player, ItemStack stack) {
        if (stack.isEmpty() || !(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        // player.position() 的 Y 就是脚底
        Vec3 pos = player.position();
        ItemEntity itemEntity = new ItemEntity(serverLevel, pos.x, pos.y, pos.z, stack);
        itemEntity.setPickUpDelay(0);
        itemEntity.setDeltaMovement(Vec3.ZERO);
        serverLevel.addFreshEntity(itemEntity);
    }
}
