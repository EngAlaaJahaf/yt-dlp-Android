package com.ytdlpgui

import com.ytdlpgui.data.engine.PlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistParserTest {

    @Test
    fun parsesEntries_andSkipsOnesWithoutUrl() {
        val json = """
            {"_type":"playlist","title":"mix","entries":[
              {"url":"https://youtu.be/a","title":"أ","duration":91.0},
              {"title":"بلا رابط"},
              {"url":"https://youtu.be/b","title":"ب","duration":30.5}
            ]}
        """.trimIndent()
        val entries = PlaylistParser.parse(json)
        assertEquals(2, entries.size)
        assertEquals("https://youtu.be/a", entries[0].url)
        assertEquals("أ", entries[0].title)
        assertEquals(91, entries[0].durationSeconds)
        assertEquals("https://youtu.be/b", entries[1].url)
        assertEquals(30, entries[1].durationSeconds)
    }

    @Test
    fun nullDuration_isAccepted() {
        val json = """{"entries":[{"url":"https://youtu.be/a","title":"أ"}]}"""
        val entries = PlaylistParser.parse(json)
        assertEquals(1, entries.size)
        assertNull(entries[0].durationSeconds)
    }

    @Test
    fun noEntriesArray_returnsEmpty() {
        val json = """{"_type":"video","title":"single"}"""
        assertTrue(PlaylistParser.parse(json).isEmpty())
    }

    @Test
    fun invalidJson_returnsEmpty() {
        assertTrue(PlaylistParser.parse("not json at all").isEmpty())
        assertTrue(PlaylistParser.parse("").isEmpty())
    }

    @Test
    fun emptyEntries_returnsEmpty() {
        val json = """{"entries":[]}"""
        assertTrue(PlaylistParser.parse(json).isEmpty())
    }

    @Test
    fun idOnlyEntry_resolvesToYoutubeWatchUrl() {
        val json = """
            {"_type":"playlist","entries":[
              {"id":"dQw4w9WgXcQ","title":"Song","duration":212.0}
            ]}
        """.trimIndent()
        val entries = PlaylistParser.parse(json)
        assertEquals(1, entries.size)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", entries[0].url)
        assertEquals("Song", entries[0].title)
        assertEquals(212, entries[0].durationSeconds)
    }
}
