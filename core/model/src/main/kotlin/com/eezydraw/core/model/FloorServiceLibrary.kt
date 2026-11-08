package com.eezydraw.core.model

fun floorServiceLibraryItems():List<LibraryItem> {
    fun item(id:String,name:String,size:Size3,kind:FloorDeviceKind)=LibraryItem(LibraryItemId("floor-service-$id"),name,Discipline.ELECTRICAL,"Floor services",size,
        geometry=listOf(Shape.Box(size)),symbolGeometry=listOf(Shape.Line(Point3(size.width*.1,size.depth*.1),Point3(size.width*.9,size.depth*.9)),Shape.Line(Point3(size.width*.1,size.depth*.9),Point3(size.width*.9,size.depth*.1))),builtIn=true,mounting=MountingType.FLOOR,floorService=kind)
    val sleeve=item("sleeve","Sleeve through slab",Size3(.15,.15,.2),FloorDeviceKind.SLAB_SLEEVE)
    // Hollow rectangular sleeve, with the void retained in its library geometry.
    val outer=listOf(Point2(0.0,0.0),Point2(.15,0.0),Point2(.15,.15),Point2(0.0,.15))
    val inner=listOf(Point2(.005,.005),Point2(.145,.005),Point2(.145,.145),Point2(.005,.145))
    val vertices=(listOf(outer,inner,outer,inner)).flatMapIndexed {ring,points->points.map {Point3(it.x,it.y,if(ring<2)0.0 else .2)}}
    val triangles=mutableListOf<Int>()
    fun quad(a:Int,b:Int,c:Int,d:Int){triangles+=listOf(a,b,c,a,c,d)}
    for(i in 0..3){val j=(i+1)%4;quad(i,j,8+j,8+i);quad(4+j,4+i,12+i,12+j);quad(j,i,4+i,4+j);quad(8+i,8+j,12+j,12+i)}
    return listOf(item("box","Recessed floor box",Size3(.2,.2,.08),FloorDeviceKind.FLOOR_BOX),item("socket","Floor socket",Size3(.12,.12,.06),FloorDeviceKind.FLOOR_SOCKET),sleeve.copy(geometry=listOf(Shape.Mesh(vertices,triangles))))
}
