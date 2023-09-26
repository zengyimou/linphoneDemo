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
package com.mib.sip.core

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Pair
import android.view.*
import android.webkit.MimeTypeMap
import androidx.lifecycle.*
import com.mib.sip.LinphoneContext.corePreferences
import com.mib.sip.R
import com.mib.sip.compatibility.Compatibility
import com.mib.sip.compatibility.PhoneStateInterface
import com.mib.sip.logger.Logger
import com.mib.sip.notifications.NotificationsManager
import com.mib.sip.telecom.TelecomHelper
import com.mib.sip.utils.AppUtils
import com.mib.sip.utils.AudioRouteUtils
import com.mib.sip.utils.Event
import com.mib.sip.utils.FileUtils
import com.mib.sip.utils.LinphoneUtils
import com.mib.sip.utils.PermissionHelper
import kotlinx.coroutines.*
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.Call
import org.linphone.core.CallParams
import org.linphone.core.ChatMessage
import org.linphone.core.ChatRoom
import org.linphone.core.Config
import org.linphone.core.ConsolidatedPresence
import org.linphone.core.Content
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.GlobalState
import org.linphone.core.LogLevel
import org.linphone.core.LoggingService
import org.linphone.core.LoggingServiceListenerStub
import org.linphone.core.MediaDirection
import org.linphone.core.MediaEncryption
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.tools.Log
import org.linphone.core.tools.service.ActivityMonitor
import org.linphone.mediastream.Version
import java.io.File
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.text.Collator
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * @author cengyimou
 * @constructor
 */
