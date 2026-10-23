package com.lyricsadapter.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WordLyricsEditingTest {
    private val qrc = """<?xml version="1.0" encoding="utf-8"?>
<QrcInfos>
<QrcHeadInfo SaveTime="1" Version="100"/>
<LyricInfo LyricCount="1">
<Lyric_1 LyricType="1" LyricContent="[0,600]你(0,300)好(300,300)
"/>
</LyricInfo>
</QrcInfos>"""

    @Test
    fun `edits only the QRC lyric content and keeps the envelope`() {
        val text = WordLyricsEditing.editableText(qrc, WordLyricsFormat.QRC)
        assertEquals("[0,600]你(0,300)好(300,300)\n", text)

        val result = WordLyricsEditing.apply(text.replace("好", "\"呀\" & \$1"), WordLyricsFormat.QRC, qrc)

        val valid = assertIs<WordLyricsEditing.Result.Valid>(result)
        assertTrue(valid.wordLyrics.contains("SaveTime=\"1\""), "the original header survives")
        assertTrue(valid.wordLyrics.contains("你(0,300)&quot;呀&quot; &amp; \$1(300,300)"), valid.wordLyrics)
        assertEquals("你\"呀\" & \$1", valid.plainText)
    }

    @Test
    fun `refuses text that lost its word timing`() {
        assertEquals(WordLyricsEditing.Result.Invalid, WordLyricsEditing.apply("你好", WordLyricsFormat.YRC, null))
    }

    @Test
    fun `wraps QRC content in a new envelope when there was none`() {
        val result = WordLyricsEditing.apply("[0,300]啊(0,300)", WordLyricsFormat.QRC, null)

        val valid = assertIs<WordLyricsEditing.Result.Valid>(result)
        assertTrue(valid.wordLyrics.startsWith("<?xml"))
        assertEquals("啊", valid.plainText)
    }
}
