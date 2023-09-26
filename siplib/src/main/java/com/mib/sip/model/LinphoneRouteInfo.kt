package com.mib.sip.model

open class LinphoneRouteInfo(
    val outCallClass: Class<*>,
    val incomingCallClass: Class<*>,
    val mainActivityClass: Class<*>,
    val callingClass: Class<*>,
) {
}

