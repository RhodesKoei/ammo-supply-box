package com.koei.ammo_supply_box.registry;

import com.koei.ammo_supply_box.AmmoSupplyBox;
import com.koei.ammo_supply_box.loot.RandomAmmoBoxLootModifier;
import com.mojang.serialization.Codec;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 战利品修改器（Global Loot Modifier）的序列化器注册表。
 *
 * <p>注册的只是<b>序列化器</b>（{@code Codec}），具体"哪个箱子、多大概率、给什么"
 * 在数据文件里：{@code data/ammo_supply_box/loot_modifiers/*.json}，
 * 再由 {@code data/forge/loot_modifiers/global_loot_modifiers.json} 挂上去。
 * 这样整合包作者不需要改代码就能调整概率甚至换物品。
 */
public final class ModLootModifiers {

    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, AmmoSupplyBox.MODID);

    /** JSON 里的 {@code "type": "ammo_supply_box:random_ammo_box"} 对应的序列化器。 */
    public static final RegistryObject<Codec<RandomAmmoBoxLootModifier>> RANDOM_AMMO_BOX =
            SERIALIZERS.register("random_ammo_box", () -> RandomAmmoBoxLootModifier.CODEC);

    private ModLootModifiers() {
    }
}
