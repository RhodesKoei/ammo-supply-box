package com.koei.ammo_supply_box.util;

import com.koei.ammo_supply_box.block.AmmoBoxTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

/**
 * 弹药盒的粒子反馈。两个使用场景共用同一套观感：
 * <ul>
 *   <li><b>持枪交互成功时</b>——三种变体各用一种 dust 颜色，见 {@link #interactionColor}；</li>
 *   <li><b>自动补货完成时</b>——固定蓝色，见 {@link #COLOR_RESTOCK_READY}。</li>
 * </ul>
 *
 * <h2>为什么是「彩色颗粒 + 白色烟雾」两套</h2>
 * <p>原版没有可以上色的烟雾粒子：{@code SMOKE} / {@code LARGE_SMOKE} 只有灰白两色。
 * 唯一能显示任意颜色的原版粒子是 {@code DUST}（本质是彩色小颗粒，不是烟）。
 * 所以这里用 {@code DUST} 负责颜色、白色 {@code LARGE_SMOKE} 补上"烟"的体积感。
 *
 * <h2>颜色是怎么来的</h2>
 * <p>三个交互色都<b>取自带对应贴图的色相</b>，但贴图本身是给方块用的暗色，
 * 直接拿来当粒子会接近黑灰、三种颜色分不清。所以做了「只抬高下限」的处理：
 * 保留色相，把饱和度拉到至少 {@code 0.70}、明度至少 {@code 0.90}，
 * 已经够鲜艳的颜色（比如红色指示灯）几乎不受影响。
 *
 * <p>{@link DustParticleOptions} 的颜色按 sRGB 的 0~1 解释，
 * 也就是「RGB 分量 ÷ 255」，和原版 {@code DustParticleOptions.REDSTONE} 的取法一致。
 */
public final class SupplyEffects {

    /** 普通弹药盒（绿）：取自 {@code ammo_box_N.png} 里最绿的像素 RGB(71, 82, 68)，色相 107°。 */
    public static final Vector3f COLOR_NORMAL = new Vector3f(0.405F, 0.900F, 0.270F);

    /** 创造模式（紫）：取自 {@code ammo_box_N_creative.png} 里最紫的像素 RGB(150, 82, 151)，色相 299°。 */
    public static final Vector3f COLOR_CREATIVE = new Vector3f(0.891F, 0.270F, 0.900F);

    /** 自动补货（红）：取自 {@code ammo_box_N_cd_refuse.png} 上的红色指示灯 RGB(224, 75, 75)，色相 0°。 */
    public static final Vector3f COLOR_AUTO_RESTOCK = new Vector3f(0.900F, 0.270F, 0.270F);

    /**
     * 分享装（金）。
     *
     * <p>这个颜色不是从贴图里取的，而是从"必须和另外三个区分开"倒推出来的：
     * 绿（色相 107°）、紫（299°）、红（0°）已经占掉了大半色环，剩下来最容易分辨的暖色就是金色
     * （色相约 44°）。补给完成用的蓝色（色相 216°）也拉得开。
     */
    public static final Vector3f COLOR_SHARE = new Vector3f(0.950F, 0.720F, 0.200F);

    /**
     * 分享装自动补货（青绿）。
     *
     * <p>同样是"必须和别的区分开"倒推的：绿 107°、紫 299°、红 0°、金 44°、补货完成的蓝 216° 已经占掉
     * 大半色环，剩下比较空的一档是青绿（色相约 165°），和蓝/绿都拉得开又不会被当成同一个变体。
     */
    public static final Vector3f COLOR_SHARE_RESTOCK = new Vector3f(0.150F, 0.850F, 0.700F);

    /**
     * 补货完成（蓝）：固定值，没有对应的贴图。
     *
     * <p>与交互色刻意区分开——蓝色代表"盒子准备好了"，不是"你拿到了补给"。
     */
    public static final Vector3f COLOR_RESTOCK_READY = new Vector3f(0.250F, 0.550F, 1.000F);

