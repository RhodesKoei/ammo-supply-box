package com.koei.ammo_supply_box.registry;

import com.koei.ammo_supply_box.AmmoSupplyBox;
import com.koei.ammo_supply_box.block.AmmoBoxTypes;
import com.koei.ammo_supply_box.item.AirdropBeaconTypes;
import com.koei.ammo_supply_box.item.RandomAmmoBoxTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AmmoSupplyBox.MODID);

    /**
     * 专属物品栏页。不指定 backgroundSuffix 时会用原版的
     * {@code textures/gui/container/creative_inventory/tab_items.png}，无需额外贴图。
     *
     * <p>标签页内容由 {@link AmmoBoxTypes#ALL} 自动填充，新增弹药盒无需改动这里。
     */
    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + AmmoSupplyBox.MODID))
                    .icon(() -> new ItemStack(ModItems.ammoBox(AmmoBoxTypes.ALL.get(0).id()).get()))
                    .displayItems((parameters, output) -> {
                        for (AmmoBoxTypes.Type type : AmmoBoxTypes.ALL) {
                            output.accept(ModItems.ammoBox(type.id()).get());
                        }
                        // 随机弹药盒紧跟 25 个方块弹药盒之后：它属于同一族"弹药盒"，
                        // 但排在最后，不打断 ammo_box_1..5 四档的连续排列
                        for (RandomAmmoBoxTypes.Type type : RandomAmmoBoxTypes.ALL) {
                            output.accept(ModItems.randomAmmoBox(type.id()).get());
                        }
                        // 空投系统：先 5 个投掷信标，再 5 个空投箱，按档位顺序一一对应
                        for (AirdropBeaconTypes.Type type : AirdropBeaconTypes.ALL) {
                            output.accept(ModItems.airdropBeacon(type.tier()).get());
                        }
                        for (AirdropBeaconTypes.Type type : AirdropBeaconTypes.ALL) {
                            output.accept(ModItems.airdropCrate(type.tier()).get());
                        }
                        // 装饰方块与合成材料放在最后
                        output.accept(ModItems.WOODEN_TRAY.get());
                        output.accept(ModItems.WOODEN_WALKWAY.get());
                        output.accept(ModItems.UPGRADE_CORE.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
