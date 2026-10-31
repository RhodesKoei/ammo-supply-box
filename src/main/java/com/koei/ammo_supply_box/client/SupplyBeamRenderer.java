package com.koei.ammo_supply_box.client;

import com.koei.ammo_supply_box.entity.SupplyBeamEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 空投光柱的客户端渲染：一底座装置模型 + 一道信标光柱 + 一行倒计时。
 *
 * <p><b>光柱本体</b>直接把原版信标的 {@code BeaconRenderer.renderBeaconBeam} 借来用。
 * 它在 1.20.1 是 <b>public static</b>，参数齐全（姿势栈、缓冲区、光束纹理、动画时间、
 * 起止高度、颜色、内芯/光晕半径），所以不需要复制那四十多行顶点代码，也不用新增贴图——
 * 直接复用原版的 {@code textures/entity/beacon_beam.png}。
 *
 * <p><b>底座装置</b>是 {@code BeamBaseModel}（Blockbench 做的方块模型），
 * 画在实体原点、方块底面那一层，正好"站在"落点上、与那圈蓝白粒子同心。
 * 早先只有一根光柱、没有实体几何，远看是一根凭空出现的线；
 * 有了底座之后光柱看起来是从装置里射出来的。
 * 装置先画、光柱后画，光柱的半透明叠加才会盖在装置之上。
 *
 * <h2>为什么渲染器的 shouldRender 直接返回 true</h2>
 * 默认实现按实体包围盒做视锥剔除，而光柱有 1024 格高、实体盒子却只有半格：
 * 玩家抬头看光柱、脚点不在视野里时，整根柱子会被剔掉。这里改成永远参与渲染，
 * 距离控制交给实体自己的 {@code shouldRenderAtSqrDistance}（256 格）与同步范围。
 *
 * <h2>为什么不用管半透明</h2>
 * {@code renderBeaconBeam} 内部用的 {@code RenderType.beaconBeam} 已经是半透明且不写深度，
 * 观感和原版信标一致。
 */
@OnlyIn(Dist.CLIENT)
public class SupplyBeamRenderer extends EntityRenderer<SupplyBeamEntity> {

    /** 光柱画到多高。取原版信标的上限（超出世界高度也照样画，原版就是如此）。 */
    private static final int BEAM_HEIGHT = BeaconRenderer.MAX_RENDER_Y;

    /**
     * 光柱粗细：内芯半径 / 外圈光晕半径，单位是方块。
     *
     * <p>原版信标用的是 {@code 0.2F / 0.25F}，这里已经减到原版的四分之一
     * （{@code 0.05F / 0.065F}）：光柱是一条纤细的光线，远看依旧成一条竖线，
     * 但不再像原版信标那样粗壮。想让光柱更粗/更细只改这两个数即可，请注意
     * <b>光晕半径应当略大于内芯半径</b>，否则外圈那层半透明辉光会被内芯完全盖住、失去柔和感。
     */
    private static final float BEAM_RADIUS = 0.05F;
    private static final float GLOW_RADIUS = 0.065F;

    /**
     * 光柱颜色：<b>全部 5 档统一用浅蓝</b>。
     *
     * <p>早先是每档一种颜色（灰白/绿/青/紫/金）用来区分档次，现在按需求统一成浅蓝——
     * 空投光柱在远处就是一个"这里有东西"的信号，颜色本身不需要再承载档位信息；
     * 档位照样能从落下来的箱子贴图和交互 particle 上分辨出来。
     */
    private static final float[] BEAM_COLOR = {0.55F, 0.80F, 1.00F};

    /**
     * 光柱上方那行提示文本的载体。
     *
     * <p>文本直接由这个光柱实体来画：光柱实体本来就是"这次空投的临时标记"，
     * 生命周期正好等于"从出现到箱子落地"，文字跟着它出现和消失，不需要额外的同步。
     *
     * <p>文案里带一个秒级倒计时，数值来自实体自己同步过来的"消失时刻"
     * （见 {@code SupplyBeamEntity#getRemainingTicks()}）。因为同步的是<b>绝对时刻</b>
     * 而不是剩余量，所以后进服的玩家、区块重载后重新收到实体的玩家，
     * 看到的倒计时都还是准的，不会从满值重新开始。
     *
     * <p>走原版 {@code EntityRenderer#renderNameTag}：拿到的是标准名牌观感
     * （半透明黑底、始终面向镜头、随距离自动淡出），不用自己算广告牌矩阵和背板。
     */
    private static final String LABEL_KEY = "message.ammo_supply_box.incoming";

    /**
     * 提示文本额外抬高多少格。
     *
     * <p>{@code renderNameTag} 自己会再抬高 {@code 实体高度 + 0.5}（本实体 0.5 高 → 1.0 格），
     * 所以文字最终浮在光柱底面以上约 2.5 格处，不会挡住落下来的箱子。
     */
    private static final float LABEL_Y = 1.5F;

    public SupplyBeamRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(SupplyBeamEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        long gameTime = entity.level().getGameTime();
        poseStack.pushPose();
        // renderBeaconBeam 内部固定 translate(0.5, 0, 0.5)（它是给方块实体写的），
        // 而实体渲染时姿势栈原点已经在实体位置上（即方块中心），所以先把它抵消掉。
        poseStack.translate(-0.5D, 0.0D, -0.5D);
        // 底座装置先画：它是实心几何体，会写深度；光柱随后叠加在上面。
        // 两者用同一个 -0.5 平移，因为方块模型的原点也在方块西北下角。
        BeamBaseModel.render(poseStack, bufferSource, packedLight);
        BeaconRenderer.renderBeaconBeam(poseStack, bufferSource, BeaconRenderer.BEAM_LOCATION,
                partialTick, 1.0F, gameTime, 0, BEAM_HEIGHT, BEAM_COLOR,
                BEAM_RADIUS, GLOW_RADIUS);
        poseStack.popPose();

        // 光柱上方的「补给下落中... N 秒」
        // 剩余 tick 换算成秒并向上取整：(remaining + 19) / 20，于是 1 tick 也显示 1 秒、
        // 归零那一秒显示 0——正好对应"马上就消失"。
        int secondsRemaining = (int) ((entity.getRemainingTicks() + 19L) / 20L);
        poseStack.pushPose();
        poseStack.translate(0.0D, LABEL_Y, 0.0D);
        this.renderNameTag(entity, Component.translatable(LABEL_KEY, secondsRemaining),
                poseStack, bufferSource, packedLight);
        poseStack.popPose();

        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    /**
     * 永久参与渲染：光柱太高，按包围盒剔除会让它整根消失。见类注释。
     *
     * <p>参数里的坐标是摄像机相对坐标，这里刻意不参与判断。
     */
    @Override
    public boolean shouldRender(SupplyBeamEntity entity, Frustum frustum,
                                double cameraX, double cameraY, double cameraZ) {
        return true;
    }

    /** 光柱是纯几何体，不走实体贴图；返回方块图集即可。 */
    @Override
    public ResourceLocation getTextureLocation(SupplyBeamEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
