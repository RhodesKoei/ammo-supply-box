package com.koei.ammo_supply_box.client;

import com.koei.ammo_supply_box.AmmoSupplyBox;
import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.resource.PathPackResources;
import org.slf4j.Logger;

/**
 * 内置的<b>可选</b>资源包：把空投箱落地音换成 {@code airdrop_land_replace.ogg}。
 *
 * <h2>为什么做成资源包，而不是直接改 sounds.json</h2>
 * 直接把 {@code assets/ammo_supply_box/sounds.json} 里 {@code airdrop_land} 的 {@code name}
 * 改成新文件更简单，但那样就没法"听回原音效"了。做成资源包之后：
 * <ul>
 *   <li><b>默认关闭</b>：刚装好模组时听到的仍是原音效 {@code airdrop_land.ogg}；</li>
 *   <li>玩家在「选项 → 资源包」里启用它，才换成 {@code airdrop_land_replace.ogg}；</li>
 *   <li>随时可以取消勾选换回去，不需要重装模组。</li>
 * </ul>
 *
 * <h2>为什么包里的 sounds.json 必须写 {@code "replace": true}</h2>
 * 这是踩过的坑，值得记下来。{@code SoundManager.Preparations#handleRegistration} 的默认行为是
 * <b>把各资源包的 sounds 列表累加</b>进同一个事件：
 * <pre>
 *   WeighedSoundEvents event = registry.get(id);
 *   if (event == null || registration.isReplace()) { event = 新建; registry.put(id, event); }
 *   for (Sound sound : registration.getSounds()) { event.addSound(sound); }   // ← 默认是追加
 * </pre>
 * 换句话说，只写 {@code name} 而不写 {@code replace}，结果是"旧音效 + 新音效都进同一个事件"，
 * 每次播放随机挑一个——听起来像两个音效共存，而不是替换。
 * {@code replace} 的语义在 {@code SoundEventRegistration} 里写得很明白：
 * <i>"if true it will override all the sounds from the resourcepacks loaded before"</i>。
 *
 * <p>因为开了 {@code replace} 会<b>整条事件替换</b>，所以包里把 {@code subtitle} 和
 * {@code attenuation_distance} 也照抄了一份，否则启用资源包后字幕会丢、64 格可听距离会掉回默认 16。
 *
 * <p>另外包里<b>只提供 sounds.json，不复制 ogg</b>：{@code airdrop_land_replace.ogg} 已经在
 * 模组自己的 {@code assets/ammo_supply_box/sounds/} 里，而音效文件是沿整条资源包栈解析的，
 * 所以包的映射指向它就能播到，省下一份 100 KB 的重复文件。
 *
 * <h2>关于"默认启用"</h2>
 * 按需求本包<b>不做任何自动启用</b>：原版的 {@code PackSource#shouldAddAutomatically()} 只在
 * 服务端数据包那条路径被用到（{@code MinecraftServer} 里），客户端资源包列表不认它，
 * 所以用 {@link PackSource#BUILT_IN} 注册进来的包天然就是关闭状态，正好符合预期。
 * 玩家手动启用后，选择由原版 {@code Options#resourcePacks} 持久化，模组这边不需要额外记录。
 */
@Mod.EventBusSubscriber(modid = AmmoSupplyBox.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BuiltinLandSoundPack {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 资源包在 jar 内的目录名。
     *
     * <p>刻意放在 {@code resourcepacks/} 下而不是 {@code assets/} 下：这样它不会被当作
     * 模组资源直接加载，只有玩家在资源包界面选中它之后才会生效。
     */
    private static final String PACK_FOLDER = "airdrop_land_sound";

    /** 资源包 id，也就是 {@code options.txt} 里 {@code resourcePacks} 列表中的那一项。 */
    private static final String PACK_ID = AmmoSupplyBox.MODID + ":" + PACK_FOLDER;

    /**
     * 把资源包挂进资源包仓库。
     *
     * <p>只处理客户端资源（{@link PackType#CLIENT_RESOURCES}）：数据包那份仓库里没有我们的东西。
     */
    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        event.addRepositorySource(packs -> {
            Path root = packRoot();
            if (root == null) {
                LOGGER.warn("[ammo_supply_box] 找不到内置资源包目录 {}，本次不会提供落地音效替换包", PACK_FOLDER);
                return;
            }
            // required = false：默认关闭，玩家可自由勾选/取消
            Pack pack = Pack.readMetaAndCreate(
                    PACK_ID,
                    Component.translatable("resourcepack.ammo_supply_box.airdrop_land_sound"),
                    false,
                    id -> new PathPackResources(id, true, root),
                    PackType.CLIENT_RESOURCES,
                    Pack.Position.TOP,
                    PackSource.BUILT_IN);
            if (pack == null) {
                // readMetaAndCreate 返回 null 表示 pack.mcmeta 缺失或格式不对
                LOGGER.warn("[ammo_supply_box] 内置资源包 {} 的 pack.mcmeta 读取失败", PACK_FOLDER);
                return;
            }
            packs.accept(pack);
        });
    }

    /**
     * 取资源包目录在运行时的真实路径。
     *
     * <p>开发环境是 {@code build/resources/main/resourcepacks/...} 这样的普通目录；
     * 正式环境是模组 jar 内部的路径（FML 把 jar 挂成了文件系统，所以 {@link Path} 一样能用
     * {@code java.nio.file.Files} 读取，这正是 {@link PathPackResources} 的工作方式）。
     */
    private static Path packRoot() {
        var modFileInfo = ModList.get().getModFileById(AmmoSupplyBox.MODID);
        if (modFileInfo == null) {
            return null;
        }
        return modFileInfo.getFile().findResource("resourcepacks", PACK_FOLDER);
    }

    private BuiltinLandSoundPack() {
    }
}
