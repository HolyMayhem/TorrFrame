package tv.torrserve.silicon

import org.junit.Assert.*
import org.junit.Test
import tv.torrserve.silicon.core.network.ServiceFailure
import tv.torrserve.silicon.ui.components.localizeServiceFailure

class LocalizationTests {
    private fun strings(locale:String):Map<String,String> {
        val directory=listOf(java.io.File("src/main/res"),java.io.File("app/src/main/res")).first{it.isDirectory}
        val factory=javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl",true) }
        val nodes=factory.newDocumentBuilder().parse(java.io.File(directory,"$locale/strings.xml")).getElementsByTagName("string")
        val entries=(0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }.map { it.getAttribute("name") to it.textContent }
        assertEquals("Duplicate resource keys in $locale",entries.size,entries.map{it.first}.toSet().size)
        return entries.toMap()
    }
    @Test fun everyResourceHasEnglishTranslationWithMatchingFormatArguments() {
        val russian=strings("values");val english=strings("values-en")
        assertEquals("Missing translations",russian.keys,english.keys)
        val placeholder=Regex("%(?:[0-9]+\\$)?[-+.0-9]*[sdf]")
        for((key,value)in russian) {
            assertFalse("Russian fallback for $key",english.getValue(key).contains(Regex("[А-Яа-яЁё]")))
            assertEquals("Format arguments for $key",placeholder.findAll(value).map{it.value}.sorted().toList(),
                placeholder.findAll(english.getValue(key)).map{it.value}.sorted().toList())
        }
    }

    @Test fun knownNetworkFailuresAreTranslatedWithoutChangingServiceIdentifiers() {
        for(status in listOf(0,401,403,408,429,-1,-2,404,500)) {
            val english=ServiceFailure("Jackett",status).message!!
            val russian=localizeServiceFailure(english)
            assertTrue("Untranslated $status",russian.contains(Regex("[А-Яа-яЁё]")))
            assertTrue(russian.startsWith("Jackett"))
            if(status in listOf(404,500))assertTrue(russian.contains("HTTP $status"))
        }
    }
    @Test fun unrecognizedMessagesAndMediaTitlesRemainUntouched() {
        for(value in listOf("Watch","The Search","Interstellar","Ошибка внешнего источника","Some unfamiliar tracker response"))
            assertEquals(value,localizeServiceFailure(value))
    }
}
