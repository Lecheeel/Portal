package com.system.location.service.ui.mock

import com.system.location.service.core.planning.DraftPhase
import com.system.location.service.core.planning.DrawingMode
import com.system.location.service.core.planning.RouteDraft

internal data class RouteEditorActions(
    val restoring: Boolean,
    val planning: Boolean,
    val hasGeometry: Boolean,
    val canPlan: Boolean,
    val canUndo: Boolean,
    val canSave: Boolean,
) {
    companion object {
        fun from(draft: RouteDraft): RouteEditorActions {
            // The ViewModel currently publishes restoration as a message, before loading the draft.
            val restoring = draft.message == "正在恢复草稿"
            val planning = draft.phase == DraftPhase.PLANNING
            return RouteEditorActions(
                restoring = restoring,
                planning = planning,
                hasGeometry = draft.start != null || draft.end != null || draft.route != null || draft.strokes.isNotEmpty(),
                canPlan = !restoring && !planning && draft.mode == DrawingMode.PLAN &&
                    draft.start != null && draft.end != null && draft.start != draft.end,
                canUndo = !restoring && draft.mode != DrawingMode.PLAN && draft.strokes.isNotEmpty(),
                canSave = !restoring && draft.phase == DraftPhase.PLANNED && draft.route != null,
            )
        }
    }
}
