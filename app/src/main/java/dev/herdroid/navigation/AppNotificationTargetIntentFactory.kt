package dev.herdroid.navigation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.herdroid.MainActivity
import dev.herdroid.core.model.AgentStatus
import dev.herdroid.core.model.OpenTargetIdentifiers
import javax.inject.Inject

internal class AppNotificationTargetIntentFactory @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val context = context.applicationContext

    fun launchIntent(notificationId: Int): PendingIntent = PendingIntent.getActivity(
        context,
        notificationId,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun targetIntent(
        target: OpenTargetIdentifiers,
        status: AgentStatus,
        notificationId: Int,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        notificationId,
        NotificationOpenPayload(target, status).toIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
