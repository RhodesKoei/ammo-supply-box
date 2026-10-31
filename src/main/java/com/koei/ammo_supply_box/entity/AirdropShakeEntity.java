package com.koei.ammo_supply_box.entity;

import com.koei.ammo_supply_box.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 「摄像机震颤」的参数载体实体：空投箱落地那一瞬间在落点生成一个，
 * 让范围内的玩家视角抖一下。
 *
 * <h2>它只负责"传参"，不负责"抖"</h2>
 * 实体本身<b>不做任何计算</b>，只携带两个同步字段：<b>强度</b>与<b>影响半径</b>。
 * 真正的相机运动由客户端的 {@code CameraShakeHandler}（弹簧-阻尼模型）完成：
 * 它在实体进入世界时收到 {@code EntityJoinLevelEvent}，按距离衰减后注入一次角速度冲击，
 * 然后自己积分回正。
 *
 * <p>之所以用实体当传输通道而不是网络包：原版实体同步天然处理"谁在附近、谁后进服"，
 * 不需要我们维护一套包与玩家列表。
 *
 * <h2>参数为什么走"生成包"而不是同步字段</h2>
 * 客户端 {@code ClientPacketListener#handleAddEntity} 的顺序是
 * {@code create → recreateFromPacket → putNonPlayerEntity}，而触发震颤的
 * {@code EntityJoinLevelEvent} 是在最后一步 {@code putNonPlayerEntity} 里抛出的；
 * 此时同步数据包（{@code ClientboundSetEntityDataPacket}）<b>还没到达</b>，
 * 从 {@code SynchedEntityData} 读到的仍是默认值 {@code 0.0F}——抖动的幅度会是零。
 *
 * <p>所以参数被塞进生成包自带的 {@code data} 位（上半 16 位半径、下半 16 位强度，
 * 都按 0.1 量化），在 {@link #recreateFromPacket} 里写回同步字段；
 * 这样事件触发时数值一定就绪。同步字段本身仍然保留——它负责"后进服的玩家"
 * 与存档重载，两条通道互为兜底。
 *
 * <h2>生命周期</h2>
 * 只活 {@value #LIFETIME_TICKS} tick 就自己 {@code discard()}——它是一次性触发器，
 * 参数在客户端收到的那一刻就已经被读走了，没有继续存在的必要。
 * 正常玩家几乎看不到它（零尺寸、不可见、无碰撞）。
 *
 * <p><b>许可</b>：震颤的数学模型移植自 Ritchie's Projectile Library（MIT，
 * Copyright (c) 2023 Wagers-of-Industrial-Warfare），详见 {@code THIRD_PARTY_NOTICES.md}
 * 与 {@code CameraShakeHandler} 的类注释。
 */
public class AirdropShakeEntity extends Entity {

    /** 冲击强度。客户端按"典型轴峰值约 0.16 × 强度、三轴合成约 0.28 × 度"换算成视角偏移。 */
    private static final EntityDataAccessor<Float> DATA_MAGNITUDE =
            SynchedEntityData.defineId(AirdropShakeEntity.class, EntityDataSerializers.FLOAT);

    /** 影响半径（格）：超出这个距离的玩家完全感觉不到。 */
    private static final EntityDataAccessor<Float> DATA_RADIUS =
            SynchedEntityData.defineId(AirdropShakeEntity.class, EntityDataSerializers.FLOAT);

    /** 存在几个 tick 就自动消失。留 10 tick 是给网络留余量：参数在生成包里，晚到也能抖。 */
    private static final int LIFETIME_TICKS = 10;

    /** 生成包 data 位的量化精度：1 个单位 = 0.1。 */
    private static final float PARAM_SCALE = 10.0F;

    /** data 位单字段的最大可表示值（16 位无符号）。 */
    private static final int PARAM_MASK = 0xFFFF;

    public AirdropShakeEntity(EntityType<? extends AirdropShakeEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setInvisible(true);
        this.setInvulnerable(true);
    }

    /**
     * 在 {@code pos} 制造一次震颤。只能在服务端调用。
     *
     * @param radius    影响半径（格）
     * @param magnitude 冲击强度（见 {@link #DATA_MAGNITUDE} 的换算说明）
     */
    public static void spawn(Level level, BlockPos pos, float radius, float magnitude) {
        if (level.isClientSide) {
            return;
        }
        AirdropShakeEntity shake = new AirdropShakeEntity(ModEntities.AIRDROP_SHAKE.get(), level);
        shake.setRadius(radius);
        shake.setMagnitude(magnitude);
        shake.setPos(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        level.addFreshEntity(shake);
    }

    @Override
    protected void defineSynchedData() {
        this.getEntityData().define(DATA_MAGNITUDE, 0.0F);
        this.getEntityData().define(DATA_RADIUS, 0.0F);
    }

    public float getMagnitude() {
        return this.entityData.get(DATA_MAGNITUDE);
    }

    public void setMagnitude(float magnitude) {
        this.entityData.set(DATA_MAGNITUDE, Math.max(0.0F, magnitude));
    }

    public float getRadius() {
        return this.entityData.get(DATA_RADIUS);
    }

    public void setRadius(float radius) {
        this.entityData.set(DATA_RADIUS, Math.max(0.0F, radius));
    }

    /**
     * 把半径与强度一起打包进生成包的 {@code data} 位。
     *
     * <p>这是"实体一进客户端就有参数"的唯一可靠通道：同步数据包总是晚于生成包到达，
     * 而客户端的震颤触发点（{@code EntityJoinLevelEvent}）恰好卡在两者之间。
     * 原版 {@code FallingBlockEntity} 也用同一个位置塞方块状态，属于既有惯例。
     */
    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        int packedRadius = Math.min(Math.round(getRadius() * PARAM_SCALE), PARAM_MASK);
        int packedMagnitude = Math.min(Math.round(getMagnitude() * PARAM_SCALE), PARAM_MASK);
        return new ClientboundAddEntityPacket(this, (packedRadius << 16) | packedMagnitude);
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        int data = packet.getData();
        if (data != 0) {
            setRadius((data >>> 16) / PARAM_SCALE);
            setMagnitude((data & PARAM_MASK) / PARAM_SCALE);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && this.tickCount > LIFETIME_TICKS) {
            this.discard();
        }
    }

    /** 纯参数载体，不参与任何碰撞 / 拾取 / 攻击。 */
    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** 零尺寸：不占任何空间，也不影响实体查询的包围盒相交判断。 */
    @Override
    public float getPickRadius() {
        return 0.0F;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("Magnitude", getMagnitude());
        tag.putFloat("Radius", getRadius());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setMagnitude(tag.getFloat("Magnitude"));
        setRadius(tag.getFloat("Radius"));
    }

    /** 便于调试时打印。 */
    @Override
    public String toString() {
        return "AirdropShakeEntity[" + position() + ", magnitude=" + getMagnitude() + ", radius=" + getRadius() + "]";
    }

    /** 触发点在方块中心，这里只是显式说明"位置就是落点"，没有额外偏移。 */
    public Vec3 effectCenter() {
        return position();
    }
}
