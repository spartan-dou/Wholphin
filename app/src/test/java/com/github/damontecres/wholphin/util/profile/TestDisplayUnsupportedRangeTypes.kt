package com.github.damontecres.wholphin.util.profile

import android.view.Display.HdrCapabilities
import org.junit.Assert
import org.junit.Test

class TestDisplayUnsupportedRangeTypes {
    private val dovi = WholphinVideoRangeType.DOVI.serialName
    private val hdr10 = WholphinVideoRangeType.HDR10.serialName
    private val hlg = WholphinVideoRangeType.HLG.serialName
    private val doviWithHdr10 = WholphinVideoRangeType.DOVI_WITH_HDR10.serialName

    @Test
    fun unknownDisplayExcludesNothing() {
        Assert.assertTrue(displayUnsupportedRangeTypes(null, true).isEmpty())
    }

    @Test
    fun dolbyVisionDisplayExcludesNothing() {
        val types = setOf(HdrCapabilities.HDR_TYPE_DOLBY_VISION)
        Assert.assertTrue(displayUnsupportedRangeTypes(types, true).isEmpty())
    }

    @Test
    fun hdr10DisplayOnlyExcludesProfile5() {
        val result = displayUnsupportedRangeTypes(setOf(HdrCapabilities.HDR_TYPE_HDR10), true)
        Assert.assertEquals(setOf(dovi), result)
    }

    @Test
    fun sdrDisplayExcludesEveryHdrType() {
        val result = displayUnsupportedRangeTypes(emptySet(), true)
        Assert.assertTrue(result.containsAll(listOf(dovi, hdr10, hlg, doviWithHdr10)))
    }

    @Test
    fun oldServerNamesAreNotUsed() {
        val result = displayUnsupportedRangeTypes(emptySet(), false)
        Assert.assertFalse(WholphinVideoRangeType.DOVI_WITH_EL.serialName in result)
    }
}