    /**
     * 空投箱 5 档的交互色，与客户端光柱的配色<b>一一对应</b>
     * （见 {@code SupplyBeamRenderer} 的 {@code TIER_COLORS}）。
     *
     * <p>这样"看到什么颜色的光柱，就落下什么颜色的箱子"，粒子也同色，
     * 玩家不用看名字就能把两者对上。档位越高越"贵重"：灰白 → 绿 → 青 → 紫 → 金。
     */
    private static final Vector3f[] COLOR_AIRDROP = {
            new Vector3f(0.850F, 0.850F, 0.850F),
            new Vector3f(0.350F, 0.850F, 0.400F),
            new Vector3f(0.150F, 0.850F, 0.700F),
            new Vector3f(0.650F, 0.350F, 0.950F),
            new Vector3f(1.000F, 0.800F, 0.250F),
    };

    /**
     * 第 {@code tier} 档空投箱的交互色。
     *
     * <p>越界档位一律钳到范围内：这里会被实体/方块在运行时按档号调用，
     * 宁可退化成首档颜色，也不要因为一个异常数字把粒子掐掉。
     */
    public static Vector3f airdropColor(int tier) {
        int index = Math.max(0, Math.min(COLOR_AIRDROP.length - 1, tier - 1));
        return COLOR_AIRDROP[index];
    }

    /** 彩色颗粒数目。扩散慢，负责"颜色"。 */
    private static final int DUST_COUNT = 12;
    /** 白色大烟雾数目。比颗粒更少，负责"烟"的体积感。 */
    private static final int SMOKE_COUNT = 6;
    private static final double DUST_SPEED = 0.02D;
    private static final double SMOKE_SPEED = 0.01D;

    /**
     * 某种变体在<b>交互时</b>该用的 dust 颜色。
     *
     * <p>用 {@code switch} 而不是三目表达式，这样以后 {@link AmmoBoxTypes.Variant} 新增成员时
     * 编译期就会报错提醒补齐，不会静默漏掉一种盒子。
     */
    public static Vector3f interactionColor(AmmoBoxTypes.Variant variant) {
        return switch (variant) {
            case NORMAL -> COLOR_NORMAL;
            case CREATIVE -> COLOR_CREATIVE;
            case AUTO_RESTOCK -> COLOR_AUTO_RESTOCK;
            case SHARE -> COLOR_SHARE;
            case SHARE_RESTOCK -> COLOR_SHARE_RESTOCK;
        };
    }

    /**
     * 在方块周围播一次粒子。
     *
     * <p>只在服务端生效：{@code sendParticles} 会把粒子包广播给附近玩家，
     * 客户端不需要（也不应该）自己再播一遍。传进来的不是 {@link ServerLevel} 时直接返回。
     *
     * <p>粒子按<b>方块自己的选框范围</b>撒，所以小型弹药盒的粒子贴着小盒子，
     * 大型弹药盒才铺满整格——五个等级的观感各自贴合自己的模型。
     *
     * @param dustColor dust 颗粒的颜色，烟雾固定白色
     */
    public static void spawn(Level level, BlockPos pos, BlockState state, Vector3f dustColor) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        // VoxelShape#bounds() 返回的是相对方块原点的 0~1 区间
        AABB bounds = state.getShape(level, pos).bounds();
        double centerX = pos.getX() + (bounds.minX + bounds.maxX) / 2.0D;
        double centerY = pos.getY() + (bounds.minY + bounds.maxY) / 2.0D;
        double centerZ = pos.getZ() + (bounds.minZ + bounds.maxZ) / 2.0D;
        double spreadX = (bounds.maxX - bounds.minX) / 2.0D;
        double spreadY = (bounds.maxY - bounds.minY) / 2.0D;
        double spreadZ = (bounds.maxZ - bounds.minZ) / 2.0D;

