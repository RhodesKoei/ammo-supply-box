package com.koei.ammo_supply_box.compat.jade;

import com.koei.ammo_supply_box.AmmoSupplyBox;
import com.koei.ammo_supply_box.block.AutoRestockAmmoBoxBlock;
import com.koei.ammo_supply_box.block.AutoRestockAmmoBoxBlockEntity;
import com.koei.ammo_supply_box.block.ShareRestockAmmoBoxBlock;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade（玉）联动入口：让玩家看着自动补货弹药盒时，能看到「还剩多久补完货」。
 *
 * <p><b>这个类不会被本模组自己实例化。</b>Jade 在 {@code FMLLoadCompleteEvent}
 * 阶段扫描所有 jar 的注解，发现 {@link WailaPlugin} 才实例化并回调
 * {@link #register} / {@link #registerClient}。因此：
 * <ul>
 *   <li>没装 Jade 时这个类只是一段躺在那儿没人碰的字节码，绝不会被加载，
 *       所以 {@code build.gradle} 里对 Jade 只需要 {@code compileOnly} +
 *       {@code runtimeOnly}，成品 jar 不含 Jade，也不产生硬依赖；</li>
 *   <li>反过来，{@code @WailaPlugin} 的值必须是本模组自己的 modid——
 *       Jade 会拿它去 {@code ModList.get().isLoaded(...)} 做一次确认，
 *       填成本模组 id 就代表「本模组在场时才注册这个插件」。</li>
 * </ul>
 *
 * <p>分工是标准的「服务端供数、客户端渲染」：
 * <ul>
 *   <li>{@link #register}（主线程，客户端和服务端都会跑）只注册数据提供器，
 *       它负责把冷却结束的绝对时间戳塞进 Jade 的网络包；</li>
 *   <li>{@link #registerClient}（只在客户端跑）注册拿这个时间戳画字的那一条。</li>
 * </ul>
 * 之所以走这条路而不在方块实体里做同步，是因为冷却时间本来就是纯服务端数据，
 * 为了一行提示去给方块实体加 {@code getUpdateTag} / {@code getUpdatePacket}
 * 得自己管广播时机，代价明显更大。
 */
@WailaPlugin(AmmoSupplyBox.MODID)
public class AmmoBoxJadePlugin implements IWailaPlugin {

    /**
     * 数据提供器和显示组件共用同一个 uid。
     *
     * <p>用同一个而不是各起一个，是 Jade 的惯例（原版熔炉、箱子等都是这么做的），
     * 好处是这个 uid 同时是配置里那个开关的键——玩家在 Jade 设置里关掉它，
     * 供数和显示会一起停，不会出现「数据还在传但没人显示」的半吊状态。
     */
    public static final ResourceLocation UID_COOLDOWN =
            ResourceLocation.fromNamespaceAndPath(AmmoSupplyBox.MODID, "auto_restock_cooldown");

    @Override
    public void register(IWailaCommonRegistration registration) {
        // 只在有方块实体、且确实需要服务端权威数据的方块上注册。
        // 普通弹药盒和创造弹药盒没有冷却，不需要多传一个空包。
        registration.registerBlockDataProvider(
                AutoRestockCooldownDataProvider.INSTANCE,
                AutoRestockAmmoBoxBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        // 两种"带冷却"的方块都要显示这一行：自动补货盒与分享装自动补货盒。
        // 分别注册具体类而不是只注册公共父类 CooldownAmmoBoxBlock，是为了不依赖
        // Jade 对父类匹配的实现细节——多注册一条是无害的（同一个 provider 实例、同一个 uid）。
        registration.registerBlockComponent(
                AutoRestockCooldownComponent.INSTANCE,
                AutoRestockAmmoBoxBlock.class);
        registration.registerBlockComponent(
                AutoRestockCooldownComponent.INSTANCE,
                ShareRestockAmmoBoxBlock.class);
    }
}
