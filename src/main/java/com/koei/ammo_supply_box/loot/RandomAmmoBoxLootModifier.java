package com.koei.ammo_supply_box.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;

/**
 * 让随机弹药盒出现在箱子战利品里（Forge 的 Global Loot Modifier）。
 *
 * <h2>为什么用 GLM 而不是直接改原版箱子表</h2>
 * 想往 {@code minecraft:chests/simple_dungeon} 里加东西，只有两条路：
 * <ol>
 *   <li>把整张原版战利品表复制到本模组里再改 —— 会<b>完全覆盖</b>原版表，
 *       任何其他模组的注入和原版更新都会被我们这张副本干掉；</li>
 *   <li>用 GLM 追加 —— 原版表保持不变，我们在生成结果之后多塞一个物品。</li>
 * </ol>
 * 显然选后者。
 *
 * <h2>生效范围由代码判定</h2>
 * JSON 里的 {@code conditions} 留空，范围在这里判：只认路径以 {@code chests/} 开头的战利品表。
 * 这样一份数据文件就能覆盖全部原版箱子（地下城、废弃矿井、要塞、村庄、前哨、府邸……），
 * 不用在 JSON 里列几十个 id，也不会误伤钓鱼、方块破坏、怪物掉落这些表。
 *
 * <p>代价是整合包作者没法在 JSON 里进一步收窄范围。真需要的话把 {@code conditions}
 * 里填 {@code forge:loot_table_id} 之类的条件即可——那些条件会由父类
 * {@link LootModifier#apply} 先跑一遍，与这里的路径判断是"与"的关系。
 *
 * <h2>只对新生成的区块生效</h2>
 * 战利品表是在箱子第一次生成时算好并写进方块实体的。已经探索过的区域不会凭空多出东西，
 * 这是战利品机制本身的性质，不是本实现的缺陷。
 */
public class RandomAmmoBoxLootModifier extends LootModifier {

    /** 原版箱子战利品表的路径前缀，例如 {@code chests/simple_dungeon}。 */
    private static final String CHEST_TABLE_PREFIX = "chests/";

    /**
     * 序列化格式，与 {@code data/ammo_supply_box/loot_modifiers/*.json} 一一对应：
     *
     * <pre>{@code
     * {
     *   "type": "ammo_supply_box:random_ammo_box",
     *   "conditions": [],
     *   "chance": 0.1,
     *   "item": "ammo_supply_box:ammo_box_random_1"
     * }
     * }</pre>
     *
     * <p>{@code conditions} 字段由 {@link #codecStart} 提供（父类负责判定），
     * 我们只追加 {@code chance} 与 {@code item} 两个字段。
     */
    public static final Codec<RandomAmmoBoxLootModifier> CODEC = RecordCodecBuilder.create(instance ->
            codecStart(instance).and(instance.group(
                    Codec.floatRange(0.0F, 1.0F).fieldOf("chance").forGetter(modifier -> modifier.chance),
                    ForgeRegistries.ITEMS.getCodec().fieldOf("item").forGetter(modifier -> modifier.item)
            )).apply(instance, RandomAmmoBoxLootModifier::new));

    /** 每个符合条件的战利品表追加物品的概率，0~1。 */
    private final float chance;

    /** 追加哪个物品。写成字段而不是写死常量，服主/整合包作者可以直接换。 */
    private final Item item;

    public RandomAmmoBoxLootModifier(LootItemCondition[] conditions, float chance, Item item) {
        super(conditions);
        this.chance = chance;
        this.item = item;
    }

    /**
     * 条件全部通过之后才会走到这里（父类负责跑 {@code conditions}）。
     *
     * <p>注意这里拿到的是<b>已经生成好的</b>战利品列表，我们只是往里追加，
     * 所以原版箱子的内容一点都不会变。
     */
    @Override
    protected @NotNull ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot,
                                                          LootContext context) {
        ResourceLocation tableId = context.getQueriedLootTableId();
        if (tableId == null || !tableId.getPath().startsWith(CHEST_TABLE_PREFIX)) {
            return generatedLoot;
        }
        // 用战利品上下文自带的随机源，和其它战利品生成共用同一个序列
        //（用新的 Random 会破坏种子可复现性）
        if (context.getRandom().nextFloat() < this.chance) {
            generatedLoot.add(new ItemStack(this.item));
        }
        return generatedLoot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
