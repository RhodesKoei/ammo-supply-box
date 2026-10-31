package com.koei.ammo_supply_box.block;

import com.koei.ammo_supply_box.config.AmmoSupplyBoxConfig;
import com.koei.ammo_supply_box.registry.ModSounds;
import com.koei.ammo_supply_box.util.GunSupplyOverride;
import com.koei.ammo_supply_box.util.SupplyEffects;
import com.koei.ammo_supply_box.util.TaczAmmoHelper;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

/**
 * 弹药盒方块，所有弹药盒共用这一个类。
 *
 * <p>与具体盒子无关的东西都通过构造函数注入：
 * <ul>
 *   <li>{@link AmmoBoxShape}：选框尺寸，每种盒子按自己的模型算一份；</li>
 *   <li>{@code damageBudget}：单次交互的总伤害预算，取自该盒子自己的配置项
 *       （「创造模式」变体与对应原件共用同一节）；</li>
 *   <li>{@code consumable}：交互后是否摧毁方块。普通弹药盒为 {@code true}，
 *       「创造模式」变体为 {@code false}，可反复补给。</li>
 * </ul>
 *
 * <p><b>本模组的模型约定：正面朝向 -Z（北）。</b>因此 blockstate 里 {@code facing=north}
 * 直接对应不旋转的模型（y=0），放置时正面朝向玩家。新模型也必须遵守这个约定。
 *
 * <p>{@link #getShape} 返回的选框同时用于<b>描边高亮</b>和<b>碰撞箱</b>。
 * 不需要额外覆写 {@code getCollisionShape}——{@code BlockBehaviour} 的默认实现本身就是
 * {@code hasCollision ? state.getShape(...) : Shapes.empty()}，所以在方块属性里不调用
 * {@code noCollission()} 之后，碰撞箱会自动等于选框形状，且每种盒子各自不同。
 */
public class AmmoBoxBlock extends HorizontalDirectionalBlock {

    /** facing -> 选框，构造时由 {@link AmmoBoxShape#buildShapes()} 生成。 */
    private final Map<Direction, VoxelShape> shapes;

    /**
     * 查「逐枪覆盖值」时用的键，例如 {@code ammo_box_1}。
     *
     * <p><b>注意它是本档的基础 id，不是本方块自己的注册 id。</b>同尺寸的普通 / 创造模式 /
     * 自动补货三个变体共用同一个键，所以「创造模式」变体这里是 {@code ammo_box_1}，
     * 而不是 {@code ammo_box_1_creative}。原因见 {@code AmmoBoxTypes.Type#gunSupplyConfigId()}。
     *
     * <p>只用于 {@code GunSupplyOverride} 的查表，不参与注册、配置节名、本地化等任何其它用途。
     */
    private final String supplyConfigId;

    /**
     * 本方块单次交互提供的总伤害预算。
     *
     * <p>从构造时注入而不是直接读配置类，这样每种弹药盒可以各用一份配置项，
     * 方块本身也不需要知道配置结构。每次交互都调 {@code get()}，所以改配置后立即生效。
     *
     * <p>「创造模式」变体注入的是与对应原件<b>相同</b>的配置项，所以两者永远同步。
     */
    private final Supplier<Integer> damageBudget;

    /**
     * 交互后是否摧毁本方块。
     *
     * <p>普通弹药盒为 {@code true}：补给成功就把方块消耗掉。
     * 「创造模式」变体为 {@code false}：方块留在原地，可以反复补给。
     * 除此之外两者流程完全相同——预算判定、拒绝提示、发放、聊天提示、音效都一致。
     */
    private final boolean consumable;

    /**
     * 交互成功时 dust 粒子用的颜色，由变体决定，见 {@link SupplyEffects#interactionColor}。
     *
     * <p>在构造时注入而不是让方块自己判断变体，这样方块不需要知道"我属于哪种变体"，
     * 只负责"用这个颜色撒粒子"。三个变体的颜色是普通=绿、创造=紫、自动补货=红。
     */
    private final Vector3f supplyParticleColor;

