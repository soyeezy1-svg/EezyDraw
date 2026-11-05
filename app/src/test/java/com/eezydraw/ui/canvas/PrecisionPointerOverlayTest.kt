package com.eezydraw.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.eezydraw.core.editing.PointPickContext
import com.eezydraw.core.editing.PrecisionPick
import com.eezydraw.core.geometry.*
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1024dp-h768dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PrecisionPointerOverlayTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var view:View
    private val viewport=Viewport2D(pixelsPerMetre=100.0)
    private val scene=DrawingScene(listOf(
        SceneSegment(Point3(.1,-1.0),Point3(.1,1.0),"red",null,0xFFFF0000),
        SceneSegment(Point3(-1.0,.15),Point3(1.0,.15),"blue",null,0xFF0000FF)),emptyList(),Bounds3(Point3(-1.0,-1.0),Point3(1.0,1.0)))
    private val document=ProjectDocument(Project(ProjectId("precision-overlay"),"Precision",0,0))
    private val context=PointPickContext(document,scene)

    @Test fun pointControlsRequirePlaceAndRemainAvailableAfterLift() {
        val actions=mutableListOf<String>()
        var pick by mutableStateOf<PrecisionPick?>(null)
        var dragging by mutableStateOf(true)
        compose.setContent {MaterialTheme {Box(Modifier.size(240.dp,400.dp)) {
            PrecisionPointerOverlay(pick,Point2(240.0,290.0),dragging,PrecisionPointerSettings(),viewport,OrbitCamera(),false,
                scene,null,.1,false,0.0,null,LengthUnit.MILLIMETRES,1,
                onPlace={actions+="place"},onCancel={actions+="cancel"},onNudge={x,y->actions+="$x,$y"},enabled=true,pickContext=context)
        }}}
        compose.onNodeWithTag("precision_place").assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp).assertIsNotEnabled()
        compose.runOnIdle {pick=PrecisionPick(Point3(),Point2(200.0,210.0))}
        compose.onNodeWithTag("precision_crosshair").assertExists()
        compose.onNodeWithTag("precision_loupe").assertExists()
        compose.runOnIdle {dragging=false}
        compose.onNodeWithTag("precision_loupe").assertDoesNotExist()
        compose.onNodeWithTag("precision_crosshair").assertExists()
        assertTrue(actions.isEmpty())
        listOf("up","down","left","right").forEach {compose.onNodeWithTag("precision_nudge_$it").assertWidthIsAtLeast(48.dp).performClick()}
        assertEquals(listOf("0,-1","0,1","-1,0","1,0"),actions)
        compose.onNodeWithTag("precision_place").assertIsEnabled().performClick()
        compose.onNodeWithTag("precision_cancel").assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(listOf("0,-1","0,1","-1,0","1,0","place","cancel"),actions)
    }

    @Test fun snapAndDistanceReadoutsUseTheSameCanonicalPickAndDisplayUnits() {
        compose.setContent {MaterialTheme {Box(Modifier.size(240.dp,400.dp)) {
            PrecisionPointerOverlay(PrecisionPick(Point3(.3,.4,1.2),Point2(200.0,200.0),SnapMode.ENDPOINT),null,false,
                PrecisionPointerSettings(),viewport,OrbitCamera(),false,scene,null,.1,false,0.0,Point3(),LengthUnit.MILLIMETRES,1,
                {},{},{_,_->},true,pickContext=context)
        }}}
        compose.onNodeWithTag("precision_snap").assertTextContains("endpoint",ignoreCase=true,substring=true)
        compose.onNodeWithTag("precision_distance").assertTextContains("1300.0 mm",substring=true)
        val node=compose.onNodeWithTag("precision_crosshair").fetchSemanticsNode()
        assertEquals(.3,node.config[PrecisionWorldX],0.0)
        assertEquals(200.0,node.config[PrecisionScreenX],0.0)
    }

    @Test fun loupeDrawsActualGeometryAtSelectedZoomAndStaysAwayFromTouchAndControls() {
        var settings by mutableStateOf(PrecisionPointerSettings(magnifierZoom=2.0))
        compose.setContent {MaterialTheme(colorScheme=lightColorScheme(primary=Color.Green)) {
            view=LocalView.current
            BoxWithConstraints(Modifier.size(240.dp,400.dp)) {
                val center=Point2(constraints.maxWidth/2.0,constraints.maxHeight/2.0)
                val finger=Point2(constraints.maxWidth*.75,constraints.maxHeight*.7)
                PrecisionPointerOverlay(PrecisionPick(Point3(),center),finger,true,settings,
                    viewport,OrbitCamera(),false,scene,null,.1,false,0.0,null,LengthUnit.METRES,2,
                    {},{},{_,_->},true,pickContext=context)
            }
        }}
        fun redCentroid():Double {
            val node=compose.onNodeWithTag("precision_loupe").fetchSemanticsNode()
            val bounds=node.boundsInRoot
            val image=compose.runOnIdle {
                val bitmap=Bitmap.createBitmap(bounds.width.toInt(),bounds.height.toInt(),Bitmap.Config.ARGB_8888)
                val canvas=Canvas(bitmap);canvas.translate(-bounds.left,-bounds.top);view.draw(canvas);bitmap.asImageBitmap()
            }.toPixelMap()
            val xs=mutableListOf<Int>()
            for(y in 0 until image.height)for(x in 0 until image.width) {
                val colour=image[x,y]
                if(colour.red>.8f && colour.green<.2f && colour.blue<.2f)xs+=x
            }
            assertTrue("The loupe must render the red drawing edge",xs.isNotEmpty())
            return xs.average()-image.width/2.0
        }
        val loupe=compose.onNodeWithTag("precision_loupe").fetchSemanticsNode().boundsInRoot
        val area=compose.onNodeWithTag("precision_pointer").fetchSemanticsNode().boundsInRoot
        assertFalse(loupe.contains(Offset(area.left+area.width*.75f,area.top+area.height*.7f)))
        val controls=compose.onNodeWithTag("precision_controls").fetchSemanticsNode().boundsInRoot
        assertFalse(loupe.overlaps(controls))
        val atTwo=redCentroid()
        compose.runOnIdle {settings=settings.copy(magnifierZoom=4.0)}
        val atFour=redCentroid()
        assertEquals("Magnification doubles the source-pixel distance from the crosshair",atTwo*2,atFour,2.0)
        compose.runOnIdle {settings=settings.copy(magnifier=false)}
        compose.onNodeWithTag("precision_loupe").assertDoesNotExist()
    }

    @Test fun darkLoupeKeepsBlackDrawingEdgesVisibleUsingTheNativeShaderContrast() {
        val blackScene=scene.copy(segments=listOf(SceneSegment(Point3(.15,-1.0),Point3(.15,1.0),"black",null,0xFF000000)))
        val blackContext=PointPickContext(document,blackScene)
        compose.setContent {MaterialTheme(colorScheme=darkColorScheme(primary=Color.Green)) {
            view=LocalView.current
            BoxWithConstraints(Modifier.size(240.dp,400.dp)) {
                val center=Point2(constraints.maxWidth/2.0,constraints.maxHeight/2.0)
                PrecisionPointerOverlay(PrecisionPick(Point3(),center),null,true,PrecisionPointerSettings(magnifierZoom=2.0),
                    viewport,OrbitCamera(),false,blackScene,null,.1,false,0.0,null,LengthUnit.METRES,2,
                    {},{},{_,_->},true,pickContext=blackContext)
            }
        }}
        val bounds=compose.onNodeWithTag("precision_loupe").fetchSemanticsNode().boundsInRoot
        val image=compose.runOnIdle {
            val bitmap=Bitmap.createBitmap(bounds.width.toInt(),bounds.height.toInt(),Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);canvas.translate(-bounds.left,-bounds.top);view.draw(canvas);bitmap.asImageBitmap()
        }.toPixelMap()
        // Source edge is 15 screen pixels right of the precision point, magnified to30px.
        val x=(image.width/2+30).coerceAtMost(image.width-2)
        val pixels=(image.height/4 until image.height*3/4).map {image[x,it]}
        assertTrue("Black geometry must be lightened in the dark loupe",pixels.any {it.red>.65f && it.green>.65f && it.blue>.65f})
        val colour=precisionLoupeColour(Color.Black,true)
        // Compose packs sRGB colours to 8 bits, as does the native framebuffer.
        assertEquals(.74f,colour.red,1f/255f)
        assertEquals(Color.Black,precisionLoupeColour(Color.Black,false))
    }

    @Test fun threeDimensionalLoupeProjectsAtTheCurrentCameraRatherThanPlanCoordinates() {
        val camera=OrbitCamera(target=Point3(3.0,4.0,5.0),orthographicHeight=4.0).preset(ViewPreset.FRONT)
        val point=Point3(3.0,4.0,5.2)
        val center=Point2(400.0,300.0)
        val projected=checkNotNull(precisionLoupePoint(point,center,viewport,camera,true,800.0,600.0,120.0,3.0))
        assertEquals(60.0,projected.x,1e-8)
        assertEquals(-30.0,projected.y,1e-8)
        val plan=checkNotNull(precisionLoupePoint(point,center,viewport,camera,false,800.0,600.0,120.0,3.0))
        assertNotEquals(plan,projected)
    }
}
