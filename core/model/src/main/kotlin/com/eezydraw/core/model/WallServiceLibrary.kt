package com.eezydraw.core.model

import kotlin.math.*

/** Wall equipment and sleeves carry explicit mounting/cutting intent, independent of their names. */
fun wallServiceLibraryItems():List<LibraryItem> {
    fun box(id:String,name:String,w:Double,d:Double,h:Double,height:Double,discipline:Discipline,mode:WallServiceMode)=LibraryItem(
        LibraryItemId("wall-service-$id"),name,discipline,"Wall services",Size3(w,d,h),builtIn=true,mounting=MountingType.WALL,
        defaultMountingHeight=height,geometry=listOf(Shape.Box(Size3(w,d,h))),symbol=LibrarySymbol.BOX,
        symbolGeometry=wallSymbol(w,d),
        wallService=WallServiceOptions(mode))
    val list=mutableListOf(
        box("db-flush","Distribution board — flush",.6,.15,.8,1.2,Discipline.ELECTRICAL,WallServiceMode.RECESS),
        box("db-surface","Distribution board — surface",.6,.15,.8,1.2,Discipline.ELECTRICAL,WallServiceMode.SURFACE),
        box("thermostat","Wall thermostat",.09,.025,.09,1.4,Discipline.HVAC,WallServiceMode.RECESS),
        box("wall-ac","Wall-mounted AC unit",.9,.22,.3,2.2,Discipline.HVAC,WallServiceMode.SURFACE),
        box("cctv","Wall-mounted CCTV",.12,.2,.15,2.3,Discipline.ICT,WallServiceMode.SURFACE),
        box("fire-device","Wall fire alarm device",.1,.04,.14,1.4,Discipline.FIRE_PROTECTION,WallServiceMode.RECESS),
        box("hose-cabinet","Hose reel cabinet",.75,.2,.85,.5,Discipline.FIRE_PROTECTION,WallServiceMode.RECESS),
        box("extinguisher-cabinet","Fire extinguisher cabinet",.4,.16,.75,.4,Discipline.FIRE_PROTECTION,WallServiceMode.RECESS),
        box("duct-opening","Rectangular duct wall penetration",.5,.3,.3,2.0,Discipline.HVAC,WallServiceMode.OPENING),
    )
    fun sleeve(id:String,name:String,diameter:Double)=LibraryItem(LibraryItemId("wall-service-$id"),name,Discipline.PLUMBING,"Wall services",
        Size3(diameter,.3,diameter),builtIn=true,mounting=MountingType.WALL,defaultMountingHeight=1.0,
        wallService=WallServiceOptions(WallServiceMode.OPENING,profile=OpeningProfile.CIRCULAR),symbolGeometry=wallSymbol(diameter,.3),geometry=listOf(sleeveMesh(diameter,.3)))
    list+=sleeve("pipe-sleeve","Pipe wall sleeve",.17)
    list+=sleeve("round-duct-sleeve","Round duct wall penetration",.315)
    list+=box("pipe-riser","Wall pipe riser",.05,.05,2.8,0.0,Discipline.PLUMBING,WallServiceMode.SURFACE).copy(geometry=listOf(wallPipeRiserMesh(.05,2.8)))
    return list
}
private fun sleeveMesh(diameter:Double,depth:Double):Shape.Mesh {
    val n=48;val r=diameter/2;val inner=(r-.005).coerceAtLeast(r*.8)
    val vertices=(0..3).flatMap {ring->List(n){i->val angle=2*PI*i/n;val radius=if(ring%2==0)r else inner
        Point3(r+radius*cos(angle),if(ring<2)0.0 else depth,r+radius*sin(angle))}}
    val triangles=mutableListOf<Int>()
    fun quad(a:Int,b:Int,c:Int,d:Int){triangles+=listOf(a,b,c,a,c,d)}
    for(i in 0 until n) {val j=(i+1)%n;quad(i,j,2*n+j,2*n+i);quad(n+j,n+i,3*n+i,3*n+j);quad(j,i,n+i,n+j);quad(2*n+i,2*n+j,3*n+j,3*n+i)}
    return Shape.Mesh(vertices,triangles)
}

private fun wallSymbol(w:Double,d:Double):List<Shape> = listOf(Shape.Line(Point3(w*.15,d*.15),Point3(w*.85,d*.85)),Shape.Line(Point3(w*.15,d*.85),Point3(w*.85,d*.15)))
private fun wallPipeRiserMesh(d:Double,h:Double):Shape.Mesh {
    val n=48;val points=(0..1).flatMap {level->List(n){i->val a=2*PI*i/n;Point3(d/2+d/2*cos(a),d/2+d/2*sin(a),level*h)}}+listOf(Point3(d/2,d/2),Point3(d/2,d/2,h))
    val triangles=mutableListOf<Int>()
    for(i in 0 until n){val j=(i+1)%n;triangles+=listOf(i,j,n+j,i,n+j,n+i,2*n,j,i,2*n+1,n+i,n+j)}
    return Shape.Mesh(points,triangles)
}
