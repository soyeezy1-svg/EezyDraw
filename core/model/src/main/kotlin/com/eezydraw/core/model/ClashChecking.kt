package com.eezydraw.core.model

import kotlinx.serialization.Serializable

@Serializable enum class ClashObjectCategory { WALLS, FLOORS_SLABS, BEAMS, COLUMNS, LIBRARY_ITEMS, ROUTES, FITTINGS, PIPES, DUCTS, CABLE_CARRIERS, UNDERFLOOR }

@Serializable
data class ClashGroup(val system: SystemId? = null, val category: Discipline? = null, val objects: ClashObjectCategory? = null) {
    init { require(listOfNotNull(system,category,objects).size<=1) }
}
@Serializable
data class ClashCheckOptions(
    val first: ClashGroup = ClashGroup(), val second: ClashGroup = ClashGroup(),
    val levelId: LevelId? = null, val visibleOnly: Boolean = true,
    val clearance: Double = .05, val tolerance: Double = .001,
    val systemClearances: Map<SystemId, Double> = emptyMap(),
    val panelClearance: Double = .3, val ignoreConnections: Boolean = true, val planView: Boolean = true,
) {
    init {
        require(listOf(clearance, tolerance, panelClearance).all { it.isFinite() && it in 0.0..100.0 })
        require(systemClearances.values.all { it.isFinite() && it in 0.0..100.0 })
    }
    fun key(): String = listOf(first.toString(),second.toString()).sorted().joinToString("|")+"|${levelId?.value ?: "all"}|$visibleOnly|${if(visibleOnly)planView else true}"
}

fun clashDiscipline(document: ProjectDocument, element: Element): Discipline =
    document.systems.find { it.id == element.systemId }?.discipline ?: when(val b = element.body) {
        is ElementBody.Wall, is ElementBody.Room -> Discipline.ARCHITECTURE
        is ElementBody.Component -> document.libraryItems.find { it.id == b.libraryItemId }?.discipline ?: Discipline.STRUCTURE
        is ElementBody.Route -> when {
            b.routing?.settings?.system?.contains("fire",true) == true -> Discipline.FIRE_PROTECTION
            b.routing?.settings?.system?.equals("ICT",true) == true -> Discipline.ICT
            b.kind == RouteKind.PIPE -> Discipline.PLUMBING
            b.kind == RouteKind.DUCT -> Discipline.HVAC
            else -> Discipline.ELECTRICAL
        }
        else -> Discipline.STRUCTURE
    }

fun ClashGroup.matches(document: ProjectDocument, element: Element) =
    (system == null || element.systemId == system) && (category == null || clashDiscipline(document,element) == category) && (objects == null || matchesObjectCategory(document,element,objects))

private fun matchesObjectCategory(document:ProjectDocument,e:Element,category:ClashObjectCategory):Boolean {
    val component=e.body as? ElementBody.Component
    val item=component?.let {c->document.libraryItems.find {it.id==c.libraryItemId}}
    val name=item?.name.orEmpty().lowercase()
    val route=e.body as? ElementBody.Route
    return when(category) {
        ClashObjectCategory.WALLS -> e.body is ElementBody.Wall || name.endsWith("wall")
        ClashObjectCategory.FLOORS_SLABS -> e.body is ElementBody.Slab || name=="floor" || "slab" in name
        ClashObjectCategory.BEAMS -> "beam" in name
        ClashObjectCategory.COLUMNS -> "column" in name
        ClashObjectCategory.LIBRARY_ITEMS -> component!=null && item?.fitting==null
        ClashObjectCategory.ROUTES -> route!=null
        ClashObjectCategory.FITTINGS -> item?.fitting!=null
        ClashObjectCategory.PIPES -> route?.kind==RouteKind.PIPE || item?.fitting?.routeKind==RouteKind.PIPE
        ClashObjectCategory.DUCTS -> route?.kind==RouteKind.DUCT || item?.fitting?.routeKind==RouteKind.DUCT
        ClashObjectCategory.CABLE_CARRIERS -> route?.kind in setOf(RouteKind.CONDUIT,RouteKind.TRUNKING,RouteKind.CABLE_TRAY,RouteKind.CABLE_LADDER,RouteKind.CABLE_BASKET) || item?.fitting?.routeKind in setOf(RouteKind.CONDUIT,RouteKind.TRUNKING,RouteKind.CABLE_TRAY,RouteKind.CABLE_LADDER,RouteKind.CABLE_BASKET)
        ClashObjectCategory.UNDERFLOOR -> e.floorRouting()!=null || (e.properties["routingOwner"] as? ParameterValue.Text)?.value?.let {owner->document.elements.find {it.id.value==owner}?.floorRouting()!=null}==true
    }
}
