package com.eezydraw.core.model

import kotlinx.serialization.Serializable

@Serializable enum class LengthUnit { MILLIMETRES, CENTIMETRES, METRES, INCHES, FEET }
@Serializable enum class AngleFormat { DEGREES, RADIANS }
@Serializable enum class ThemeMode { SYSTEM, LIGHT, DARK }
@Serializable enum class InputMode { TOUCH, STYLUS }
@Serializable enum class RenderQuality { PERFORMANCE, BALANCED, QUALITY }
@Serializable enum class SnapMode { GRID, ENDPOINT, MIDPOINT, INTERSECTION, PERPENDICULAR, ORTHO }
@Serializable enum class ProjectTemplate { OFFICE, RESIDENTIAL, DATA_CENTER, EMPTY }
/** Portable EEZY DRAW archives are the currently implemented export format. */
@Serializable enum class ExportFormat { EEZYDRAW }

@Serializable
data class SnapSettings(
    val enabled: Boolean = true,
    val modes: Set<SnapMode> = setOf(SnapMode.GRID, SnapMode.ENDPOINT),
    val gridSpacing: Double = 0.1,
    val tolerance: Double = 0.01,
) {
    init { require(gridSpacing.isFinite() && gridSpacing > 0 && tolerance.isFinite() && tolerance > 0) }
}

@Serializable
data class ProjectSettings(
    val lengthUnit: LengthUnit = LengthUnit.MILLIMETRES,
    val angleFormat: AngleFormat = AngleFormat.DEGREES,
    val decimalPlaces: Int = 2,
    val snap: SnapSettings = SnapSettings(),
    val defaultWallThickness: Double = 0.2,
    val defaultWallHeight: Double = 3.0,
    val defaultRouteElevations: Map<RouteKind, ElevationSetting> = emptyMap(),
    val viewMode: ViewMode = ViewMode.WIREFRAME,
    val showMeasurements: Boolean = true,
) {
    init {
        require(decimalPlaces in 0..8)
        require(defaultWallThickness.isFinite() && defaultWallThickness > 0)
        require(defaultWallHeight.isFinite() && defaultWallHeight > 0)
    }
}

/** Device preferences are separate from project data and from Android permission grants. */
@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val languageTag: String = "en",
    val inputMode: InputMode = InputMode.TOUCH,
    val renderQuality: RenderQuality = RenderQuality.BALANCED,
    val autosaveIntervalSeconds: Int = 60,
    val backupEnabled: Boolean = true,
    val undoLimit: Int = 100,
    val gestureSensitivity: Double = 1.0,
    val defaultTemplate: ProjectTemplate = ProjectTemplate.EMPTY,
    val saveLocationUri: String? = null,
    val projectDefaults: ProjectSettings = ProjectSettings(),
    val defaultLineWeightMm: Double = 0.25,
    val fileNamingPattern: String = "{name}-{date}",
    val exportFormat: ExportFormat = ExportFormat.EEZYDRAW,
    val exportIncludeUnderlays: Boolean = true,
    val showAutosaveNotification: Boolean = false,
    val bodyDragMovesSelection: Boolean = false,
) {
    init {
        require(languageTag.isNotBlank())
        require(autosaveIntervalSeconds in 0..3600 && undoLimit in 1..10000)
        require(gestureSensitivity.isFinite() && gestureSensitivity in 0.25..4.0)
        require(defaultLineWeightMm.isFinite() && defaultLineWeightMm > 0)
        require(isValidFileNamingPattern(fileNamingPattern))
    }
}

/** A filename component, with optional {name}, {date}, and {id} substitutions. */
fun isValidFileNamingPattern(pattern: String): Boolean {
    if (pattern.isBlank() || pattern.length > 80 || pattern.any { it == '/' || it == '\\' || it.isISOControl() }) return false
    val remainder = pattern.replace("{name}", "").replace("{date}", "").replace("{id}", "")
    return '{' !in remainder && '}' !in remainder && pattern != "." && pattern != ".."
}
