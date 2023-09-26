package com.mib.sip

import android.annotation.SuppressLint
import android.content.Context
import com.mib.sip.core.CoreContext
import com.mib.sip.core.CorePreferences
import com.mib.sip.core.CoreService
import com.mib.sip.logger.Logger
import org.linphone.core.Factory
import org.linphone.core.LogCollectionState
import org.linphone.core.LogLevel

/**
 *  author : cengyimou
 *  date : 2023/8/24 11:23
 *  description : LinphoneApplication app中继承
 */
object LinphoneContext {
	const val TAG = "LinphoneContext"

	@SuppressLint("StaticFieldLeak")
	lateinit var corePreferences: CorePreferences

	@SuppressLint("StaticFieldLeak")
	lateinit var coreContext: CoreContext

	private fun createConfig(context: Context) {
		if (::corePreferences.isInitialized) {
			return
		}

		Factory.instance().setLogCollectionPath(context.filesDir.absolutePath)
		Factory.instance().enableLogCollection(LogCollectionState.Enabled)

		// For VFS
		Factory.instance().setCacheDir(context.cacheDir.absolutePath)

		corePreferences = CorePreferences(context)
		corePreferences.copyAssetsFromPackage()

		if (corePreferences.vfsEnabled) {
			CoreContext.activateVFS()
		}

		val config = Factory.instance().createConfigWithFactory(
			corePreferences.configPath,
			corePreferences.factoryConfigPath
		)
		corePreferences.config = config

		val appName = context.getString(R.string.app_name)
		Factory.instance().setLoggerDomain(appName)
		Factory.instance().enableLogcatLogs(corePreferences.logcatLogsOutput)
		if (corePreferences.debugLogs) {
			Factory.instance().loggingService.setLogLevel(LogLevel.Message)
		}

		Logger.i(TAG,"[Application] Core config & preferences created")
	}

	fun ensureCoreExists(
		context: Context,
		pushReceived: Boolean = false,
		service: CoreService? = null,
		useAutoStartDescription: Boolean = false,
		skipCoreStart: Boolean = false
	): Boolean {
		if (::coreContext.isInitialized && !coreContext.stopped) {
			Logger.i(TAG,"[Application] Skipping Core creation (push received? $pushReceived)")
			return false
		}

		Logger.i(TAG, "[Application] Core context is being created ${if (pushReceived) "from push" else ""}")
		coreContext = CoreContext(
			context,
			corePreferences.config,
			service,
			useAutoStartDescription
		)
		if (!skipCoreStart) {
			coreContext.start()
		}
		return true
	}

	fun contextExists(): Boolean {
		return ::coreContext.isInitialized
	}

	fun init(applicationContext: Context) {
		createConfig(applicationContext)
		Logger.i(TAG, "[Application] Created")
	}

}