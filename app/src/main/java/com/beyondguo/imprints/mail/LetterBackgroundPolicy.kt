package com.beyondguo.imprints.mail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台运行策略（ML-06/ML-09）。
 *
 * 数据库是事实来源，但「到点提醒」依赖系统把 AlarmManager 广播送到我们进程：
 * 进程被杀（划掉后台、厂商清理）后，若应用还处在系统的电池优化/受限名单里，
 * 部分 ROM（实测 MIUI 12：`Restricted packages` + `standby bucket = RARE`）会**直接丢弃精确闹钟**，
 * 表现就是「到期没有通知，直到再次打开应用才对账补发」。
 *
 * 因此引导用户把本应用加入「忽略电池优化」白名单，是送达提醒可靠性的前提条件。
 */
object LetterBackgroundPolicy {
    /** 是否已豁免电池优化（可直接查系统状态，无需额外权限）。 */
    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 请求豁免：优先弹系统的「允许后台运行」对话框；
     * 没有对应页面时退回到电池优化列表，让用户手动把印迹设为「不优化」。
     *
     * 已豁免时直接返回 —— 系统什么都不会弹，而且已豁免的 App 不会出现在电池优化列表里，
     * 跳过去也看不到自己。调用方（设置页）在已豁免时应把整行设为不可点，
     * 而不是调这里再给一句提示。
     */
    fun requestExemption(context: Context) {
        if (isExempt(context)) return
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching {
            context.startActivity(direct)
            true
        }.getOrDefault(false)
        if (!opened) runCatching { context.startActivity(fallback) }
    }

    /** 打开本应用的通知设置（含厂商的「通知过滤规则」）。 */
    fun openNotificationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 打开本应用在系统里的信息页。
     *
     * 为什么需要它：电池优化豁免**只有申请 API、没有撤销 API**，所以"已豁免后想关掉或检查"
     * 只能把用户送到系统页。MIUI 的这个页面还带「自启动」开关与「电量消耗」（省电策略），
     * 是真正能调整后台存活能力的地方。
     * 拿不到应用详情页时退回到电池优化列表。
     */
    fun openAppDetails(context: Context) {
        val opened = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
        if (!opened) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
