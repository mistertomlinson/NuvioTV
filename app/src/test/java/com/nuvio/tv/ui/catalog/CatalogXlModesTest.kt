package com.nuvio.tv.ui.catalog

import com.nuvio.tv.domain.model.CatalogXlMode
import org.junit.Assert.*
import org.junit.Test

class CatalogXlModesTest {
    @Test fun `XL cycles through summary hidden summary and off`() {
        assertEquals(CatalogXlMode.WITH_SUMMARY,CatalogXlMode.OFF.next())
        assertEquals(CatalogXlMode.WITHOUT_SUMMARY,CatalogXlMode.WITH_SUMMARY.next())
        assertEquals(CatalogXlMode.OFF,CatalogXlMode.WITHOUT_SUMMARY.next())
    }
}
