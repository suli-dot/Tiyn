package kz.sultan.spendlimit.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import kz.sultan.spendlimit.R
import kz.sultan.spendlimit.domain.BudgetPeriod
import kz.sultan.spendlimit.domain.category.Categories

/**
 * Локальное уведомление, когда траты за день вышли за лимит.
 */
object LimitAlertNotifier {

    private const val CHANNEL_ID = "limit_alert"
    private const val NOTIFICATION_ID = 1001

    // База для ID уведомлений по категориям: смещение от дневного (1001),
    // плюс индекс категории — у каждой категории свой стабильный ID.
    private const val CATEGORY_NOTIFICATION_ID_BASE = 2000

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.alert_channel_desc)
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    /**
     * @param overspentTiyn на сколько превышен лимит (положительное число тиынов).
     */
    fun notifyExceeded(context: Context, overspentTiyn: Long) {
        post(
            context,
            title = "Дневной лимит превышен",
            text = "Вы вышли за лимит на ${Money.formatTiyn(overspentTiyn)}"
        )
    }

    /**
     * Предупреждение при приближении к лимиту (≈80%).
     * @param remainingTiyn сколько ещё можно потратить сегодня (положительное число).
     */
    fun notifyApproaching(context: Context, remainingTiyn: Long) {
        post(
            context,
            title = "Лимит на исходе",
            text = "Потрачено 80% дневного лимита. Осталось ${Money.formatTiyn(remainingTiyn)}"
        )
    }

    /**
     * Исчерпан лимит категории на конкретный период (день/неделя/месяц).
     * @param categorySlug категория; @param period период лимита.
     * @param spentTiyn потрачено за период; @param limitTiyn заданный лимит.
     */
    fun notifyCategoryExceeded(
        context: Context,
        categorySlug: String,
        period: BudgetPeriod,
        spentTiyn: Long,
        limitTiyn: Long
    ) {
        val c = Categories.bySlug(categorySlug)
        // Свой ID на пару категория×период — чтобы уведомления не затирали друг друга.
        val idx = Categories.ALL.indexOfFirst { it.slug == categorySlug }.coerceAtLeast(0)
        val id = CATEGORY_NOTIFICATION_ID_BASE + idx * BudgetPeriod.entries.size + period.ordinal
        post(
            context,
            title = "Исчерпан ${period.adjective} лимит",
            text = "${c.emoji} ${c.title}: потрачено ${Money.formatTiyn(spentTiyn)} из ${Money.formatTiyn(limitTiyn)} за ${period.title.lowercase()}",
            notificationId = id
        )
    }

    /** Общая отправка: канал, проверка разрешения (Android 13+). */
    private fun post(context: Context, title: String, text: String, notificationId: Int = NOTIFICATION_ID) {
        ensureChannel(context)

        // На Android 13+ нужно разрешение POST_NOTIFICATIONS.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
