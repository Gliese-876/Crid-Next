package cn.crid.next.ui

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.crid.next.MainActivity
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import java.io.File

class AppMotionInstrumentedTest {
    private val durationScale=object:MotionDurationScale {
        var value=1f
        override val scaleFactor:Float get()=value
    }
    @get:Rule val compose=createAndroidComposeRule<MainActivity>(effectContext=durationScale)
    @After fun restoreClock() {durationScale.value=1f;compose.mainClock.autoAdvance=true}

    @Test fun twoTriggerPositionsHaveMatchingOutwardAndReturnPaths() {
        val fixture=Fixture()
        install(fixture)
        listOf("upper_trigger","lower_trigger").forEach { trigger ->
            val source=compose.onNodeWithTag(trigger).fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance=false
            compose.onNodeWithTag(trigger).performClick()
            compose.mainClock.advanceTimeBy(112)
            val entering=modalBounds()
            compose.mainClock.advanceTimeBy(1_200)
            val settled=modalBounds()
            compose.onNodeWithTag("close_modal").performClick()
            compose.mainClock.advanceTimeBy(96)
            val leaving=modalBounds()
            val entryVector=entering.center-settled.center
            val exitVector=leaving.center-settled.center
            assertTrue("Entry must travel from the selected control",(entering.center-source.center).getDistance()<(settled.center-source.center).getDistance())
            assertTrue("Exit must return toward that same control",(leaving.center-source.center).getDistance()<(settled.center-source.center).getDistance())
            assertTrue("The two paths must point in the same direction",entryVector.x*exitVector.x+entryVector.y*exitVector.y>0f)
            val normalizedCross=abs(entryVector.x*exitVector.y-entryVector.y*exitVector.x)/(entryVector.getDistance()*exitVector.getDistance())
            assertTrue("Entry and return follow the same line",normalizedCross<.12f)
            assertTrue(entering.width<settled.width)
            assertTrue(leaving.width<settled.width)
            compose.mainClock.advanceTimeBy(1_200)
            compose.onNodeWithTag("motion_modal").assertDoesNotExist()
            compose.mainClock.autoAdvance=true
            compose.waitForIdle()
        }
        assertEquals(2,fixture.dismissals)
    }

    @Test fun fullScreenEntryKeepsItsAspectRatioAndStopsAtTheWindowBoundary() {
        val fixture=Fixture(fullScreen=true)
        install(fixture)
        val sourceNode=compose.onNodeWithTag("upper_trigger").fetchSemanticsNode()
        val source=sourceNode.boundsInRoot.translate(sourceNode.positionOnScreen-sourceNode.positionInRoot)
        compose.mainClock.autoAdvance=false
        compose.onNodeWithTag("upper_trigger").performClick()
        compose.mainClock.advanceTimeBy(48)
        assertFalse("Full-screen motion must not add a dark veil over its launching page",dialogDimsBackground())
        val frames=mutableListOf<Rect>()
        repeat(90) {
            compose.mainClock.advanceTimeByFrame()
            frames+=unclippedModalScreenBounds()
            assertFalse("The full-screen window must stay undimmed throughout entry",dialogDimsBackground())
        }
        val settled=frames.last()
        val restScale=minOf(source.width/settled.width,source.height/settled.height).coerceIn(.08f,.8f)
        assertTrue("A full-screen surface must visibly travel out from its source",frames.first().width<settled.width*.9f)
        frames.forEach { frame ->
            val scale=frame.width/settled.width
            assertEquals("The growing page keeps its content proportions",scale,frame.height/settled.height,.001f)
            // Use transformed coordinates without clipping; boundsInRoot hides window overshoot.
            assertTrue("The full-screen surface must not grow beyond its final width",frame.width<=settled.width+.5f)
            assertTrue("The full-screen surface must not grow beyond its final height",frame.height<=settled.height+.5f)
            val progress=(scale-restScale)/(1f-restScale)
            val expected=source.center+(settled.center-source.center)*progress
            assertTrue("Scale and translation share the same source-to-window progress",
                (frame.center-expected).getDistance()<2f)
        }
        frames.zipWithNext().forEach { (before,after) ->
            assertTrue("Entry approaches the edge without a clipped bounce",after.width>=before.width-.5f)
        }
    }

