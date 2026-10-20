package tv.torrserve.silicon

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.last
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Opt-in network probe; requires the matching Debug target and instrumentation APK. */
@RunWith(AndroidJUnit4::class)
class CatalogDnsConnectivityTest {
    @Test fun publicCubConnectionReuseProbe() = kotlinx.coroutines.runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("catalogSourcesLive") == "true")
        val graph=(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TorrFrameApplication).graph
        graph.ready.await()
        for(mode in listOf("pooled", "fresh", "http1-close")) {
            val pool=okhttp3.ConnectionPool(if(mode=="pooled")5 else 0,1,java.util.concurrent.TimeUnit.SECONDS)
            val client=graph.http.client.newBuilder().connectionPool(pool)
                .connectTimeout(5,java.util.concurrent.TimeUnit.SECONDS).readTimeout(4,java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(6,java.util.concurrent.TimeUnit.SECONDS).apply {
                    if(mode=="http1-close")protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                }.build()
            try {
                repeat(3) { index ->
                    val request=okhttp3.Request.Builder().url("https://tmdb.cub.best/?page=1&cat=movie&sort=latest")
                        .header("Accept","application/json").apply { if(mode=="http1-close")header("Connection","close") }.build()
                    val start=android.os.SystemClock.elapsedRealtime()
                    try {
                        client.newCall(request).execute().use { response ->
                            val bytes=response.body.bytes()
                            val count=org.json.JSONObject(bytes.toString(Charsets.UTF_8)).optJSONArray("results")?.length()
                            println("CUB connection probe $mode #$index: code=${response.code}; protocol=${response.protocol}; rows=$count; bytes=${bytes.size}; elapsed=${android.os.SystemClock.elapsedRealtime()-start}ms")
                        }
                    } catch(e:Exception) { println("CUB connection probe $mode #$index: ${e.javaClass.simpleName}; elapsed=${android.os.SystemClock.elapsedRealtime()-start}ms") }
                }
            } finally { pool.evictAll() }
        }
    }
    @Test fun configuredCubSectionsAndTorrentQueryRecover() = kotlinx.coroutines.runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("catalogSourcesLive") == "true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val graph=(context.applicationContext as TorrFrameApplication).graph
        graph.ready.await()
        val options=graph.settings.state.value.torrentSearch.copy(enabledProviders=setOf("torrentscsv"))
        val target=tv.torrserve.torrentsearch.model.SearchTarget("Матрица","The Matrix",1999)
        val result=graph.torrentSearch.search(target,options).last()
        println("Torrent engine queries=${target.queries(options.titleMode)}; results=${result.results.size}; sources=${result.sources}")
        if(result.results.isEmpty()) {
            try {
                println("TorrentsCSV resolver=${graph.http.client.dns.javaClass.simpleName}; addresses="+
                    graph.http.client.dns.lookup("torrents-csv.com").joinToString { it.hostAddress.orEmpty() })
                val rows=tv.torrserve.torrentsearch.provider.TorrentsCsvProvider(graph.torrentSearchNetwork).search("The Matrix 1999",target.type)
                println("TorrentsCSV direct query rows=${rows.size}")
            } catch(e:Exception) { println("TorrentsCSV direct error=${e.javaClass.simpleName}; status=${(e as? tv.torrserve.silicon.core.network.ServiceFailure)?.status}; causes="+
                generateSequence<Throwable>(e) { it.cause }.take(8).map { it.javaClass.simpleName }.joinToString()) }
        }
        val cub=tv.torrserve.silicon.features.catalog.data.CubCatalogApi(graph.http,graph.settings.state.value.catalogNetwork)
        val failures=mutableListOf<String>()
        for(section in tv.torrserve.silicon.features.catalog.domain.CatalogSection.entries) {
            val start=android.os.SystemClock.elapsedRealtime()
            try {
                val page=tv.torrserve.silicon.features.catalog.domain.CatalogParser.page(cub.section(section,"ru-RU",1),section.type)
                println("CUB ${section.name}: ${page.items.size} parsed titles in ${android.os.SystemClock.elapsedRealtime()-start}ms")
                if(page.items.isEmpty())failures+=section.name
            } catch(e:Exception) {
                println("CUB ${section.name}: ${e.javaClass.simpleName}; status=${(e as? tv.torrserve.silicon.core.network.ServiceFailure)?.status}; cause=${e.cause?.javaClass?.simpleName}")
                failures+=section.name
            }
        }
        assertEquals("Some real CUB sections remain unavailable",emptyList<String>(),failures)
        assertTrue("TorrentsCSV should recover through the application resolver",result.results.isNotEmpty())
    }
    @Test fun configuredCatalogAndTorrentSources() = kotlinx.coroutines.runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("catalogSourcesLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = (context.applicationContext as TorrFrameApplication).graph
        graph.ready.await()
        val config = graph.settings.state.value.catalogNetwork
        println("Configured source=${config.source}; custom CUB=${config.cubUrl.isNotBlank()}; CUB token=${config.cubToken.isNotBlank()}")
        val cub = tv.torrserve.silicon.features.catalog.data.CubCatalogApi(graph.http, config)
        for (section in tv.torrserve.silicon.features.catalog.domain.CatalogSection.entries) {
            try {
                val page = cub.section(section, "ru-RU", 1)
                println("CUB ${section.name}: ${page.getJSONArray("results").length()} titles")
            } catch (e: Exception) {
                println("CUB ${section.name}: ${e.javaClass.simpleName}; status=${(e as? tv.torrserve.silicon.core.network.ServiceFailure)?.status}; cause=${e.cause?.javaClass?.simpleName}")
            }
        }
        val options = graph.settings.state.value.torrentSearch
        println("Torrent options: title=${options.titleMode}; enabled=${options.enabledProviders}; timeout=${options.sourceTimeoutMs}")
        kotlinx.coroutines.coroutineScope {
            graph.torrentSearch.providers.forEach { provider ->
                launch(kotlinx.coroutines.Dispatchers.Default) {
                    try {
                        val rows = kotlinx.coroutines.withTimeout(20_000) {
                            provider.search(if (provider.id == "rutorinfo" || provider.id == "nonameclub") "Матрица" else "Matrix", tv.torrserve.torrentsearch.model.MediaType.Movie)
                        }
                        println("Torrent ${provider.id}: ${rows.size} results")
                    } catch (e: Exception) {
                        println("Torrent ${provider.id}: ${e.javaClass.simpleName}; status=${(e as? tv.torrserve.silicon.core.network.ServiceFailure)?.status}; cause=${e.cause?.javaClass?.simpleName}")
                    }
                }
            }
        }
    }
    @Test fun applicationResolverLoadsCubWhenSystemDnsIsUnavailable() = kotlinx.coroutines.runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("catalogDnsLive") == "true")
        val dns=tv.torrserve.silicon.core.network.TmdbDns(system=okhttp3.Dns { throw java.net.UnknownHostException("fixture router DNS failure") })
        for(host in listOf("api.themoviedb.org","image.tmdb.org","tmdb.cub.best","imagetmdb.com","nl.imagetmdb.com","de.imagetmdb.com")) {
            assertTrue(dns.lookup(host).isNotEmpty())
        }
        val http=tv.torrserve.silicon.core.network.Http(dnsOverride=dns)
        val api=tv.torrserve.silicon.features.catalog.data.CubCatalogApi(http,
            tv.torrserve.silicon.core.network.CatalogNetworkSettings(source=tv.torrserve.silicon.core.network.CatalogSource.Cub))
        val page=api.section(
            tv.torrserve.silicon.features.catalog.domain.CatalogSection.PopularMovies,"en-US",1)
        assertTrue(page.getJSONArray("results").length()>0)
        println("Catalog DNS recovery: ${page.getJSONArray("results").length()} real CUB titles with unavailable system DNS")
    }
    @Test fun googleDohBootstrapWorksWithoutRouterDns() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("catalogDnsLive") == "true")
        Socket().use { tcp ->
            tcp.connect(InetSocketAddress("8.8.8.8", 443), 5000)
            ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(tcp, "dns.google", 443, true) as SSLSocket).use { tls ->
                tls.soTimeout = 5000
                tls.sslParameters = tls.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName("dns.google"))
                }
                tls.startHandshake()
                tls.outputStream.write(("GET /resolve?name=tmdb.cub.best&type=A&edns_client_subnet=0.0.0.0%2F0 HTTP/1.1\r\n" +
                    "Host: dns.google\r\nAccept: application/dns-json\r\nConnection: close\r\n\r\n").toByteArray())
                tls.outputStream.flush()
                val response = tls.inputStream.bufferedReader().readText()
                assertTrue(response.lineSequence().first().contains("200"))
                assertTrue(response.contains("\"Status\":0"))
                assertTrue(response.contains("\"type\":1"))
            }
        }
    }
}
