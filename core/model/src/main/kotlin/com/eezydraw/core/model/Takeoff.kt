package com.eezydraw.core.model

import kotlinx.serialization.Serializable

@Serializable enum class ScheduleCategory { EQUIPMENT, ROUTES, FITTINGS, ROOMS, WALL_CUTS, BOQ }
@Serializable enum class ScheduleColumn(val label:String) {
    ITEM("Item"), TYPE("Type"), SIZE("Size (mm)"), SYSTEM("System"), LEVEL("Level"), ROOM("Room"),
    QUANTITY("Quantity"), UNIT("Unit"), LENGTH("Measured length (m)"), ALLOWED_LENGTH("Length with allowance (m)"),
    FITTINGS("Fittings"), AREA("Area (m²)"), WIDTH("Width (mm)"), HEIGHT("Height (mm)"), DEPTH("Depth (mm)"),
    WALL("Wall"), POSITION("Position (m)"), RATE("Unit rate"), COST("Cost")
}
@Serializable enum class ScheduleGroup { ITEM, TYPE, SIZE, SYSTEM, LEVEL, ROOM, WALL, POSITION }
@Serializable enum class TakeoffUnit(val symbol:String) { EACH("ea"), METRE("m"), SQUARE_METRE("m²") }

@Serializable data class ScheduleDefinition(
    val id:String, val name:String, val category:ScheduleCategory,
    val routeKind:RouteKind?=null,
    val columns:List<ScheduleColumn> = listOf(ScheduleColumn.ITEM,ScheduleColumn.TYPE,ScheduleColumn.SIZE,ScheduleColumn.SYSTEM,ScheduleColumn.LEVEL,ScheduleColumn.QUANTITY),
    val grouping:List<ScheduleGroup> = listOf(ScheduleGroup.TYPE,ScheduleGroup.SIZE,ScheduleGroup.SYSTEM,ScheduleGroup.LEVEL),
    val sort:ScheduleColumn=ScheduleColumn.TYPE, val descending:Boolean=false,
    val levels:Set<LevelId> = emptySet(), val systems:Set<SystemId> = emptySet(),
    val query:String="", val visibleOnly:Boolean=true, val totals:Boolean=true,
) {
    init {require(id.isNotBlank() && name.isNotBlank() && name.length<=120 && query.length<=500)
        require(columns.isNotEmpty() && columns.distinct().size==columns.size && grouping.distinct().size==grouping.size)}
}

@Serializable data class TakeoffSettings(
    val allowancePercent:Double=0.0, val currency:String="USD",
    val schedules:List<ScheduleDefinition> = emptyList(),
    /** Rate identity -> ISO currency -> amount; currency changes never relabel existing rates. */
    val rates:Map<String,Map<String,Double>> = emptyMap(),
) {
    init {require(allowancePercent.isFinite() && allowancePercent in 0.0..1000.0);require(currency.matches(Regex("[A-Z]{3}")))
        require(schedules.size<=100 && schedules.map {it.id}.distinct().size==schedules.size)
        require(rates.size<=20000 && rates.values.all {validUnitRates(it)})}
}
fun validUnitRates(rates:Map<String,Double>)=rates.size<=100 && rates.all {(currency,value)->currency.matches(Regex("[A-Z]{3}")) && value.isFinite() && value in 0.0..1e12}

fun providedSchedules():List<ScheduleDefinition> {
    val equipment=ScheduleDefinition("equipment","Equipment and devices",ScheduleCategory.EQUIPMENT,
        columns=listOf(ScheduleColumn.ITEM,ScheduleColumn.TYPE,ScheduleColumn.SIZE,ScheduleColumn.SYSTEM,ScheduleColumn.LEVEL,ScheduleColumn.ROOM,ScheduleColumn.QUANTITY),
        grouping=listOf(ScheduleGroup.ITEM,ScheduleGroup.TYPE,ScheduleGroup.SIZE,ScheduleGroup.SYSTEM,ScheduleGroup.LEVEL,ScheduleGroup.ROOM))
    val routes=RouteKind.entries.map {kind->ScheduleDefinition("route-${kind.name}",kind.name.replace('_',' ').lowercase().replaceFirstChar {it.uppercase()},ScheduleCategory.ROUTES,kind,
        columns=listOf(ScheduleColumn.TYPE,ScheduleColumn.SIZE,ScheduleColumn.LEVEL,ScheduleColumn.SYSTEM,ScheduleColumn.LENGTH,ScheduleColumn.ALLOWED_LENGTH,ScheduleColumn.FITTINGS))}
    return listOf(equipment)+routes+listOf(
        ScheduleDefinition("fittings","Fittings",ScheduleCategory.FITTINGS),
        ScheduleDefinition("rooms","Rooms",ScheduleCategory.ROOMS,columns=listOf(ScheduleColumn.ITEM,ScheduleColumn.AREA,ScheduleColumn.LEVEL),grouping=listOf(ScheduleGroup.ITEM,ScheduleGroup.LEVEL)),
        ScheduleDefinition("wall-cuts","Wall chases, recesses and openings",ScheduleCategory.WALL_CUTS,
            columns=listOf(ScheduleColumn.WALL,ScheduleColumn.ITEM,ScheduleColumn.TYPE,ScheduleColumn.WIDTH,ScheduleColumn.HEIGHT,ScheduleColumn.DEPTH,ScheduleColumn.LENGTH,ScheduleColumn.POSITION,ScheduleColumn.LEVEL),
            grouping=listOf(ScheduleGroup.WALL,ScheduleGroup.ITEM,ScheduleGroup.TYPE,ScheduleGroup.POSITION)),
        ScheduleDefinition("boq","Bill of quantities",ScheduleCategory.BOQ,
            columns=listOf(ScheduleColumn.ITEM,ScheduleColumn.TYPE,ScheduleColumn.SIZE,ScheduleColumn.LEVEL,ScheduleColumn.SYSTEM,ScheduleColumn.UNIT,ScheduleColumn.QUANTITY,ScheduleColumn.RATE,ScheduleColumn.COST)))
}
