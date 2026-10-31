package com.koei.ammo_supply_box.event;

import com.koei.ammo_supply_box.AmmoSupplyBox;
import com.koei.ammo_supply_box.command.AmmoSupplyBoxCommand;
import com.koei.ammo_supply_box.loot.AmmoBoxLootInjection;
import com.koei.ammo_supply_box.util.GunSupplyOverride;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.LootTableLoadEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = AmmoSupplyBox.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommonEvents {

    /**
     * 数据包重载时清掉逐枪覆盖值的缓存。
     *
     * <p>枪包是资源包，{@code /reload} 或重新进入世界后枪械 data 文件的内容可能已经变了，
     * 不清缓存的话会一直用旧值。这个事件在服务端资源重载时触发，也覆盖首次进世界。
     */
    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        GunSupplyOverride.clearCache();
    }

    /**
     * 注册 {@code /ammosupplybox} 命令，用于在游戏内修改弹药盒配置。
     *
     * <p>用命令而不是 GUI：本模组配置是服务端权威的 SERVER 配置，
     * 客户端界面在专用服务器上改不动它。详见 {@link AmmoSupplyBoxCommand}。
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        AmmoSupplyBoxCommand.register(event.getDispatcher());
    }

    /**
     * 把普通弹药盒注入原版箱子战利品表。
     *
     * <p>投放规则（哪张表放哪一档、概率多少）见 {@link AmmoBoxLootInjection}。
     * 用事件而不是覆写原版 json：前者是追加，不会和其他模组或数据包冲突。
     */
    @SubscribeEvent
    public static void onLootTableLoad(LootTableLoadEvent event) {
        AmmoBoxLootInjection.onLootTableLoad(event);
    }

    private CommonEvents() {
    }
}
