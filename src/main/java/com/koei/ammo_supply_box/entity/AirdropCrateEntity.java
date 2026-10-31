package com.koei.ammo_supply_box.entity;

import com.koei.ammo_supply_box.registry.ModEntities;
import com.koei.ammo_supply_box.registry.ModSounds;
import com.koei.ammo_supply_box.util.SupplyEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 空投箱的下落实体：原版下落方块，但<b>重力是原版的八倍</b>，落地会发声，
 * 并且会像<b>铁砧</b>一样砸伤被命中的实体。
 *
 * <h2>为什么要自定义一个实体</h2>
 * 原版 {@link FallingBlockEntity#tick} 把重力写死成常量 {@code -0.04}，没有可以覆写的钩子；
 * 而 {@code FallingBlockEntity.fall(...)} 又是唯一能指定方块状态的入口（构造器私有，且
 * {@code blockState} 只有 getter），它固定 {@code new FallingBlockEntity(...)}、返回的必然是基类。
 * 想改重力只能另起一个类型。
 *
 * <p>顺带解决了用 {@code fall(...)} 时没法避免的一件事：
 * <b>{@code fall(...)} 会把目标坐标上的方块换成空气</b>（那是给"沙子变实体"用的），
 * 本类自己 placement，绝不碰世界。
 *
 * <h2>为什么自己接管落地放置</h2>
 * 原版 {@code FallingBlockEntity#tick} 的落点判定用的是 {@code blockpos = blockPosition()},
 * 也就是<b>箱子底部所在的那一格</b>。只要那一格<b>被占用且不可替换</b>，判定就失败
 * （原版会把方块当物品丢出来）。而"被占用"有两种成因：
 * <ul>
 *   <li><b>无碰撞箱</b>的方块（火把、压力板、红石线、拉杆、绊线、告示牌、花草…，注册时带
 *       {@code noCollission()}）：箱子<b>直接穿过</b>它们，落到它们下面那块支撑方块的顶面，
 *       于是落点格正是它们自己那一格；</li>
 *   <li><b>有碰撞箱但很矮</b>的方块（地毯、花盆、蜡烛、灯笼、按钮、附魔台、床…）：
 *       箱子停在它们的碰撞箱顶部（也就是那一格内部），落点格同样是它们自己那一格。</li>
 * </ul>
 * 两种情况殊途同归 —— 所以"有没有碰撞箱"本身并不足以分类（火把没有、地毯有，
 * 但它们同样需要处理），真正该问的是<b>箱子会不会落进那一格</b>：
 * 只有<b>碰撞箱顶部低于满格（&lt; 1.0）</b>或<b>没有碰撞箱</b>的方块才会被落进；
 * 顶部正好到 1.0 的方块（门、竖着的活板门、上半活板门、栅栏、锁链…）箱子落在它<b>上面一格</b>，
 * 落点格是空气，因此永远不会被砸——这与落在完整方块上没有任何区别。
 *
 * <p>于是本类调用原版 {@code disableDrop()}（把父类的 {@code cancelDrop} 置 true）：
 * 父类落地后<b>不放置、也不掉物品</b>，直接 {@code discard()}；放置由
 * {@link #onLanded()} / {@link #placeCrate()} 接管。
 *
 * <h2>压碎判据：「不可替换」或「体积 &lt; 25%」</h2>
 * 落点那一格被占时，只要满足<b>任意一条</b>就把阻挡者<b>砸碎</b>
 * （走原版破坏流程，它自己变成掉落物），腾出落点格按原版方式放进去：
 * <ul>
 *   <li><b>不可替换</b>：几乎一切实体方块都满足，包括附魔台、床、台阶、讲台、蛋糕
 *       ——<b>这是刻意的</b>，160 格高砸下来的箱子把它们砸成掉落物，力量感优先于"保护玩家家具"；</li>
 *   <li><b>体积 &lt; {@link #CRUSH_VOLUME_RATIO}</b>：覆盖那些"可替换的薄片"
 *       （雪层、花草、火），它们会被砸碎并掉落自身，而不是被静默顶掉。</li>
 * </ul>
 * 只有<b>空气</b>与<b>流体</b>（水/岩浆）不砸：前者没什么可砸，后者交给 {@code setBlock} 顶掉，
 * 对流体调 {@code destroyBlock} 会引出流体回填之类的怪行为。
 *
 * <p>注意"不可替换"这一条实际上已经覆盖了绝大多数方块，所以体积阈值现在只在
 * "可替换的薄片"上有区分意义。若哪天想让附魔台这类实心家具重新免疫，
 * 把代码里的 {@code ||} 改回 {@code &&}，并删掉 {@code !liquid()} 之外的多余守卫即可。
 *
 * <h2>方块状态怎么注入</h2>
 * {@code blockState} 是 private 且没有 setter，唯一的写入途径是
 * {@link FallingBlockEntity#readAdditionalSaveData}（它是 public 的），所以
 * {@link #spawn} 里用一份只含 {@code BlockState} 的标签把状态灌进去。
 * 注意那份标签还必须带上 {@code CancelDrop = true}——{@code readAdditionalSaveData} 里
 * 是 {@code getBoolean("CancelDrop")}，键不存在时会读成 false，把构造器里设好的值冲掉。
 */
public class AirdropCrateEntity extends FallingBlockEntity {

    /**
     * 额外的一份重力。
     *
     * <p>父类每 tick 自己会 {@code add(0, -0.04, 0)}，这里在 {@code super.tick()} 之前再加
     * <b>-0.04</b>，两份在同一个 tick 内累加后再结算位移 → <b>合计 -0.08/tick，是原版的 2 倍</b>。
     *
     * <p>模拟结果（含父类末尾那次 {@code ×0.98} 阻力）：160 格下落需要 <b>80 tick（约 4 秒）</b>，
     * 原版重力下同样高度要 126 tick。这个速度能看清整条坠落轨迹，又明显快于原版下落方块。
     */
    private static final double EXTRA_GRAVITY = 0.04D;

    /** 每下落 1 格造成的伤害，与原版铁砧一致。 */
    private static final float FALL_DAMAGE_PER_BLOCK = 2.0F;

    /**
     * 单次落地的伤害上限：<b>不设上限</b>。
     *
     * <p>原版铁砧是 {@code 40}（=20 颗心），这里取 {@link Integer#MAX_VALUE} 把封顶去掉，
     * 伤害就按 {@link #FALL_DAMAGE_PER_BLOCK} 线性随下落格数增长：
     * <ul>
     *   <li>10 格 → 18 点；</li>
     *   <li>40 格 → 78 点；</li>
     *   <li><b>160 格（本模组的空投高度）→ 318 点（约 159 颗心）</b>。</li>
     * </ul>
     * 也就是落点正下方的生物必定被秒杀；护甲、抗性等减伤照常生效，创造与旁观者由原版过滤器排除。
     *
     * <p>想恢复封顶就把它改回一个正数（例如 {@code 40} 保持与铁砧一致），父类的
     * {@code FallingBlockEntity#causeFallDamage} 会取 {@code min(floor(格数 × 每格伤害), 上限)}。
     */
    private static final int FALL_DAMAGE_MAX = Integer.MAX_VALUE;

    /**
     * 落地震颤：影响半径（格）。
     *
     * <p>半径内按<b>平方</b>衰减（{@code (1 - d/r)²}）：贴脸最猛，靠边界迅速归零。
     * 20 格意味着大约 6 格内是半强度以上——空投是"大事件"，半个视野范围都该有反馈。
     */
    private static final float SHAKE_RADIUS = 20.0F;

    /**
     * 落地震颤：冲击强度。
     *
     * <p>换成弹簧-阻尼模型后，这个值的语义是<b>注入的角速度冲击</b>，不是直接的位移角度。
     * 三轴各自独立取份量（每轴保底 30%），实测标定：<b>典型轴峰值位移 ≈ 0.16 × 强度</b>、
     * 最猛的一轴约 0.25 ×、三轴合成模长峰值约 0.28 ×：
     * <ul>
     *   <li>80 → 单轴约 13 度、合成约 22 度（当前取值，贴脸三轴一起甩且方向在游走）；</li>
     *   <li>40 → 单轴约 6.5 度、合成约 11 度（上一版取值，偏轻）；</li>
     *   <li>20 → 单轴约 3 度（轻微）。</li>
     * </ul>
     * 注意 {@code CameraShakeHandler.MAX_DISPLACEMENT} 是 60 度，比最猛单轴还高三倍余量。
     */
    private static final float SHAKE_MAGNITUDE = 80.0F;

    /** 是否已经处理过落地（放置 + 发声 + 碎屑 + 震颤，防止重复触发）。 */
    private boolean landedHandled;

    /** 落点那一格被占时，最多往上找几格再落。 */
    private static final int LANDING_SEARCH = 4;

    /**
     * 压碎判据之一：「碰撞箱体积占整格的比例」低于这个值就算经不起砸。
     *
     * <p>它与「不可替换」是<b>或</b>的关系（见 {@link #placeCrate()}），所以这个阈值
     * 实际只在<b>可替换</b>的方块上有区分意义——也就是把雪层、花草、火这类
     * "原版会被静默顶掉"的薄片也纳入砸碎范围，让它们掉落自身。
     *
     * <p>为什么用体积而不是高度：高度分不开「薄片」和「矮家具」——压力板高 1/16、
     * 床高 9/16、附魔台高 12/16，按高度切会把附魔台一起切掉。
     * 体积则能干净地分成两簇（实测原版数值）：
     * <ul>
     *   <li><b>≤ 19%</b>：中继器 12.5%、雪层 12.5%、绊线 9.4%、拉杆 7.0%、灯笼 6.9%、
     *       告示牌/地毯 6.3%、花盆 5.3%、压力板 4.8%、火把 3.9%、红石线 2.4%、
     *       按钮 1.2%、蜡烛 0.6%、以及<b>所有无碰撞箱方块（0%）</b>；</li>
     *   <li><b>≥ 31%</b>：讲台 31.3%、蛋糕 38.3%、下半台阶 50%、床 56.3%、附魔台 75%。</li>
     * </ul>
     * 中间 19%~31% 是空档，阈值放在这一段里的任何值结果都一样，取 25%。
     *
     * <p><b>注意体积不是"会不会被砸"的决定因素</b>：真正决定的是<b>箱子会不会落进那一格</b>，
     * 而那取决于碰撞箱<b>顶部高度</b>——顶部到满格（1.0）的方块，箱子落在它<b>上面一格</b>，
     * 落点格是空气，永远不会被砸。典型例子：
     * <ul>
     *   <li><b>门</b>（碰撞箱 3/16 宽 × 满高）：体积只有 18.75%，但箱子落在它顶上 → <b>不砸</b>；</li>
     *   <li><b>打开（竖着）的活板门</b>（3/16 宽 × 满高）：同理 → <b>不砸</b>；</li>
     *   <li><b>关闭·上半的活板门</b>（13~16/16，顶部正好 1.0）：同样 → <b>不砸</b>；</li>
     *   <li>只有<b>关闭·下半的活板门</b>（0~3/16）才会被箱子落进那一格、进而被砸碎。</li>
     * </ul>
     * 想让"落在门/竖着的活板门顶上时也把它砸掉"，需要另一条完全不同的规则
     * （判定脚下那格的 XZ 占位再让箱子下沉一格）。<b>试过、实测无效、已撤除</b>：
     * 那条实现依赖"脚底高度正好等于支撑方块顶部"这个浮点条件下以容差判定，
     * 实测门/活板门/栏杆都未能触发，因此不保留。现在的行为是它们完好、箱子落在它们上面。
     *
     * <p>将来若想让 ≥31% 的实心家具重新免疫压碎，把 {@code placeCrate()} 里的
     * {@code ||} 改成 {@code &&}，这个阈值就恢复成"与"关系下的决定性条件。
     */
    private static final double CRUSH_VOLUME_RATIO = 0.25D;

    /**
     * 下落尾迹：是否开启。
     *
     * <p>关掉就完全没有尾迹粒子。
     */
    private static final boolean FALL_TRAIL_ENABLED = true;

    /**
     * 下落尾迹用的粒子。
     *
     * <p>先用原版营火的烟粒子试效果：{@link ParticleTypes#CAMPFIRE_COSY_SMOKE}
     * 是白色、体积感明显、并且自带缓慢上升速度的一种烟——箱子往下掉、烟慢慢往上飘，
     * 于是在身后拖出一条自然散开的烟柱，而不是一条生硬的直线。
     *
     * <p>想让烟更浓可以换 {@link ParticleTypes#CAMPFIRE_SIGNAL_SMOKE}（信号烟，上升更快更高），
     * 也可以换成 {@code LARGE_SMOKE} / {@code CLOUD} 之类对比效果。
     */
    private static final ParticleOptions FALL_TRAIL_PARTICLE = ParticleTypes.CAMPFIRE_COSY_SMOKE;

    /** 每 tick 撒几颗。20 tick/秒 × 6 颗 = 每秒 120 颗，整段约 4 秒的下落共约 480 颗。 */
    private static final int FALL_TRAIL_COUNT = 6;

    /** 每颗的随机散布半径（格），让烟柱有体积感而不是一条线。 */
    private static final double FALL_TRAIL_SPREAD = 0.25D;

    /** 初速度。营火烟自己会往上飘，这里给 0 让它"原地起步"。 */
    private static final double FALL_TRAIL_SPEED = 0.0D;

    /**
     * 尾迹粒子是否按"远距离"广播。
     *
     * <p>普通粒子只发给 32 格内的玩家，而箱子是从 <b>160 格高空</b>往下掉的——
     * 玩家站在地面上时，箱子大半段路程都在 32 格以外，开这个开关才能在远处看到
     * 那条从天上垂下来的烟柱。代价是每 tick 给全维度玩家发一个小包（几十字节），
     * 单次空投只有约 80 个包，可以接受。
     */
    private static final boolean FALL_TRAIL_LONG_DISTANCE = true;

    public AirdropCrateEntity(EntityType<? extends AirdropCrateEntity> type, Level level) {
        super(type, level);
        // 下面两个标记父类的私有构造器（fall() 用的那个）会置上，这里走公开构造器必须自己补：
        // blocksBuilding = true 才会与方块碰撞；漏了会直接穿过地面。
        this.blocksBuilding = true;
        // 落点被占时不掉出方块物品（空投箱是拿不到的，见类注释）。
        this.dropItem = false;
        // 关键：让父类<b>不要自己尝试放置</b>。落地判定与放置全部由本类的 onLanded() 接管，
        // 原因见类注释「为什么自己接管落地放置」。
        //
        // 用的是原版公开方法 disableDrop()：它把父类内部的 private 字段 cancelDrop 置 true，
        // 于是落地分支变成"只 discard()、既不放置也不掉物品"。方法名容易误会成"禁止掉落物品"，
        // 实际语义是"这一下落什么都不做"。
        this.disableDrop();
        // 砸伤实体：参数取自原版铁砧（{@code AnvilBlock#falling} 里是
        // {@code setHurtsEntities(2.0F, 40)}），但<b>上限被解除</b>（见 FALL_DAMAGE_MAX）。
        // 结算全部交给父类 {@code FallingBlockEntity#causeFallDamage}：它只在落地那一下，对落在自己
        // 碰撞箱里的 **活着的生物** 造成 fallingBlock 伤害；创造模式与旁观者由原版过滤器自动排除，
        // 我们不需要再写任何判定。160 格下落约 318 点（159 颗心），落点正下方的生物必死。
        this.setHurtsEntities(FALL_DAMAGE_PER_BLOCK, FALL_DAMAGE_MAX);
    }

    /**
     * 在 {@code pos} 处生成一个下落中的空投箱。
     *
     * <p>调用方必须保证 {@code pos} 是可放置的格子：本方法不会清空它，
     * 但实体从方块内部开始下落会卡住。
     */
    public static AirdropCrateEntity spawn(ServerLevel level, BlockPos pos, BlockState state) {
        AirdropCrateEntity entity = new AirdropCrateEntity(ModEntities.AIRDROP_CRATE.get(), level);

        // 唯一的方块状态注入口，见类注释。
        // CancelDrop 必须跟着一起写：readAdditionalSaveData 用 getBoolean 读它，
        // 键不存在时会读成 false，直接把构造器里设好的 cancelDrop 冲掉。
        CompoundTag tag = new CompoundTag();
        tag.put("BlockState", NbtUtils.writeBlockState(state));
        tag.putBoolean("CancelDrop", true);
        entity.readAdditionalSaveData(tag);

        // 与 FallingBlockEntity.fall(...) 一致的落点：格子中心、方块底面
        entity.setPos(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setStartPos(entity.blockPosition());

        level.addFreshEntity(entity);
        return entity;
    }

    /**
     * 先补一份重力再交给父类，返回后处理"落地"这件事。
     *
     * <p>重力顺序很重要：必须<b>在</b> {@code super.tick()} 之前加，这样这一 tick 的重力是
     * 「父类的 -0.04 + 这里的 -0.04」一起参与位移结算，才是真正的两倍重力。
     * 加在之后的话，这一份要等下一 tick 才生效，且会被父类末尾的 {@code ×0.98} 先衰减一次。
     *
     * <p>落地处理只能写在这里：父类在落地那一 tick 里 {@code setBlock} 然后立刻 {@code discard()}
     * 自己，没有任何回调点，所以只能在 {@code super.tick()} 返回后检查"是不是已经被移除了"。
     */
    @Override
    public void tick() {
        // 尾迹放在最前面：用的是"这一 tick 开始时的位置"，于是烟柱与箱子的实际轨迹
        // 一一对应；落地那一 tick 也会留下最后一缕。
        spawnFallTrail();

        this.setDeltaMovement(this.getDeltaMovement().add(0.0D, -EXTRA_GRAVITY, 0.0D));
        super.tick();

        // 父类的 cancelDrop 分支在落地那一 tick 直接 discard() 自己，所以这里能立刻看到。
        // onGround() 这道判断是为了排除"非落地原因被移除"（/kill、掉出世界、超时）——
        // 那些情况不应该凭空变出一个空投箱。
        if (this.isRemoved() && !this.landedHandled) {
            this.landedHandled = true;
            if (this.onGround()) {
                onLanded();
            }
        }
    }

    /**
     * 下落尾迹：每 tick 在箱子当前位置撒一小簇烟粒子。
     *
     * <p>只在服务端做，由 {@code sendParticles} 广播——客户端不需要也不应该自己再生成一遍，
     * 否则每个客户端看到的位置都会不一样。
     *
     * <p>取的是箱子<b>包围盒中心</b>而不是脚底，这样烟是从箱体中间冒出来的，
     * 更像"这个东西在摩擦生烟"，而不是从底部漏出来。
     *
     * <p><b>API 注意</b>：原版只有两种 {@code sendParticles}——
     * 一种是"广播给 32 格内的玩家、不能强制"（{@code (T, double...)}），
     * 另一种是"指定单个玩家 + 可强制"（{@code (ServerPlayer, T, boolean, ...)}）。
     * 没有"广播 + 强制"的重载，所以想要全维度可见就得自己遍历玩家逐个发。
     */
    private void spawnFallTrail() {
        if (!FALL_TRAIL_ENABLED || this.level().isClientSide) {
            return;
        }
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        double x = this.getX();
        double y = this.getY() + this.getBbHeight() * 0.5D;
        double z = this.getZ();

        if (FALL_TRAIL_LONG_DISTANCE) {
            // ServerLevel#players() 只包含本维度的玩家
            for (ServerPlayer player : serverLevel.players()) {
                serverLevel.sendParticles(player, FALL_TRAIL_PARTICLE, true,
                        x, y, z, FALL_TRAIL_COUNT,
                        FALL_TRAIL_SPREAD, FALL_TRAIL_SPREAD, FALL_TRAIL_SPREAD,
                        FALL_TRAIL_SPEED);
            }
        } else {
            serverLevel.sendParticles(FALL_TRAIL_PARTICLE,
                    x, y, z, FALL_TRAIL_COUNT,
                    FALL_TRAIL_SPREAD, FALL_TRAIL_SPREAD, FALL_TRAIL_SPREAD,
                    FALL_TRAIL_SPEED);
        }
    }

    /**
     * 落地：自己放置方块，然后再做反馈（音效 / 碎屑 / 烟雾 / 震颤）。
     *
     * <p>为什么必须自己放置，见类注释「为什么自己接管落地放置」。
     */
    private void onLanded() {
        if (this.level().isClientSide) {
            return;
        }
        BlockPos placedPos = placeCrate();
        if (placedPos == null) {
            // 实在没地方放：这次空投作废。空投箱按设计不掉物品，所以不会在地上留垃圾。
            return;
        }

        playLandingSound(placedPos);

        // 碎屑取「落点<b>下方</b>那一格」的方块——箱子是从上方砸到它上面的，
        // 炸开的理应是脚下这块地面的碎屑，而不是空投箱自己的木屑。
        // 极少数情况（下方是空气）退回用箱子自己的状态兜底：
        // BLOCK 粒子必须有一个非空气的方块状态，否则找不到贴图。
        BlockState ground = this.level().getBlockState(placedPos.below());
        SupplyEffects.spawnLandingDebris(this.level(), placedPos, ground.isAir() ? getBlockState() : ground);

        // 与下落尾迹同款的营火烟，落地瞬间炸开一大团
        SupplyEffects.spawnLandingSmoke(this.level(), placedPos);

        AirdropShakeEntity.spawn(this.level(), placedPos, SHAKE_RADIUS, SHAKE_MAGNITUDE);
    }

    /**
     * 把空投箱放到落点，返回实际放置的坐标；放不下返回 {@code null}。
     *
     * <p><b>放置位置与原版完全一致</b>：原版 {@code FallingBlockEntity} 只会尝试
     * {@code blockPosition()}（箱子底部所在那一格），判定条件是
     * <ol>
     *   <li>那一格 {@code canBeReplaced(...)}（能不能顶掉）；</li>
     *   <li>本方块 {@code canSurvive(...)}（放得稳不稳）——沙/铁砧/本方块都是恒真，
     *       但原版确实会查，这里照抄以保持一致。</li>
     * </ol>
     * 两者都满足就放置（= 原版成功路径）。
     *
     * <p>与原生不同的只有"失败之后干什么"。原版失败时会把<b>下落方块自己</b>变成掉落物
     * （{@code spawnAtLocation}），而空投箱按设计拿不到物品，所以这里的替代方案是
     * <b>按碰撞箱体积分两档</b>：
     * <ul>
     *   <li><b>体积 &lt; {@link #CRUSH_VOLUME_RATIO}</b>（≤19%，含所有无碰撞箱方块）→
     *       <b>压碎阻挡者</b>（{@code destroyBlock(…, true)}，它自己变成掉落物），
     *       腾出落点格后按原版方式放进去 —— 空投落点不变；</li>
     *   <li><b>体积足够</b>（≥31%：附魔台 / 床 / 台阶 / 讲台 / 蛋糕）→ <b>不压碎</b>，
     *       往上找最近的可放置格（{@link #LANDING_SEARCH} 格以内）落下。
     *       这一档是刻意保留的：空中的箱子不该把附魔台砸成掉落物，也不该让整次空投作废。</li>
     * </ul>
     */
    private BlockPos placeCrate() {
        BlockPos landingPos = this.blockPosition();
        BlockState crateState = this.getBlockState();

        BlockState occupied = this.level().getBlockState(landingPos);
        // 判据 = 「不可替换」或「体积 < 25%」，字面意义上的"或"：
        //   ① 不可替换 → 几乎所有实体方块都满足，包括附魔台/床/台阶/讲台/蛋糕
        //      （刻意如此：160 格砸下来的箱子把它们砸成掉落物）；
        //   ② 体积 < 25% → 覆盖"可替换的薄片"（雪层、花草、火），让它们也掉落自身。
        // 只有空气与流体跳过：空气没什么可砸，流体交给下面的 setBlock 顶掉
        //（对水/岩浆调 destroyBlock 会引出流体回填之类的怪行为）。
        if (!occupied.isAir() && !occupied.liquid()
                && (!occupied.canBeReplaced()
                        || collisionVolumeRatio(occupied, landingPos) < CRUSH_VOLUME_RATIO)) {
            // 压碎：走原版破坏流程，会正常掉落这个方块的物品、播破坏粒子与音效
            this.level().destroyBlock(landingPos, true);
        }

        for (int dy = 0; dy <= LANDING_SEARCH; dy++) {
            BlockPos candidate = landingPos.above(dy);
            BlockState target = this.level().getBlockState(candidate);
            // 与原版同一个判定：能不能顶掉 + 放得稳不稳
            if (!target.canBeReplaced() || !crateState.canSurvive(this.level(), candidate)) {
                continue;
            }
            if (this.level().setBlock(candidate, crateState, Block.UPDATE_ALL)) {
                return candidate;
            }
            return null;
        }
        return null;
    }

    /**
     * 方块碰撞箱的体积占整格的比例（整格 = 1.0）。
     *
     * <p>用 {@code toAabbs()} 把形状拆成若干长方体逐个累加：原版形状都是长方体的并集，
     * 误差只来自少量重叠，对"阈值取在两簇之间"这种用法完全够用。
     *
     * <p><b>无碰撞箱的方块（火把、压力板、红石线、拉杆、绊线、告示牌、花草…）返回 0</b>，
     * 天然落在"该砸"那一档——所以不需要单独判 {@code noCollission}，
     * 体积判据已经把"挡不住人的装饰"和"有体积的家具"一起分开了。
     */
    private double collisionVolumeRatio(BlockState state, BlockPos pos) {
        VoxelShape shape = state.getCollisionShape(this.level(), pos);
        if (shape.isEmpty()) {
            return 0.0D;
        }
        double volume = 0.0D;
        for (AABB box : shape.toAabbs()) {
            volume += (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
        }
        return volume;
    }

    /**
     * 落地音效的音量。
     *
     * <p><b>保持 1.0</b>：客户端把最终增益钳在 {@code [0, 1]}，调到 1 以上不会更响，
     * 只会白白改变某些距离判定，所以"传得远"不靠这个值。
     *
     * <p>远距离靠的是另外两处配合（缺一不可）：
     * <ul>
     *   <li>{@code ModSounds.AIRDROP_LAND} 用 {@code createFixedRangeEvent(…, 64)}，
     *       让<b>服务端</b>把声音包发给 64 格内的玩家；</li>
     *   <li>{@code sounds.json} 里该条目写 {@code "attenuation_distance": 64}，
     *       让<b>客户端</b>不把 64 格内的声音剔除（原版默认只有 16 格，超出直接不播）。</li>
     * </ul>
     */
    private static final float LANDING_SOUND_VOLUME = 1.0F;

    /** 落地音效的音高。固定 1.0，不随机，保证每次都是同一记闷响。 */
    private static final float LANDING_SOUND_PITCH = 1.0F;

    /**
     * 落地音效：本模组自带的 {@code sounds/airdrop_land.ogg}。
     *
     * <p><b>原版下落方块落地是完全静音的</b>（{@code FallingBlockEntity} 落地只调
     * {@code level.setBlock}，不发声），从 160 格高砸下来一点动静都没有会很突兀，
     * 所以这里补一声专用音效。
     *
     * <p>播放位置用落点方块中心；音量为 {@link #LANDING_SOUND_VOLUME}（决定响度与可听半径，
     * 见该常量的注释），音高固定为 {@link #LANDING_SOUND_PITCH}。
     */
    private void playLandingSound(BlockPos landedPos) {
        this.level().playSound(null, landedPos, ModSounds.AIRDROP_LAND.get(),
                SoundSource.BLOCKS, LANDING_SOUND_VOLUME, LANDING_SOUND_PITCH);
    }
}
