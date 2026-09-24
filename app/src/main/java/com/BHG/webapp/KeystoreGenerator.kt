package com.BHG.webapp

import android.os.Handler
import android.os.Looper
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.Executors

/**
 * Generates a real, self-signed signing keystore on the device — the same kind
 * of key `keytool -genkeypair` produces on a desktop, but built with Java crypto
 * APIs plus BouncyCastle (Android has no public API to create/sign an X.509
 * certificate).
 *
 * Output is a PKCS12 keystore (.p12); the build pipeline treats it exactly like
 * an uploaded custom keystore — Android Gradle auto-detects PKCS12, so no server
 * change is needed. Generation runs off the main thread; callbacks fire on it.
 */
object KeystoreGenerator {

    /** The distinguished-name and credential inputs collected from the user. */
    data class Details(
        val alias: String,
        val storePassword: String,
        val keyPassword: String,
        val commonName: String,   // CN — first & last name
        val orgUnit: String,      // OU — organizational unit
        val org: String,          // O  — organization
        val locality: String,     // L  — city / locality
        val state: String,        // ST — state / province
        val country: String       // C  — two-letter country code
    )

    /** Generated keystore bytes plus the credentials needed to sign with it. */
    data class Result(
        val bytes: ByteArray,
        val alias: String,
        val storePassword: String,
        val keyPassword: String
    )

    // A private BC provider instance handed straight to the builders, so we don't
    // register it globally and clash with Android's bundled "BC" provider.
    private val bc = BouncyCastleProvider()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "KeystoreGen").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 30 years — comfortably past Google Play's minimum validity requirement. */
    private const val VALIDITY_DAYS = 30L * 365L

    fun generate(
        details: Details,
        onSuccess: (Result) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val bytes = build(details)
                mainHandler.post {
                    onSuccess(Result(bytes, details.alias, details.storePassword, details.keyPassword))
                }
            } catch (e: Exception) {
                mainHandler.post { onError(e.message ?: "Key generation failed") }
            }
        }
    }

    private fun build(d: Details): ByteArray {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        val keyPair = keyGen.generateKeyPair()

        val nameBuilder = X500NameBuilder(BCStyle.INSTANCE)
        if (d.commonName.isNotBlank()) nameBuilder.addRDN(BCStyle.CN, d.commonName)
        if (d.orgUnit.isNotBlank())    nameBuilder.addRDN(BCStyle.OU, d.orgUnit)
        if (d.org.isNotBlank())        nameBuilder.addRDN(BCStyle.O, d.org)
        if (d.locality.isNotBlank())   nameBuilder.addRDN(BCStyle.L, d.locality)
        if (d.state.isNotBlank())      nameBuilder.addRDN(BCStyle.ST, d.state)
        if (d.country.isNotBlank())    nameBuilder.addRDN(BCStyle.C, d.country)
        val subject = nameBuilder.build()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000L) // small backdate to tolerate clock skew
        val notAfter = Date(now + VALIDITY_DAYS * 24L * 60L * 60L * 1000L)
        val serial = BigInteger(64, SecureRandom())

        // Self-signed: issuer == subject.
        val certBuilder = JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, keyPair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(keyPair.private)
        val cert = JcaX509CertificateConverter().setProvider(bc).getCertificate(certBuilder.build(signer))

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(d.alias, keyPair.private, d.keyPassword.toCharArray(), arrayOf(cert))

        return ByteArrayOutputStream().use { out ->
            keyStore.store(out, d.storePassword.toCharArray())
            out.toByteArray()
        }
    }
}
