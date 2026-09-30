package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Use the same scoped, link-local path for manual APs as for Wi-Fi Direct. */
internal fun wirelessHostAddress(
    addresses: List<InetAddress>,
    interfaceIndex: Int,
    preferIpv4: Boolean = false,
): InetAddress? {
    val ipv4 = addresses.firstOrNull {
        it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress && !it.isMulticastAddress
    }
    // MG4/SWI69's Android 9 SoftAP routes clients over its private IPv4 gateway. Its IPv6
    // link-local address exists on ap0 but is not reachable by the joining iPhone.
    if (preferIpv4 && ipv4 != null) return ipv4
    if (interfaceIndex > 0) {
        addresses.filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }?.let {
            return Inet6Address.getByAddress(null, it.address, interfaceIndex)
        }
    }
    return ipv4
}
