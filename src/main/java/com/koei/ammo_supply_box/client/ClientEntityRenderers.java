package com.koei.ammo_supply_box.client;

import com.koei.ammo_supply_box.AmmoSupplyBox;
import com.koei.ammo_supply_box.entity.AirdropProjectile;
import com.koei.ammo_supply_box.registry.ModEntities;
import net.minecraft.client.renderer.entity.FallingBlockRenderer;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端实体渲染器注册。
 *
 * <p>挂在 {@link Dist#CLIENT} 的 MOD 事件总线上，专用服务端根本不会加载这个类
 * （{@link SupplyBeamRenderer} 里引用了 {@code BeaconRenderer} 这类纯客户端类）。
 *
 * <ul>
 *   <li>信号弹：直接复用原版 {@link ThrownItemRenderer}——它会渲染
 *       {@code ItemSupplier#getItem()}，而本模组的信号弹按档号返回对应信标物品，
 *       于是“扔出去的球”天然就是那一档信标的样子，不需要额外贴图或渲染代码；</li>
 *   <li>光柱：{@link SupplyBeamRenderer}，内部调用原版信标的光束绘制；</li>
 *   <li>下落中的空投箱：直接复用原版 {@link FallingBlockRenderer}——它按实体携带的方块状态
 *       渲染，所以空投箱会带着完整的方块模型和朝向掉下来。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AmmoSupplyBox.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEntityRenderers {

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.AIRDROP_PROJECTILE.get(),
                context -> new ThrownItemRenderer<AirdropProjectile>(context, 1.0F, true));
        event.registerEntityRenderer(ModEntities.SUPPLY_BEAM.get(), SupplyBeamRenderer::new);
        // 下落中的空投箱：复用原版下落方块的渲染器（它按实体携带的方块状态画），
        // 泛型参数是 FallingBlockEntity，我们的子类天然满足
        event.registerEntityRenderer(ModEntities.AIRDROP_CRATE.get(), FallingBlockRenderer::new);
        // 摄像机震颤标记是纯数据载体，用原版 NoopRenderer（什么都不画）。
        // 实体类型必须注册渲染器，否则会刷"没有渲染器"的警告。
        event.registerEntityRenderer(ModEntities.AIRDROP_SHAKE.get(), NoopRenderer::new);
    }

    private ClientEntityRenderers() {
    }
}
