package com.koei.ammo_supply_box.registry;

import com.koei.ammo_supply_box.AmmoSupplyBox;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, AmmoSupplyBox.MODID);

    /**
     * 交互成功时播放的补给音效。
     *
     * <p>具体播哪一个由原版声音系统随机决定：三个音频都列在
     * {@code assets/ammo_supply_box/sounds.json} 的同一个事件下，
     * 每次播放时 {@code SoundEvent.getSound(random)} 会按权重挑一个（默认权重相同即等概率），
     * 所以不需要自己写随机逻辑。
     */
    public static final RegistryObject<SoundEvent> AMMO_BOX_SUPPLY = SOUNDS.register("ammo_box_supply",
            () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(AmmoSupplyBox.MODID, "ammo_box_supply")));

    /**
     * 空投相关音效（光柱升起 / 箱子落地）的可听半径（格）。
     *
     * <p><b>服务端与客户端都要配</b>：服务端按 {@code SoundEvent#getRange(volume)} 决定把声音包发给谁；
     * 客户端还会再用 {@code max(volume, 1) × attenuation_distance} 过滤一次，
     * 而 {@code attenuation_distance} 来自 {@code sounds.json}、缺省只有 16。
     * 所以本常量要和 {@code sounds.json} 里 {@code airdrop_beam} / {@code airdrop_land}
     * 两个条目的 {@code attenuation_distance} 保持一致，否则远超 16 格的那一段会
     * "服务端发了包、客户端不播"。
     *
     * <p>声明在两个 {@code SoundEvent} 之前：Java 不允许静态字段初始化器引用<b>后面</b>才声明的
     * 静态字段（illegal forward reference），哪怕引用写在 lambda 里也一样。
     */
    private static final float AIRDROP_SOUND_RANGE = 64.0F;

    /**
     * 信号弹命中顶面、光柱升起时的音效。
     *
     * <p>事件 id 是 {@code ammo_supply_box:airdrop_beam}，对应音频文件
     * {@code sounds/beam.ogg}。注意事件名和文件名不必相同：{@code sounds.json} 里
     * 这个事件的 {@code name} 写的是 {@code ammo_supply_box:beam}，指向的是<b>文件</b>。
     *
     * <h2>为什么用「固定距离」事件（由可变距离事件改过来）</h2>
     * 两种事件 {@code getRange(volume)} 的算法完全不同：
     * <ul>
     *   <li>{@code createVariableRangeEvent}：半径 = {@code volume > 1 ? 16 * volume : 16}
     *       ——<b>音量 ≤ 1 时半径被钉死 16 格</b>；想传更远只能把音量调到 1 以上，
     *       而客户端增益上限是 1.0，等于白调；</li>
     *   <li>{@code createFixedRangeEvent}：半径 = 构造时给的 {@code range}，<b>与音量无关</b>，
     *       所以能同时满足"响度正常（1.0、不削波）"和"传 64 格"。</li>
     * </ul>
     * 空投光柱本身在几百格外都能看到，提示音却只有 16 格就太割裂了，所以改成固定 64 格。
     */
    public static final RegistryObject<SoundEvent> AIRDROP_BEAM = SOUNDS.register("airdrop_beam",
            () -> SoundEvent.createFixedRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(AmmoSupplyBox.MODID, "airdrop_beam"),
                    AIRDROP_SOUND_RANGE));

    /**
     * 空投箱落地时的重物落地音效。
     *
     * <p>事件 id 与音频文件同名：{@code ammo_supply_box:airdrop_land} → {@code sounds/airdrop_land.ogg}。
     * 播放时音高固定为 1.0（不做随机化），保证每次落地听到的都是同一记闷响。
     *
     * <p>同样用固定距离事件、半径 {@value #AIRDROP_SOUND_RANGE} 格，理由见 {@link #AIRDROP_BEAM}：
     * 可变距离事件在音量 ≤ 1 时半径被钉死在 16 格，没法既保持响度又传得远。
     */
    public static final RegistryObject<SoundEvent> AIRDROP_LAND = SOUNDS.register("airdrop_land",
            () -> SoundEvent.createFixedRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(AmmoSupplyBox.MODID, "airdrop_land"),
                    AIRDROP_SOUND_RANGE));

    private ModSounds() {
    }
}
