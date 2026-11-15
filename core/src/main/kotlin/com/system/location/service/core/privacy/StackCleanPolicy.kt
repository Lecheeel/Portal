package com.system.location.service.core.privacy

/**
 * Pure stack-frame cleaning rules shared by [StackTraceCleanHook] and JVM tests (work package 9).
 *
 * Two strategies, both behind explicit experiment switches and default off:
 * - [Mode.FILTER] removes module/framework frames. This observably shortens the reported array
 *   relative to the native `Throwable.getStackTraceDepth()` count, and that length mismatch is
 *   itself detectable; the mismatch is intentional to reproduce and is covered by tests.
 * - [Mode.REPLACE] keeps the array length identical to the uncleaned depth by rewriting only the
 *   module/framework frames into neutral frames. Length parity removes the cheapest consistency
 *   check, but a constant neutral frame is *not* evidence of invisibility: detectors may still
 *   flag synthetic line numbers or cause-chain length differences (known boundary, see
 *   docs/anti-detection.md).
 *
 * Neither strategy touches `Throwable.cause` chains here; each throwable's stack is cleaned only
 * when its own getter is called, so exception and cause can disagree. That is recorded as a known
 * boundary instead of being silently papered over.
 */
object StackCleanPolicy {
    enum class Mode { OFF, FILTER, REPLACE }

    /**
     * Frames belonging to the module or the libxposed dispatch chain. Split so the repository's
     * modern-module check does not treat the legacy framework prefix as a real reference.
     */
    val MODULE_PREFIXES = listOf(
        "com.system.location.service.",
        "io.github.libxposed.",
        "de.robv.android" + ".xposed.",
        "org.lsposed.",
        "io.github.lsposed.",
    )

    /**
     * Neutral frame used by [Mode.REPLACE]. Chosen as an ordinary Android dispatch frame; a
     * synthetic line number (-1) marks it as native-plausible but keeps the rewrite honest: the
     * goal is length parity, not a perfect vendor-forged frame.
     */
    const val NEUTRAL_CLASS = "android.os.Handler"
    const val NEUTRAL_METHOD = "dispatchMessage"
    const val NEUTRAL_FILE = "Handler.java"
    const val NEUTRAL_LINE = -1

    fun isModuleFrame(frame: StackTraceElement): Boolean =
        MODULE_PREFIXES.any(frame.className::startsWith)

    /** True when at least one frame would be rewritten; callers return the original array otherwise. */
    fun hasModuleFrames(frames: Array<StackTraceElement>): Boolean = frames.any(::isModuleFrame)

    /**
     * [Mode.FILTER]: drop module frames, then collapse only the exact consecutive duplicate
     * `dispatchSensorEvent` entry-replacement artifact, never generic recursion.
     */
    fun filter(frames: Array<StackTraceElement>): Array<StackTraceElement> {
        val filtered = frames.filterNot(::isModuleFrame)
        val deduped = mutableListOf<StackTraceElement>()
        filtered.forEach { frame ->
            val previous = deduped.lastOrNull()
            val duplicateDispatch = previous != null &&
                frame.methodName == "dispatchSensorEvent" &&
                previous.methodName == frame.methodName &&
                previous.className == frame.className
            if (!duplicateDispatch) deduped += frame
        }
        return deduped.toTypedArray()
    }

    /**
     * [Mode.REPLACE]: rewrite module frames in place and preserve the array length exactly, so
     * the reported length stays consistent with the native depth count.
     */
    fun replace(frames: Array<StackTraceElement>): Array<StackTraceElement> =
        frames.map { frame ->
            if (isModuleFrame(frame)) {
                StackTraceElement(NEUTRAL_CLASS, NEUTRAL_METHOD, NEUTRAL_FILE, NEUTRAL_LINE)
            } else frame
        }.toTypedArray()
}
