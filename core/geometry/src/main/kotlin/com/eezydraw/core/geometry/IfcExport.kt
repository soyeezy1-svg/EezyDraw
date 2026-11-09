package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import java.io.OutputStream
import java.math.BigInteger
import java.util.UUID

internal fun ifcClass(document:ProjectDocument,m:ExportMesh):String {
    if(m.host!=null)return "IFCOPENINGELEMENT"
    return when(val b=m.element.body) {
        is ElementBody.Wall->"IFCWALL"
        is ElementBody.Room->"IFCSPACE"
        is ElementBody.Slab->"IFCSLAB"
        is ElementBody.Route->when(b.kind){RouteKind.PIPE->"IFCPIPESEGMENT";RouteKind.DUCT->"IFCDUCTSEGMENT";else->"IFCCABLECARRIERSEGMENT"}
        is ElementBody.Component->{val item=document.libraryItems.find {it.id==b.libraryItemId};val name=item?.name.orEmpty().lowercase();val building=item?.discipline in listOf(Discipline.ARCHITECTURE,Discipline.STRUCTURE,Discipline.CIVIL)
            when {item?.fitting!=null->when(item.fitting!!.routeKind){RouteKind.PIPE->"IFCPIPEFITTING";RouteKind.DUCT->"IFCDUCTFITTING";else->"IFCCABLECARRIERFITTING"}
                building && Regex("\\bwall$").containsMatchIn(name)->"IFCWALL";building && ("slab" in name || name=="floor")->"IFCSLAB";building && Regex("\\bdoor\\b").containsMatchIn(name)->"IFCDOOR";building && Regex("\\bwindow\\b").containsMatchIn(name)->"IFCWINDOW";building && "column" in name->"IFCCOLUMN";building && "beam" in name->"IFCBEAM"
                "pump" in name->"IFCPUMP";"valve" in name->"IFCVALVE";"fan" in name->"IFCFAN";"ahu" in name || "fcu" in name || "air handling" in name->"IFCUNITARYEQUIPMENT"
                item?.symbol==LibrarySymbol.PANEL->"IFCELECTRICDISTRIBUTIONBOARD";item?.symbol in listOf(LibrarySymbol.SOCKET,LibrarySymbol.FLOOR_BOX)->"IFCOUTLET";item?.symbol==LibrarySymbol.LIGHT->"IFCLIGHTFIXTURE";item?.symbol==LibrarySymbol.SWITCH->"IFCSWITCHINGDEVICE";item?.symbol==LibrarySymbol.RACK->"IFCCOMMUNICATIONSAPPLIANCE";else->"IFCBUILDINGELEMENTPROXY"}}
        else->"IFCBUILDINGELEMENTPROXY"
    }
}
internal fun writeIfc(document:ProjectDocument,meshes:List<ExportMesh>,options:ModelExportOptions,output:OutputStream,originShift:Point3=Point3()) {
    val w=output.bufferedWriter();var index=0
    fun entity(type:String,args:String):Int {val id=++index;w.appendLine("#$id=$type($args);");return id}
    fun str(s:String):String = if(s.any {it.code !in 32..126})"'\\X2\\"+s.toByteArray(Charsets.UTF_16BE).joinToString(""){(it.toInt() and 255).toString(16).uppercase().padStart(2,'0')}+"\\X0\\'"else "'"+s.replace("'","''")+"'"
    fun guid():String {var n=BigInteger(UUID.randomUUID().toString().replace("-",""),16);val alphabet="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_$";var result="";repeat(22){result=alphabet[n.and(BigInteger.valueOf(63)).toInt()]+result;n=n.shiftRight(6)};return str(result)}
    fun refs(ids:List<Int>)="("+ids.joinToString(","){"#$it"}+")"
    val f=options.units.factor
    w.appendLine("ISO-10303-21;\nHEADER;\nFILE_DESCRIPTION(('ViewDefinition [DesignTransferView]',${str(exportCoordinateNote(options,originShift))}),'2;1');\nFILE_NAME('model.ifc','${java.time.Instant.now()}',(''),(''),${str("EEZY DRAW "+options.appVersion)},'EEZY DRAW','');\nFILE_SCHEMA(('IFC4'));\nENDSEC;\nDATA;")
    val zero=entity("IFCCARTESIANPOINT","(0.,0.,0.)");val axis=entity("IFCAXIS2PLACEMENT3D","#$zero,$,$")
    val placement=entity("IFCLOCALPLACEMENT","$,#$axis")
    val context=entity("IFCGEOMETRICREPRESENTATIONCONTEXT","$,'Model',3,1.E-6,#$axis,$")
    val prefix=when(options.units){ModelUnits.MM->".MILLI.";ModelUnits.CM->".CENTI.";else->"$"}
    val unit=entity("IFCSIUNIT","*,.LENGTHUNIT.,$prefix,.METRE.")
    val units=entity("IFCUNITASSIGNMENT","(#$unit)")
    val project=entity("IFCPROJECT","${guid()},$,${str(document.project.name)},${str(document.project.description)},$,$,$,(#$context),#$units")
    val building=entity("IFCBUILDING","${guid()},$,${str(document.project.name)},$,$,#$placement,$,$,.ELEMENT.,$,$,$")
    entity("IFCRELAGGREGATES","${guid()},$,$,$,#$project,(#$building)")
    val levels=document.levels.filter {l->options.scope==ModelScope.PROJECT || meshes.any {it.element.levelId==l.id}}.associate {l->l.id to entity("IFCBUILDINGSTOREY","${guid()},$,${str(l.name)},$,$,#$placement,$,$,.ELEMENT.,${(l.elevation+document.project.origin.z-originShift.z)*f}")}.toMutableMap<LevelId?,Int>()
    if(meshes.any {it.element.levelId==null})levels[null]=entity("IFCBUILDINGSTOREY","${guid()},$,'Project datum',$,$,#$placement,$,$,.ELEMENT.,${(document.project.origin.z-originShift.z)*f}")
    entity("IFCRELAGGREGATES","${guid()},$,$,$,#$building,${refs(levels.values.toList())}")
    val objects=linkedMapOf<ElementId,Int>();val contained=mutableMapOf<LevelId?,MutableList<Int>>();val systems=mutableMapOf<String,MutableList<Int>>()
    val pendingVoids=mutableListOf<Pair<Int,ElementId>>()
    for(m in meshes) {
        val points=entity("IFCCARTESIANPOINTLIST3D",m.vertices.joinToString(",","(",")"){"(${it.x*f},${it.y*f},${it.z*f})"})
        val faces=entity("IFCTRIANGULATEDFACESET","#$points,$,.F.,${m.vertices.indices.step(3).joinToString(",","(",")"){"(${it+1},${it+2},${it+3})"}},$")
        val colour=entity("IFCCOLOURRGB","$,${rgb(m.colourArgb).joinToString()}")
        val shading=entity("IFCSURFACESTYLERENDERING","#$colour,${1-(m.colourArgb shr 24 and 255)/255.0},$,$,$,$,$,$,.MATT.")
        val style=entity("IFCSURFACESTYLE","${str(m.name+" matte")},.BOTH.,(#$shading)")
        entity("IFCSTYLEDITEM","#$faces,(#$style),$")
        val shape=entity("IFCSHAPEREPRESENTATION","#$context,'Body','Tessellation',(#$faces)")
        val representation=entity("IFCPRODUCTDEFINITIONSHAPE","$,$,(#$shape)")
        val type=if(m.host!=null && (meshes.none {it.element.id==m.host} || meshes.any {it.element.id==m.host && it.element.body is ElementBody.Room}))"IFCBUILDINGELEMENTPROXY"else ifcClass(document,m)
        val base="${guid()},$,${str(m.name)},$,$,#$placement,#$representation,${str(m.element.id.value)}"
        val args=when(type){"IFCDOOR","IFCWINDOW"->"$base,$,$,.NOTDEFINED.,.NOTDEFINED.,$";"IFCSPACE"->"$base,.ELEMENT.,.NOTDEFINED.,$";else->"$base,.NOTDEFINED."}
        val id=entity(type,args);objects[m.element.id]=id
        // Spaces and opening voids may have styled geometry, but no material association.
        if(type!="IFCSPACE" && type!="IFCOPENINGELEMENT") {
            val materialName=document.materials.find {it.id==m.element.materialId}?.name ?: renderMaterial(m.element).name.lowercase()+" matte"
            val material=entity("IFCMATERIAL","${str(materialName)},$,$")
            entity("IFCRELASSOCIATESMATERIAL","${guid()},$,$,$,(#$id),#$material")
        }
        if(m.host!=null && type=="IFCOPENINGELEMENT")pendingVoids+=id to m.host else if(type=="IFCSPACE")entity("IFCRELAGGREGATES","${guid()},$,$,$,#${levels.getValue(m.element.levelId)},(#$id)")else contained.getOrPut(m.element.levelId){mutableListOf()}.add(id)
        val layer=document.layers.find {it.id==m.element.layerId}?.name.orEmpty()
        entity("IFCPRESENTATIONLAYERASSIGNMENT","${str(layer)},$,(#$faces),$")
        val system=m.element.systemId?.let {s->document.systems.find {it.id==s}?.name} ?: (m.element.body as? ElementBody.Route)?.routing?.settings?.system.orEmpty()
        if(system.isNotBlank())systems.getOrPut(system){mutableListOf()}.add(id)
        val properties=linkedMapOf<String,String>("Layer" to layer,"System" to system,"Name" to m.name,"ExportUpAxis" to options.resolvedUpAxis().name,"ExportUnits" to options.units.name,"ExportAppVersion" to options.appVersion,"ExportCoordinates" to exportCoordinateNote(options,originShift))
        val body=m.element.body
        if(body is ElementBody.Component){val item=document.libraryItems.find {it.id==body.libraryItemId};properties["Manufacturer"]=(body.parameters[LibraryParameterKeys.MANUFACTURER] as? ParameterValue.Text)?.value ?: item?.manufacturer.orEmpty();properties["Model"]=(body.parameters[LibraryParameterKeys.MODEL] as? ParameterValue.Text)?.value ?: item?.model.orEmpty()}
        val pids=properties.map {(k,v)->entity("IFCPROPERTYSINGLEVALUE","${str(k)},$,IFCLABEL(${str(v)}),$")}.toMutableList()
        val lengths=linkedMapOf<String,Double>()
        when(body){is ElementBody.Wall->{lengths["Thickness"]=body.thickness;lengths["Height"]=body.height};is ElementBody.Slab->lengths["Thickness"]=body.thickness;is ElementBody.Component->{lengths["Width"]=body.size.width;lengths["Depth"]=body.size.depth;lengths["Height"]=body.size.height};is ElementBody.Route->when(val s=body.section){is RouteSection.Circular->lengths["Diameter"]=s.diameter;is RouteSection.Rectangular->{lengths["Width"]=s.width;lengths["Height"]=s.height}};else->{}}
        if(body is ElementBody.Component) {
            body.parameters.forEach {(key,value)->if(value is ParameterValue.Number && value.quantity==Quantity.LENGTH)lengths[key]=value.value}
            val definition=document.libraryItems.find {it.id==body.libraryItemId}?.fitting
            fun profile(prefix:String,section:RouteSection) {when(section){is RouteSection.Circular->lengths[prefix+"Diameter"]=section.diameter;is RouteSection.Rectangular->{lengths[prefix+"Width"]=section.width;lengths[prefix+"Height"]=section.height}}}
            definition?.let {profile("Inlet",it.section);it.ports.getOrNull(1)?.let {port->profile("Outlet",port.section)}}
        }
        lengths["MountingHeight"]=(body as? ElementBody.Component)?.parameters?.get(LibraryParameterKeys.MOUNTING_HEIGHT)?.let {(it as? ParameterValue.Number)?.value} ?: (m.element.transform.translation.z-(document.levels.find {it.id==m.element.levelId}?.elevation ?: 0.0))
        for((k,v)in lengths)pids+=entity("IFCPROPERTYSINGLEVALUE","${str(k)},$,IFCLENGTHMEASURE(${v*f}),$")
        val set=entity("IFCPROPERTYSET","${guid()},$,'Pset_EezyDraw',$,${refs(pids)}")
        entity("IFCRELDEFINESBYPROPERTIES","${guid()},$,$,$,(#$id),#$set")
    }
    for((level,ids)in contained)entity("IFCRELCONTAINEDINSPATIALSTRUCTURE","${guid()},$,$,$,${refs(ids)},#${levels.getValue(level)}")
    for((name,ids)in systems){val system=entity("IFCSYSTEM","${guid()},$,${str(name)},$,$");entity("IFCRELASSIGNSTOGROUP","${guid()},$,$,$,${refs(ids)},$,#$system")}
    for((id,host)in pendingVoids)objects[host]?.let {entity("IFCRELVOIDSELEMENT","${guid()},$,$,$,#$it,#$id")}
    w.appendLine("ENDSEC;\nEND-ISO-10303-21;");w.flush()
}
