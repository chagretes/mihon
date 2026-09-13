package eu.kanade.tachiyomi.ui.reader.viewer.guided

data class GuidedRegion(
    val bounds: NormalizedRect,
    val confidence: Float,
)

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun intersectionOverUnion(other: NormalizedRect): Float {
        val intersectionWidth = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
        val intersectionHeight = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        val intersection = intersectionWidth * intersectionHeight
        val union = width * height + other.width * other.height - intersection
        return if (union > 0f) intersection / union else 0f
    }

    fun expanded(fraction: Float): NormalizedRect {
        val horizontal = width * fraction
        val vertical = height * fraction
        return NormalizedRect(
            left = (left - horizontal).coerceAtLeast(0f),
            top = (top - vertical).coerceAtLeast(0f),
            right = (right + horizontal).coerceAtMost(1f),
            bottom = (bottom + vertical).coerceAtMost(1f),
        )
    }
}

enum class GuidedReadingDirection {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
}
