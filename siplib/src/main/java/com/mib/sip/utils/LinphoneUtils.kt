/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
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
package com.mib.sip.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.telephony.TelephonyManager.*
import com.mib.sip.LinphoneContext.coreContext
import com.mib.sip.LinphoneContext.corePreferences
import com.mib.sip.R
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.Call
import org.linphone.core.CallLog
import org.linphone.core.Conference
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Various utility methods for Linphone SDK
 */
class LinphoneUtils {
    companion object {
        private const val RECORDING_DATE_PATTERN = "dd-MM-yyyy-HH-mm-ss"

        fun getDisplayName(address: Address?): String {
            if (address == null) return "[null]"
            if (address.displayName == null) {
                val account = coreContext.core.accountList.find { account ->
                    account.params.identityAddress?.asStringUriOnly() == address.asStringUriOnly()
                }
                val localDisplayName = account?.params?.identityAddress?.displayName
                // Do not return an empty local display name
                if (!localDisplayName.isNullOrEmpty()) {
                    return localDisplayName
                }
            }
            // Do not return an empty display name
            return address.displayName ?: address.username ?: address.asString()
        }

        fun getDisplayableAddress(address: Address?): String {
            if (address == null) return "[null]"
            return if (corePreferences.replaceSipUriByUsername) {
                address.username ?: address.asStringUriOnly()
            } else {
                val copy = address.clone()
                copy.clean() // To remove gruu if any
                copy.asStringUriOnly()
            }
        }

        fun getCleanedAddress(address: Address): Address {
            // To remove the GRUU if any
            val cleanAddress = address.clone()
            cleanAddress.clean()
            return cleanAddress
        }

        fun getConferenceAddress(call: Call): Address? {
            val remoteContact = call.remoteContact
            val conferenceAddress = if (call.dir == Call.Dir.Incoming) {
                if (remoteContact != null) {
                    coreContext.core.interpretUrl(remoteContact, false)
                } else {
                    null
                }
            } else {
                call.remoteAddress
            }
            return conferenceAddress
        }

        fun getConferenceSubject(conference: Conference): String? {
            return if (conference.subject.isNullOrEmpty()) {
                val conferenceInfo = coreContext.core.findConferenceInformationFromUri(
                    conference.conferenceAddress
                )
                if (conferenceInfo != null) {
                    conferenceInfo.subject
                } else {
                    if (conference.me.isFocus) {
                        coreContext.context.getString(R.string.conference_local_title)
                    } else {
                        coreContext.context.getString(R.string.conference_default_title)
                    }
                }
            } else {
                conference.subject
            }
        }

        fun isEndToEndEncryptedChatAvailable(): Boolean {
            val core = coreContext.core
            return core.isLimeX3DhEnabled &&
                (core.limeX3DhServerUrl != null || core.defaultAccount?.params?.limeServerUrl != null) &&
                core.defaultAccount?.params?.conferenceFactoryUri != null
        }

        fun getRecordingFilePathForAddress(address: Address): String {
            val displayName = getDisplayName(address)
            val dateFormat: DateFormat = SimpleDateFormat(
                RECORDING_DATE_PATTERN,
                Locale.getDefault()
            )
            val fileName = "${displayName}_${dateFormat.format(Date())}.mkv"
            return FileUtils.getFileStoragePath(fileName).absolutePath
        }

        fun getRecordingFilePathForConference(subject: String?): String {
            val dateFormat: DateFormat = SimpleDateFormat(
                RECORDING_DATE_PATTERN,
                Locale.getDefault()
            )
            val fileName = if (subject.isNullOrEmpty()) {
                "conference_${dateFormat.format(Date())}.mkv"
            } else {
                "${subject}_${dateFormat.format(Date())}.mkv"
            }
            return FileUtils.getFileStoragePath(fileName).absolutePath
        }

        fun getRecordingDateFromFileName(name: String): Date {
            return SimpleDateFormat(RECORDING_DATE_PATTERN, Locale.getDefault()).parse(name)
        }

        @SuppressLint("MissingPermission")
        fun checkIfNetworkHasLowBandwidth(context: Context): Boolean {
            val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkInfo: NetworkInfo? = connMgr.activeNetworkInfo
            if (networkInfo != null && networkInfo.isConnected) {
                if (networkInfo.type == ConnectivityManager.TYPE_MOBILE) {
                    return when (networkInfo.subtype) {
                        NETWORK_TYPE_EDGE, NETWORK_TYPE_GPRS, NETWORK_TYPE_IDEN -> true
                        else -> false
                    }
                }
            }
            // In doubt return false
            return false
        }

        fun isCallLogMissed(callLog: CallLog): Boolean {
            return (
                callLog.dir == Call.Dir.Incoming &&
                    (
                        callLog.status == Call.Status.Missed ||
                            callLog.status == Call.Status.Aborted ||
                            callLog.status == Call.Status.EarlyAborted
                        )
                )
        }


        fun getAccountsNotHidden(): List<Account> {
            val list = arrayListOf<Account>()

            for (account in coreContext.core.accountList) {
                if (account.getCustomParam("hidden") != "1") {
                    list.add(account)
                }
            }

            return list
        }

        fun applyInternationalPrefix(): Boolean {
            val account = coreContext.core.defaultAccount
            if (account != null) {
                val params = account.params
                return params.useInternationalPrefixForCallsAndChats
            }

            return true // Legacy behavior
        }

        fun isPushNotificationAvailable(): Boolean {
            val core = coreContext.core
            if (!core.isPushNotificationAvailable) {
                return false
            }

            val pushConfig = core.pushNotificationConfig ?: return false
            if (pushConfig.provider.isNullOrEmpty()) return false
            if (pushConfig.param.isNullOrEmpty()) return false
            if (pushConfig.prid.isNullOrEmpty()) return false

            return true
        }

        fun isFileTransferAvailable(): Boolean {
            val core = coreContext.core
            return core.fileTransferServer.orEmpty().isNotEmpty()
        }

    }
}
