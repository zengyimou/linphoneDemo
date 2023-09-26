package com.linphone

import android.app.Application
import android.content.Context
import com.mib.sip.LinphoneContext
import com.mib.sip.core.LinphoneConfig

/**
 *  author : cengyimou
 *  date : 2023/9/26 14:17
 *  description :
 */
class App: Application() {

	override fun attachBaseContext(base: Context?) {
		super.attachBaseContext(base)
		/** 配置linphone需要的跳转页面*/
		LinphoneConfig.Builder()
//			.outCallClass(CallOutgoingActivity::class.java)
//			.incomingCallClass(CallIncomingActivity::class.java)
//			.mainActivityClass(MainActivity::class.java)
//			.callingClass(CallActivity::class.java)
			.versionName(BuildConfig.VERSION_NAME)
			.versionCode("${BuildConfig.VERSION_CODE}")
			.buildType(BuildConfig.BUILD_TYPE)
			.flavor("")
			.applicationId(BuildConfig.APPLICATION_ID)
			.build()
	}

	override fun onCreate() {
		super.onCreate()
		LinphoneContext.init(this)
	}
}