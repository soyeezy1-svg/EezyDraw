package com.eezydraw.core.geometry

import com.eezydraw.core.model.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class MaterialExportTest {
    @Test fun portableModelsRetainTheSameBaseColourAndMatteFinish() {
        val p=ProjectId("p");val l=LayerId("l");val e=Element(ElementId("wall"),p,l,ElementBody.Wall(Point2(0.0,0.0),Point2(3.0,0.0),.2,3.0))
        val doc=ProjectDocument(Project(p,"Colours",0,0),layers=listOf(Layer(l,p,"Base")),elements=listOf(e))
        assertEquals(MattePalette.WALL,ModelExport.meshes(doc,ModelExportOptions()).single().colourArgb)
        fun write(format:ModelFormat):String=ByteArrayOutputStream().also {ModelExport.write(doc,ModelExportOptions(format=format),it)}.toString(Charsets.UTF_8)
        val json=Json.parseToJsonElement(write(ModelFormat.GLTF)).jsonObject
        val material=json.getValue("materials").jsonArray.single().jsonObject.getValue("pbrMetallicRoughness").jsonObject
        val colour=material.getValue("baseColorFactor").jsonArray.map {it.jsonPrimitive.double}
        assertEquals(linearRgb(MattePalette.WALL)+1.0,colour)
        assertEquals(.9,material.getValue("roughnessFactor").jsonPrimitive.double,0.0)
        assertEquals(0.0,material.getValue("metallicFactor").jsonPrimitive.double,0.0)
        assertEquals(0,json.getValue("meshes").jsonArray.single().jsonObject.getValue("primitives").jsonArray.single().jsonObject.getValue("material").jsonPrimitive.int)
        val obj=write(ModelFormat.OBJ)
        assertTrue("mtllib model.mtl" in obj);assertTrue("usemtl material_0_Wall_matte" in obj)
        val mtl=ByteArrayOutputStream().also {ModelExport.writeObjMaterials(doc,ModelExportOptions(format=ModelFormat.OBJ),it)}.toString(Charsets.UTF_8)
        assertTrue("Kd ${rgb(MattePalette.WALL).joinToString(" ")}" in mtl)
        val ifc=write(ModelFormat.IFC)
        assertTrue("IFCCOLOURRGB" in ifc);assertTrue("IFCSURFACESTYLERENDERING" in ifc);assertTrue(".MATT." in ifc);assertTrue("IFCRELASSOCIATESMATERIAL" in ifc)
    }
}
