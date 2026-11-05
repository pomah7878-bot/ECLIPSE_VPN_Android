package com.v2ray.ang.fmt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class VlessFmtTest {

    @Test
    fun parse_realityProfile_preservesConnectionParameters() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@example.com:443" +
            "?encryption=none&security=reality&sni=www.example.org&fp=chrome" +
            "&pbk=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8%3D&sid=0123456789abcdef" +
            "&spx=%2F&flow=xtls-rprx-vision&type=tcp&headerType=none#Reality"

        val profile = VlessFmt.parse(uri)

        assertNotNull(profile)
        assertEquals("example.com", profile?.server)
        assertEquals("443", profile?.serverPort)
        assertEquals("reality", profile?.security)
        assertEquals("www.example.org", profile?.sni)
        assertEquals("chrome", profile?.fingerPrint)
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", profile?.publicKey)
        assertEquals("0123456789abcdef", profile?.shortId)
        assertEquals("/", profile?.spiderX)
        assertEquals("xtls-rprx-vision", profile?.flow)
        assertEquals("tcp", profile?.network)
    }

    @Test
    fun parse_rawPaddedPublicKey_doesNotTruncatePadding() {
        val publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        val uri = "vless://00000000-0000-0000-0000-000000000001@example.com:443" +
            "?encryption=none&security=reality&sni=www.example.org&fp=chrome" +
            "&pbk=$publicKey&sid=&spx=%2F&type=tcp#Reality"

        val profile = VlessFmt.parse(uri)

        assertNotNull(profile)
        assertEquals(publicKey, profile?.publicKey)
        assertEquals("", profile?.shortId)
        assertEquals("/", profile?.spiderX)
    }

    @Test
    fun parse_emptyAndFlagParameters_doNotInvalidateProfile() {
        val uri = "vless://00000000-0000-0000-0000-000000000001@example.com:443" +
            "?encryption=none&security=reality&sni=www.example.org&pbk=&sid=&spx=" +
            "&allowInsecure&type=tcp&&flow=xtls-rprx-vision#Reality"

        val profile = VlessFmt.parse(uri)

        assertNotNull(profile)
        assertEquals("", profile?.publicKey)
        assertEquals("", profile?.shortId)
        assertEquals("", profile?.spiderX)
        assertEquals("xtls-rprx-vision", profile?.flow)
        assertEquals("tcp", profile?.network)
    }

    @Test
    fun toUri_realityProfile_roundTripsAllParameters() {
        val original = "vless://00000000-0000-0000-0000-000000000001@example.com:443" +
            "?encryption=none&security=reality&sni=www.example.org&fp=chrome" +
            "&pbk=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8%3D&sid=0123456789abcdef" +
            "&spx=%2F&flow=xtls-rprx-vision&type=tcp&headerType=none#Reality"
        val profile = VlessFmt.parse(original)

        assertNotNull(profile)
        val restored = VlessFmt.parse(VlessFmt.toUri(profile!!))

        assertNotNull(restored)
        assertEquals(profile.publicKey, restored?.publicKey)
        assertEquals(profile.shortId, restored?.shortId)
        assertEquals(profile.spiderX, restored?.spiderX)
        assertEquals(profile.fingerPrint, restored?.fingerPrint)
        assertEquals(profile.flow, restored?.flow)
        assertEquals(profile.sni, restored?.sni)
        assertEquals(profile.security, restored?.security)
    }
}