    @Test fun closingDuringFullScreenEntryRetainsPositionAndVelocity() {
        val fixture=Fixture(fullScreen=true)
        install(fixture)
        compose.mainClock.autoAdvance=false
        compose.onNodeWithTag("upper_trigger").performClick()
        // Observe an actual in-flight frame rather than assuming Dialog layout finishes at a fixed time.
        var current:Rect?=null
        repeat(30) {
            if(current==null) {
                compose.mainClock.advanceTimeByFrame()
                val node=compose.onNodeWithTag("motion_modal").fetchSemanticsNode()
                val frame=unclippedModalScreenBounds()
                if(frame.width/node.size.width in .25f.. .55f)current=frame
            }
        }
        val beforePrevious=requireNotNull(current) {"No early full-screen entry frame was observed"}
        compose.mainClock.advanceTimeByFrame()
        val before=unclippedModalScreenBounds()
        val openingStep=before.width-beforePrevious.width
        assertTrue("The interrupted spring must still have outward velocity",openingStep>0f)
        val close=requireNotNull(compose.onNodeWithTag("close_modal").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnUiThread {close()}
        val atRequest=unclippedModalScreenBounds()
        assertEquals("Requesting close must not snap the current width",before.width,atRequest.width,.5f)
        assertTrue("Requesting close must not teleport the page",(before.center-atRequest.center).getDistance()<.5f)
        // Retargeting Animatable preserves its current velocity, then the native exit spring brakes it.
        // The first frame may establish its animation start time; sample two frames to see motion.
        compose.mainClock.advanceTimeByFrame()
        val first=unclippedModalScreenBounds()
        compose.mainClock.advanceTimeByFrame()
        val second=unclippedModalScreenBounds()
        assertTrue("Changing target must brake outward motion, not instantly reverse it",
            maxOf(first.width,second.width)>before.width+.5f)
        assertTrue("Retargeting must not introduce a larger expansion step",
            maxOf(first.width,second.width)-before.width<=openingStep*2f+1f)
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithTag("motion_modal").assertDoesNotExist()
        assertEquals(1,fixture.dismissals)
    }

    @Test fun aDetachedSourceOrChangedWindowFallsBackToCenteredRetraction() {
        val fixture=Fixture()
        install(fixture)
        listOf(false,true).forEach { changedWindow ->
            compose.onNodeWithTag("upper_trigger").performClick()
            val settled=modalBounds()
            compose.runOnUiThread {
                if(changedWindow)fixture.changedWindow=true else fixture.sourceVisible=false
            }
            compose.waitForIdle()
            compose.mainClock.autoAdvance=false
            compose.onNodeWithTag("close_modal").performClick()
            compose.mainClock.advanceTimeBy(112)
            val leaving=modalBounds()
            val reason=if(changedWindow)"Changed window" else "Detached source"
            assertEquals("$reason fallback does not invent a horizontal origin",settled.center.x,leaving.center.x,2f)
            assertEquals("$reason fallback does not always slide downward",settled.center.y,leaving.center.y,2f)
            assertTrue("Fallback still visibly retracts",leaving.width<settled.width)
            compose.mainClock.advanceTimeBy(1_200)
            compose.onNodeWithTag("motion_modal").assertDoesNotExist()
            compose.mainClock.autoAdvance=true
            compose.runOnUiThread {fixture.sourceVisible=true;fixture.changedWindow=false}
            compose.waitForIdle()
        }
    }

    @Test fun predictiveBackCancellationRestoresTheSurfaceAndCompletionReturnsToOrigin() {
        val fixture=Fixture()
        install(fixture)
        val source=compose.onNodeWithTag("lower_trigger").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("lower_trigger").performClick()
        val settled=modalBounds()
        compose.runOnUiThread {
            val dispatcher=dialogDispatcher()
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f,200f,0f,BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(30f,200f,.65f,BackEventCompat.EDGE_LEFT))
        }
        val retreating=modalBounds()
        assertTrue("Predictive back follows the captured origin",(retreating.center-source.center).getDistance()<(settled.center-source.center).getDistance())
        assertTrue(retreating.width<settled.width)
        compose.runOnUiThread {dialogDispatcher().dispatchOnBackCancelled()}
        compose.waitForIdle()
        val restored=modalBounds()
        assertEquals(settled.left,restored.left,1f)
        assertEquals(settled.top,restored.top,1f)
        assertEquals(settled.width,restored.width,1f)
        assertEquals(0,fixture.dismissals)
        compose.runOnUiThread {
            val dispatcher=dialogDispatcher()
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f,200f,0f,BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(30f,200f,.65f,BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("motion_modal").assertDoesNotExist()
        assertEquals(1,fixture.dismissals)
    }

    @Test fun outsideTapDismissesContentDoesNotPassThroughAndSaveRunsOnce() {
        val fixture=Fixture()
        install(fixture)
        compose.onNodeWithTag("upper_trigger").performClick()
        compose.onNodeWithTag("modal_body").performTouchInput {click(center)}
        compose.onNodeWithTag("motion_modal").assertIsDisplayed()
        assertEquals(0,fixture.backgroundClicks)
        assertEquals(0,fixture.dismissals)
        compose.onNode(isDialog()).performTouchInput {click(Offset(8f,8f))}
        compose.onNodeWithTag("motion_modal").assertDoesNotExist()
        assertEquals(1,fixture.dismissals)

        compose.onNodeWithTag("lower_trigger").performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance=false
        val save=requireNotNull(compose.onNodeWithTag("save_modal").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        // Two callbacks in one UI event test the duplicate-action guard without advancing input
        // event time or trying to hit a control that has already moved during the exit animation.
        compose.runOnUiThread {save();save()}
        assertEquals("Saving waits for the return animation",0,fixture.saves)
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithTag("motion_modal").assertDoesNotExist()
        assertEquals(1,fixture.saves)
        compose.mainClock.autoAdvance=true
    }

    @Test fun zeroAnimatorScaleStillCompletesOpenAndCloseCallbacks() {
        try {
            // The test rule supplies its own Recomposer, bypassing the WindowRecomposer that reads
            // Android's setting. Set the same public coroutine-context element explicitly here.
            durationScale.value=0f
            val fixture=Fixture()
            install(fixture)
            compose.mainClock.autoAdvance=false
            compose.onNodeWithTag("upper_trigger").performClick()
            repeat(4) {compose.mainClock.advanceTimeByFrame()}
            val opened=compose.onNodeWithTag("motion_modal").fetchSemanticsNode()
            assertEquals("Zero scale opens at full size",opened.size.width.toFloat(),opened.boundsInRoot.width,1f)
            compose.onNodeWithTag("save_modal").performClick()
            repeat(4) {compose.mainClock.advanceTimeByFrame()}
            compose.onNodeWithTag("motion_modal").assertDoesNotExist()
            assertEquals(1,fixture.saves)
        } finally {
            compose.mainClock.autoAdvance=true
            durationScale.value=1f
        }
    }

    @Test fun fullScreenCornersRemainVisibleDuringEnterExitAndPredictiveBack() {
        val fixture=Fixture(fullScreen=true,pixelProbe=true)
        install(fixture)
        compose.mainClock.autoAdvance=false
        compose.onNodeWithTag("upper_trigger").performClick()
        advanceToCornerFrame(entering=true)
        assertRoundedPixels("Full-screen entry")
        compose.mainClock.advanceTimeBy(1_200)
        assertSquarePixels("Settled full screen")
        compose.runOnUiThread {
            val dispatcher=dialogDispatcher()
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f,200f,0f,BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(40f,200f,.7f,BackEventCompat.EDGE_LEFT))
        }
        compose.mainClock.advanceTimeByFrame()
        assertRoundedPixels("Predictive return")
        compose.runOnUiThread {dialogDispatcher().dispatchOnBackCancelled()}
        compose.mainClock.advanceTimeBy(1_200)
        assertSquarePixels("Cancelled predictive return")
        val close=requireNotNull(compose.onNodeWithTag("close_modal").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnUiThread {close()}
        advanceToCornerFrame(entering=false)
        assertRoundedPixels("Full-screen exit")
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithTag("motion_modal").assertDoesNotExist()
        assertEquals(1,fixture.dismissals)
    }

    @Test fun floatingDialogsRetainSubstantialCornerCutoutsThroughoutScaling() {
        val fixture=Fixture(pixelProbe=true)
        install(fixture)
        compose.mainClock.autoAdvance=false
        compose.onNodeWithTag("lower_trigger").performClick()
        advanceToCornerFrame(entering=true)
        assertTrue("Floating dialogs retain the platform's background dim",dialogDimsBackground())
        assertRoundedPixels("Floating entry")
        compose.mainClock.advanceTimeBy(1_200)
        assertRoundedPixels("Settled floating dialog")
        val close=requireNotNull(compose.onNodeWithTag("close_modal").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnUiThread {close()}
        advanceToCornerFrame(entering=false)
        assertRoundedPixels("Floating exit")
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithTag("motion_modal").assertDoesNotExist()
    }

    private fun assertRoundedPixels(phase:String) {
        // captureToImage already waits for ViewTreeObserver's frame-commit callback before
        // PixelCopy. Keep the virtual clock frozen so the committed frame matches these bounds.
        val image=compose.onNodeWithTag("motion_modal").captureToImage()
        val pixels=image.toPixelMap()
        fun filled(x:Int,y:Int)=isProbeColor(pixels[x,y])
        try {
            assertTrue("$phase must show an opaque surface; top center=${pixels[pixels.width/2,3]}",filled(pixels.width/2,3))
            listOf(3 to 3,(pixels.width-4) to 3,3 to (pixels.height-4),(pixels.width-4) to (pixels.height-4)).forEach { (x,y) ->
                assertFalse("$phase must clip the real content at all four corners",filled(x,y))
            }
            val firstFilled=(0 until pixels.width/2).firstOrNull {filled(it,3)} ?: pixels.width/2
            // Require a visible cutout measured in screen pixels, not merely a non-zero logical
            // shape whose radius shrinks to a few pixels with the surface.
            val minimumCutout=minOf(9f*compose.activity.resources.displayMetrics.density,pixels.width*.1f,pixels.height*.1f)
            assertTrue("$phase corner cutout is too small: $firstFilled px",firstFilled>=minimumCutout)
        } catch(failure:AssertionError) {
            recordCornerFailure(phase,image)
            throw failure
        }
    }

    private fun assertSquarePixels(phase:String) {
        val pixels=compose.onNodeWithTag("motion_modal").captureToImage().toPixelMap()
        listOf(3 to 3,(pixels.width-4) to 3,3 to (pixels.height-4),(pixels.width-4) to (pixels.height-4)).forEach { (x,y) ->
            assertTrue("$phase must fill the screen corners",isProbeColor(pixels[x,y]))
        }
    }

    private fun isProbeColor(color:Color)=color.alpha>.9f && color.red<.25f && color.green in 0.2f..0.5f && color.blue>.7f

    private fun advanceToCornerFrame(entering:Boolean) {
        // Window creation/layout can consume a different number of frames on each device. Sample
        // the actual middle of the spatial animation, after its short initial opacity ramp.
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            val node=compose.onNodeWithTag("motion_modal").fetchSemanticsNode()
            val scale=node.boundsInRoot.width/node.size.width.toFloat()
            if(if(entering)scale>=.6f else scale<=.75f) {
                assertTrue("Corner sample must still be spatially in flight, scale=$scale",scale in .5f.. .9f)
                return
            }
        }
        fail("Could not observe a middle frame of the modal animation")
    }

    private fun recordCornerFailure(phase:String,image:ImageBitmap) {
        val node=compose.onNodeWithTag("motion_modal").fetchSemanticsNode()
        val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"motion-corner-diagnostics").apply {mkdirs()}
        val name=phase.lowercase().replace(' ','-')
        File(directory,"$name.png").outputStream().use {image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        val pixels=image.toPixelMap()
        File(directory,"$name.txt").writeText("bounds=${node.boundsInRoot}\nlayout=${node.size}\nimage=${image.width}x${image.height}\ntopCenter=${pixels[pixels.width/2,3]}\ncenter=${pixels[pixels.width/2,pixels.height/2]}\ncorner=${pixels[3,3]}\n")
        val windowImage=compose.onNode(isDialog()).captureToImage()
        File(directory,"$name-window.png").outputStream().use {windowImage.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }

    private fun install(fixture:Fixture) {
        compose.runOnUiThread {compose.activity.setContent {MaterialTheme {FixtureContent(fixture)}}}
        compose.waitForIdle()
    }
    private fun modalBounds():Rect=compose.onNodeWithTag("motion_modal").fetchSemanticsNode().boundsInRoot
    private fun unclippedModalScreenBounds():Rect {
        val coordinates=compose.onNodeWithTag("motion_modal").fetchSemanticsNode().layoutInfo.coordinates
        return Rect(coordinates.localToScreen(Offset.Zero),coordinates.localToScreen(
            Offset(coordinates.size.width.toFloat(),coordinates.size.height.toFloat())))
    }
    private fun dialogDispatcher()=(requireNotNull(WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::dialogWindow)).callback as OnBackPressedDispatcherOwner).onBackPressedDispatcher
    private fun dialogDimsBackground():Boolean {
        // Dialog.show() attaches its native host before the content's composition effects run.
        // Synchronize with the actual modal node and inspect its own window, never an arbitrary
        // attached DialogLayout returned before that content has been installed.
        val node=compose.onNodeWithTag("motion_modal").fetchSemanticsNode()
        val view=(requireNotNull(node.root) as ViewRootForTest).view
        var dimmed=false
        compose.runOnUiThread {
            val window=generateSequence(view.parent) {it.parent}
                .filterIsInstance<DialogWindowProvider>().first().window
            dimmed=window.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
        }
        return dimmed
    }
    private fun dialogWindow(view:View):Window? {
        if(view is DialogWindowProvider)return view.window
        if(view is ViewGroup)for(index in 0 until view.childCount)dialogWindow(view.getChildAt(index))?.let {return it}
        return null
    }

    private class Fixture(val fullScreen:Boolean=false,val pixelProbe:Boolean=false) {
        var open by mutableStateOf(false)
        var sourceVisible by mutableStateOf(true)
        var changedWindow by mutableStateOf(false)
        var activeOrigin by mutableStateOf<ModalOrigin?>(null)
        var dismissals=0
        var saves=0
        var backgroundClicks=0
    }

    @Composable private fun FixtureContent(fixture:Fixture) {
        val upper=rememberModalOrigin();val lower=rememberModalOrigin()
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Button(onClick={fixture.backgroundClicks++},modifier=Modifier.align(Alignment.Center)) {Text("Behind modal")}
            if(fixture.sourceVisible) {
                Button(onClick={upper.capture();fixture.activeOrigin=upper;fixture.open=true},
                    modifier=Modifier.align(Alignment.TopStart).padding(32.dp).modalOrigin(upper).testTag("upper_trigger")) {Text("Upper")}
                Button(onClick={lower.capture();fixture.activeOrigin=lower;fixture.open=true},
                    modifier=Modifier.align(Alignment.BottomEnd).padding(32.dp).modalOrigin(lower).testTag("lower_trigger")) {Text("Lower")}
            }
        }
        if(fixture.open) {
            val configuration=LocalConfiguration.current
            val targetConfiguration=if(fixture.changedWindow)Configuration(configuration).apply {screenWidthDp+=100}else configuration
            CompositionLocalProvider(LocalConfiguration provides targetConfiguration) {
                AnimatedAppDialog(onDismissRequest={fixture.dismissals++;fixture.open=false},origin=fixture.activeOrigin,fullScreen=fixture.fullScreen,
                    properties=DialogProperties(usePlatformDefaultWidth=false)) {motion ->
                    Surface((if(fixture.fullScreen)Modifier.fillMaxSize()else Modifier.size(240.dp,200.dp)).testTag("motion_modal"),
                        color=if(fixture.pixelProbe)Color(0xFF2459D9)else MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(16.dp).then(if(fixture.fullScreen)Modifier.safeDrawingPadding()else Modifier),horizontalAlignment=Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(64.dp).testTag("modal_body"),contentAlignment=Alignment.Center) {Text("Details")}
                            Button(onClick=motion.dismiss,modifier=Modifier.testTag("close_modal")) {Text("Close")}
                            Button(onClick={motion.finish {fixture.saves++;fixture.open=false}},modifier=Modifier.testTag("save_modal")) {Text("Save")}
                        }
                    }
                }
            }
        }
    }
}
