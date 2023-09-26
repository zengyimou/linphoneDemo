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

import android.content.Context
import android.media.AudioManager
import android.text.format.Formatter.formatShortFileSize
import android.util.TypedValue
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import com.mib.sip.LinphoneContext.coreContext
import org.linphone.core.tools.Log

/**
 * Various utility methods for application
 */
class AppUtils {
    interface KeyboardVisibilityListener {
        fun onKeyboardVisibilityChanged(visible: Boolean)
    }

    companion object {

        fun getString(id: Int): String {
            return coreContext.context.getString(id)
        }

        fun getStringWithPlural(id: Int, count: Int): String {
            return coreContext.context.resources.getQuantityString(id, count, count)
        }

        fun getStringWithPlural(id: Int, count: Int, value: String): String {
            return coreContext.context.resources.getQuantityString(id, count, value)
        }

        fun getDimension(id: Int): Float {
            return coreContext.context.resources.getDimension(id)
        }

        fun pixelsToDp(pixels: Float): Float {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                pixels,
                coreContext.context.resources.displayMetrics
            )
        }

        fun dpToPixels(context: Context, dp: Float): Float {
            return dp * context.resources.displayMetrics.density
        }

        fun bytesToDisplayableSize(bytes: Long): String {
            return formatShortFileSize(coreContext.context, bytes)
        }

        fun isMediaVolumeLow(context: Context): Boolean {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            Log.i("[Media Volume] Current value is $currentVolume, max value is $maxVolume")
            return currentVolume <= maxVolume * 0.5
        }

        fun acquireAudioFocusForVoiceRecordingOrPlayback(context: Context): AudioFocusRequestCompat {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val audioAttrs = AudioAttributesCompat.Builder()
                .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                .setContentType(AudioAttributesCompat.CONTENT_TYPE_SPEECH)
                .build()

            val request =
                AudioFocusRequestCompat.Builder(
                    AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
                    .setAudioAttributes(audioAttrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
            when (AudioManagerCompat.requestAudioFocus(audioManager, request)) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                    Log.i("[Audio Focus] Voice recording/playback audio focus request granted")
                }
                AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                    Log.w("[Audio Focus] Voice recording/playback audio focus request failed")
                }
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                    Log.w("[Audio Focus] Voice recording/playback audio focus request delayed")
                }
            }
            return request
        }

        fun releaseAudioFocusForVoiceRecordingOrPlayback(
            context: Context,
            request: AudioFocusRequestCompat
        ) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            AudioManagerCompat.abandonAudioFocusRequest(audioManager, request)
            Log.i("[Audio Focus] Voice recording/playback audio focus request abandoned")
        }
    }
}
