package tv.torrserve.silicon

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.core.network.TmdbDns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class TmdbDnsTests {
    private val api = "api.themoviedb.org"
    private val image = "image.tmdb.org"
    private fun address(ip: String) = InetAddress.getByAddress(ip.split('.').map { it.toInt().toByte() }.toByteArray())
    private fun answer(ip: String = "99.84.152.32", ttl: Int = 30) =
        """{"Status":0,"Answer":[{"type":5,"data":"cdn.example"},{"type":1,"data":"$ip","TTL":$ttl}]}"""
    private fun resolver(server: MockWebServer, system: Dns = Dns { listOf(address("127.0.0.1")) }, now: () -> Long = { 0 }) =
        TmdbDns(system, OkHttpClient.Builder().followRedirects(false).callTimeout(500, TimeUnit.MILLISECONDS).build(), server.url("/dns-query"), now)

    @Test fun healthySystemDnsAndUnrelatedHostsDoNotContactSecureDns() {
        MockWebServer().use { server ->
            server.start(); val calls=mutableListOf<String>()
            val dns=resolver(server,Dns { host -> calls+=host; listOf(address(if(host==api) "99.84.152.32" else "192.168.1.1")) })
            assertEquals("99.84.152.32",dns.lookup(api).single().hostAddress)
            assertEquals("192.168.1.1",dns.lookup("torrserver.lan").single().hostAddress)
            assertEquals(listOf(api,"torrserver.lan"),calls)
            assertEquals(0,server.requestCount)
        }
    }

    @Test fun poisonedAnswerUsesSecureDnsAndSendsOnlyTheHostname() {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setBody(answer()))
            val dns=resolver(server)
            assertEquals("99.84.152.32",dns.lookup(api).single().hostAddress)
            assertEquals("99.84.152.32",dns.lookup(api).single().hostAddress)
            assertEquals(1,server.requestCount)
            val request=server.takeRequest()
            assertEquals("application/dns-json",request.getHeader("Accept"))
            assertEquals(api,request.requestUrl!!.queryParameter("name"))
            assertEquals("A",request.requestUrl!!.queryParameter("type"))
            assertEquals(setOf("name","type","edns_client_subnet"),request.requestUrl!!.queryParameterNames)
            assertEquals("0.0.0.0/0",request.requestUrl!!.queryParameter("edns_client_subnet"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun unresolvedSystemDnsAlsoUsesTheFallback() {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setBody(answer()))
            val dns=resolver(server,Dns { throw UnknownHostException("system DNS failed") })
            assertEquals("99.84.152.32",dns.lookup(api).single().hostAddress)
        }
    }

    @Test fun privateSystemAddressesAreRejectedButHealthyPublicOnesAreKept() {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setBody(answer()))
            val dns=resolver(server,Dns { listOf(address("0.0.0.0"),address("192.168.1.1"),address("169.254.1.1")) })
            assertEquals("99.84.152.32",dns.lookup(api).single().hostAddress)
            val healthy=resolver(server,Dns { listOf(address("127.0.0.1"),address("99.84.152.53")) })
            assertEquals("99.84.152.53",healthy.lookup(api).single().hostAddress)
            assertEquals(1,server.requestCount)
        }
    }

    @Test fun cacheExpiresAndApiAndImagesHaveIndependentEntries() {
        MockWebServer().use { server ->
            server.start();var time=0L
            server.enqueue(MockResponse().setBody(answer(ttl=1)))
            server.enqueue(MockResponse().setBody(answer("79.127.134.229")))
            server.enqueue(MockResponse().setBody(answer("99.84.152.53")))
            val dns=resolver(server,now={time})
            dns.lookup(api);dns.lookup(image);time=999
            assertEquals("99.84.152.32",dns.lookup(api).single().hostAddress)
            time=1000
            assertEquals("99.84.152.53",dns.lookup(api).single().hostAddress)
            assertEquals("79.127.134.229",dns.lookup(image).single().hostAddress)
            assertEquals(3,server.requestCount)
        }
    }

    @Test fun invalidOrPoisonedSecureAnswersFailClosedAndAreNotCached() {
        MockWebServer().use { server ->
            server.start();val dns=resolver(server)
            for (payload in listOf(answer("127.0.0.1"), answer("10.1.2.3"),answer("999.1.1.1"), answer("another.example"),
                """{"Status":3}""", "not JSON", """{"Status":0,"TC":true,"Answer":[]}""")) {
                server.enqueue(MockResponse().setBody(payload))
                try {dns.lookup(api);fail("Invalid response was accepted")}catch(_:UnknownHostException) { }
            }
            server.enqueue(MockResponse().setBody(answer()))
            assertEquals("99.84.152.32",dns.lookup(api).single().hostAddress)
            assertEquals(8,server.requestCount)
        }
    }

    @Test fun redirectsOversizedResponsesAndTimeoutsDoNotProduceAddresses() {
        MockWebServer().use { server ->
            server.start();val dns=resolver(server)
            for (response in listOf(MockResponse().setResponseCode(302).setHeader("Location","https://unrelated.example"),
                MockResponse().setChunkedBody("x".repeat(17000),2048),MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))) {
                server.enqueue(response)
                try {dns.lookup(api);fail()}catch(_:UnknownHostException) { }
            }
            assertEquals(3,server.requestCount)
        }
    }

    @Test fun dohBootstrapDoesNotDependOnWorkingSystemDnsAndOnlyOverridesItsOwnHost() {
        val calls=mutableListOf<String>()
        val dns=TmdbDns.bootstrapDns(Dns { host -> calls+=host;throw UnknownHostException(host) })
        assertEquals(listOf("8.8.8.8","8.8.4.4"),dns.lookup("dns.google").map { it.hostAddress })
        assertTrue(calls.isEmpty())
        try { dns.lookup("torrserver.lan");fail("LAN should keep system DNS") } catch(_:UnknownHostException) { }
        assertEquals(listOf("torrserver.lan"),calls)
    }
    @Test fun cubAndEveryBuiltInPosterMirrorRecoverFromSystemDnsFailure() {
        MockWebServer().use { server ->
            server.start()
            val dns=resolver(server,Dns { throw UnknownHostException("router DNS unavailable") })
            val hosts=listOf("tmdb.cub.best", "torrents-csv.com") + tv.torrserve.silicon.core.network.CatalogNetworkSettings.PUBLIC_IMAGE_MIRRORS
                .map { okhttp3.HttpUrl.Companion.run { it.toHttpUrl().host } }
            hosts.forEach { host ->
                server.enqueue(MockResponse().setBody(answer()))
                assertEquals("99.84.152.32",dns.lookup(host).single().hostAddress)
                assertEquals(host,server.takeRequest().requestUrl!!.queryParameter("name"))
            }
            // Unknown custom endpoints are not silently sent to a public resolver.
            try { dns.lookup("custom.example");fail() } catch(_:UnknownHostException) { }
            assertEquals(hosts.size,server.requestCount)
        }
    }

    @Test fun simultaneousPosterRequestsShareOneFallbackLookup() {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setBody(answer()))
            val dns=resolver(server);val pool=Executors.newFixedThreadPool(8)
            try {
                val results=(1..16).map {pool.submit<List<InetAddress>> {dns.lookup(image)}}
                for(result in results)assertEquals("99.84.152.32",result.get(2,TimeUnit.SECONDS).single().hostAddress)
                assertEquals(1,server.requestCount)
            }finally {pool.shutdownNow()}
        }
    }

    @Test fun stalledHostDoesNotBlockCachedPostersOrOtherHostLookups() {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setBody(answer("79.127.134.229")))
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val dns=resolver(server,Dns { host ->
                when(host) {
                    image -> throw UnknownHostException("fixture")
                    api -> { entered.countDown();check(release.await(5,TimeUnit.SECONDS));listOf(address("99.84.152.32")) }
                    else -> listOf(address("104.21.83.236"))
                }
            })
            assertEquals("79.127.134.229",dns.lookup(image).single().hostAddress)
            val pool=Executors.newFixedThreadPool(3)
            try {
                val slow=pool.submit<List<InetAddress>> { dns.lookup(api) }
                assertTrue(entered.await(2,TimeUnit.SECONDS))
                val cached=pool.submit<List<InetAddress>> { dns.lookup(image) }
                val other=pool.submit<List<InetAddress>> { dns.lookup("tmdb.cub.best") }
                assertEquals("79.127.134.229",cached.get(2,TimeUnit.SECONDS).single().hostAddress)
                assertEquals("104.21.83.236",other.get(2,TimeUnit.SECONDS).single().hostAddress)
                assertFalse(slow.isDone);assertEquals(1,server.requestCount)
                release.countDown();assertEquals("99.84.152.32",slow.get(2,TimeUnit.SECONDS).single().hostAddress)
            } finally { release.countDown();pool.shutdownNow() }
        }
    }
}
