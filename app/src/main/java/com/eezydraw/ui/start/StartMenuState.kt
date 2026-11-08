package com.eezydraw.ui.start

/** Presentation metadata. Adapters resolve domain thumbnail asset IDs into local content URIs. */
data class RecentProject(val id: String, val name: String, val detail: String, val thumbnailUri: String? = null)

/** Ordered most recent first; an empty catalogue never fabricates user projects. */
data class StartMenuUiState(val recentProjects: List<RecentProject> = emptyList()) {
    val canContinue: Boolean get() = recentProjects.isNotEmpty()
}

sealed interface StartMenuAction {
    data object CreateProject : StartMenuAction
    data object ContinueProject : StartMenuAction
    data object OpenFile : StartMenuAction
    data object SampleProjects : StartMenuAction
    data object Templates : StartMenuAction
    data object Settings : StartMenuAction
    data object Help : StartMenuAction
    data class OpenRecent(val projectId: String) : StartMenuAction
}
