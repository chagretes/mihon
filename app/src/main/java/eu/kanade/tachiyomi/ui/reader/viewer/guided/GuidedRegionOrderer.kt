package eu.kanade.tachiyomi.ui.reader.viewer.guided

object GuidedRegionOrderer {

    fun order(
        regions: List<GuidedRegion>,
        direction: GuidedReadingDirection,
    ): List<GuidedRegion> = xyCut(regions, direction)

    private fun xyCut(
        regions: List<GuidedRegion>,
        direction: GuidedReadingDirection,
    ): List<GuidedRegion> {
        if (regions.size <= 1) return regions

        findHorizontalCut(regions)?.let { cut ->
            val top = regions.filter { it.bounds.bottom <= cut }
            val bottom = regions.filter { it.bounds.top >= cut }
            if (top.isNotEmpty() && bottom.isNotEmpty()) {
                return xyCut(top, direction) + xyCut(bottom, direction)
            }
        }

        findVerticalCut(regions)?.let { cut ->
            val left = regions.filter { it.bounds.right <= cut }
            val right = regions.filter { it.bounds.left >= cut }
            if (left.isNotEmpty() && right.isNotEmpty()) {
                return when (direction) {
                    GuidedReadingDirection.LEFT_TO_RIGHT -> xyCut(left, direction) + xyCut(right, direction)
                    GuidedReadingDirection.RIGHT_TO_LEFT -> xyCut(right, direction) + xyCut(left, direction)
                }
            }
        }

        return fallbackRowSort(regions, direction)
    }

    private fun findHorizontalCut(regions: List<GuidedRegion>): Float? {
        val coordinates = regions.flatMap { listOf(it.bounds.top, it.bounds.bottom) }.distinct().sorted()
        return coordinates.zipWithNext()
            .mapNotNull { (start, end) ->
                val cut = (start + end) / 2f
                val crossesCut = regions.any { it.bounds.top < cut && cut < it.bounds.bottom }
                if (!crossesCut) cut to (end - start) else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun findVerticalCut(regions: List<GuidedRegion>): Float? {
        val coordinates = regions.flatMap { listOf(it.bounds.left, it.bounds.right) }.distinct().sorted()
        return coordinates.zipWithNext()
            .mapNotNull { (start, end) ->
                val cut = (start + end) / 2f
                val crossesCut = regions.any { it.bounds.left < cut && cut < it.bounds.right }
                if (!crossesCut) cut to (end - start) else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun fallbackRowSort(
        regions: List<GuidedRegion>,
        direction: GuidedReadingDirection,
    ): List<GuidedRegion> {
        val rows = mutableListOf<MutableList<GuidedRegion>>()
        regions.sortedBy { it.bounds.top }.forEach { region ->
            val row = rows.firstOrNull { candidate ->
                val reference = candidate.first().bounds
                val overlap = minOf(region.bounds.bottom, reference.bottom) -
                    maxOf(region.bounds.top, reference.top)
                overlap > minOf(region.bounds.height, reference.height) * ROW_OVERLAP_THRESHOLD
            }
            if (row != null) row += region else rows += mutableListOf(region)
        }

        return rows.sortedBy { row -> row.minOf { it.bounds.top } }
            .flatMap { row ->
                when (direction) {
                    GuidedReadingDirection.LEFT_TO_RIGHT -> row.sortedBy { it.bounds.centerX }
                    GuidedReadingDirection.RIGHT_TO_LEFT -> row.sortedByDescending { it.bounds.centerX }
                }
            }
    }

    private const val ROW_OVERLAP_THRESHOLD = 0.4f
}
