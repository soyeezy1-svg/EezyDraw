package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import java.io.OutputStream

/** Paper dimensions are millimetres; model coordinates remain metres until the final writer. */
enum class DrawingPaper(val width:Double,val height:Double) { A4(297.0,210.0),A3(420.0,297.0),A2(594.0,420.0),A1(841.0,594.0),A0(1189.0,841.0) }
data class PlanClip(val minX:Double,val minY:Double,val maxX:Double,val maxY:Double) {
    init {require(listOf(minX,minY,maxX,maxY).all(Double::isFinite) && maxX>minX && maxY>minY)}
    fun clip(a:Point3,b:Point3):Pair<Point3,Point3>? {
        var lo=0.0;var hi=1.0
        val dx=b.x-a.x;val dy=b.y-a.y
        fun edge(p:Double,q:Double):Boolean {if(kotlin.math.abs(p)<1e-12)return q>=0;val t=q/p;if(p<0)lo=maxOf(lo,t)else hi=minOf(hi,t);return lo<=hi}
        if(!edge(-dx,a.x-minX)||!edge(dx,maxX-a.x)||!edge(-dy,a.y-minY)||!edge(dy,maxY-a.y))return null
        fun at(t:Double)=Point3(a.x+dx*t,a.y+dy*t,0.0)
        return at(lo) to at(hi)
    }
}
data class DxfRaster(val corners:List<Point2>,val width:Int,val height:Int,val pixels:IntArray) {
    init {require(corners.size==4 && width in 1..256 && height in 1..256 && pixels.size==width*height)}
}
fun exportPlanSegments(document:ProjectDocument,level:LevelId?):List<SceneSegment> {
    val source=document.copy(project=document.project.copy(settings=document.project.settings.copy(showHiddenObjects=false)))
    val scene=buildDrawingScene(source,planSymbols=true)
    return scene.segments.filter {it.display!=ObjectDisplay.HIDDEN && (level==null || it.levelId==null || it.levelId==level.value)}
}

