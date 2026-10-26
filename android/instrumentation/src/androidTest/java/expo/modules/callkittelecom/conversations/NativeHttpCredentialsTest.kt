package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeHttpCredentialsTest {
    @Test fun httpsFetcherUsesNativeAuthenticationAndPreservesWireIdentity() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .followRedirects(false).callTimeout(2, TimeUnit.SECONDS).build()
        val fetcher = NativeHttpCredentials(client)
        var authenticated = 0
        NativeMediaAuthenticationProviders.providers["fixture-device"] = {
            authenticated++
            NativeMediaAuthentication("fixture-not-production-auth", "device-1")
        }
        val policy = JSONObject(mapOf("type" to "http", "baseUrl" to server.url("/").toString(), "authentication" to "fixture-device"))
        val invitation = mapOf("serverCallId" to "call/é +", "eventId" to "event-1")
        try {
            server.enqueue(MockResponse().setBody(JSONObject(mapOf("provider" to "livekit", "room" to "room-1",
                "identity" to "device-1", "url" to "wss://media.invalid", "token" to "fixture-not-a-jwt",
                "expiresAt" to System.currentTimeMillis() / 1000 + 120)).toString()))
            val credentials = fetcher.fetch(invitation, policy)
            assertEquals("livekit", credentials["provider"])
            assertEquals("room-1", credentials["room"])
            val request = server.takeRequest(2, TimeUnit.SECONDS) ?: error("Missing native request")
            assertEquals("POST", request.method)
            assertEquals("/v1/calls/call%2F%C3%A9%20%2B/media-credentials", request.path)
            assertEquals("Bearer fixture-not-production-auth", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("event-1", body.getString("eventId"))
            assertEquals("device-1", body.getString("deviceId"))
            assertEquals(1, authenticated)
            for (code in listOf(404, 410)) {
                server.enqueue(MockResponse().setResponseCode(code))
                val failure = runCatching { fetcher.fetch(invitation, policy) }.exceptionOrNull()
                assertTrue(failure is MediaCredentialsUnavailable)
                assertEquals(code, (failure as MediaCredentialsUnavailable).status)
            }
            val requestsBefore = server.requestCount
            val insecure = JSONObject(policy.toString()).put("baseUrl", "http://localhost/")
            assertTrue(runCatching { fetcher.fetch(invitation, insecure) }.isFailure)
            assertEquals(requestsBefore, server.requestCount)
        } finally { NativeMediaAuthenticationProviders.providers.remove("fixture-device"); server.shutdown() }
    }

    @Test fun cancelledFetcherDoesNotWaitForLateCredentials() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(tls.sslSocketFactory(), false); server.start()
        NativeMediaAuthenticationProviders.providers["cancel-device"] = { NativeMediaAuthentication("fixture", "device") }
        try {
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(5, TimeUnit.SECONDS))
            val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            val work = async { NativeHttpCredentials(client).fetch(mapOf("serverCallId" to "call", "eventId" to "event"),
                JSONObject(mapOf("type" to "http", "baseUrl" to server.url("/").toString(), "authentication" to "cancel-device"))) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            withTimeout(1000) { work.cancelAndJoin() }
            assertTrue(work.isCancelled)
        } finally { NativeMediaAuthenticationProviders.providers.remove("cancel-device"); server.shutdown() }
    }
}
