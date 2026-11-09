package expo.modules.callkittelecom.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixtureMediaCredentialsTest {
    @Test fun httpsFetcherUsesNativeAuthenticationAndPreservesWireIdentity() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .followRedirects(false).callTimeout(2, TimeUnit.SECONDS).build()
        val http = NativeMediaHttp(client)
        var authenticated = 0
        val fetcher = FixtureMediaCredentials({
            authenticated++
            FixtureMediaAuthentication("fixture-not-production-auth", "device-1")
        }, http)
        val baseUrl = server.url("/").toString()
        val invitation = mapOf("serverCallId" to "call/é +", "eventId" to "event-1")
        try {
            server.enqueue(MockResponse().setBody(JSONObject(mapOf("provider" to "livekit", "room" to "room-1",
                "identity" to "device-1", "url" to "wss://media.invalid", "token" to "fixture-not-a-jwt",
                "expiresAt" to System.currentTimeMillis() / 1000 + 120)).toString()))
            val credentials = fetcher.fetch(invitation, baseUrl)
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
                val failure = runCatching { fetcher.fetch(invitation, baseUrl) }.exceptionOrNull()
                assertTrue(failure is NativeMediaHttpFailure)
                assertEquals(code, (failure as NativeMediaHttpFailure).status)
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            }
            val requestsBefore = server.requestCount
            assertTrue(runCatching { fetcher.fetch(invitation, "http://localhost/") }.isFailure)
            assertEquals(requestsBefore, server.requestCount)
            server.enqueue(MockResponse().setBody(JSONObject(mapOf("provider" to "webrtc", "url" to server.url("/sdp").toString(),
                "token" to "fixture", "expiresAt" to System.currentTimeMillis()/1000 + 120,
                "iceServers" to listOf(mapOf("urls" to listOf("turns:relay.invalid:443"), "username" to "device", "credential" to "fixture")))).toString()))
            val webRTC = fetcher.fetch(invitation, baseUrl)
            val ice = (webRTC["iceServers"] as List<*>).single() as Map<*, *>
            assertEquals(listOf("turns:relay.invalid:443"), ice["urls"])
            server.takeRequest(2, TimeUnit.SECONDS)
            server.enqueue(MockResponse().setBody(JSONObject(mapOf("type" to "answer", "sdp" to "v=0\r\n")).toString()))
            val answer = http.post(server.url("/sdp").toString(), "fixture", mapOf("type" to "offer", "sdp" to "v=0\r\n"))
            assertEquals("v=0\r\n", answer["sdp"])
            val offer = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("v=0\r\n", JSONObject(offer.body.readUtf8()).getString("sdp"))
            val beforeOversized = server.requestCount
            assertTrue(runCatching { http.post(server.url("/sdp").toString(), "fixture", mapOf("sdp" to "x".repeat(65_537))) }.isFailure)
            assertEquals(beforeOversized, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun cancelledFetcherDoesNotWaitForLateCredentials() = runBlocking {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(tls.sslSocketFactory(), false); server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            val fetcher = FixtureMediaCredentials({ FixtureMediaAuthentication("fixture", "device") }, NativeMediaHttp(client))
            val work = async { fetcher.fetch(mapOf("serverCallId" to "call", "eventId" to "event"), server.url("/").toString()) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            withTimeout(1000) { work.cancelAndJoin() }
            assertTrue(work.isCancelled)
        } finally { server.shutdown() }
    }
}