    public AmmoBoxBlock(Properties properties, String supplyConfigId, Supplier<Integer> damageBudget,
                        AmmoBoxShape shape, boolean consumable, Vector3f supplyParticleColor) {
        super(properties);
        this.supplyConfigId = supplyConfigId;
        this.damageBudget = damageBudget;
        this.shapes = shape.buildShapes();
        this.consumable = consumable;
        this.supplyParticleColor = supplyParticleColor;
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 正面朝向玩家，和箱子 / 熔炉一致
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return this.shapes.get(state.getValue(FACING));
    }

    /**
     * 一次<b>有效的补给交互</b>的上下文，由 {@link #resolveSupply} 产出。
     *
     * @param gun          手持枪械
     * @param heldStack    手持物品
     * @param supplyConfig 枪包作者在枪械 data 里写的补给配置
     * @param gunData      枪械数值
     */
    protected record SupplyContext(IGun gun, ItemStack heldStack,
                                   GunSupplyOverride.GunSupplyConfig supplyConfig, GunData gunData) {
    }

    /**
     * 判断并解析「这次交互是不是一次有效的补给尝试」。
     *
     * <p>返回 {@code null} 表示与补给无关，调用方应当静默 {@code PASS}。三种情况：
     * <ul>
     *   <li>手持的不是 TACZ 枪械（空手、拿方块、拿弓……）；</li>
     *   <li>枪械数据缺失（{@code TimelessAPI.getCommonGunIndex} 取不到）；</li>
     *   <li>枪包作者在枪械 data 里用 {@code ammo_supply_box.active = false} 禁用了它。</li>
     * </ul>
     *
     * <p>抽成 {@code protected} 是为了让子类（{@code AutoRestockAmmoBoxBlock}）能用
     * <b>完全相同的口径</b>判断"该不该给玩家『正在补货』的反馈"——否则空手、或者拿着
     * 被禁用的枪去点冷却中的盒子，也会收到冷却提示。判定与取值一次做完，
     * 两个调用方不会出现口径分歧。
     */
    protected static SupplyContext resolveSupply(Player player, InteractionHand hand) {
        ItemStack heldStack = player.getItemInHand(hand);
        IGun gun = IGun.getIGunOrNull(heldStack);
        if (gun == null) {
            return null;
        }

        Optional<CommonGunIndex> gunIndex = TimelessAPI.getCommonGunIndex(gun.getGunId(heldStack));
        if (gunIndex.isEmpty()) {
            return null;
        }

        // 枪包作者在枪械 data 里写的配置；没写就是默认值（启用 + 无覆盖）
        GunSupplyOverride.GunSupplyConfig supplyConfig = GunSupplyOverride.getConfig(gunIndex.get());

        // active = false：该枪被明确禁止从弹药盒获取子弹。
        // 按需求这种情况什么都不做——不给弹药、不响、不摧毁方块，也不发聊天提示。
        if (!supplyConfig.active()) {
            return null;
        }

        return new SupplyContext(gun, heldStack, supplyConfig, gunIndex.get().getGunData());
    }

