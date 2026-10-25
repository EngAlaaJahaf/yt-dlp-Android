package com.ytdlpgui

import com.ytdlpgui.data.engine.ErrorClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorClassifierTest {

    @Test
    fun network_errors_classified() {
        val msg = ErrorClassifier.classify("Connection timed out while fetching data")
        assertTrue(msg.contains("مشكلة اتصال بالشبكة"))
        assertTrue(msg.contains("تفاصيل تقنية"))
    }

    @Test
    fun auth_gate_classified() {
        val msg = ErrorClassifier.classify("Sign in to confirm you're not a bot")
        assertTrue(msg.contains("تسجيل دخول"))
    }

    @Test
    fun geo_restricted_classified() {
        val msg = ErrorClassifier.classify("This video is not available in your country")
        assertTrue(msg.contains("محجوب جغرافياً"))
    }

    @Test
    fun storage_full_classified() {
        val msg = ErrorClassifier.classify("No space left on device")
        assertTrue(msg.contains("مساحة تخزين كافية"))
    }

    @Test
    fun unknown_error_noCrash_andShowsRaw() {
        val msg = ErrorClassifier.classify("weird thing happened")
        assertFalse(msg.isBlank())
        assertTrue(msg.contains("weird thing happened"))
    }

    @Test
    fun null_or_blank_handled() {
        assertEquals("خطأ غير معروف", ErrorClassifier.classify(null))
        assertEquals("خطأ غير معروف", ErrorClassifier.classify("   "))
    }

    @Test
    fun case_insensitive_matching() {
        val msg = ErrorClassifier.classify("CONNECTION TIMED OUT")
        assertTrue(msg.contains("مشكلة اتصال بالشبكة"))
    }
}
