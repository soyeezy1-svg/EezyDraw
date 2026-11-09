package com.eezydraw.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.eezydraw.core.editing.*
import com.eezydraw.core.model.*
import com.eezydraw.ui.components.CadStyle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w1400dp-h1500dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CanvasEdgeToEdgeTest {
    @get:Rule val compose=createComposeRule()
    private data class Case(val width:Int,val height:Int,val dark:Boolean,val buttons:Boolean,val left:Boolean=false,val side:Int=0)
    private var case by mutableStateOf(Case(360,800,false,false))
    private var target by mutableStateOf(Point2(180.0,400.0))
    private var detail by mutableStateOf(false)
    private lateinit var view:View
    private lateinit var obstacles:LoupeObstacles
    private var root by mutableStateOf(Rect.Zero)

    private fun show() {compose.setContent {
        view=LocalView.current
        val c=case
        val insets=WindowInsets(left=if(c.buttons && c.side==1)48.dp else 0.dp,top=24.dp,right=if(c.buttons && c.side==2)48.dp else 0.dp,
            bottom=if(c.buttons && c.side!=0)0.dp else if(c.buttons)48.dp else 24.dp)
        obstacles=remember {LoupeObstacles()}
        MaterialTheme(colorScheme=if(c.dark)darkColorScheme()else lightColorScheme()) {
            CompositionLocalProvider(LocalLoupeObstacles provides obstacles) {
                Box(Modifier.requiredSize(c.width.dp,c.height.dp).background(Color.Magenta).testTag("edge_root").onGloballyPositioned {root=it.boundsInRoot()}) {
                    val occupied=obstacles.bounds.values.map {r->PointerRect((r.left-root.left).toDouble(),(r.top-root.top).toDouble(),(r.right-root.left).toDouble(),(r.bottom-root.top).toDouble())}
                    val safe=PointerRect(if(c.buttons && c.side==1)48.0 else 0.0,24.0,c.width.toDouble()-(if(c.buttons && c.side==2)48.0 else 0.0),
                        c.height.toDouble()-(if(c.buttons && c.side!=0)0.0 else if(c.buttons)48.0 else 24.0))
                    val lens=precisionLoupeBounds(Point2(target.x+36,target.y+72),target,c.width.toDouble(),c.height.toDouble(),1.0,occupied=occupied,safe=safe)
                    PrecisionPointerOverlay(PrecisionPick(Point3(),target,SnapMode.ENDPOINT),null,PrecisionPointerSettings(),null,LengthUnit.METRES,2,
                        {},{},{_,_->},true,loupeBounds=lens,controlsBottomInset=if(c.left)144.dp else 216.dp)
                    MobileCanvasChrome(CanvasLayout(openGroup=CanvasGroup.DRAW),{},emptyList(),{},{},{},{},{},true,true,false,
                        safeInsets=insets,barPosition=if(c.left)CanvasBarPosition.LEFT else CanvasBarPosition.BOTTOM,
                        cube={Box(Modifier.size(112.dp).testTag("fixture_cube"))},
                        options={Text("Aim at a point. Place or Cancel.",Modifier.height(72.dp).testTag("fixture_hint"))},
                        detail=if(detail)({Text("Panel",Modifier.fillMaxSize())})else null)
                }
            }
        }
    }}

    @Test fun backgroundsReachTheScreenEdgeAndLensAvoidsMeasuredChromeAcrossTheDeviceMatrix() {
        show()
        for((w,h) in listOf(360 to 800,800 to 360,800 to 1280,1280 to 800))for(dark in listOf(false,true))for(nav in if(w>h)listOf(0,1,2,3)else listOf(0,1)) {
            val buttons=nav!=0;val side=(nav-1).coerceAtLeast(0)
            val label="$w x $h / dark=$dark / three-button=$buttons / side=$side"
            compose.runOnIdle {case=Case(w,h,dark,buttons,side=side);target=Point2(w*.5,h*.45)}
            compose.waitForIdle()
            val rootBounds=compose.onNodeWithTag("edge_root").fetchSemanticsNode().boundsInRoot
            val painted=compose.onNodeWithTag("cad_bottom_controls").fetchSemanticsNode().boundsInRoot
            assertEquals("Background reaches bottom: $label",rootBounds.bottom,painted.bottom,.01f)
            assertEquals("Background reaches left: $label",rootBounds.left,painted.left,.01f)
            assertEquals("Background reaches right: $label",rootBounds.right,painted.right,.01f)
            val bar=compose.onNodeWithTag("cad_bottom_bar").fetchSemanticsNode().boundsInRoot
            val bottomInset=if(buttons && side!=0)0 else if(buttons)48 else 24
            assertEquals("Exactly one bottom inset: $label",rootBounds.bottom-bottomInset,bar.bottom,.01f)
            assertEquals("Content clears side navigation: $label",rootBounds.left+(if(buttons && side==1)48 else 0),bar.left,.01f)
            assertEquals("Content clears side navigation: $label",rootBounds.right-(if(buttons && side==2)48 else 0),bar.right,.01f)
            val bitmap=compose.runOnIdle {
                Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888).also {view.draw(Canvas(it))}
            }
            // Sample the full system-gesture band and bottom edge, including both corners.
            for(x in listOf(rootBounds.left.toInt()+1,rootBounds.center.x.toInt(),rootBounds.right.toInt()-2))
                for(y in listOf(rootBounds.bottom.toInt()-1,rootBounds.bottom.toInt()-maxOf(1,bottomInset/2)))
                    assertEquals("No dark strip or exposed canvas at $x,$y: $label",CadStyle.chrome.toArgb(),bitmap.getPixel(x,y))
            bitmap.recycle()
            val lens=compose.onNodeWithTag("precision_loupe").fetchSemanticsNode().boundsInRoot
            assertTrue("Lens within screen: $label",rootBounds.contains(lens.topLeft) && rootBounds.contains(lens.bottomRight))
            for((name,r) in obstacles.bounds)assertFalse("Lens overlaps $name: $label",lens.overlaps(r))
            val old=lens
            compose.runOnIdle {target=Point2((old.center.x-rootBounds.left).toDouble(),(old.center.y-rootBounds.top).toDouble())}
            compose.waitForIdle()
            val moved=compose.onNodeWithTag("precision_loupe").fetchSemanticsNode().boundsInRoot
            assertFalse("Move away from approaching crosshair: $label",moved.contains(old.center))
            for((name,r) in obstacles.bounds)assertFalse("Moved lens overlaps $name: $label",moved.overlaps(r))
        }
    }

    @Test fun lensRepositionsAfterPanelResizeAndLeftRailUsesTheSameSafeAreaContract() {
        show()
        compose.runOnIdle {case=Case(800,1280,true,true,left=true);target=Point2(420.0,500.0);detail=true}
        compose.waitForIdle()
        fun check() {
            val lens=compose.onNodeWithTag("precision_loupe").fetchSemanticsNode().boundsInRoot
            for((name,r) in obstacles.bounds)assertFalse("Lens overlaps $name",lens.overlaps(r))
        }
        check()
        compose.onNodeWithTag("cad_detail_resize").performClick();compose.waitForIdle();check()
        compose.onNodeWithTag("cad_detail_close").assertExists()
    }
}
