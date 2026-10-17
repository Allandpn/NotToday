package com.allan.nottoday.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainEditTest {
    @Test
    fun shortensDownToTheMainDomainButNeverToASuffix() {
        assertEquals(listOf("advertise.cloudlinks.cn", "cloudlinks.cn"), DomainEdit.levels("advertise.cloudlinks.cn"))
        assertEquals(listOf("a.b.example.com", "b.example.com", "example.com"), DomainEdit.levels("a.b.example.com"))
        assertEquals(listOf("x.shop.com.br", "shop.com.br"), DomainEdit.levels("x.shop.com.br"))
        assertEquals(listOf("cloudlinks.cn"), DomainEdit.levels("cloudlinks.cn")) // já é o principal
        assertNull(DomainEdit.shorter("cloudlinks.cn"))
        assertNull(DomainEdit.shorter("shop.com.br"))
    }

    @Test
    fun recognisesPublicSuffixes() {
        for (s in listOf("cn", "com", "com.br", "co.uk", "gov.cn")) assertTrue(s, DomainEdit.isPublicSuffix(s))
        for (s in listOf("cloudlinks.cn", "uol.com.br", "example.com", "site.co.uk")) assertFalse(s, DomainEdit.isPublicSuffix(s))
    }
}