class CoreContext(
    val context: Context,
    coreConfig: Config,
    service: CoreService? = null,
    useAutoStartDescription: Boolean = false
) : LifecycleOwner, ViewModelStoreOwner {

    private val _lifecycleRegistry = LifecycleRegistry(this)

    private val _viewModelStore = ViewModelStore()

    override fun getLifecycle(): Lifecycle {
        return _lifecycleRegistry
    }

    override fun getViewModelStore(): ViewModelStore {
        return _viewModelStore
    }

    private val collator: Collator = Collator.getInstance()

    var stopped = false
    val core: Core
    val handler: Handler = Handler(Looper.getMainLooper())

    val notificationsManager: NotificationsManager by lazy {
        NotificationsManager(context)
    }

    val callErrorMessageResourceId: MutableLiveData<Event<String>> by lazy {
        MutableLiveData<Event<String>>()
    }

    val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val loggingService = Factory.instance().loggingService

    private var previousCallState = Call.State.Idle
    private lateinit var phoneStateListener: PhoneStateInterface

    private val activityMonitor = ActivityMonitor()

    /**
     * 最后一次记录的呼叫类型 0初始化 1呼入 2呼出
     */
    private var lastCallType = CALL_INIT

    private val listener: CoreListenerStub = object : CoreListenerStub() {
        override fun onGlobalStateChanged(core: Core, state: GlobalState, message: String) {
            Logger.i(TAG, "[Context] Global state changed [$state]")
            if (state == GlobalState.On) {
                if (corePreferences.disableVideo) {
                    // if video has been disabled, don't forget to tell the Core to disable it as well
                    Logger.w(TAG, 
                        "[Context] Video has been disabled in app, disabling it as well in the Core"
                    )
                    core.isVideoCaptureEnabled = false
                    core.isVideoDisplayEnabled = false

                    val videoPolicy = core.videoActivationPolicy
                    videoPolicy.automaticallyInitiate = false
                    videoPolicy.automaticallyAccept = false
                    core.videoActivationPolicy = videoPolicy
                }

            }
        }

        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            Logger.i(TAG, 
                "[Context] Account [${account.params.identityAddress?.asStringUriOnly()}] registration state changed [$state]"
            )
            if (state == RegistrationState.Ok && account == core.defaultAccount) {
                notificationsManager.stopForegroundNotificationIfPossible()
            }
        }

        override fun onPushNotificationReceived(core: Core, payload: String?) {
            Logger.i(TAG, "[Context] Push notification received: $payload")
        }

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State,
            message: String
        ) {
            Logger.i(TAG, "[Context] Call state changed [$state]")
            if (state == Call.State.IncomingReceived || state == Call.State.IncomingEarlyMedia) {

                if (declineCallDueToGsmActiveCall()) {
                    call.decline(Reason.Busy)
                    return
                }

                // Starting SDK 24 (Android 7.0) we rely on the fullscreen intent of the call incoming notification
//                if (Version.sdkStrictlyBelow(Version.API24_NOUGAT_70)) {
//                    onIncomingReceived()
//                }
                onIncomingReceived()

                if (corePreferences.autoAnswerEnabled) {
                    val autoAnswerDelay = corePreferences.autoAnswerDelay
                    if (autoAnswerDelay == 0) {
                        Logger.w(TAG, "[Context] Auto answering call immediately")
                        answerCall(call)
                    } else {
                        Logger.i(TAG, 
                            "[Context] Scheduling auto answering in $autoAnswerDelay milliseconds"
                        )
                        handler.postDelayed(
                            {
                                Logger.w(TAG, "[Context] Auto answering call")
                                answerCall(call)
                            },
                            autoAnswerDelay.toLong()
                        )
                    }
                }
            } else if (state == Call.State.OutgoingProgress) {
                val conferenceInfo = core.findConferenceInformationFromUri(call.remoteAddress)
                // Do not show outgoing call view for conference calls, wait for connected state
                if (conferenceInfo == null) {
                    onOutgoingStarted()
                }

                if (core.callsNb == 1 && corePreferences.routeAudioToBluetoothIfAvailable) {
                    AudioRouteUtils.routeAudioToBluetooth(call)
                }
            } else if (state == Call.State.Connected) {
                onCallStarted()
            } else if (state == Call.State.StreamsRunning) {
                if (previousCallState == Call.State.Connected) {
                    // Do not automatically route audio to bluetooth after first call
                    if (core.callsNb == 1) {
                        // Only try to route bluetooth / headphone / headset when the call is in StreamsRunning for the first time
                        Logger.i(TAG, 
                            "[Context] First call going into StreamsRunning state for the first time, trying to route audio to headset or bluetooth if available"
                        )
                        if (AudioRouteUtils.isHeadsetAudioRouteAvailable()) {
                            AudioRouteUtils.routeAudioToHeadset(call)
                        } else if (corePreferences.routeAudioToBluetoothIfAvailable && AudioRouteUtils.isBluetoothAudioRouteAvailable()) {
                            AudioRouteUtils.routeAudioToBluetooth(call)
                        }
                    }

                    // Only start call recording when the call is in StreamsRunning for the first time
                    if (corePreferences.automaticallyStartCallRecording && !call.params.isRecording) {
                        if (call.conference == null) { // TODO: FIXME: We disabled conference recording for now
                            Logger.i(TAG, 
                                "[Context] We were asked to start the call recording automatically"
                            )
                            call.startRecording()
                        }
                    }
                }
            } else if (state == Call.State.End || state == Call.State.Error || state == Call.State.Released) {
                if (state == Call.State.Error) {
                    Logger.w(TAG,
                        "[Context] Call error reason is ${call.errorInfo.protocolCode} / ${call.errorInfo.reason} / ${call.errorInfo.phrase}"
                    )
                    val toastMessage = when (call.errorInfo.reason) {
                        Reason.Busy -> context.getString(R.string.call_error_user_busy)
                        Reason.IOError -> context.getString(R.string.call_error_io_error)
                        Reason.NotAcceptable -> context.getString(
                            R.string.call_error_incompatible_media_params
                        )
                        Reason.NotFound -> context.getString(R.string.call_error_user_not_found)
                        Reason.ServerTimeout -> context.getString(
                            R.string.call_error_server_timeout
                        )
                        Reason.TemporarilyUnavailable -> context.getString(
                            R.string.call_error_temporarily_unavailable
                        )
                        else -> context.getString(R.string.call_error_generic).format(
                            "${call.errorInfo.protocolCode} / ${call.errorInfo.phrase}"
                        )
                    }
                    callErrorMessageResourceId.value = Event(toastMessage)
                } else if (state == Call.State.End &&
                    call.dir == Call.Dir.Outgoing &&
                    call.errorInfo.reason == Reason.Declined &&
                    core.callsNb == 0
                ) {
                    Logger.i(TAG, "[Context] Call has been declined")
                    val toastMessage = context.getString(R.string.call_error_declined)
                    callErrorMessageResourceId.value = Event(toastMessage)
                }
            }

            previousCallState = state
        }

        override fun onLastCallEnded(core: Core) {
            Logger.i(TAG, "[Context] Last call has ended")
            if (!core.isMicEnabled) {
                Logger.w(TAG, "[Context] Mic was muted in Core, enabling it back for next call")
                core.isMicEnabled = true
            }
        }

        override fun onMessagesReceived(
            core: Core,
            chatRoom: ChatRoom,
            messages: Array<out ChatMessage>
        ) {
            for (message in messages) {
                exportFileInMessage(message)
            }
        }
    }

    private val loggingServiceListener = object : LoggingServiceListenerStub() {
        override fun onLogMessageWritten(
            logService: LoggingService,
            domain: String,
            level: LogLevel,
            message: String
        ) {
            if (corePreferences.logcatLogsOutput) {
                when (level) {
                    LogLevel.Error -> Logger.e(TAG,  message)
                    LogLevel.Warning -> Logger.w(TAG, message)
                    LogLevel.Message -> Logger.i(TAG, message)
                    LogLevel.Fatal -> android.util.Log.wtf(domain, message)
                    else -> Logger.d(TAG, message)
                }
            }
        }
    }

    init {
        _lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED

        if (service != null) {
            Logger.i(TAG, "[Context] Starting foreground service")
            notificationsManager.startForeground(service, useAutoStartDescription)
        }

        core = Factory.instance().createCoreWithConfig(coreConfig, context)

        stopped = false
        _lifecycleRegistry.currentState = Lifecycle.State.CREATED

        (context as Application).registerActivityLifecycleCallbacks(activityMonitor)
        Logger.i(TAG, "[Context] Ready")
    }

    fun start() {
        Logger.i(TAG, "[Context] Starting")

        core.addListener(listener)

        // CoreContext listener must be added first!
        if (Version.sdkAboveOrEqual(Version.API26_O_80) && corePreferences.useTelecomManager) {
            if (Compatibility.hasTelecomManagerPermissions(context)) {
                Logger.i(TAG, 
                    "[Context] Creating Telecom Helper, disabling audio focus requests in AudioHelper"
                )
                core.config.setBool("audio", "android_disable_audio_focus_requests", true)
                val telecomHelper = TelecomHelper.required(context)
                Logger.i(TAG, 
                    "[Context] Telecom Helper created, account is ${if (telecomHelper.isAccountEnabled()) "enabled" else "disabled"}"
                )
            } else {
                Logger.w(TAG, "[Context] Can't create Telecom Helper, permissions have been revoked")
                corePreferences.useTelecomManager = false
            }
        }

        configureCore()

        core.start()
        _lifecycleRegistry.currentState = Lifecycle.State.STARTED

        initPhoneStateListener()

        notificationsManager.onCoreReady()

        collator.strength = Collator.NO_DECOMPOSITION

        if (corePreferences.vfsEnabled) {
            val notClearedCount = FileUtils.countFilesInDirectory(corePreferences.vfsCachePath)
            if (notClearedCount > 0) {
                Logger.w(TAG, 
                    "[Context] [VFS] There are [$notClearedCount] plain files not cleared from previous app lifetime, removing them now"
                )
            }
            FileUtils.clearExistingPlainFiles()
        }

        if (corePreferences.keepServiceAlive) {
            Logger.i(TAG, "[Context] Background mode setting is enabled, starting Service")
            notificationsManager.startForeground()
        }

        _lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        Logger.i(TAG, "[Context] Started")
    }

    fun stop() {
        Logger.i(TAG, "[Context] Stopping")
        coroutineScope.cancel()

        if (::phoneStateListener.isInitialized) {
            phoneStateListener.destroy()
        }
        notificationsManager.destroy()
        if (TelecomHelper.exists()) {
            Logger.i(TAG, "[Context] Destroying telecom helper")
            TelecomHelper.get().destroy()
            TelecomHelper.destroy()
        }

        core.stop()
        core.removeListener(listener)
        stopped = true
        _lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        loggingService.removeListener(loggingServiceListener)

        (context as Application).unregisterActivityLifecycleCallbacks(activityMonitor)
    }

    fun onForeground() {
        // We can't rely on defaultAccount?.params?.isPublishEnabled
        // as it will be modified by the SDK when changing the presence status
        if (corePreferences.publishPresence) {
            Logger.i(TAG, "[Context] App is in foreground, PUBLISHING presence as Online")
            core.consolidatedPresence = ConsolidatedPresence.Online
        }
    }

    fun onBackground() {
        // We can't rely on defaultAccount?.params?.isPublishEnabled
        // as it will be modified by the SDK when changing the presence status
        if (corePreferences.publishPresence) {
            Logger.i(TAG, "[Context] App is in background, un-PUBLISHING presence info")
            // We don't use ConsolidatedPresence.Busy but Offline to do an unsubscribe,
            // Flexisip will handle the Busy status depending on other devices
            core.consolidatedPresence = ConsolidatedPresence.Offline
        }
    }

    private fun configureCore() {
        Logger.i(TAG, "[Context] Configuring Core")

        core.staticPicture = corePreferences.staticPicturePath

        // Migration code
        if (core.config.getBool("app", "incoming_call_vibration", true)) {
            core.isVibrationOnIncomingCallEnabled = true
            core.config.setBool("app", "incoming_call_vibration", false)
        }

        if (core.config.getInt("misc", "conference_layout", 1) > 1) {
            core.config.setInt("misc", "conference_layout", 1)
        }

        // Now LIME server URL is set on accounts
        val limeServerUrl = core.limeX3DhServerUrl.orEmpty()
        if (limeServerUrl.isNotEmpty()) {
            Logger.w(TAG, "[Context] Removing LIME X3DH server URL from Core config")
            core.limeX3DhServerUrl = null
        }

        // Disable Telecom Manager on Android < 10 to prevent crash due to OS bug in Android 9
        if (Version.sdkStrictlyBelow(Version.API29_ANDROID_10)) {
            if (corePreferences.useTelecomManager) {
                Logger.w(TAG, 
                    "[Context] Android < 10 detected, disabling telecom manager to prevent crash due to OS bug"
                )
            }
            corePreferences.useTelecomManager = false
            corePreferences.manuallyDisabledTelecomManager = true
        }

        initUserCertificates()

        computeUserAgent()

        val fiveOneMigrationRequired = core.config.getBool("app", "migration_5.1_required", true)
        if (fiveOneMigrationRequired) {
            core.config.setBool(
                "sip",
                "update_presence_model_timestamp_before_publish_expires_refresh",
                true
            )
        }

        for (account in core.accountList) {
            if (account.params.identityAddress?.domain == corePreferences.defaultDomain) {
                var paramsChanged = false
                val params = account.params.clone()

                if (fiveOneMigrationRequired) {
                    val newExpire = 31536000 // 1 year
                    if (account.params.expires != newExpire) {
                        Logger.i(TAG, 
                            "[Context] Updating expire on account ${params.identityAddress?.asString()} from ${account.params.expires} to newExpire"
                        )
                        params.expires = newExpire
                        paramsChanged = true
                    }

                    // Enable presence publish/subscribe for new feature
                    if (!account.params.isPublishEnabled) {
                        Logger.i(TAG, 
                            "[Context] Enabling presence publish on account ${params.identityAddress?.asString()}"
                        )
                        params.isPublishEnabled = true
                        params.publishExpires = 120
                        paramsChanged = true
                    }
                }

                // Ensure conference factory URI is set on sip.linphone.org accounts
                if (account.params.conferenceFactoryUri == null) {
                    val uri = corePreferences.conferenceServerUri
                    Logger.i(TAG, 
                        "[Context] Setting conference factory on account ${params.identityAddress?.asString()} to default value: $uri"
                    )
                    params.conferenceFactoryUri = uri
                    paramsChanged = true
                }

                // Ensure audio/video conference factory URI is set on sip.linphone.org accounts
                if (account.params.audioVideoConferenceFactoryAddress == null) {
                    val uri = corePreferences.audioVideoConferenceServerUri
                    val address = core.interpretUrl(uri, false)
                    if (address != null) {
                        Logger.i(TAG, 
                            "[Context] Setting audio/video conference factory on account ${params.identityAddress?.asString()} to default value: $uri"
                        )
                        params.audioVideoConferenceFactoryAddress = address
                        paramsChanged = true
                    } else {
                        Logger.e(TAG,  "[Context] Failed to parse audio/video conference factory URI: $uri")
                    }
                }

                // Enable Bundle mode by default
                if (!account.params.isRtpBundleEnabled) {
                    Logger.i(TAG, 
                        "[Context] Enabling RTP bundle mode on account ${params.identityAddress?.asString()}"
                    )
                    params.isRtpBundleEnabled = true
                    paramsChanged = true
                }

                // Ensure we allow CPIM messages in basic chat rooms
                if (!account.params.isCpimInBasicChatRoomEnabled) {
                    params.isCpimInBasicChatRoomEnabled = true
                    paramsChanged = true
                    Logger.i(TAG, 
                        "[Context] CPIM allowed in basic chat rooms for account ${params.identityAddress?.asString()}"
                    )
                }

                if (account.params.limeServerUrl.isNullOrEmpty()) {
                    if (limeServerUrl.isNotEmpty()) {
                        params.limeServerUrl = limeServerUrl
                        paramsChanged = true
                        Logger.i(TAG, 
                            "[Context] Moving Core's LIME X3DH server URL [$limeServerUrl] on account ${params.identityAddress?.asString()}"
                        )
                    } else {
                        params.limeServerUrl = corePreferences.limeServerUrl
                        paramsChanged = true
                        Logger.w(TAG, 
                            "[Context] Linphone account [${params.identityAddress?.asString()}] didn't have a LIME X3DH server URL, setting one: ${corePreferences.limeServerUrl}"
                        )
                    }
                }

                if (paramsChanged) {
                    Logger.i(TAG, "[Context] Account params have been updated, apply changes")
                    account.params = params
                }
            }
        }
        core.config.setBool("app", "migration_5.1_required", false)

        Logger.i(TAG, "[Context] Core configured")
    }

    private fun computeUserAgent() {
//        val deviceName: String = corePreferences.deviceName
        val appName: String = LinphoneConfig.APPLICATION_ID
        val userAgent = appName + "_" + LinphoneConfig.BUILD_TYPE + "_" + LinphoneConfig.FLAVOR
        val sdkUserAgent = LinphoneConfig.VERSION_NAME + "_" + LinphoneConfig.VERSION_CODE
        core.setUserAgent(userAgent, sdkUserAgent)
    }

    private fun initUserCertificates() {
        val userCertsPath = corePreferences.userCertificatesPath
        val f = File(userCertsPath)
        if (!f.exists()) {
            if (!f.mkdir()) {
                Logger.e(TAG,  "[Context] $userCertsPath can't be created.")
            }
        }
        core.userCertificatesPath = userCertsPath
    }

    fun newAccountConfigured(isLinphoneAccount: Boolean) {
        Logger.i(TAG, 
            "[Context] A new ${if (isLinphoneAccount) AppUtils.getString(R.string.app_name) else "third-party"} account has been configured"
        )

        if (isLinphoneAccount) {
            core.config.setString("sip", "rls_uri", corePreferences.defaultRlsUri)
            val rlsAddress = core.interpretUrl(corePreferences.defaultRlsUri, false)
            if (rlsAddress != null) {
                for (friendList in core.friendsLists) {
                    friendList.rlsAddress = rlsAddress
                }
            }
            if (core.mediaEncryption == MediaEncryption.None) {
                Logger.i(TAG, "[Context] Enabling SRTP media encryption instead of None")
                core.mediaEncryption = MediaEncryption.SRTP
            }
        } else {
            Logger.i(TAG, "[Context] Background mode with foreground service automatically enabled")
            corePreferences.keepServiceAlive = true
            notificationsManager.startForeground()
        }
    }

    /* Call related functions */

    fun initPhoneStateListener() {
        if (PermissionHelper.required(context).hasReadPhoneStatePermission()) {
            try {
                phoneStateListener =
                    Compatibility.createPhoneListener(
                        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    )
            } catch (exception: SecurityException) {
                val hasReadPhoneStatePermission =
                    PermissionHelper.get().hasReadPhoneStateOrPhoneNumbersPermission()
                Logger.e(TAG,  
                    "[Context] Failed to create phone state listener: $exception, READ_PHONE_STATE permission status is $hasReadPhoneStatePermission"
                )
            }
        } else {
            Logger.w(TAG, 
                "[Context] Can't create phone state listener, READ_PHONE_STATE permission isn't granted"
            )
        }
    }

    fun declineCallDueToGsmActiveCall(): Boolean {
        if (!corePreferences.useTelecomManager) { // Can't use the following call with Telecom Manager API as it will "fake" GSM calls
            var gsmCallActive = false
            if (::phoneStateListener.isInitialized) {
                gsmCallActive = phoneStateListener.isInCall()
            }

            if (gsmCallActive) {
                Logger.w(TAG, "[Context] Refusing the call with reason busy because a GSM call is active")
                return true
            }
        } else {
            if (TelecomHelper.exists()) {
                if (!TelecomHelper.get().isIncomingCallPermitted() ||
                    TelecomHelper.get().isInManagedCall()
                ) {
                    Logger.w(TAG, 
                        "[Context] Refusing the call with reason busy because Telecom Manager will reject the call"
                    )
                    return true
                }
            } else {
                Logger.e(TAG,  "[Context] Telecom Manager singleton wasn't created!")
            }
        }
        return false
    }

    fun videoUpdateRequestTimedOut(call: Call) {
        coroutineScope.launch {
            Logger.w(TAG, "[Context] 30 seconds have passed, declining video request")
            answerCallVideoUpdateRequest(call, false)
        }
    }

    fun answerCallVideoUpdateRequest(call: Call, accept: Boolean) {
        val params = core.createCallParams(call)

        if (accept) {
            params?.isVideoEnabled = true
            core.isVideoCaptureEnabled = true
            core.isVideoDisplayEnabled = true
        } else {
            params?.isVideoEnabled = false
        }

        call.acceptUpdate(params)
    }

    fun answerCall(call: Call): Boolean {
        Logger.i(TAG, "[Context] Answering call $call")
        val params = core.createCallParams(call)
        if (params == null) {
            Logger.w(TAG, "[Context] Answering call without params!")
            call.accept()
            return false
        }

        params.recordFile = LinphoneUtils.getRecordingFilePathForAddress(call.remoteAddress)

        if (LinphoneUtils.checkIfNetworkHasLowBandwidth(context)) {
            Logger.w(TAG, "[Context] Enabling low bandwidth mode!")
            params.isLowBandwidthEnabled = true
        }

        if (call.callLog.wasConference()) {
            // Prevent incoming group call to start in audio only layout
            // Do the same as the conference waiting room
            params.isVideoEnabled = true
            params.videoDirection = if (core.videoActivationPolicy.automaticallyInitiate) MediaDirection.SendRecv else MediaDirection.RecvOnly
            Logger.i(TAG, 
                "[Context] Enabling video on call params to prevent audio-only layout when answering"
            )
        }

        call.acceptWithParams(params)
        return true
    }

    fun declineCall(call: Call) {
        val voiceMailUri = corePreferences.voiceMailUri
        if (voiceMailUri != null && corePreferences.redirectDeclinedCallToVoiceMail) {
            val voiceMailAddress = core.interpretUrl(voiceMailUri, false)
            if (voiceMailAddress != null) {
                Logger.i(TAG, "[Context] Redirecting call $call to voice mail URI: $voiceMailUri")
                call.redirectTo(voiceMailAddress)
            }
        } else {
            val reason = if (core.callsNb > 1) {
                Reason.Busy
            } else {
                Reason.Declined
            }
            Logger.i(TAG, "[Context] Declining call [$call] with reason [$reason]")
            call.decline(reason)
        }
    }

    fun terminateCall(call: Call) {
        Logger.i(TAG, "[Context] Terminating call $call")
        call.terminate()
    }

    fun transferCallTo(addressToCall: String): Boolean {
        val currentCall = core.currentCall ?: core.calls.firstOrNull()
        if (currentCall == null) {
            Logger.e(TAG,  "[Context] Couldn't find a call to transfer")
        } else {
            val address = core.interpretUrl(addressToCall, LinphoneUtils.applyInternationalPrefix())
            if (address != null) {
                Logger.i(TAG, "[Context] Transferring current call to $addressToCall")
                currentCall.transferTo(address)
                return true
            }
        }
        return false
    }

    fun terminateCurrentCallOrConferenceOrAll() {
        val call = core.currentCall
        call?.terminate() ?: if (core.conference?.isIn == true) {
            core.terminateConference()
        } else {
            core.terminateAllCalls()
        }
    }

    fun startCall(to: String, businessId: String) {
        val stringAddress = to.trim()
        Logger.e(TAG,  "[Context] stringAddress: ${stringAddress}")
        val address: Address? = core.interpretUrl(
            stringAddress,
            LinphoneUtils.applyInternationalPrefix()
        )
        if (address == null) {
            Logger.e(TAG,  "[Context] Failed to parse $stringAddress, abort outgoing call")
            callErrorMessageResourceId.value = Event(
                context.getString(R.string.call_error_network_unreachable)
            )
            return
        }

        startCall(address = address, businessId = businessId)
    }

    private fun startCall(
        address: Address,
        callParams: CallParams? = null,
        businessId: String
    ) {
        if (!core.isNetworkReachable) {
            Logger.e(TAG,  "[Context] Network unreachable, abort outgoing call")
            callErrorMessageResourceId.value = Event(
                context.getString(R.string.call_error_network_unreachable)
            )
            return
        }

        val params = callParams ?: core.createCallParams(null)
        if (params == null) {
            val call = core.inviteAddress(address)
            Logger.w(TAG, "[Context] Starting call $call without params")
            return
        }

        if (LinphoneUtils.checkIfNetworkHasLowBandwidth(context)) {
            Logger.w(TAG, "[Context] Enabling low bandwidth mode!")
            params.isLowBandwidthEnabled = true
        }
        params.recordFile = LinphoneUtils.getRecordingFilePathForAddress(address)

        if (corePreferences.sendEarlyMedia) {
            params.isEarlyMediaSendingEnabled = true
        }

        val call = core.inviteAddressWithParams(address, params)
        Logger.i(TAG, "[Context] Starting call $call")
    }

    fun switchCamera() {
        val currentDevice = core.videoDevice
        Logger.i(TAG, "[Context] Current camera device is $currentDevice")

        for (camera in core.videoDevicesList) {
            if (camera != currentDevice && camera != "StaticImage: Static picture") {
                Logger.i(TAG, "[Context] New camera device will be $camera")
                core.videoDevice = camera
                break
            }
        }

        val conference = core.conference
        if (conference == null || !conference.isIn) {
            val call = core.currentCall
            if (call == null) {
                Logger.w(TAG, "[Context] Switching camera while not in call")
                return
            }
            call.update(null)
        }
    }

    fun showSwitchCameraButton(): Boolean {
        return !corePreferences.disableVideo && core.videoDevicesList.size > 2 // Count StaticImage camera
    }

    private fun exportFileInMessage(message: ChatMessage) {
        // Only do it if auto download feature isn't disabled, otherwise it's done in the user-initiated download process
        if (core.maxSizeForAutoDownloadIncomingFiles != -1) {
            var hasFile = false
            for (content in message.contents) {
                if (content.isFile) {
                    hasFile = true
                    break
                }
            }
            if (hasFile) {
                exportFilesInMessageToMediaStore(message)
            }
        }
    }

    private fun exportFilesInMessageToMediaStore(message: ChatMessage) {
        if (message.isEphemeral) {
            Logger.w(TAG, "[Context] Do not make ephemeral file(s) public")
            return
        }
        if (corePreferences.vfsEnabled) {
            Logger.w(TAG, "[Context] [VFS] Do not make received file(s) public when VFS is enabled")
            return
        }
        if (!corePreferences.makePublicMediaFilesDownloaded) {
            Logger.w(TAG, "[Context] Making received files public setting disabled")
            return
        }

        if (PermissionHelper.get().hasWriteExternalStoragePermission()) {
            for (content in message.contents) {
                if (content.isFile && content.filePath != null && content.userData == null) {
                    Logger.i(TAG, "[Context] Trying to export file [${content.name}] to MediaStore")
                    addContentToMediaStore(content)
                }
            }
        } else {
            Logger.e(TAG,  
                "[Context] Can't make file public, app doesn't have WRITE_EXTERNAL_STORAGE permission"
            )
        }
    }

    fun addContentToMediaStore(content: Content) {
        if (corePreferences.vfsEnabled) {
            Logger.w(TAG, "[Context] [VFS] Do not make received file(s) public when VFS is enabled")
            return
        }
        if (!corePreferences.makePublicMediaFilesDownloaded) {
            Logger.w(TAG, "[Context] Making received files public setting disabled")
            return
        }

        if (PermissionHelper.get().hasWriteExternalStoragePermission()) {
            coroutineScope.launch {
                val filePath = content.filePath.orEmpty()
                Logger.i(TAG, "[Context] Trying to export file [$filePath] through Media Store API")

                val extension = FileUtils.getExtensionFromFileName(filePath)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                when (FileUtils.getMimeType(mime)) {
                    FileUtils.MimeType.Image -> {
                        if (Compatibility.addImageToMediaStore(context, content)) {
                            Logger.i(TAG, 
                                "[Context] Successfully exported image [${content.name}] to Media Store"
                            )
                        } else {
                            Logger.e(TAG,  
                                "[Context] Something went wrong while copying file to Media Store..."
                            )
                        }
                    }
                    FileUtils.MimeType.Video -> {
                        if (Compatibility.addVideoToMediaStore(context, content)) {
                            Logger.i(TAG, 
                                "[Context] Successfully exported video [${content.name}] to Media Store"
                            )
                        } else {
                            Logger.e(TAG,  
                                "[Context] Something went wrong while copying file to Media Store..."
                            )
                        }
                    }
                    FileUtils.MimeType.Audio -> {
                        if (Compatibility.addAudioToMediaStore(context, content)) {
                            Logger.i(TAG, 
                                "[Context] Successfully exported audio [${content.name}] to Media Store"
                            )
                        } else {
                            Logger.e(TAG,  
                                "[Context] Something went wrong while copying file to Media Store..."
                            )
                        }
                    }
                    else -> {
                        Logger.w(TAG, 
                            "[Context] File [$filePath] isn't either an image, an audio file or a video [${content.type}/${content.subtype}], can't add it to the Media Store"
                        )
                    }
                }
            }
        }
    }

    fun checkIfForegroundServiceNotificationCanBeRemovedAfterDelay(delayInMs: Long) {
        coroutineScope.launch {
            withContext(Dispatchers.Default) {
                delay(delayInMs)
                withContext(Dispatchers.Main) {
                    if (core.defaultAccount != null && core.defaultAccount?.state == RegistrationState.Ok) {
                        Logger.i(TAG, 
                            "[Context] Default account is registered, cancel foreground service notification if possible"
                        )
                        notificationsManager.stopForegroundNotificationIfPossible()
                    }
                }
            }
        }
    }

    /* Start call related activities */

    private fun onIncomingReceived() {
        if (corePreferences.preventInterfaceFromShowingUp) {
            Logger.w(TAG, "[Context] We were asked to not show the incoming call screen")
            return
        }

        lastCallType = CALL_INCOMING
        try {
            val clz = LinphoneConfig.ROUTE_INFO.incomingCallClass
            val intent = Intent(context, clz)
            // This flag is required to start an Activity from a Service context
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ClassNotFoundException) {
            Logger.i(TAG, "can not find CallIncomingActivity")
        }
    }

    private fun onOutgoingStarted() {
        if (corePreferences.preventInterfaceFromShowingUp) {
            Logger.w(TAG, "[Context] We were asked to not show the outgoing call screen")
            return
        }

        lastCallType = CALL_OUTGOING
        try {
            val clz = LinphoneConfig.ROUTE_INFO.outCallClass
            val intent = Intent(context, clz)
            // This flag is required to start an Activity from a Service context
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ClassNotFoundException) {
            Logger.i(TAG, "can not find CallOutgoingActivity")
        }
    }

    fun onCallStarted() {
        if (corePreferences.preventInterfaceFromShowingUp) {
            Logger.w(TAG, "[Context] We were asked to not show the call screen")
            return
        }

        /** businessId为空则为通过号码维度呼叫，不为空则为案件详情通过案件维度呼叫 */
        try {
            //SIP常规通话界面
            val clz = LinphoneConfig.ROUTE_INFO.callingClass
            val intent = Intent(context, clz)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ClassNotFoundException) {
            Logger.i(TAG, "can not find Class")
        }
        lastCallType = CALL_INIT
    }

    /* VFS */

    companion object {
        private const val TAG = "CoreContext"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val ALIAS = "vfs"
        private const val LINPHONE_VFS_ENCRYPTION_AES256GCM128_SHA256 = 2
        private const val VFS_IV = "vfsiv"
        private const val VFS_KEY = "vfskey"

        private const val CALL_INIT = 0
        private const val CALL_INCOMING = 1
        private const val CALL_OUTGOING = 2

        @Throws(java.lang.Exception::class)
        private fun generateSecretKey() {
            val keyGenerator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            keyGenerator.generateKey()
        }

        @Throws(java.lang.Exception::class)
        private fun getSecretKey(): SecretKey? {
            val ks = KeyStore.getInstance(ANDROID_KEY_STORE)
            ks.load(null)
            val entry = ks.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry
            return entry.secretKey
        }

        @Throws(java.lang.Exception::class)
        fun generateToken(): String {
            return sha512(UUID.randomUUID().toString())
        }

        @Throws(java.lang.Exception::class)
        private fun encryptData(textToEncrypt: String): Pair<ByteArray, ByteArray> {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val iv = cipher.iv
            return Pair<ByteArray, ByteArray>(
                iv,
                cipher.doFinal(textToEncrypt.toByteArray(StandardCharsets.UTF_8))
            )
        }

        @Throws(java.lang.Exception::class)
        private fun decryptData(encrypted: String?, encryptionIv: ByteArray): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, encryptionIv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            val encryptedData = Base64.decode(encrypted, Base64.DEFAULT)
            return String(cipher.doFinal(encryptedData), StandardCharsets.UTF_8)
        }

        @Throws(java.lang.Exception::class)
        fun encryptToken(string_to_encrypt: String): Pair<String?, String?> {
            val encryptedData = encryptData(string_to_encrypt)
            return Pair<String?, String?>(
                Base64.encodeToString(encryptedData.first, Base64.DEFAULT),
                Base64.encodeToString(encryptedData.second, Base64.DEFAULT)
            )
        }

        @Throws(java.lang.Exception::class)
        fun sha512(input: String): String {
            val md = MessageDigest.getInstance("SHA-512")
            val messageDigest = md.digest(input.toByteArray())
            val no = BigInteger(1, messageDigest)
            var hashtext = no.toString(16)
            while (hashtext.length < 32) {
                hashtext = "0$hashtext"
            }
            return hashtext
        }

        @Throws(java.lang.Exception::class)
        fun getVfsKey(sharedPreferences: SharedPreferences): String {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
            keyStore.load(null)
            return decryptData(
                sharedPreferences.getString(VFS_KEY, null),
                Base64.decode(sharedPreferences.getString(VFS_IV, null), Base64.DEFAULT)
            )
        }

        fun activateVFS() {
            try {
                Logger.i(TAG, "[Context] [VFS] Activating VFS")
                val preferences = corePreferences.encryptedSharedPreferences
                if (preferences == null) {
                    Logger.e(TAG,  "[Context] [VFS] Can't get encrypted SharedPreferences, can't init VFS")
                    return
                }

                if (preferences.getString(VFS_IV, null) == null) {
                    generateSecretKey()
                    encryptToken(generateToken()).let { data ->
                        preferences
                            .edit()
                            .putString(VFS_IV, data.first)
                            .putString(VFS_KEY, data.second)
                            .commit()
                    }
                }
                Factory.instance().setVfsEncryption(
                    LINPHONE_VFS_ENCRYPTION_AES256GCM128_SHA256,
                    getVfsKey(preferences).toByteArray().copyOfRange(0, 32),
                    32
                )

                Logger.i(TAG, "[Context] [VFS] VFS activated")
            } catch (e: Exception) {
                Log.f("[Context] [VFS] Unable to activate VFS encryption: $e")
            }
        }
    }

}
