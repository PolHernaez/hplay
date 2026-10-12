package com.hplay.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class UpdaterTest {
    private val json = """{"version":"1.2","versionCode":3,"minRequired":2,"notes":"- arreglos",
        "files":{"HPlay.apk":{"size":10,"sha256":"ABCDEF","package":"com.hplay.app"},"HPlay-Receptor.exe":{"size":5,"sha256":"00"}}}"""

    @Test fun parsesManifest() {
        val m = Updater.parse(json)
        assertEquals("1.2", m.versionName)
        assertEquals(3L, m.versionCode)
        assertEquals(2L, m.minRequired)
        assertEquals("abcdef", m.files["HPlay.apk"]!!.sha256) // en minúsculas, como sha256()
        assertEquals("com.hplay.app", m.files["HPlay.apk"]!!.packageName)
        assertEquals(null, m.files["HPlay-Receptor.exe"]!!.packageName)
    }

    @Test fun stateNewerForcedLatest() {
        val m = Updater.parse(json)
        assertEquals(Updater.State.FORCED, Updater.state(1, m))   // por debajo del mínimo
        assertEquals(Updater.State.NEWER, Updater.state(2, m))
        assertEquals(Updater.State.LATEST, Updater.state(3, m))
        assertEquals(Updater.State.LATEST, Updater.state(9, m))   // instalada más nueva que el release
        assertEquals(Updater.State.LATEST, Updater.state(1, null)) // sin red y sin caché: no se bloquea nada
    }

    @Test fun signatureMustMatchExactBytes() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pub = Base64.getEncoder().encodeToString(kp.public.encoded)
        val data = json.toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(kp.private); update(data); sign() }
        assertTrue(Updater.verifySignature(data, sig, pub))
        assertFalse(Updater.verifySignature(json.replace("\"minRequired\":2", "\"minRequired\":0").toByteArray(), sig, pub))
        assertFalse(Updater.verifySignature(data, sig)) // la clave real de HPlay no firmó esto
        assertFalse(Updater.verifySignature(data, ByteArray(10), pub))
    }

    @Test fun sha256OfFile() {
        val f = File.createTempFile("hplay", ".bin").apply { writeText("abc"); deleteOnExit() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Updater.sha256(f))
    }
}
