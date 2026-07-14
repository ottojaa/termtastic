/**
 * Tests for [ServerUrl]'s URL construction — in particular that raw IPv6
 * literal hosts are bracketed in the authority so the `:port` suffix stays
 * unambiguous (pairing candidates may carry IPv6 addresses).
 */
package se.soderbjorn.lunamux.client

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerUrlTest {

    @Test
    fun ipv4AndHostnamesPassThrough() {
        assertEquals(
            "https://192.168.1.5:8443/api/ui-settings",
            ServerUrl("192.168.1.5", 8443).httpUrl("/api/ui-settings"),
        )
        assertEquals(
            "wss://my-mac.local:8443/window",
            ServerUrl("my-mac.local", 8443).wsUrl("window"),
        )
    }

    @Test
    fun rawIpv6LiteralsAreBracketed() {
        assertEquals(
            "https://[2001:db8::1]:8443/x",
            ServerUrl("2001:db8::1", 8443).httpUrl("/x"),
        )
        assertEquals(
            "wss://[2001:db8::1]:8443/window",
            ServerUrl("2001:db8::1", 8443).wsUrl("/window"),
        )
    }

    @Test
    fun preBracketedHostsAreLeftAlone() {
        assertEquals(
            "wss://[2001:db8::1]:8443/window",
            ServerUrl("[2001:db8::1]", 8443).wsUrl("/window"),
        )
    }
}