        serverLevel.sendParticles(new DustParticleOptions(dustColor, 1.0F),
                centerX, centerY, centerZ, DUST_COUNT, spreadX, spreadY, spreadZ, DUST_SPEED);
        serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE,
                centerX, centerY, centerZ, SMOKE_COUNT, spreadX, spreadY, spreadZ, SMOKE_SPEED);
    }

    /**
     * 空投落点那圈烟雾的颜色之一：蓝。
     *
     * <p>与光柱的浅蓝保持一致（见 {@code SupplyBeamRenderer#BEAM_COLOR}），
     * 这样"光柱—落点圈—落下的箱子"是同一套配色。
     */
    public static final Vector3f COLOR_AIRDROP_RING = new Vector3f(0.55F, 0.80F, 1.00F);

    /** 落点圈的另一半颜色：白。与上面那颗蓝的成对出现，凑成"蓝白"两色。 */
    public static final Vector3f COLOR_AIRDROP_RING_WHITE = new Vector3f(1.00F, 1.00F, 1.00F);

    /** 落点圈上撒多少个点。半径 3 格、2 个点，绕一圈是两团对称的蓝白。 */
    private static final int RING_POINTS = 2;

    /** 落点圈半径（格）。 */
    private static final double RING_RADIUS = 3.0D;

    /** 落点圈贴地高度，稍微抬一点避免与地面 z-fighting 或被方块面吞掉。 */
    private static final double RING_Y_OFFSET = 0.15D;

    /**
     * 白色那颗相对蓝色那颗的额外抬高量。
     *
     * <p>不加这个偏移的话两颗 dust 会完全重合，看起来只有一颗（后画的盖住先画的）。
     * 抬高一点点既保证两颗都能看见，又不至于让"一个点"变成上下两截。
     */
    private static final double RING_WHITE_Y_OFFSET = 0.08D;

    /**
     * 在空投落点画一圈<b>蓝白色粒子</b>（半径 3 格）。
     *
     * <p>每个点放两颗彩色 {@code DUST}：一颗蓝、一颗白，凑成"蓝白"。
     * 早先用的是"彩色 dust + 白色 CLOUD"（借烟雾粒子补体积感），现在改成两颗 dust，
     * 观感更干净、也省掉了烟雾粒子那种蓬松的边界。
     *
     * <p>整圈会随时间缓慢旋转（{@code gameTime} 驱动的相位），观感上是两团蓝白绕着落点打转。
     *
     * <p>只在服务端调用：{@code sendParticles} 会把粒子包广播给附近玩家。
     * 广播半径是原版默认值（约 32 格），和普通粒子一致。
     */
    public static void spawnAirdropRing(Level level, BlockPos pos, long gameTime) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        double centerX = pos.getX() + 0.5D;
        double centerZ = pos.getZ() + 0.5D;
        double y = pos.getY() + RING_Y_OFFSET;
        // 每 80 tick 转完一圈（4 秒）
        double phase = (double) (gameTime % 80L) / 80.0D * (Math.PI * 2.0D);

        for (int i = 0; i < RING_POINTS; i++) {
            double angle = phase + (Math.PI * 2.0D) * i / RING_POINTS;
            double x = centerX + Math.cos(angle) * RING_RADIUS;
            double z = centerZ + Math.sin(angle) * RING_RADIUS;
            // 蓝色那颗：位置固定，speed 给 0，安静地点在圆周上
            serverLevel.sendParticles(new DustParticleOptions(COLOR_AIRDROP_RING, 1.0F),
                    x, y, z, 1, 0.02D, 0.02D, 0.02D, 0.0D);
            // 白色那颗：略微抬高一点点，否则会和蓝色完全重合、看起来只有一颗
            serverLevel.sendParticles(new DustParticleOptions(COLOR_AIRDROP_RING_WHITE, 1.0F),
                    x, y + RING_WHITE_Y_OFFSET, z, 1, 0.02D, 0.02D, 0.02D, 0.0D);
        }
    }

    /** 落地碎屑：外圈撒多少个点。 */
    private static final int DEBRIS_RING_POINTS = 80;

    /** 落地碎屑：外圈半径（格）。 */
    private static final double DEBRIS_RADIUS = 2.5D;

    /** 落地碎屑：中心那一团撒多少颗。 */
    private static final int DEBRIS_CENTER_COUNT = 160;

    /**
     * 空投箱落地时炸开一圈<b>方块碎屑</b>。
     *
     * <p>粒子用的是 {@code ParticleTypes.BLOCK} + {@link BlockParticleOption}，它会把
     * <b>指定方块状态的模型贴图</b>当作碎屑——所以落在草方块上炸出草屑、落在石头上炸出石屑，
     * 完全由调用方传入的 {@code ground} 决定，不需要我们为每种地面准备素材。
     *
     * <p>千万别传空气：{@code BlockParticleOption} 要拿这个状态去查模型，空气没有可用的贴图。
     * 调用方已经做了兜底（下方是空气时退回用空投箱自己的状态）。
     *
     * <p>结构是"外圈一整圈 + 中心一大团"：外圈负责范围感（半径 2.5 格，像冲击波），
     * 中心那一团给"炸开"的体积感，并带一点上扬速度。共 {@code 80 + 160 = 240} 颗。
     *
     * <p>只在服务端调用，{@code sendParticles} 会广播给附近玩家（原版默认约 32 格）。
     */
    public static void spawnLandingDebris(Level level, BlockPos pos, BlockState ground) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockParticleOption debris = new BlockParticleOption(ParticleTypes.BLOCK, ground);
        double centerX = pos.getX() + 0.5D;
        double centerY = pos.getY() + 0.1D;
        double centerZ = pos.getZ() + 0.5D;

        // 外圈：沿半径均匀撒一圈，带一点点向外的随机扩散
        for (int i = 0; i < DEBRIS_RING_POINTS; i++) {
            double angle = Math.PI * 2.0D * i / DEBRIS_RING_POINTS;
            double x = centerX + Math.cos(angle) * DEBRIS_RADIUS;
            double z = centerZ + Math.sin(angle) * DEBRIS_RADIUS;
            serverLevel.sendParticles(debris, x, centerY, z, 1, 0.2D, 0.15D, 0.2D, 0.05D);
        }

        // 中心：一大团碎屑，范围铺满整格并略微上扬
        serverLevel.sendParticles(debris, centerX, centerY + 0.2D, centerZ,
                DEBRIS_CENTER_COUNT, 0.8D, 0.3D, 0.8D, 0.12D);
    }

    /** 落地烟雾：用的粒子。与箱子下落尾迹同款（原版营火的白色烟）。 */
    private static final ParticleOptions LANDING_SMOKE_PARTICLE = ParticleTypes.CAMPFIRE_COSY_SMOKE;

    /** 落地烟雾：一次炸出多少颗。 */
    private static final int LANDING_SMOKE_COUNT = 80;

    /** 落地烟雾：水平散布半径（格）。 */
    private static final double LANDING_SMOKE_SPREAD_XZ = 1.5D;

    /** 落地烟雾：竖直散布半径（格）。 */
    private static final double LANDING_SMOKE_SPREAD_Y = 0.6D;

    /** 落地烟雾：初速度，给一点点向外扩散的推力（营火烟自己会往上飘）。 */
    private static final double LANDING_SMOKE_SPEED = 0.05D;

    /**
     * 空投箱落地时炸开一大团<b>营火烟</b>。
     *
     * <p>和箱子下落途中的尾迹用的是同一个粒子类型，所以观感是连贯的：
     * 一路拖下来的烟柱，在落地那一刻"炸开"成一团。
     *
     * <p>按<b>远距离</b>广播（服务端逐个给本维度玩家发包）——原版没有"广播 + 强制"
     * 的公开重载，只能遍历玩家调用 {@code sendParticles(ServerPlayer, T, boolean, ...)}。
     * 这样 30~60 格外看热闹的玩家也能看到那一团烟，与落地音 64 格的可听范围相匹配。
     */
    public static void spawnLandingSmoke(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        for (ServerPlayer player : serverLevel.players()) {
            serverLevel.sendParticles(player, LANDING_SMOKE_PARTICLE, true,
                    x, y, z, LANDING_SMOKE_COUNT,
                    LANDING_SMOKE_SPREAD_XZ, LANDING_SMOKE_SPREAD_Y, LANDING_SMOKE_SPREAD_XZ,
                    LANDING_SMOKE_SPEED);
        }
    }

    private SupplyEffects() {
    }
}
