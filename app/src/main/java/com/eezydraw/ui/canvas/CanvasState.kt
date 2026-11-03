package com.eezydraw.ui.canvas

import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.semantics.SemanticsPropertyKey
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.LengthUnit
import com.eezydraw.core.model.Point2
import com.eezydraw.core.model.Point3
import com.eezydraw.core.model.Projection
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

internal val PlanCameraSaver = listSaver<Viewport2D, Double>(
    save = { listOf(it.center.x, it.center.y, it.pixelsPerMetre) },
    restore = { Viewport2D(Point2(it[0], it[1]), it[2]) },
)
internal val OrbitCameraSaver = listSaver<OrbitCamera, Double>(
    save = { listOf(it.target.x, it.target.y, it.target.z, it.yaw, it.pitch, it.distance,
        it.orthographicHeight, it.projection.ordinal.toDouble()) },
    restore = { OrbitCamera(Point3(it[0], it[1], it[2]), it[3], it[4], it[5], it[6], Projection.entries[it[7].toInt()]) },
)
internal val PlanCenterX = SemanticsPropertyKey<Double>("PlanCenterX")
internal val PlanCenterY = SemanticsPropertyKey<Double>("PlanCenterY")
internal val PlanScale = SemanticsPropertyKey<Double>("PlanScale")
internal val OrbitYaw = SemanticsPropertyKey<Double>("OrbitYaw")
internal val OrbitPitch = SemanticsPropertyKey<Double>("OrbitPitch")

internal fun unitFactor(unit: LengthUnit): Double = when (unit) {
    LengthUnit.MILLIMETRES -> 1000.0
    LengthUnit.CENTIMETRES -> 100.0
    LengthUnit.METRES -> 1.0
    LengthUnit.INCHES -> 1.0 / .0254
    LengthUnit.FEET -> 1.0 / .3048
}
internal fun coordinate(value: Double, unit: LengthUnit, precision: Int): String {
    val scaled = value * unitFactor(unit)
    val cleaned = if (abs(scaled) < .5 * 10.0.pow(-precision)) 0.0 else scaled
    return String.format(Locale.getDefault(), "%.${precision}f", cleaned)
}
