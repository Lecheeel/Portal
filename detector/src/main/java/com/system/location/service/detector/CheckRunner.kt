package com.system.location.service.detector

import com.system.location.service.detector.model.Check
import com.system.location.service.detector.model.CheckContext
import com.system.location.service.detector.model.CheckResult
import com.system.location.service.detector.model.CheckStatus

/** Runs checks sequentially and converts unexpected exceptions into UNKNOWN instead of aborting. */
object CheckRunner {
    fun runAll(
        context: CheckContext,
        checks: List<Check>,
        onProgress: (check: Check, index: Int, total: Int) -> Unit = { _, _, _ -> },
    ): List<CheckResult> = checks.mapIndexed { index, check ->
        onProgress(check, index + 1, checks.size)
        runCatching { check.run(context) }.getOrElse { error ->
            CheckResult(
                id = check.id,
                title = check.title,
                status = CheckStatus.UNKNOWN,
                detail = "检查异常：${error.javaClass.simpleName}: ${error.message}",
                evidence = listOf(error.stackTraceToString().take(MAX_STACK_TRACE)),
                blocking = check.blocking,
            )
        }
    }

    private const val MAX_STACK_TRACE = 4_000
}
