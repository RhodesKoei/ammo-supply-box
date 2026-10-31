package com.koei.ammo_supply_box;

import com.koei.ammo_supply_box.config.AmmoSupplyBoxConfig;
import com.koei.ammo_supply_box.registry.ModBlockEntities;
import com.koei.ammo_supply_box.registry.ModBlocks;
import com.koei.ammo_supply_box.registry.ModCreativeTabs;
import com.koei.ammo_supply_box.registry.ModEntities;
import com.koei.ammo_supply_box.registry.ModItems;
import com.koei.ammo_supply_box.registry.ModLootModifiers;
import com.koei.ammo_supply_box.registry.ModSounds;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(AmmoSupplyBox.MODID)
public class AmmoSupplyBox {
    public static final String MODID = "ammo_supply_box";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AmmoSupplyBox(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        // 空投系统的两个实体（信号弹 / 光柱），渲染器在 ClientEntityRenderers 里挂载
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModSounds.SOUNDS.register(modEventBus);
        ModCreativeTabs.TABS.register(modEventBus);
        // 战利品修改器的序列化器；具体生效范围与概率在 data/... 的 json 里
        ModLootModifiers.SERIALIZERS.register(modEventBus);
        // 注册到 saves/<世界名>/serverconfig/ammo_supply_box-server.toml
        // 用 SERVER 而不是 COMMON：tooltip 由客户端渲染，而 Forge 只同步 SERVER 类型的配置
        context.registerConfig(ModConfig.Type.SERVER, AmmoSupplyBoxConfig.SPEC);
    }
}