/** Single-file DXF R2007. Underlays are bounded, flattened true-colour SOLIDs behind vector entities. */
object DxfExport {
    fun write(document:ProjectDocument,level:LevelId?,units:ModelUnits,output:OutputStream,clip:PlanClip?=null,rasters:List<DxfRaster> = emptyList()) {
        val raw=exportPlanSegments(document,level)
        val elements=document.elements.associateBy {it.id.value}
        val floorIds=raw.map {it.elementId}.filter {elements[it]?.floorRouting()!=null}.toSet()
        val segments=raw.filterNot {it.elementId in floorIds}.toMutableList()
        for(id in floorIds){val e=elements.getValue(id);val route=e.body as ElementBody.Route
            (route.fabricationPath ?: route.path).map {measurementToWorld(e.transform,it)}.zipWithNext().forEach {(a,b)->segments+=SceneSegment(a,b,id,e.levelId?.value,raw.first {it.elementId==id}.colorArgb)}}
        val sourceNames=segments.associate {s->val e=elements[s.elementId];s.elementId to (e?.systemId?.let {id->document.systems.find {it.id==id}?.name}
            ?: (e?.body as? ElementBody.Route)?.routing?.settings?.system ?: e?.layerId?.let {id->document.layers.find {it.id==id}?.name} ?: "Drawing")}
        val used=mutableSetOf("0","UNDERLAY")
        val layers=sourceNames.values.distinct().associateWith {name->val base=name.replace(Regex("[<>/\\\\:;?*|=\"\\r\\n]"),"_").take(200).ifBlank {"Drawing"};var result=base;var i=2
            while(!used.add(result.uppercase()))result="${base}_${i++}";result}
        val writer=output.bufferedWriter(Charsets.UTF_8)
        fun pair(code:Int,value:Any){writer.appendLine(code.toString());writer.appendLine(value.toString())}
        var handle=16
        fun h()=(handle++).toString(16).uppercase()
        val model=h();val paper=h()
        fun start(type:String,owner:String?=null):String {val id=h();pair(0,type);pair(5,id);owner?.let {pair(330,it)};return id}
        fun section(name:String){pair(0,"SECTION");pair(2,name)}
        fun table(name:String,count:Int){start("TABLE");pair(2,name);pair(100,"AcDbSymbolTable");pair(70,count)}
        val f=units.factor;val origin=document.project.origin
        section("HEADER");pair(9,"\$ACADVER");pair(1,"AC1021");pair(9,"\$DWGCODEPAGE");pair(3,"UTF-8");pair(9,"\$INSUNITS");pair(70,when(units){ModelUnits.MM->4;ModelUnits.CM->5;ModelUnits.M->6});pair(9,"\$MEASUREMENT");pair(70,1);pair(0,"ENDSEC")
        section("TABLES")
        table("LTYPE",4)
        for(name in listOf("ByBlock","ByLayer","CONTINUOUS","DASHED")){start("LTYPE");pair(100,"AcDbSymbolTableRecord");pair(100,"AcDbLinetypeTableRecord");pair(2,name);pair(70,0);pair(3,name);pair(72,65);pair(73,if(name=="DASHED")2 else 0);pair(40,if(name=="DASHED").24*f else 0.0);if(name=="DASHED"){pair(49,.12*f);pair(74,0);pair(49,-.12*f);pair(74,0)}}
        pair(0,"ENDTAB")
        table("LAYER",layers.size+2)
        for(name in listOf("0","UNDERLAY")+layers.values){start("LAYER");pair(100,"AcDbSymbolTableRecord");pair(100,"AcDbLayerTableRecord");pair(2,name);pair(70,if(name=="UNDERLAY")4 else 0);pair(62,7);pair(6,"CONTINUOUS")};pair(0,"ENDTAB")
        table("STYLE",1);start("STYLE");pair(100,"AcDbSymbolTableRecord");pair(100,"AcDbTextStyleTableRecord");pair(2,"STANDARD");pair(70,0);pair(40,0.0);pair(41,1.0);pair(50,0.0);pair(71,0);pair(42,1.0);pair(3,"txt");pair(4,"");pair(0,"ENDTAB")
        table("BLOCK_RECORD",2)
        for((id,name)in listOf(model to "*Model_Space",paper to "*Paper_Space")){pair(0,"BLOCK_RECORD");pair(5,id);pair(100,"AcDbSymbolTableRecord");pair(100,"AcDbBlockTableRecord");pair(2,name)};pair(0,"ENDTAB");pair(0,"ENDSEC")
        section("BLOCKS")
        for((id,name)in listOf(model to "*Model_Space",paper to "*Paper_Space")){start("BLOCK",id);pair(100,"AcDbEntity");pair(8,"0");pair(100,"AcDbBlockBegin");pair(2,name);pair(70,0);pair(10,0);pair(20,0);pair(30,0);pair(3,name);pair(1,"");start("ENDBLK",id);pair(100,"AcDbEntity");pair(8,"0");pair(100,"AcDbBlockEnd")};pair(0,"ENDSEC")
        section("ENTITIES")
        fun position(code:Int,p:Point2){pair(code,(p.x+origin.x)*f);pair(code+10,(p.y+origin.y)*f);pair(code+20,0.0)}
        for(r in rasters){fun at(x:Double,y:Double):Point2 {val a=r.corners[0];val b=r.corners[1];val d=r.corners[3];return Point2(a.x+(b.x-a.x)*x+(d.x-a.x)*y,a.y+(b.y-a.y)*x+(d.y-a.y)*y)}
            for(y in 0 until r.height){var x=0;while(x<r.width){val colour=r.pixels[y*r.width+x] and 0xFFFFFF;var end=x+1;while(end<r.width && (r.pixels[y*r.width+end] and 0xFFFFFF)==colour)end++
                if(colour!=0xFFFFFF){start("SOLID",model);pair(100,"AcDbEntity");pair(8,"UNDERLAY");pair(420,colour);pair(100,"AcDbTrace");val points=listOf(at(x.toDouble()/r.width,y.toDouble()/r.height),at(end.toDouble()/r.width,y.toDouble()/r.height),at(x.toDouble()/r.width,(y+1.0)/r.height),at(end.toDouble()/r.width,(y+1.0)/r.height));points.forEachIndexed {i,p->position(10+i,p)}};x=end}}
        }
        val textIds=elements.values.filter {(it.body as? ElementBody.DrawingShape)?.shape is Shape.Text && it.transform.rotation.x==0.0 && it.transform.rotation.y==0.0}.map {it.id.value}.toSet()
        for(s in segments){if(s.elementId in textIds)continue
            val line=clip?.clip(s.a,s.b) ?: if(clip==null)(s.a to s.b)else continue
            if(kotlin.math.hypot(line.first.x-line.second.x,line.first.y-line.second.y)<1e-12)continue
            val e=elements[s.elementId];val owner=(e?.properties?.get("routingOwner") as? ParameterValue.Text)?.value?.let(elements::get)
            start("LINE",model);pair(100,"AcDbEntity");pair(8,layers.getValue(sourceNames.getValue(s.elementId)));pair(420,s.colorArgb and 0xFFFFFF);pair(6,if((owner ?: e)?.floorRouting()!=null)"DASHED"else "CONTINUOUS");pair(100,"AcDbLine");position(10,Point2(line.first.x,line.first.y));position(11,Point2(line.second.x,line.second.y))
        }
        for(id in textIds.intersect(segments.map {it.elementId}.toSet())){val e=elements.getValue(id);val text=(e.body as ElementBody.DrawingShape).shape as Shape.Text;val p=e.transform.translation
            if(clip!=null && (p.x !in clip.minX..clip.maxX || p.y !in clip.minY..clip.maxY))continue
            start("TEXT",model);pair(100,"AcDbEntity");pair(8,layers.getValue(sourceNames.getValue(id)));pair(420,raw.first {it.elementId==id}.colorArgb and 0xFFFFFF);pair(100,"AcDbText");position(10,Point2(p.x,p.y));pair(40,text.height*kotlin.math.abs(e.transform.scale.y)*f);pair(41,kotlin.math.abs(e.transform.scale.x/e.transform.scale.y));pair(71,(if(e.transform.scale.x<0)2 else 0)+(if(e.transform.scale.y<0)4 else 0));pair(1,text.text.replace('\n',' ').replace('\r',' '));pair(50,e.transform.rotation.z*180/Math.PI);pair(7,"STANDARD");pair(100,"AcDbText")}
        pair(0,"ENDSEC");pair(0,"EOF");writer.flush()
    }
}
