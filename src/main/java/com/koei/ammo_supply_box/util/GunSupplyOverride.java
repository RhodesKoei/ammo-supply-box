package com.koei.ammo_supply_box.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koei.ammo_supply_box.AmmoSupplyBox;
import com.tacz.guns.resource.index.CommonGunIndex;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * 逐枪的弹药盒补给配置。
 *
 * <p>枪包作者可以在枪械自己的 data 文件顶层加一段 {@code ammo_supply_box}：
 *
 * <pre>
 * {
 *   // ...TACZ 原有字段...
 *
 *   "ammo_supply_box": {
 *     "active": true,
 *     "ammo_box": {
 *       "ammo_box_1": 30,
 *       "ammo_box_2": 120
 *     }
 *   }
 * }
 * </pre>
 *
 * <ul>
 *   <li>{@code active}——是否允许该枪从弹药盒获取子弹，<b>默认 true</b>。
 *       为 false 时交互完全无回应（不给弹药、不响、不摧毁方块、也不发提示）。</li>
 *   <li>{@code ammo_box}——各弹药盒 id 对应的<b>弹药物品个数</b>。
 *       没写某个盒子时该盒子回落到总伤害预算算法；写成 0 或负数表示明确拒绝交互。
 *       <b>这里写的是绝对值</b>，实际发放时还会再乘上模组配置里的
 *       {@code gunPackAmmoMultiplier}（默认 1.0，见 {@code AmmoSupplyBoxConfig}），
 *       方便整合包作者在改过各盒子补给预算后统一缩放这些数字。</li>
 * </ul>
 *
 * <p>整段都可以不写——那样就是"启用 + 全部走默认算法"，保证不认识本模组的枪包照常工作。
 *
 * <h2>为什么可以直接读到</h2>
 * <p>TACZ 的 {@code GunPackLoader} 实现了 {@code RepositorySource} 并产出 {@code PackResources}，
 * 也就是说枪包就是普通的原版资源包，其 {@code data/} 目录能被标准 {@code ResourceManager} 读到。
 * 文件路径由枪械索引里的 {@code data} 字段推导：
 * <pre>
 * 索引的 data = "exception_armorer:accatran_xii_data"
 *   → data/&lt;命名空间&gt;/data/guns/&lt;路径&gt;.json
 *   → data/exception_armorer/data/guns/accatran_xii_data.json
 * </pre>
 * （{@code data/guns} 这个约定是从 TACZ 的 {@code CommonAssetsManager} 里反编译确认的。）
 *
 * <p>TACZ 自己用 GSON 解析这些文件，未知顶层字段会被忽略，所以加这一段对 TACZ 无害。
 */
public final class GunSupplyOverride {

    /** 枪械 data 文件里承载本模组配置的字段名。 */
    public static final String FIELD_NAME = "ammo_supply_box";

    /** 枪械 data 文件 id -> 解析结果。 */
    private static final Map<ResourceLocation, GunSupplyConfig> CACHE = new ConcurrentHashMap<>();

    private GunSupplyOverride() {
    }

    /**
     * 一种弹药盒的逐枪配置。
     *
     * @param active  是否允许该枪从弹药盒获取子弹
     * @param amounts 弹药盒 id -&gt; 弹药物品个数；没配的盒子不在表里
     */
    public record GunSupplyConfig(boolean active, Map<String, Integer> amounts) {

        /** 枪械没写 {@code ammo_supply_box} 时的默认值：启用 + 全部走默认算法。 */
        public static final GunSupplyConfig DEFAULT = new GunSupplyConfig(true, Collections.emptyMap());
    }

    /**
     * 取某把枪的弹药盒补给配置。没写或读失败都返回 {@link GunSupplyConfig#DEFAULT}。
     */
    public static GunSupplyConfig getConfig(CommonGunIndex gunIndex) {
        ResourceLocation dataId = gunIndex.getPojo().getData();
        if (dataId == null) {
            return GunSupplyConfig.DEFAULT;
        }
        return CACHE.computeIfAbsent(dataId, GunSupplyOverride::readConfig);
    }

