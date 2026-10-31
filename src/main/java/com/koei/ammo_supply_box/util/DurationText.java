package com.koei.ammo_supply_box.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 把一段时长统一渲染成「X 分 Y 秒」或「Y 秒」的本地化文本。
 *
 * <p>本模组有五个地方要显示冷却时长：物品 tooltip、{@code getconfig} 列表、设置确认回执、
 * 冷却中的拒绝提示、以及 Jade 那一行。它们措辞各不相同，但<b>拆分规则必须完全一致</b>——
 * 否则同一份配置在聊天里说「还需 4 分 59 秒」、在 Jade 里说「补货剩余：299 秒」，
 * 玩家根本没法互相对照。所以拆分规则只在这里写一遍。
 *
 * <h2>规则</h2>
 * <ul>
 *   <li>不足 1 分钟 → 只用秒，走 {@code secondsKey}；</li>
 *   <li>满 1 分钟 → 分 + 秒，走 {@code minutesKey}。</li>
 * </ul>
 * 这个"满一分钟才出现「分」"的形态与 Jade 自带条目的习惯一致，也是玩家最习惯的读法：
 * 「45 秒」比「0 分 45 秒」自然，而「5 分 20 秒」比「320 秒」直观。
 *
 * <h2>为什么是「两个键名当参数」而不是一个共享的时长键</h2>
 * 每个显示点的句子结构不同（有的前面还挂着弹药盒名字），如果共用一个
 * {@code "%s 分 %s 秒"} 的键，就得把时长作为<b>嵌套组件</b>塞进外层翻译里。
 * 那样虽然也能工作，但把"翻译里套翻译"引了进来，出错时表现很不直观。
 * 这里改成由调用方给出自己那一对键名，{@link #leadingArgs} 负责把名字等前缀参数接在前面，
 * 于是一个方法就能同时伺候「只有时长」和「名字 + 时长」两种句子。
 *
 * <p>注意所有模板里的占位符一律是 {@code %s}：{@code TranslatableContents} 对 {@code %d}
 * 会抛异常并原样显示模板，而 {@code %s} 既吃数字也吃文本组件。
 */
public final class DurationText {

    /** 一秒等于 20 刻。与 {@code AutoRestockAmmoBoxBlockEntity#startCooldown} 的换算同源。 */
    private static final long TICKS_PER_SECOND = 20L;

    private static final long SECONDS_PER_MINUTE = 60L;

    /**
     * 把刻换算成秒并<b>向上取整</b>。
     *
     * <p>必须向上取整：还剩 1 刻时如果算成 0 秒，玩家会看到「还需 0 秒」却拿不到补给，
     * 以为盒子坏了。
     */
    public static long ceilToSeconds(long ticks) {
        return (Math.max(0L, ticks) + TICKS_PER_SECOND - 1L) / TICKS_PER_SECOND;
    }

    /**
     * 由<b>刻</b>渲染，供"还剩多久"这类实时值使用（聊天拒绝提示、Jade 那一行）。
     *
     * @param minutesKey 满 1 分钟时用的模板，需按「前缀参数…、分、秒」的顺序接收占位符
     * @param secondsKey 不足 1 分钟时用的模板，需按「前缀参数…、秒」的顺序接收占位符
     * @param ticks      剩余刻数，内部会向上取整成秒
     * @param leadingArgs 接在时长前面的参数，例如弹药盒名字；没有就不传
     */
    public static MutableComponent ofTicks(String minutesKey, String secondsKey, long ticks,
                                           Object... leadingArgs) {
        return ofSeconds(minutesKey, secondsKey, ceilToSeconds(ticks), leadingArgs);
    }

    /**
     * 由<b>秒</b>渲染，供配置值这类本身就是秒的数量使用（tooltip、命令回执）。
     *
     * @see #ofTicks(String, String, long, Object...)
     */
    public static MutableComponent ofSeconds(String minutesKey, String secondsKey, long totalSeconds,
                                             Object... leadingArgs) {
        long safeSeconds = Math.max(0L, totalSeconds);
        long minutes = safeSeconds / SECONDS_PER_MINUTE;
        long seconds = safeSeconds % SECONDS_PER_MINUTE;

        if (minutes <= 0L) {
            return Component.translatable(secondsKey, concat(leadingArgs, seconds));
        }
        return Component.translatable(minutesKey, concat(leadingArgs, minutes, seconds));
    }

    /** 把前缀参数和时长参数拼成一个数组，交给 {@code Component.translatable} 当变参。 */
    private static Object[] concat(Object[] leadingArgs, Object... trailingArgs) {
        Object[] all = new Object[leadingArgs.length + trailingArgs.length];
        System.arraycopy(leadingArgs, 0, all, 0, leadingArgs.length);
        System.arraycopy(trailingArgs, 0, all, leadingArgs.length, trailingArgs.length);
        return all;
    }

    private DurationText() {
    }
}
