package com.koei.ammo_supply_box.client;

import com.koei.ammo_supply_box.AmmoSupplyBox;
import com.koei.ammo_supply_box.block.AmmoBoxTypes;
import com.koei.ammo_supply_box.item.AirdropBeaconTypes;
import com.koei.ammo_supply_box.registry.ModItems;
import com.koei.ammo_supply_box.util.ShareBoxData;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 让「分享装」与「空投箱」的<b>物品</b>模型跟着剩余次数变。
 *
 * <h2>为什么方块能变、物品不能</h2>
 * <p>方块外观由 {@code blockstates/*.json} 按<b>方块状态</b>选模型，{@code uses} 属性一变
 * 客户端就自动换模型，不需要写代码。而物品没有方块状态——物品模型只有两种可变来源：
 * 一是 NBT（原版不直接支持），二是<b>物品属性（item property）</b>：
 * 在物品模型 JSON 里写 {@code overrides}，由一个注册过的回调在渲染时读取 ItemStack
 * 并返回一个 0~1 的浮点数，命中哪个阀值就用哪个模型。这里就是把
 * 「剩余次数」注册成这样一个属性。
 *
 * <h2>为什么是三个属性，而不是一个 {@code uses}</h2>
 * <p>原版的 {@code overrides} 匹配是「回调值 ≥ 阀值」，且同一次渲染里可能命中多个条目，
 * 最终用哪一个取决于列表的遍历顺序——用单个 {@code uses} 属性写
 * {@code 0.25 / 0.5 / 0.75 / 1.0} 四个阀值就会同时命中好几个，结果依赖这个顺序。
 * 所以这里改成<b>每个剩余次数一个 0/1 指示属性</b>：
 * <ul>
 *   <li>{@code ammo_supply_box:share_uses_1}：剩 1 次时返回 1，否则 0；</li>
 *   <li>{@code ..._2} / {@code ..._3} 同理；</li>
 *   <li>剩 4 次（满）时不注册任何属性，走物品模型自己的 {@code parent}，即 {@code _4}。</li>
 * </ul>
 * 这样<b>任何时刻最多只有一个 override 命中</b>，结果与遍历顺序无关，行为可预测。
 *
 * <h2>属性名是共享的</h2>
 * <p>{@code ItemProperties} 是按<b>物品实例</b>注册的，所以分享装和空投箱可以共用
 * {@code share_uses_N} 这一组名字，两者各注册自己那几份，互不干扰。
 *
 * <h2>为什么只在这里注册</h2>
 * <p>{@code ItemProperties} 是纯客户端类（{@code net.minecraft.client.*}），专用服务端没有它，
 * 所以本类挂在 {@link Dist#CLIENT} 的 MOD 事件总线上，服务端根本不会加载它。
 * 注册必须发生在物品模型被烘焙之前，{@link FMLClientSetupEvent#enqueueWork} 正好满足
 * ——这是 Forge 文档给的写法，晚于它注册会打出"模型已经烘焙完毕"的警告并失效。
 */
@Mod.EventBusSubscriber(modid = AmmoSupplyBox.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ShareItemModels {

    /** 属性名前缀，后缀是剩余次数。必须与 {@code models/item/*.json} 里的 predicate 键一致。 */
    private static final String PROPERTY_PREFIX = "share_uses_";

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // enqueueWork：把注册放到主线程上、且在资源加载（含物品模型烘焙）之前执行
        event.enqueueWork(ShareItemModels::register);
    }

    private static void register() {
        for (AmmoBoxTypes.Type type : AmmoBoxTypes.ALL) {
            if (type.variant() != AmmoBoxTypes.Variant.SHARE) {
                // 只有分享装有「剩余次数」这个概念，别的变体物品模型是静态的
                continue;
            }
            registerUsesProperties(ModItems.ammoBox(type.id()).get());
        }
        // 空投箱同样是"4 次用完就消失"的补给点，物品模型也按剩余次数换外观，
        // 用的是同一组属性名（属性按物品注册，不会和分享装冲突）
        for (AirdropBeaconTypes.Type type : AirdropBeaconTypes.ALL) {
            registerUsesProperties(ModItems.airdropCrate(type.tier()).get());
        }
    }

    /**
     * 给一个物品注册「剩 N 次」指示属性，N 从 1 到 {@code MAX_USES - 1}。
     *
     * <p>满次数不注册：那种情况走模型自己的 {@code parent}，见类注释。
     */
    private static void registerUsesProperties(Item item) {
        for (int uses = 1; uses < ShareBoxData.MAX_USES; uses++) {
            int target = uses;
            ItemProperties.register(item, propertyName(target),
                    // 读的是手上这一个物品的 NBT，所以同一种盒子剩 1 次和剩 3 次显示不同模型。
                    // 返回 0/1 而不是次数本身，见类注释「为什么是三个属性」。
                    (stack, level, entity, seed) ->
                            ShareBoxData.getRemainingUses(stack) == target ? 1.0F : 0.0F);
        }
    }

    /** {@code ammo_supply_box:share_uses_N}，N 为剩余次数。 */
    public static ResourceLocation propertyName(int uses) {
        return ResourceLocation.fromNamespaceAndPath(AmmoSupplyBox.MODID, PROPERTY_PREFIX + uses);
    }

    private ShareItemModels() {
    }
}
