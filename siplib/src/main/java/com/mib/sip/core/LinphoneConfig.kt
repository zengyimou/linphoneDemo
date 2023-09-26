package com.mib.sip.core

import com.mib.sip.model.LinphoneRouteInfo

/**
 *  author : cengyimou
 *  date : 2023/8/31 11:13
 *  description : linphone配置
 */
object LinphoneConfig {
	private lateinit var routeInfo: LinphoneRouteInfo
	val ROUTE_INFO: LinphoneRouteInfo
		get() {
			if (this::routeInfo.isInitialized) {
				return routeInfo
			}
			throw IllegalStateException("routeInfo has not been init")
		}

	private lateinit var applicationId: String
	private lateinit var versionName: String
	private lateinit var versionCode: String
	private lateinit var buildType: String
	private lateinit var flavor: String
	val APPLICATION_ID: String
		get() {
			if (this::applicationId.isInitialized) {
				return applicationId
			}
			throw IllegalStateException("applicationId has not been init")
		}

	val FLAVOR: String
		get() {
			if (this::flavor.isInitialized) {
				return flavor
			}
			throw IllegalStateException("flavor code has not been init")
		}

	val BUILD_TYPE: String
		get() {
			if (this::buildType.isInitialized) {
				return buildType
			}
			throw IllegalStateException("buildType has not been init")
		}

	val VERSION_NAME: String
		get() {
			if (this::versionName.isInitialized) {
				return versionName
			}
			throw IllegalStateException("versionName has not been init")
		}

	val VERSION_CODE: String
		get() {
			if (this::versionCode.isInitialized) {
				return versionCode
			}
			throw IllegalStateException("versionCode has not been init")
		}

	class Builder {
		//通话界面
		private var callingClass: Class<*>? = null

		//拨出界面
		private var outCallClass: Class<*>? = null

		//来电界面
		private var incomingCallClass: Class<*>? = null

		//main界面
		private var mainActivityClass: Class<*>? = null

		//案件详情界面
		private var taskDetailActivityClass: Class<*>? = null

		fun callingClass(name: Class<*>): Builder {
			callingClass = name
			return this
		}

		fun outCallClass(name: Class<*>): Builder {
			outCallClass = name
			return this
		}

		fun incomingCallClass(name: Class<*>): Builder {
			incomingCallClass = name
			return this
		}

		fun mainActivityClass(name: Class<*>): Builder {
			mainActivityClass = name
			return this
		}

		fun taskDetailActivityClass(name: Class<*>): Builder {
			taskDetailActivityClass = name
			return this
		}

		fun flavor(f: String): Builder {
			flavor = f
			return this
		}

		fun versionName(name: String): Builder {
			versionName = name
			return this
		}

		fun versionCode(code: String): Builder {
			versionCode = code
			return this
		}

		fun buildType(type: String): Builder {
			buildType = type
			return this
		}

		fun applicationId(id: String): Builder {
			applicationId = id
			return this
		}

		fun build() {
			if (LinphoneConfig::routeInfo.isInitialized) {
				throw IllegalStateException("routeInfo has been init")
			}

			routeInfo = LinphoneRouteInfo(
				outCallClass = outCallClass!!,
				incomingCallClass = incomingCallClass!!,
				mainActivityClass = mainActivityClass!!,
				taskDetailActivityClass = taskDetailActivityClass!!,
				callingClass = callingClass!!,
			)
		}
	}
}