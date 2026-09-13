package eu.kanade.tachiyomi.ui.reader.viewer.guided

class GuidedPageState {

    var regions: List<GuidedRegion> = emptyList()
        private set

    var currentIndex: Int = NO_REGION
        private set

    var detectionState = DetectionState.NOT_STARTED
        private set

    private var pendingStep: Direction? = null
    private var selectLastWhenReady = false

    fun markLoading() {
        detectionState = DetectionState.LOADING
    }

    fun setRegions(regions: List<GuidedRegion>): Step? {
        this.regions = regions
        detectionState = DetectionState.READY
        val shouldFocusLast = selectLastWhenReady && regions.isNotEmpty()
        if (selectLastWhenReady) {
            currentIndex = regions.lastIndex
            selectLastWhenReady = false
        }
        return pendingStep?.let {
            pendingStep = null
            step(it)
        } ?: if (shouldFocusLast) Step.Focus(regions.last()) else null
    }

    fun forward(): Step = step(Direction.FORWARD)

    fun backward(): Step = step(Direction.BACKWARD)

    fun selectLastRegion() {
        if (detectionState == DetectionState.READY) {
            currentIndex = regions.lastIndex
        } else {
            selectLastWhenReady = true
        }
    }

    fun reset() {
        currentIndex = NO_REGION
    }

    private fun step(direction: Direction): Step {
        if (detectionState != DetectionState.READY) {
            pendingStep = direction
            return Step.Wait
        }
        if (regions.isEmpty()) return Step.LeavePage(direction)

        return when (direction) {
            Direction.FORWARD -> {
                if (currentIndex < regions.lastIndex) {
                    currentIndex++
                    Step.Focus(regions[currentIndex])
                } else {
                    currentIndex = NO_REGION
                    Step.LeavePage(direction)
                }
            }
            Direction.BACKWARD -> {
                when {
                    currentIndex > 0 -> {
                        currentIndex--
                        Step.Focus(regions[currentIndex])
                    }
                    currentIndex == 0 -> {
                        currentIndex = NO_REGION
                        Step.ShowWholePage
                    }
                    else -> Step.LeavePage(direction)
                }
            }
        }
    }

    enum class DetectionState {
        NOT_STARTED,
        LOADING,
        READY,
    }

    sealed interface Step {
        data object Wait : Step
        data class Focus(val region: GuidedRegion) : Step
        data object ShowWholePage : Step
        data class LeavePage(val direction: Direction) : Step
    }

    enum class Direction {
        FORWARD,
        BACKWARD,
    }

    private companion object {
        const val NO_REGION = -1
    }
}
