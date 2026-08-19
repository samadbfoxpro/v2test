package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.parser.ConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("v2rayNG Pro", appName)
    }

    @Test
    fun `parse vless link correctly`() {
        val vlessLink = "vless://e7498c06-c87d-4ba6-8c46-17b5f1f94532@de1.v2ray-fastnode.net:443?security=reality&encryption=none&pbk=p0c9n8m7q6w5e4r3t2y1u0i9o8p7a6s5d4f3g2h1j0k&headerType=none&fp=chrome&spx=%2F&type=tcp&flow=xtls-rprx-vision&sni=www.speedtest.net&sid=6a7b8c9d#🇩🇪 Frankfurt VLESS"
        val server = ConfigParser.parseSingleUri(vlessLink)
        assertNotNull(server)
        assertEquals("VLESS", server?.protocol)
        assertEquals("de1.v2ray-fastnode.net", server?.address)
        assertEquals(443, server?.port)
        assertTrue(server?.isReality == true)
        assertEquals("www.speedtest.net", server?.sni)
    }

    @Test
    fun `parse trojan link correctly`() {
        val trojanLink = "trojan://SecretPassword@us-la.edge.net:443?security=tls&type=grpc#🇺🇸 US Trojan"
        val server = ConfigParser.parseSingleUri(trojanLink)
        assertNotNull(server)
        assertEquals("TROJAN", server?.protocol)
        assertEquals("us-la.edge.net", server?.address)
        assertEquals(443, server?.port)
    }
}
