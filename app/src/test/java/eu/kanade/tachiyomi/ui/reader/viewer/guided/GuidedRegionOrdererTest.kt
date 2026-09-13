package eu.kanade.tachiyomi.ui.reader.viewer.guided

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuidedRegionOrdererTest {

    @Test
    fun `orders western comic rows from left to right`() {
        val regions = listOf(
            region("bottom-right", 0.6f, 0.6f, 0.9f, 0.8f),
            region("top-right", 0.6f, 0.1f, 0.9f, 0.3f),
            region("bottom-left", 0.1f, 0.6f, 0.4f, 0.8f),
            region("top-left", 0.1f, 0.1f, 0.4f, 0.3f),
        )

        val result = GuidedRegionOrderer.order(regions.map { it.second }, GuidedReadingDirection.LEFT_TO_RIGHT)

        assertEquals(listOf("top-left", "top-right", "bottom-left", "bottom-right"), names(regions, result))
    }

    @Test
    fun `orders manga rows from right to left`() {
        val regions = listOf(
            region("bottom-right", 0.6f, 0.6f, 0.9f, 0.8f),
            region("top-left", 0.1f, 0.1f, 0.4f, 0.3f),
            region("bottom-left", 0.1f, 0.6f, 0.4f, 0.8f),
            region("top-right", 0.6f, 0.1f, 0.9f, 0.3f),
        )

        val result = GuidedRegionOrderer.order(regions.map { it.second }, GuidedReadingDirection.RIGHT_TO_LEFT)

        assertEquals(listOf("top-right", "top-left", "bottom-right", "bottom-left"), names(regions, result))
    }

    @Test
    fun `uses recursive cuts for staggered layouts`() {
        val regions = listOf(
            region("bottom", 0.1f, 0.65f, 0.9f, 0.9f),
            region("upper-right-low", 0.6f, 0.35f, 0.9f, 0.55f),
            region("upper-left", 0.1f, 0.1f, 0.45f, 0.55f),
            region("upper-right-high", 0.6f, 0.1f, 0.9f, 0.3f),
        )

        val result = GuidedRegionOrderer.order(regions.map { it.second }, GuidedReadingDirection.LEFT_TO_RIGHT)

        assertEquals(listOf("upper-left", "upper-right-high", "upper-right-low", "bottom"), names(regions, result))
    }

    private fun region(
        name: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = name to GuidedRegion(NormalizedRect(left, top, right, bottom), confidence = 1f)

    private fun names(
        namedRegions: List<Pair<String, GuidedRegion>>,
        result: List<GuidedRegion>,
    ) = result.map { resultRegion -> namedRegions.first { it.second == resultRegion }.first }
}