    /** 数据包重载后枪械 data 文件可能已经变化，必须让缓存失效。 */
    public static void clearCache() {
        CACHE.clear();
    }

    /** 读出指定枪械 data 文件里的配置；读不到或没写都返回默认值。 */
    private static GunSupplyConfig readConfig(ResourceLocation dataId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            trace(dataId, "服务端尚未就绪，按默认配置处理");
            return GunSupplyConfig.DEFAULT;
        }

        ResourceLocation fileId = ResourceLocation.fromNamespaceAndPath(
                dataId.getNamespace(), "data/guns/" + dataId.getPath() + ".json");
        Optional<Resource> resource = server.getResourceManager().getResource(fileId);
        if (resource.isEmpty()) {
            // 这是最容易被误判成"配置写错了"的一种情况：文件在枪包目录里确实存在，
            // 但服务端的 ResourceManager 看不到它。所以把推导出的 fileId 打出来。
            trace(dataId, "服务端 ResourceManager 里找不到 " + fileId + "，按默认配置处理");
            return GunSupplyConfig.DEFAULT;
        }

        try (InputStream input = resource.get().open();
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            // TACZ 的 data 文件带 // 与 /* */ 注释，实测 GSON 的 JsonParser 能直接解析
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                trace(dataId, fileId + " 的根节点不是 JSON 对象，按默认配置处理");
                return GunSupplyConfig.DEFAULT;
            }
            JsonElement field = ((JsonObject) root).get(FIELD_NAME);
            if (field == null || !field.isJsonObject()) {
                trace(dataId, fileId + " 里没有 " + FIELD_NAME + " 段，按默认配置处理");
                return GunSupplyConfig.DEFAULT;
            }

            JsonObject section = (JsonObject) field;

            // active 缺失时默认 true；写成非布尔值也按 true 处理，不让一个笔误废掉整把枪
            JsonElement activeElement = section.get("active");
            boolean active = activeElement == null
                    || !activeElement.isJsonPrimitive()
                    || !activeElement.getAsJsonPrimitive().isBoolean()
                    || activeElement.getAsBoolean();

            Map<String, Integer> amounts = new LinkedHashMap<>();
            JsonElement ammoBoxElement = section.get("ammo_box");
            if (ammoBoxElement != null && ammoBoxElement.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : ((JsonObject) ammoBoxElement).entrySet()) {
                    JsonElement value = entry.getValue();
                    // 只接受数字，写错类型的条目直接忽略并留给回落逻辑
                    if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                        amounts.put(entry.getKey(), value.getAsInt());
                    } else {
                        trace(dataId, "ammo_box." + entry.getKey() + " 的值不是数字，已忽略");
                    }
                }
            }

            trace(dataId, "读取成功 " + fileId + "  active=" + active + "  amounts=" + amounts);
            return new GunSupplyConfig(active, Collections.unmodifiableMap(amounts));
        } catch (Exception e) {
            // 仍然回落到默认行为，不让一把枪的笔误影响游戏运行；但要留下痕迹，
            // 否则"配置没生效"会变成一个完全无从下手的问题。
            trace(dataId, "解析 " + fileId + " 失败: " + e);
            return GunSupplyConfig.DEFAULT;
        }
    }

    /**
     * 诊断日志。
     *
     * <p>用 DEBUG 级别：正式版的 {@code latest.log} 里不会出现，
     * 但开发环境（{@code run/logs/debug.log}）会完整记录，排查"枪包配置没生效"时很有用。
     * 因为结果被 {@link #CACHE} 缓存，每个 data 文件只会打一次。
     */
    private static void trace(ResourceLocation dataId, String message) {
        AmmoSupplyBox.LOGGER.debug("[ammo-supply] {}: {}", dataId, message);
    }
}
