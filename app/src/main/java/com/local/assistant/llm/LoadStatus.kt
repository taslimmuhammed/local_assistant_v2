package com.local.assistant.llm

/**
 * Human-readable description of what a model load is doing right now.
 *
 * Loading can legitimately take minutes the first time, because measuring the context window
 * means filling it for real. Without a running commentary that is indistinguishable from a hang,
 * so every stage says what it is doing and roughly how far along it is.
 */
data class LoadStatus(
    val headline: String,
    val detail: String?,
    /** 0f..1f when the stage has a meaningful fraction, null when it is indeterminate. */
    val fraction: Float?,
) {
    companion object {
        fun of(
            state: LlmService.State,
            backend: String?,
            calibration: CalibrationProgress?,
        ): LoadStatus? = when {
            calibration is CalibrationProgress.Trying -> LoadStatus(
                headline = "Measuring the context window",
                detail = "Trying ${calibration.tokens} tokens on ${backend ?: "this device"} " +
                    "(step ${calibration.step} of ${calibration.steps})",
                fraction = calibration.step.toFloat() / calibration.steps,
            )

            calibration is CalibrationProgress.Confirming -> LoadStatus(
                headline = "Confirming ${calibration.tokens} tokens",
                detail = "Filling the window for real so it is not just a guess — " +
                    "${calibration.reached} of ${calibration.tokens} so far.",
                fraction = calibration.reached.toFloat() / calibration.tokens,
            )

            state is LlmService.State.Loading -> LoadStatus(
                headline = "Starting the model",
                detail = backend?.let { "Backend: $it" },
                fraction = null,
            )

            else -> null
        }
    }
}
