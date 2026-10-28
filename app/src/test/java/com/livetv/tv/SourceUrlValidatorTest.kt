package com.livetv.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceUrlValidatorTest {

    @Test
    fun `null and blank urls are rejected`() {
        assertFalse(SourceUrlValidator.isValid(null))
        assertFalse(SourceUrlValidator.isValid(""))
        assertFalse(SourceUrlValidator.isValid("    "))
        assertEquals("", SourceUrlValidator.normalized("   "))
    }

    @Test
    fun `non http schemes are rejected`() {
        assertFalse(SourceUrlValidator.isValid("ftp://example.com/list.m3u"))
        assertFalse(SourceUrlValidator.isValid("file:///sdcard/list.m3u"))
        assertFalse(SourceUrlValidator.isValid("/sdcard/list.m3u"))
        assertFalse(SourceUrlValidator.isValid("example.com/list.m3u"))
    }

    @Test
    fun `http url without host is rejected`() {
        assertFalse(SourceUrlValidator.isValid("http://"))
        assertFalse(SourceUrlValidator.isValid("https://"))
        assertFalse(SourceUrlValidator.isValid("https:///list.m3u"))
    }

    @Test
    fun `plain m3u url is accepted`() {
        assertTrue(SourceUrlValidator.isValid("http://example.com/list.m3u"))
        assertTrue(SourceUrlValidator.isValid("https://example.com/iptv/cn.m3u"))
    }

    @Test
    fun `url with query port and credentials is accepted`() {
        assertTrue(SourceUrlValidator.isValid("https://example.com:8080/list.txt?token=abc"))
        assertTrue(SourceUrlValidator.isValid("https://user:pwd@example.com/list.m3u"))
    }

    @Test
    fun `surrounding whitespace is trimmed before validation`() {
        assertTrue(SourceUrlValidator.isValid("  https://example.com/list.m3u  "))
        assertEquals("https://example.com/list.m3u", SourceUrlValidator.normalized("  https://example.com/list.m3u  "))
    }

    @Test
    fun `valid url has no error message`() {
        assertNull(SourceUrlValidator.errorMessageRes("https://example.com/list.m3u"))
    }
}
