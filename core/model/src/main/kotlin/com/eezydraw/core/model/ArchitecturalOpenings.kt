package com.eezydraw.core.model

import kotlin.math.*

/** Frames occupy the wall thickness; open leaves and swing symbols use the true door width. */
fun openingGeometry(kind:OpeningKind,size:Size3,swing:DoorSwing=DoorSwing(),plan:Boolean=false):List<Shape> {
    val w=size.width;val d=size.depth;val h=size.height
    val t=minOf(.05,w/10,h/10);val leaf=minOf(.045,d/3)
    fun box(x:Double,y:Double,z:Double,width:Double,depth:Double,height:Double)=Shape.Mesh(
        listOf(Point3(x,y,z),Point3(x+width,y,z),Point3(x+width,y+depth,z),Point3(x,y+depth,z),
            Point3(x,y,z+height),Point3(x+width,y,z+height),Point3(x+width,y+depth,z+height),Point3(x,y+depth,z+height)),
        listOf(0,2,1,0,3,2,4,5,6,4,6,7,0,1,5,0,5,4,1,2,6,1,6,5,2,3,7,2,7,6,3,0,4,3,4,7))
    return buildList {
        add(box(0.0,0.0,0.0,t,d,h));add(box(w-t,0.0,0.0,t,d,h))
        if(!plan)add(box(t,0.0,h-t,w-2*t,d,t))
        when(kind) {
            OpeningKind.DOOR,OpeningKind.DOUBLE_DOOR->{
                val radius=if(kind==OpeningKind.DOUBLE_DOOR)(w-2*t)/2 else w-2*t
                val hinges=if(kind==OpeningKind.DOUBLE_DOOR)listOf(DoorHinge.LEFT,DoorHinge.RIGHT)else listOf(swing.hinge)
                val direction=if(swing.direction==DoorDirection.IN)1.0 else -1.0
                for(hinge in hinges) {
                    val x=if(hinge==DoorHinge.LEFT)t else w-t-leaf
                    add(box(x,if(direction>0)d/2 else d/2-radius,0.0,leaf,radius,h-t))
                }
            }
            OpeningKind.SLIDING_DOOR->add(box(t,d/2,0.0,(w-2*t)/2,leaf,h-t))
            OpeningKind.WINDOW,OpeningKind.DOUBLE_WINDOW->{
                if(!plan)add(box(t,0.0,0.0,w-2*t,d,t))
                if(kind==OpeningKind.DOUBLE_WINDOW)add(box(w/2-t/2,0.0,0.0,t,d,h))
            }
        }
    }
}
fun openingPlanSymbol(kind:OpeningKind,size:Size3,swing:DoorSwing=DoorSwing()):List<Shape> {
    if(kind !in setOf(OpeningKind.DOOR,OpeningKind.DOUBLE_DOOR))return if(kind in setOf(OpeningKind.WINDOW,OpeningKind.DOUBLE_WINDOW))
        listOf(Shape.Polyline(listOf(Point3(0.0,size.depth/2),Point3(size.width,size.depth/2)))) else emptyList()
    val t=minOf(.05,size.width/10,size.height/10)
    val radius=if(kind==OpeningKind.DOUBLE_DOOR)(size.width-2*t)/2 else size.width-2*t
    val hinges=if(kind==OpeningKind.DOUBLE_DOOR)listOf(DoorHinge.LEFT,DoorHinge.RIGHT)else listOf(swing.hinge)
    val direction=if(swing.direction==DoorDirection.IN)1.0 else -1.0
    return hinges.map {hinge->val x=if(hinge==DoorHinge.LEFT)t else size.width-t
        val sign=if(hinge==DoorHinge.LEFT)1.0 else -1.0
        Shape.Polyline(List(33){i->val angle=PI/2*i/32;Point3(x+sign*radius*cos(angle),size.depth/2+direction*radius*sin(angle))})
    }
}
