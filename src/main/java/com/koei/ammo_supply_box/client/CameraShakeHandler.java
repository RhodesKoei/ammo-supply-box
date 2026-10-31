package com.koei.ammo_supply_box.client;

import com.koei.ammo_supply_box.AmmoSupplyBox;
import com.koei.ammo_supply_box.entity.AirdropShakeEntity;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 摄像机震颤的客户端实现：<b>三轴解调的弹簧-阻尼（spring-damper）</b>模型。
 *
 * <blockquote><b>来源与许可</b><br>
 * 本类的数学结构（三轴位移/速度/加速度 + 子步积分 + 冲击式激励）移植自
 * <a href="https://github.com/Wagers-of-Industrial-Warfare/RitchiesProjectileLib">Ritchie's Projectile Library</a>
 * 的 {@code ModScreenShakeHandler}（作者 rbasamoyai，团队 Wagers-of-Industrial-Warfare），
 * 该库以 <b>MIT 协议</b>发布：<i>Copyright (c) 2023 Wagers-of-Industrial-Warfare</i>。
 * MIT 协议允许使用、修改与再分发，要求保留版权声明与许可声明，
 * 完整文本见本仓库根目录的 {@code THIRD_PARTY_NOTICES.md}。
 * 本模组在此基础上替换了参数体系、距离衰减方式、触发通道，
 * 并把单刚度改成<b>三轴各自独立的刚度/阻尼</b>（见下文）。</blockquote>
 *
 * <h2>为什么是弹簧而不是余弦</h2>
 * 之前的实现每帧算出 {@code 振幅 × cos(频率 × 时间)} 直接加到相机角度上，本质是"按固定节奏摆动"。
 * 现在改成物理模型：给相机一个**初始角速度冲击**，之后让它像被弹簧拉住一样自然回正——
 * <pre>
 *   每轴加速度 = -该轴刚度 × 该轴位移 - 该轴阻尼 × 该轴速度   （每 tick 分 {@value #SUBSTEPS} 个子步积分）
 * </pre>
 * 好处是收尾自然（有回弹、不会"啪"地停住），而且**多次冲击会自然叠加**：连着落两个箱子，
 * 第二个冲击是在第一个还没停下来时叠上去的，观感更真实。
 *
 * <h2>为什么三轴要"解调"</h2>
 * 旧版三轴共用一套刚度/阻尼，于是三个分量都在同一刻从"位移 0、速度 v₀"起步，
 * 振荡频率与相位完全一致：
 * <pre>
 *   x(t) = v₀ˣ · sin(ωt)/ω,   y(t) = v₀ʸ · sin(ωt)/ω,   z(t) = v₀ᶻ · sin(ωt)/ω
 * </pre>
 * 三者共用同一个 {@code sin(ωt)}，合成角位移恒等于<b>常数向量 × sin(ωt)</b>——
 * 方向固定，看起来只是"沿某一条线来回点一下"，这就是之前"单轴、单一"的根因，
 * 跟强度大小无关。
 *
 * <p>现在把三轴的频率错开约 ±10%~20%（见 {@link #STIFFNESS}），相位差在十几个 tick 内
 * 累积到 60°~80°，合成轨迹从直线变成一条不断转向的空间曲线（李萨如式），
 * 三个轴才是真的"同时在震、但方向在游走"。
 * 另外每次冲击给每轴<b>保底份量</b>（{@link #SHARE_MIN}），
 * 避免随机数偶尔把某一轴压到接近零而退化成单轴抖动。
 *
 * <h2>三轴的含义</h2>
 * 位移向量的 x/y/z 分别对应相机 <b>yaw / pitch / roll</b>，单位是<b>度</b>。
 *
 * <h2>怎么被触发</h2>
 * 服务端生成一个 {@link AirdropShakeEntity}（纯参数载体），客户端在它进入世界时收到
 * {@link EntityJoinLevelEvent}，按到玩家的距离算衰减后注入一次冲击。
 * 用实体当传输通道的好处是不用自己写网络包，且原版实体同步天然处理"谁在附近"。
 */
@Mod.EventBusSubscriber(modid = AmmoSupplyBox.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CameraShakeHandler {

    /**
     * 三轴各自的刚度（yaw / pitch / roll）：<b>故意不相等</b>，这是"多轴感"的来源。
     *
     * <p>角频率 ω = √刚度：0.50 → 0.707、0.62 → 0.787、0.42 → 0.648（rad/tick），
     * 周期分别约 <b>8.9 / 8.0 / 9.7 tick</b>。三者不成整数比，所以相位不会重新对齐，
     * 合成运动不会退化成直线，也不会周期性地"复读"同一条轨迹。
     */
    private static final Vec3 STIFFNESS = new Vec3(0.50D, 0.62D, 0.42D);

    /**
     * 三轴各自的阻尼（yaw / pitch / roll），同样错开。
     *
     * <p>阻尼比 ζ = 阻尼 / (2√刚度)：0.28 / 0.22 / 0.36，都属于欠阻尼（会回弹一两次）。
     * pitch 的 ζ 最小，余音最长（点头感更明显）；roll 的 ζ 最大，收得最快。
     * 三个轴衰减节奏不同，就不会"整齐划一地一起停"。
     */
    private static final Vec3 DRAG = new Vec3(0.40D, 0.34D, 0.46D);

    /** 每 tick 的积分子步数。子步越多数值越稳（位移不会因帧率/单调而爆掉），10 是够用的折中。 */
    private static final int SUBSTEPS = 10;

    /**
     * 单轴位移上限（度）。超过就钳住并把该轴速度清零，防止极近距叠加时把视角甩飞。
     *
     * <p>取值要高于"一次空投的峰值"才有意义，否则等于给正常震颤踩刹车
     * （表现为回弹突然变成急停）。当前贴脸最猛单轴约 20 度，所以留到 60 度，
     * 只当连砸好几个箱子的失控保险。
     */
    private static final double MAX_DISPLACEMENT = 60.0D;

    /** 三个状态量都小于这个阈值（平方值）时视为静止，直接清零，避免留下永久微抖。 */
    private static final double REST_EPSILON_SQR = 1.0E-4D;

    /**
     * 每轴保底份量比例：每轴至少拿到满冲击的 30%，剩下的按随机分配。
     *
     * <p>没有这个保底时，{@code (rand - rand) × 0.5} 有相当概率让某一轴接近零，
     * 那一次震颤就退化成"单轴抖"，观感不稳定。
     */
    private static final double SHARE_MIN = 0.30D;

    /**
     * 冲击总标度。用当前这套积分器实测标定：<b>典型轴峰值位移 ≈ 0.16 × 强度</b>
     * （每轴份量取平均 0.65 时），份量拉满的那一轴可达 <b>0.25 ×</b>，三轴合成模长峰值约 <b>0.28 ×</b>。
     *
     * <p>注意这里不能用"无阻尼估算" {@code v₀/ω}：阻尼比 0.22~0.36 会把首个峰值压到理论值的约 68%，
     * 所以简单按 {@code v₀/ω} 算会高估 45%。下面这些数字是把子步积分器跑出来量出来的。
     */
    private static final double IMPULSE_SCALE = 0.25D;

    // ---- 弹簧状态：x = yaw, y = pitch, z = roll，单位均为「度」 ----
    private static Vec3 displacement = Vec3.ZERO;
    private static Vec3 velocity = Vec3.ZERO;
    private static Vec3 acceleration = Vec3.ZERO;

    private static final Random RANDOM = new Random();

    /**
     * 注入一次冲击。
     *
     * <p>冲击直接加在<b>角速度</b>上（不是位移上），这样观感是"被撞了一下、然后回弹停住"。
     * 三轴各自独立取符号与份量，所以每次震颤的方向都不同——
     * 再配合三轴不同的刚度，合成轨迹是一条转向的空间曲线，而不是沿一条线伸缩。
     *
     * @param magnitude 冲击强度；本模组的标度下<b>典型轴峰值位移 ≈ 0.16 × magnitude 度</b>
     *                  （最猛的一轴约 0.25 ×，三轴合成约 0.28 ×）
     * @param falloff   距离衰减因子（0~1），由触发方按距离算好
     */
    public static void addImpulse(float magnitude, float falloff) {
        double m = magnitude * falloff;
        if (m <= 0.0D) {
            return;
        }
        velocity = velocity.add(signedImpulse(m), signedImpulse(m), signedImpulse(m));
    }

    /** 单轴冲击：方向随机、份量在 {@link #SHARE_MIN}~1 之间随机。 */
    private static double signedImpulse(double magnitude) {
        double share = SHARE_MIN + (1.0D - SHARE_MIN) * RANDOM.nextDouble();
        double sign = RANDOM.nextBoolean() ? 1.0D : -1.0D;
        return magnitude * IMPULSE_SCALE * share * sign;
    }

    /**
     * 客户端加入世界时接住震颤实体：按距离算衰减系数后注入冲击。
     *
     * <p>衰减用<b>平方</b>（{@code (1 - d/r)²}）：贴脸最猛，靠近边界迅速归零，
     * 比线性更符合"越远越轻微"的直觉。
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide) {
            return;
        }
        if (!(event.getEntity() instanceof AirdropShakeEntity shake)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        double radius = shake.getRadius();
        if (radius <= 0.0D) {
            return;
        }
        double dist = Math.sqrt(shake.distanceToSqr(minecraft.player));
        double frac = 1.0D - Math.min(dist / radius, 1.0D);
        addImpulse(shake.getMagnitude(), (float) (frac * frac));
    }

    /** 每客户端 tick 积分一次弹簧（三轴各自用各自的刚度与阻尼）。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        double dt = 1.0D / SUBSTEPS;

        for (int i = 0; i < SUBSTEPS; i++) {
            // 半隐式积分：先用当前加速度推位移，再更新加速度与速度
            Vec3 nextDisplacement = displacement
                    .add(velocity.scale(dt))
                    .add(acceleration.scale(0.5D * dt * dt));
            Vec3 nextAcceleration = componentMultiply(displacement, STIFFNESS)
                    .add(componentMultiply(velocity, DRAG))
                    .scale(-1.0D);
            Vec3 nextVelocity = velocity
                    .add(acceleration.add(nextAcceleration).scale(0.5D * dt));

            displacement = nextDisplacement;
            velocity = nextVelocity;
            acceleration = nextAcceleration;
            clampDisplacement();
        }

        if (displacement.lengthSqr() < REST_EPSILON_SQR
                && velocity.lengthSqr() < REST_EPSILON_SQR
                && acceleration.lengthSqr() < REST_EPSILON_SQR) {
            // 已经静止：完全清零，别让浮点残值一直让视角微抖
            displacement = Vec3.ZERO;
            velocity = Vec3.ZERO;
            acceleration = Vec3.ZERO;
        }
    }

    /**
     * 每帧把当前位移叠加到相机角度上。
     *
     * <p>用二阶泰勒展开把"上一 tick 的位移"外推到当前帧的插值时刻，所以高帧率下也是平滑的，
     * 不会出现"每 tick 跳一下"的观感。
     */
    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        float partialTick = Minecraft.getInstance().getFrameTime();
        Vec3 current = displacement
                .add(velocity.scale(partialTick))
                .add(acceleration.scale(partialTick * partialTick * 0.5D));
        if (current.lengthSqr() <= 0.0D) {
            return;
        }
        event.setYaw(event.getYaw() + (float) current.x);
        event.setPitch(event.getPitch() + (float) current.y);
        event.setRoll(event.getRoll() + (float) current.z);
    }

    /** 逐分量相乘（Vec3 没有提供），用来让三轴各乘自己的刚度/阻尼。 */
    private static Vec3 componentMultiply(Vec3 value, Vec3 factor) {
        return new Vec3(value.x * factor.x, value.y * factor.y, value.z * factor.z);
    }

    /** 把三轴位移各自钳到 {@link #MAX_DISPLACEMENT} 内；被钳住的轴同时清零速度，避免贴边抖动。 */
    private static void clampDisplacement() {
        if (Math.abs(displacement.x) > MAX_DISPLACEMENT) {
            displacement = new Vec3(Math.copySign(MAX_DISPLACEMENT, displacement.x), displacement.y, displacement.z);
            velocity = velocity.multiply(0.0D, 1.0D, 1.0D);
        }
        if (Math.abs(displacement.y) > MAX_DISPLACEMENT) {
            displacement = new Vec3(displacement.x, Math.copySign(MAX_DISPLACEMENT, displacement.y), displacement.z);
            velocity = velocity.multiply(1.0D, 0.0D, 1.0D);
        }
        if (Math.abs(displacement.z) > MAX_DISPLACEMENT) {
            displacement = new Vec3(displacement.x, displacement.y, Math.copySign(MAX_DISPLACEMENT, displacement.z));
            velocity = velocity.multiply(1.0D, 1.0D, 0.0D);
        }
    }

    private CameraShakeHandler() {
    }
}
