package com.koei.ammo_supply_box.registry;

import com.koei.ammo_supply_box.AmmoSupplyBox;
import com.koei.ammo_supply_box.block.AmmoBoxTypes;
import com.koei.ammo_supply_box.block.AutoRestockAmmoBoxBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 方块实体类型注册。
 *
 * <p>目前只有「自动补货弹药盒」需要方块实体（记录冷却），其余弹药盒是纯方块。
 */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, AmmoSupplyBox.MODID);

    public static final RegistryObject<BlockEntityType<AutoRestockAmmoBoxBlockEntity>> AUTO_RESTOCK_AMMO_BOX =
            BLOCK_ENTITIES.register("auto_restock_ammo_box",
                    () -> BlockEntityType.Builder.of(
                            AutoRestockAmmoBoxBlockEntity::new,
                            cooldownBlocks()).build(null));

    /**
     * 所有带冷却的弹药盒方块。
     *
     * <p>由清单推导，新增带冷却的弹药盒不需要改这里。
     *
     * <p>这里可以安全地调 {@code RegistryObject.get()}：<b>方块</b>注册表在 Forge 的注册顺序里
     * 先于<b>方块实体类型</b>注册表，而且这段代码是在注册事件真正触发时才执行的
     * （它被包在 {@code register} 的 supplier 里），此时方块早已就绪。
     */
    private static Block[] cooldownBlocks() {
        return AmmoBoxTypes.ALL.stream()
                .filter(type -> type.cooldownSeconds() > 0)
                .map(type -> ModBlocks.ammoBox(type.id()).get())
                .toArray(Block[]::new);
    }

    private ModBlockEntities() {
    }
}
