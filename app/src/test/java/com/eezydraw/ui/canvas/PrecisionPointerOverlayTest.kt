package com.eezydraw.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.eezydraw.core.editing.PointerRect
import com.eezydraw.core.editing.PrecisionPick
import com.eezydraw.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1024dp-h768dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PrecisionPointerOverlayTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var view:View
    private val bounds=PointerRect(8.0,8.0,128.0,128.0)

    @Test fun pointControlsRequirePlaceAndRemainAvailableAfterLift() {
        val actions=mutableListOf<String>()
        var pick by mutableStateOf<PrecisionPick?>(null)
        var dragging by mutableStateOf(true)
        compose.setContent {MaterialTheme {Box(Modifier.size(240.dp,400.dp)) {
            PrecisionPointerOverlay(pick,Point2(240.0,290.0),PrecisionPointerSettings(),null,LengthUnit.MILLIMETRES,1,
                onPlace={actions+="place"},onCancel={actions+="cancel"},onNudge={x,y->actions+="$x,$y"},enabled=true,
                loupeBounds=if(dragging)bounds else null)
        }}}
        compose.onNodeWithTag("precision_place").assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp).assertIsNotEnabled()
        compose.runOnIdle {pick=PrecisionPick(Point3(),Point2(200.0,210.0))}
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
            PrecisionPointerOverlay(PrecisionPick(Point3(.3,.4,1.2),Point2(200.0,200.0),SnapMode.ENDPOINT),null,
                PrecisionPointerSettings(),Point3(),LengthUnit.MILLIMETRES,1,{},{},{_,_->},true,loupeBounds=bounds)
        }}}
        compose.onNodeWithTag("precision_snap").assertTextContains("endpoint",ignoreCase=true,substring=true)
        compose.onNodeWithTag("precision_distance").assertTextContains("1300.0 mm",substring=true)
        val node=compose.onNodeWithTag("precision_crosshair").fetchSemanticsNode()
        assertEquals(.3,node.config[PrecisionWorldX],0.0)
        assertEquals(200.0,node.config[PrecisionScreenX],0.0)
        val lens=compose.onNodeWithTag("precision_loupe").fetchSemanticsNode()
        assertEquals(node.config[PrecisionScreenX],lens.config[PrecisionScreenX],0.0)
        assertEquals(node.config[PrecisionScreenY],lens.config[PrecisionScreenY],0.0)
    }

    @Test fun framebufferCropScalesAroundOffCentreCrosshairAndFollowsItInBothThemes() {
        var zoom by mutableStateOf(2.0)
        var at by mutableStateOf(Point2(210.25,245.75))
        var dark by mutableStateOf(false)
        var fixedCapture by mutableStateOf<CapturedLoupe?>(null)
        var lastCapture:CapturedLoupe?=null
        compose.setContent {MaterialTheme(colorScheme=if(dark)darkColorScheme()else lightColorScheme()) {
            view=LocalView.current
            val capture=fixedCapture ?: captureLoupe(LoupeSource(at,120.0,zoom),400,400) {left,bottom,w,h,buffer->
                // Native framebuffer fixture: a red edge at x=220, plus different top/bottom colours.
                for(y in 0 until h)for(x in 0 until w) {
                    val red=left+x in 219..221
                    val upper=400-1-(bottom+y)<at.y
                    buffer.put((if(red)255 else if(dark)14 else 247).toByte())
                        .put((if(red)0 else if(upper)80 else 130).toByte())
                        .put((if(red)0 else if(dark)26 else 252).toByte()).put(255.toByte())
                }
            }
            SideEffect {lastCapture=capture}
            Box(Modifier.size(400.dp,500.dp)) {
                PrecisionPointerOverlay(PrecisionPick(Point3(),at),null,PrecisionPointerSettings(magnifierZoom=zoom),
                    null,LengthUnit.METRES,2,{},{},{_,_->},true,loupeBounds=bounds,capture=capture)
            }
        }}
        fun redCentroid():Double {
            val rect=compose.onNodeWithTag("precision_loupe").fetchSemanticsNode().boundsInRoot
            val pixels=compose.runOnIdle {
                val bitmap=Bitmap.createBitmap(rect.width.toInt(),rect.height.toInt(),Bitmap.Config.ARGB_8888)
                val canvas=Canvas(bitmap);canvas.translate(-rect.left,-rect.top);view.draw(canvas);bitmap.asImageBitmap()
            }.toPixelMap()
            val xs=mutableListOf<Int>()
            for(y in pixels.height/4 until pixels.height*3/4)for(x in 0 until pixels.width) {
                val c=pixels[x,y];if(c.red>.8f && c.green<.2f && c.blue<.2f)xs+=x
            }
            assertTrue("Show the source edge under the crosshair",xs.isNotEmpty())
            // Asymmetric source rows detect a vertically mirrored framebuffer.
            assertTrue(pixels[pixels.width/4,pixels.height/4].green<pixels[pixels.width/4,pixels.height*3/4].green)
            return xs.average()-pixels.width/2.0
        }
        val atTwo=redCentroid()
        compose.runOnIdle {zoom=4.0;dark=true}
        val atFour=redCentroid()
        assertEquals(atTwo*2,atFour,2.0)
        compose.runOnIdle {at=Point2(at.x+5,at.y)}
        assertEquals("Source follows the crosshair, not the finger or camera centre",atFour-20,redCentroid(),2.0)
        compose.runOnIdle {fixedCapture=lastCapture;at=Point2(at.x+5,at.y)}
        assertEquals("Re-centre existing source pixels while the next GL crop is in flight",atFour-40,redCentroid(),2.0)
    }
}
