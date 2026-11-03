package com.eezydraw.ui.settings

import androidx.compose.runtime.saveable.Saver
import com.eezydraw.core.model.AppSettings
import com.eezydraw.core.model.ElevationReference
import com.eezydraw.core.model.ElevationSetting
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.RouteKind
import com.eezydraw.core.model.isValidFileNamingPattern
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Retains partial numeric input until it can be validated, without constructing invalid models. */
internal data class SettingsDraft(val settings: AppSettings, val inputs: Map<String, String>) {
    fun input(key: String, value: String) = copy(inputs = inputs + (key to value))

    fun changeUnits(unit: LengthUnit): SettingsDraft {
        val conversion = settings.projectDefaults.lengthUnit.metresPerUnit / unit.metresPerUnit
        return copy(
            settings = settings.copy(projectDefaults = settings.projectDefaults.copy(lengthUnit = unit)),
            inputs = inputs.mapValues { (key, text) ->
                if (key == "grid" || key == "tolerance" || key.startsWith("height_")) {
                    text.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { formatNumber(it * conversion) } ?: text
                } else text
            },
        )
    }

    fun routeReference(kind: RouteKind, reference: ElevationReference): SettingsDraft {
        val old = settings.projectDefaults.defaultRouteElevations[kind]
            ?: ElevationSetting(2.5, ElevationReference.ABOVE_FINISHED_FLOOR)
        return copy(settings = settings.copy(projectDefaults = settings.projectDefaults.copy(
            defaultRouteElevations = settings.projectDefaults.defaultRouteElevations + (kind to old.copy(reference = reference)),
        )))
    }

    fun errors(): Set<String> = buildSet {
        val precision = inputs["precision"]?.toIntOrNull()
        val autosave = inputs["autosave"]?.toIntOrNull()
        val undo = inputs["undo"]?.toIntOrNull()
        if (precision == null || precision !in 0..8) add("precision")
        if (autosave == null || autosave !in 0..3600) add("autosave")
        if (undo == null || undo !in 1..10000) add("undo")
        for (key in listOf("grid", "tolerance", "line_weight")) {
            val value = inputs[key]?.toDoubleOrNull()
            val canonical = value?.times(if (key == "line_weight") 1.0 else settings.projectDefaults.lengthUnit.metresPerUnit)
            if (canonical == null || !canonical.isFinite() || canonical <= 0) add(key)
        }
        val sensitivity = inputs["gesture"]?.toDoubleOrNull()
        if (sensitivity == null || !sensitivity.isFinite() || sensitivity !in 0.25..4.0) add("gesture")
        RouteKind.entries.forEach { kind ->
            val key = "height_${kind.name}"
            val value = inputs[key]?.toDoubleOrNull()
            if (value == null || !value.isFinite()) add(key)
        }
        if (!isValidFileNamingPattern(inputs["file_naming"].orEmpty())) add("file_naming")
    }

    fun toSettings(): AppSettings? {
        if (errors().isNotEmpty()) return null
        val defaults = settings.projectDefaults
        val factor = defaults.lengthUnit.metresPerUnit
        return settings.copy(
            autosaveIntervalSeconds = inputs.getValue("autosave").toInt(),
            undoLimit = inputs.getValue("undo").toInt(),
            gestureSensitivity = inputs.getValue("gesture").toDouble(),
            defaultLineWeightMm = inputs.getValue("line_weight").toDouble(),
            fileNamingPattern = inputs.getValue("file_naming"),
            projectDefaults = defaults.copy(
                decimalPlaces = inputs.getValue("precision").toInt(),
                snap = defaults.snap.copy(
                    gridSpacing = inputs.getValue("grid").toDouble() * factor,
                    tolerance = inputs.getValue("tolerance").toDouble() * factor,
                ),
                defaultRouteElevations = RouteKind.entries.associateWith { kind ->
                    ElevationSetting(
                        inputs.getValue("height_${kind.name}").toDouble() * factor,
                        defaults.defaultRouteElevations[kind]?.reference ?: ElevationReference.ABOVE_FINISHED_FLOOR,
                    )
                },
            ),
        )
    }

    companion object {
        fun from(settings: AppSettings): SettingsDraft {
            val defaults = settings.projectDefaults
            val factor = defaults.lengthUnit.metresPerUnit
            return SettingsDraft(settings, buildMap {
                put("precision", defaults.decimalPlaces.toString())
                put("grid", formatNumber(defaults.snap.gridSpacing / factor))
                put("tolerance", formatNumber(defaults.snap.tolerance / factor))
                put("autosave", settings.autosaveIntervalSeconds.toString())
                put("undo", settings.undoLimit.toString())
                put("gesture", formatNumber(settings.gestureSensitivity))
                put("line_weight", formatNumber(settings.defaultLineWeightMm))
                put("file_naming", settings.fileNamingPattern)
                RouteKind.entries.forEach { kind ->
                    put("height_${kind.name}", formatNumber((defaults.defaultRouteElevations[kind]?.value ?: 2.5) / factor))
                }
            })
        }

        val saver = Saver<SettingsDraft, String>(
            save = { draft ->
                buildJsonObject {
                    put("settings", Json.encodeToString(draft.settings))
                    put("inputs", Json.encodeToString(draft.inputs))
                }.toString()
            },
            restore = { saved ->
                val value = Json.parseToJsonElement(saved).jsonObject
                SettingsDraft(
                    Json.decodeFromString<AppSettings>(value.getValue("settings").jsonPrimitive.content),
                    Json.decodeFromString<Map<String, String>>(value.getValue("inputs").jsonPrimitive.content),
                )
            },
        )
    }
}

internal val LengthUnit.metresPerUnit: Double get() = when (this) {
    LengthUnit.MILLIMETRES -> 0.001
    LengthUnit.CENTIMETRES -> 0.01
    LengthUnit.METRES -> 1.0
    LengthUnit.INCHES -> 0.0254
    LengthUnit.FEET -> 0.3048
}

private fun formatNumber(value: Double): String =
    if (value.isFinite()) value.toBigDecimal().stripTrailingZeros().toPlainString() else value.toString()