    /**
     * 手持 TACZ 枪械交互时，按枪械单发伤害换算出弹药发放量，把弹药交给玩家，并给交互者发一条
     * 说明"从哪个盒子拿了多少"的聊天提示。成功之后是否把方块移除，取决于构造时注入的
     * {@code consumable}：
     * <ul>
     *   <li>普通弹药盒：静默移除方块（不掉落、无原型破坏粒子与音效）；</li>
     *   <li>「创造模式」与「自动补货」变体：方块保留，可以反复交互。</li>
     * </ul>
     *
     * <p>发放量遵循本方块配置的总伤害预算：单发伤害越低给得越多。单发伤害超过预算则拒绝交互
     * （返回 PASS；对创造模式变体而言就是不产生任何效果）。
     *
     * <p>补给有两个去向，由枪械自己决定：普通枪拿到弹药物品；处于<b>虚拟备弹</b>
     *（DummyAmmo）模式的枪则把同样数量的「发数」写进枪械自带的备弹池。两者共用同一套预算
     * 换算，详见 {@link TaczAmmoHelper#getGrantAmount}。
     *
     * <p>交互反馈共三路：聊天提示（只有交互者看得到）、自定义补给音效（同样只有交互者听得到），
     * 以及彩色颗粒 + 白色烟雾粒子（附近所有玩家都能看到，颜色按变体区分，
     * 见 {@link SupplyEffects#interactionColor}）。
     *
     * <p>注意 TACZ 会取消持枪时的原版右键（{@code ClientPreventGunClick} 会 cancel
     * {@code InteractionKeyMappingTriggered}），所以这个方法实际是由 TACZ 的交互键
     * （默认 O）触发的，且方块必须在 {@code tacz:interact_key/whitelist} 方块标签里，
     * 见 {@code data/tacz/tags/blocks/interact_key/whitelist.json}。
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide) {
            // 客户端不做任何实际逻辑，只决定"要不要吞掉这次交互"。
            //
            // 手持 TACZ 枪械 → SUCCESS：这次是补给交互，由服务端处理，客户端只做摆动预测。
            // 其它情况      → 必须 PASS，把交互交还给原版。
            //
            // 为什么不能无条件返回 SUCCESS：{@code Minecraft#startUseItem} 里只要
            // {@code useItemOn} 返回的动作 {@code consumesAction()} 就直接 return，
            // 不再走 {@code useItem}（放方块）那条路。于是「拿着方块右键弹药盒」时
            // 客户端既不本地预测放置、也不本地播放放置音效；而服务端广播放置音效时
            // 又把放置者排除掉了（{@code BlockItem} 把 player 作为 except 传进 playSound，
            // {@code ServerLevel#playSeededSound} 最终走 playerList.broadcast(except, ...)）。
            // 两头一排除，玩家就会看到"方块放上了但没有声音"。
            return IGun.getIGunOrNull(player.getItemInHand(hand)) != null
                    ? InteractionResult.SUCCESS
                    : InteractionResult.PASS;
        }

        // 三步前置判断（是不是枪 / 枪数据在不在 / 枪包有没有禁用）统一走 resolveSupply，
        // 子类也用同一个方法判断"该不该给玩家反馈"，两边口径完全一致
        SupplyContext supply = resolveSupply(player, hand);
        if (supply == null) {
            // 手持的不是 TACZ 枪械、枪械数据缺失、或被枪包 active=false 禁用
            // → 交还给原版逻辑，不要吞掉这次交互
            return InteractionResult.PASS;
        }

        IGun gun = supply.gun();
        ItemStack heldStack = supply.heldStack();
        GunSupplyOverride.GunSupplyConfig supplyConfig = supply.supplyConfig();
        GunData gunData = supply.gunData();

        // 枪包显式配置的数量会再乘上一层管理员乘数（见 AmmoSupplyBoxConfig#gunPackAmmoMultiplier）。
        // 没配置的枪走预算算法，乘数对它无效。
        int amount = TaczAmmoHelper.getGrantAmount(gunData,
                supplyConfig.amounts().get(this.supplyConfigId),
                this.damageBudget.get(),
                AmmoSupplyBoxConfig.gunPackAmmoMultiplier());
        if (amount <= 0) {
            // 预算不足以补给这把武器 → 拒绝交互，不消耗弹药盒
            //
            // displayClientMessage 的第二个参数 false 表示发到聊天框（true 则是动作栏），
            // 它只发给调用它的这名玩家，其他玩家看不到。
            player.displayClientMessage(
                    Component.translatable("message.ammo_supply_box.insufficient")
                            .withStyle(ChatFormatting.RED),
                    false);
            return InteractionResult.PASS;
        }

        // 本方块自己的本地化名字（block.ammo_supply_box.ammo_box_N），用于补给提示。
        // Block#getName() 就是 Component.translatable(getDescriptionId())，返回的已经是
        // Component，所以能被翻译参数直接采用（TranslatableContents#getArgument 对
        // Component 是原样使用，不会退化成 toString）。
        Component boxName = state.getBlock().getName();

        if (gun.useDummyAmmo(heldStack)) {
            // 虚拟备弹（DummyAmmo）枪械：补给写进枪械自带的备弹池，而不是发弹药物品。
            //
            // 这类枪装填时只扣池子、完全不读背包，所以给弹药物品等于白发——玩家会看到方块
            // 消失、声音也响了，背包里却是一堆永远用不上的子弹；池子为 0 时更糟，拿着一背包
            // 子弹照样装填不了。详见 TaczAmmoHelper#getDummyReserveSpace 的类注释。
            //
            // 池子装不下这次补给（含池子已满）时同样拒绝，好让「每次成功交互都恰好给出 amount
            // 的补给」这条不变式继续成立，也避免花了方块却只补到一点点。
            //
            // 注意这里只 add、从不 set：addDummyAmmoAmount 对普通枪是空操作，所以本方块无法
            // 把一把普通枪变成虚拟备弹枪。那是不可逆的（DummyAmmo 标签只写不删），刻意不做。
            if (TaczAmmoHelper.getDummyReserveSpace(gun, heldStack) < amount) {
                player.displayClientMessage(
                        Component.translatable("message.ammo_supply_box.dummy_reserve_full")
                                .withStyle(ChatFormatting.RED),
                        false);
                return InteractionResult.PASS;
            }

            TaczAmmoHelper.supplyDummyAmmo(gun, heldStack, amount);

            // 这条提示是必需的：虚拟备弹模式下玩家背包里不会多出任何东西，只靠音效会让玩家
            // 以为补给失败了。
            player.displayClientMessage(
                    Component.translatable("message.ammo_supply_box.dummy_supplied", boxName, amount)
                            .withStyle(ChatFormatting.GREEN),
                    false);
        } else {
            ResourceLocation ammoId = gunData.getAmmoId();
            if (ammoId == null) {
                // 枪械数据异常，给不出东西，也不提示（这不是"补给不足"）
                return InteractionResult.PASS;
            }

            TaczAmmoHelper.giveAmmo(player, ammoId, amount);

            // 让玩家明确知道拿到了多少。文案里的单位是"份"而不是"发"：弹匣制枪一份等于一发，
            // fuel 枪一份等于一个满弹匣，写死"发"在 fuel 枪上就是错的。
            player.displayClientMessage(
                    Component.translatable("message.ammo_supply_box.supplied", boxName, amount)
                            .withStyle(ChatFormatting.GREEN),
                    false);
        }

        // 交互粒子：与「自动补货完成」同款观感（彩色颗粒 + 白色烟雾），只有颜色按变体区分
        //（普通=绿 / 创造=紫 / 自动补货=红）。附近所有玩家都能看到。
        //
        // 必须在移除方块之前调用——普通弹药盒的方块在这之后就不存在了，
        // 而粒子是拿方块自己的选框范围算中心和扩散半径的。
        SupplyEffects.spawn(level, pos, state, this.supplyParticleColor);

        // 只发给交互的这一名玩家，其他玩家听不到：
        // Player.playNotifySound 在 ServerPlayer 里被覆写成"只往该玩家自己的连接发
        // ClientboundSoundPacket"，而不是走广播的 Level.playSound。
        // 具体播哪个音频是随机的，由 sounds.json 里同一事件下的多个条目决定。
        player.playNotifySound(ModSounds.AMMO_BOX_SUPPLY.get(), SoundSource.BLOCKS, 1.0F, 1.0F);

        // 普通弹药盒：交互过就算已发放，用 removeBlock 静默移除（无粒子、无音效、无掉落）。
        //
        // 这里刻意不用 destroyBlock：它内部会 levelEvent(2001, pos, Block.getId(state))，
        // 客户端收到后既要播方块破坏的碎屑粒子、又要播原版破坏音。而上方已经播了我们自己那套
        // 彩色颗粒与白色烟雾，两者叠在一起过于热闹。改用 removeBlock 之后，一次交互的反馈
        // 就只剩三样：聊天提示、自定义补给音、我们自己那套粒子。
        //
        // removeBlock 的第二个参数是 isMoving——传 false 表示「不是被活塞推动导致的移除」，
        // 它只把方块换成流体对应的方块（没做含水，所以就是空气），全程没有任何特效；
        // 而且不走 Block.dropResources，所以弹药盒不会被掉出来
        //（我们虽然给它配了 loot table，这条路径用不上）。
        //
        // 「创造模式」与「自动补货」变体（consumable=false）跳过这一步，方块留在原地。
        if (this.consumable) {
            level.removeBlock(pos, false);
        }
        return InteractionResult.SUCCESS;
    }

    // rotate / mirror 由 HorizontalDirectionalBlock 提供，无需覆写。
}
