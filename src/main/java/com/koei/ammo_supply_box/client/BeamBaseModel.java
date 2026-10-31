package com.koei.ammo_supply_box.client;

import com.koei.ammo_supply_box.AmmoSupplyBox;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 空投光柱底座（信标装置）的模型：把 Blockbench 做的方块模型画到光柱实体脚下。
 *
 * <h2>为什么用方块模型 JSON，而不是写 Java 实体模型</h2>
 * 1.20.1 原版<b>没有</b> JSON 实体模型格式——实体模型只能是 Java 类（Blockbench 的
 * "Java Entity" 导出会把立方体坐标硬编码进 .java）。对一个静态道具来说，
 * 方块模型 JSON 既能在 Blockbench 里所见即所得，又不用维护一堆 Java 立方体坐标。
 *
 * <h2>模型没有绑定任何方块，那是怎么被加载的</h2>
 * 原版只烘焙"被某个 blockstate 引用到的"模型，而 {@code beacon_entity.json} 不对应任何方块，
 * 所以它不会自动加载。{@link ModelEvent.RegisterAdditional}（<b>mod 总线、仅客户端</b>）
 * 就是给这种情况留的口子：Forge 会把它当顶层模型烘焙，
 * 之后可用 {@code ResourceLocation}（而不是带 variant 的 {@code ModelResourceLocation}）取回。
 * 好处是不用为了"画个模型"去注册一个永远不放的假方块。
 *
 * <h2>几个刻意的选择</h2>
 * <ul>
 *   <li><b>不缓存 BakedModel</b>：{@code ModelManager#getModel} 只是一次 HashMap 查表，
 *       开销可以忽略；而缓存会在 F3+T 重载资源后指向旧的烘焙结果。每帧现取永远是对的。</li>
 *   <li><b>取不到就整体跳过</b>：模型没加载好时 {@code getModel} 会返回 missing model，
 *       照画会得到紫黑方块。所以先与 {@code getMissingModel()} 比对，相等就什么都不画。</li>
 *   <li><b>{@code RenderType.cutout()}</b>：贴图是二值透明（只有 alpha 0 和 255，没有半透明），
 *       用 cutout 即可；换成 translucent 反而要多一次深度排序、且半透明层不写深度会让
 *       斜鳍和底座互相穿透。若以后贴图里出现半透明像素，把这里改成
 *       {@code RenderType.translucent()}。</li>
 *   <li><b>满亮度渲染</b>（{@link #FULLBRIGHT}）：装置本身是发光体，光柱从它里面射出去，
 *       受世界光照会让它在夜里变成一团黑铁。渲染时方向性明暗仍在（模型的 {@code shade}），
 *       所以不会变成纯色片。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AmmoSupplyBox.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BeamBaseModel {

    /**
     * 模型位置：{@code assets/ammo_supply_box/models/block/beacon_entity.json}。
     *
     * <p>注意这是<b>资源路径</b>而不是 {@code ModelResourceLocation}：通过
     * {@link ModelEvent.RegisterAdditional} 注册进来的模型以普通 {@code ResourceLocation} 为键。
     */
    private static final ResourceLocation MODEL = new ResourceLocation(AmmoSupplyBox.MODID, "block/beacon_entity");

    /** 是否用满亮度渲染（自发光观感）。想看"受世界光照的金属装置"就改成 {@code false}。 */
    private static final boolean FULLBRIGHT = true;

    /** 贴图只有全透明/不透明两种像素，所以走 cutout。 */
    private static final RenderType RENDER_TYPE = RenderType.cutout();

    /** 把模型登记进烘焙队列。这个事件只在客户端、只在 mod 总线上触发。 */
    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        event.register(MODEL);
    }

    /**
     * 在光柱实体的原点处画出底座装置。
     *
     * <p><b>调用方负责坐标</b>：本方法不自己做平移。方块模型的坐标空间是 0..16、
     * 原点在方块的西北下角，而实体渲染的姿势栈原点在实体位置（方块中心），
     * 所以调用方要先 {@code translate(-0.5, 0, -0.5)}（与 {@code renderBeaconBeam} 用的是同一个平移）。
     *
     * @param packedLight 世界光照；{@link #FULLBRIGHT} 为真时会被满亮度覆盖
     */
    public static void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        BakedModel model = bakedModel();
        if (model == null) {
            return;
        }
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        dispatcher.getModelRenderer().renderModel(
                poseStack.last(),
                bufferSource.getBuffer(RENDER_TYPE),
                // 传 null 表示"没有对应的方块状态"：本模型不参与方块光照/环境光遮蔽计算
                null,
                model,
                1.0F, 1.0F, 1.0F,
                FULLBRIGHT ? LightTexture.FULL_BRIGHT : packedLight,
                OverlayTexture.NO_OVERLAY);
    }

    /** 取烘焙好的模型；还没加载好（或资源缺失）时返回 {@code null}，调用方据此跳过绘制。 */
    private static BakedModel bakedModel() {
        ModelManager manager = Minecraft.getInstance().getModelManager();
        BakedModel model = manager.getModel(MODEL);
        return model == manager.getMissingModel() ? null : model;
    }

    private BeamBaseModel() {
    }
}
