/*
 * Copyright (c) 2010-2021 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.mib.sip.compatibility

import android.annotation.TargetApi
import android.app.*
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mib.sip.LinphoneContext.coreContext
import com.mib.sip.LinphoneContext.corePreferences
import com.mib.sip.R
import com.mib.sip.utils.LinphoneUtils
import org.linphone.core.Call
import com.mib.sip.notifications.Notifiable
import com.mib.sip.notifications.NotificationsManager

@TargetApi(26)
class XiaomiCompatibility {
    companion object {
        fun createIncomingCallNotification(
            context: Context,
            call: Call,
            notifiable: Notifiable,
            pendingIntent: PendingIntent,
            notificationsManager: NotificationsManager
        ): Notification {
            val address: String
            val info: String

            val remoteContact = call.remoteContact
            val conferenceAddress = if (remoteContact != null) {
                coreContext.core.interpretUrl(
                    remoteContact,
                    false
                )
            } else {
                null
            }
            val conferenceInfo = if (conferenceAddress != null) {
                coreContext.core.findConferenceInformationFromUri(
                    conferenceAddress
                )
            } else {
                null
            }
            if (conferenceInfo == null) {
                address = LinphoneUtils.getDisplayableAddress(call.remoteAddress)
                info = context.getString(R.string.incoming_call_notification_title)
            } else {
                address = LinphoneUtils.getDisplayableAddress(conferenceInfo.organizer)
                info = context.getString(R.string.incoming_group_call_notification_title)
            }

            val builder = NotificationCompat.Builder(
                context,
                context.getString(R.string.notification_channel_incoming_call_id)
            )
                .setSmallIcon(R.drawable.topbar_call_notification)
                .setContentTitle("")
                .setContentText(address)
                .setSubText(info)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(false)
                .setShowWhen(true)
                .setOngoing(true)
                .setColor(ContextCompat.getColor(context, R.color.primary_color))
                .setFullScreenIntent(pendingIntent, true)
                .addAction(notificationsManager.getCallDeclineAction(notifiable))
                .addAction(notificationsManager.getCallAnswerAction(notifiable))

            if (!corePreferences.preventInterfaceFromShowingUp) {
                builder.setContentIntent(pendingIntent)
            }

            return builder.build()
        }
    }
}
