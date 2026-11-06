package com.eezydraw.ui.canvas

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eezydraw.R
import com.eezydraw.core.editing.LibraryPlacement
import com.eezydraw.core.editing.PrecisionPick
import com.eezydraw.core.editing.projectPrecisionPoint
import com.eezydraw.core.geometry.OrbitCamera
import com.eezydraw.core.geometry.Viewport2D
import com.eezydraw.core.model.*

/** Keep the raw pointer aim separate from the resolved insertion/connection used for display. */
internal fun resolvedLibraryPrecisionPick(
    pick: PrecisionPick?, placement: LibraryPlacement, viewport: Viewport2D,
    orbit: OrbitCamera, is3D: Boolean, width: Double, height: Double,
): PrecisionPick? {
    if (pick == null) return null
    val point = placement.connectionPoint ?: placement.element.transform.translation
    val screen = projectPrecisionPoint(point,viewport,orbit,is3D,width,height) ?: return null
    return pick.copy(point=point,screen=screen,wallEdge=placement.wallSnapped,
        mode=if(placement.connectedRouteId!=null) SnapMode.ENDPOINT else pick.mode.takeIf {pick.point==point},
        elementId=placement.connectedRouteId ?: (placement.element.body as? ElementBody.Component)?.host?.hostElementId)
}

@Composable
internal fun LibraryPlacementFeedback(
    document: ProjectDocument, item: LibraryItem, placement: LibraryPlacement?, error: String?,
    modifier: Modifier = Modifier,
) {
    val unit = document.project.settings.lengthUnit
    val precision = document.project.settings.decimalPlaces
    val suffix = unitSuffix(unit)
    Surface(modifier.testTag("library_mounting_height"),shape=MaterialTheme.shapes.small,tonalElevation=4.dp) {
        Column(Modifier.padding(horizontal=12.dp,vertical=8.dp)) {
            if (placement == null) {
                Text(error ?: stringResource(if(item.fitting!=null)R.string.library_fitting_needs_route
                    else R.string.library_placement_needs_wall),Modifier.testTag("library_placement_error"),
                    style=MaterialTheme.typography.labelLarge)
            } else {
                Text(stringResource(R.string.library_placement_mounting_height,coordinate(placement.mountingHeight,unit,precision),suffix),
                    style=MaterialTheme.typography.labelLarge)
                if (item.fitting != null) {
                    val route = document.elements.find {it.id==placement.connectedRouteId}
                    Text(if(placement.connectedRouteId==null)stringResource(R.string.library_fitting_seek_route)
                        else stringResource(R.string.library_fitting_connected_route,
                            route?.name?.takeIf {it.isNotBlank()} ?: stringResource(R.string.library_fitting_route)),
                        Modifier.testTag("fitting_connection_status"),style=MaterialTheme.typography.labelMedium)
                    val section = placement.connectionSection ?: placement.item.fitting?.section
                    if (section != null) Text(sectionLabel(section,unit,precision,suffix),
                        Modifier.testTag("fitting_connection_section"),style=MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun sectionLabel(section: RouteSection, unit: LengthUnit, precision: Int, suffix: String): String = when(section) {
    is RouteSection.Circular -> stringResource(R.string.library_fitting_section_circular,
        coordinate(section.diameter,unit,precision),suffix)
    is RouteSection.Rectangular -> stringResource(R.string.library_fitting_section_rectangular,
        coordinate(section.width,unit,precision),coordinate(section.height,unit,precision),suffix)
}
