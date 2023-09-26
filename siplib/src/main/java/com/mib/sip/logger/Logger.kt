package com.mib.sip.logger

import android.util.Log
import com.mib.sip.BuildConfig

/**
 * @author yimou
 * date：2021/7/15 
 * description:
 */
object Logger {
    fun e(tag: String, msg: String?) {
        if (BuildConfig.DEBUG) Log.e(tag, msg ?: "")
    }

    fun e(tag: String, msg: String?, e: Throwable?) {
        if (BuildConfig.DEBUG) Log.e(tag, msg, e)
    }

    fun w(tag: String, msg: String?) {
        if (BuildConfig.DEBUG) Log.w(tag, msg ?: "")
    }

    fun i(tag: String, msg: String?) {
        if (BuildConfig.DEBUG) logInfo(tag, msg ?: "")
    }

    fun d(tag: String, msg: String?) {
        if (BuildConfig.DEBUG) Log.d(tag, msg ?: "")
    }

    fun v(tag: String, msg: String?) {
        if (BuildConfig.DEBUG) Log.v(tag, msg ?: "")
    }

    private fun logInfo(tag: String, msg: String) {  //信息太长,分段打印
        //因为String的length是字符数量不是字节数量所以为了防止中文字符过多，
        //  把4*1024的MAX字节打印长度改为2001字符数
        var logStr = msg
        val maxStrLength = 2001 - tag.length
        //大于4000时
        while (logStr.length > maxStrLength) {
            Log.i(tag, logStr.substring(0, maxStrLength))
            logStr = logStr.substring(maxStrLength)
        }
        //剩余部分
        Log.i(tag, logStr)
    }
}