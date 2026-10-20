package tv.torrframe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.torrframe.features.torrentsearch.data.AppTorrentSearchActions
import tv.torrserve.torrentsearch.engine.TorrentSearchEngine
import tv.torrserve.torrentsearch.model.*
import tv.torrserve.torrentsearch.provider.SearchProvider

@RunWith(AndroidJUnit4::class)
class TorrentSearchIntegrationTest {
    private val graph get()=(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TorrFrameApplication).graph
    @Test fun selectedTorrentFileAndMagnetUseExistingNativeImportAndFileSelection()=runBlocking {
        graph.ready.await();graph.server.requireRunning()
        val provider=object:SearchProvider {
            override val id="fixture";override val name="Local fixture"
            override suspend fun search(query:String,type:MediaType)=emptyList<TorrentResult>()
        }
        val actions=AppTorrentSearchActions(graph,TorrentSearchEngine(listOf(provider)))
        val row=TorrentResult("fixture","Local fixture","Original Gradient TorrentSearch",1,0,
            torrentUrl="http://10.0.2.2:18765/fixture.torrent")
        val hash=actions.add(row)
        try {
            val files=actions.files(hash)
            assertEquals(1,files.size);assertEquals("video.mp4",files.single().path.substringAfterLast('/'))
            assertEquals(files.single(),actions.preferred(hash,files))
            graph.api.remove(hash);graph.store.remove(hash)
            val magnetRow=row.copy(magnet="magnet:?xt=urn:btih:$hash&tr=http%3A%2F%2F10.0.2.2%3A18765%2Fannounce")
            assertEquals(hash,actions.add(magnetRow));assertEquals(1,actions.files(hash).size)
            assertEquals(hash,actions.add(magnetRow))
            assertEquals(1,graph.api.list().count{it.hash==hash});assertEquals(1,graph.store.list().count{it.hash==hash})
        }finally{graph.api.remove(hash);graph.store.remove(hash)}
    }
    @Test fun directAndroidProvidersReturnProgressiveSeedSortedResults()=runBlocking {
        graph.ready.await()
        val reports=mutableListOf<String>()
        for(target in listOf(SearchTarget("Interstellar","Interstellar",2014),SearchTarget("Breaking Bad","Breaking Bad",2008,MediaType.Series))) {
            val snapshots=withTimeout(65_000){graph.torrentSearch.search(target).toList()}
            val final=snapshots.last()
            for(snapshot in snapshots)assertEquals(snapshot.results.sortedWith(TorrentSearchEngine.order),snapshot.results)
            assertTrue("No provider returned results: ${final.sources}",final.results.isNotEmpty())
            assertFalse(final.searching)
            val summary="${target.title}: ${final.results.size} results; ${final.sources.joinToString{ "${it.name}=${it.phase}(${it.count})" }}"
            Log.i("TorrentSearchLive",summary);reports+=summary
        }
        val report=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"torrentsearch-live.txt")
        report.writeText(reports.joinToString("\n"))
    }
}
