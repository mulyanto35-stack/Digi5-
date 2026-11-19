package dw.up.digi.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.Image
import dw.up.digi.detection.CellClassifier
import dw.up.digi.detection.CalibrationValidator
import dw.up.digi.detection.GridDetector
import dw.up.digi.detection.PreviewClassifier
import dw.up.digi.strategy.AutoMoveController
import java.io.File
import java.io.FileOutputStream

object CaptureFrameAnalyzer {
    /** Candidate threshold. [GridDetector] already checks equidistance, aspect ratio, coverage
     *  and a contrast ratio of >=5 against the background. Confidence only measures how clear the
     *  lines are, and that varies strongly with the game's scene graphics (bright scene ~0.84,
     *  dark night scene ~0.74 with identical, correct geometry). A high threshold would therefore
     *  reject correct detections. The real safeguard is [STABLE_FRAMES]. */
    private const val CANDIDATE_MIN = .55
    /** This many consecutive analyses must yield the same bounds before calibration is accepted. */
    private const val STABLE_FRAMES = 3
    /** Allowed deviation per edge between two frames, in pixels. */
    private const val BOUNDS_TOLERANCE = 4
    /** Minimum interval between two diagnostic PNGs, in milliseconds. */
    private const val DIAGNOSTIC_INTERVAL = 300_000L
    @Volatile private var lastDiagnostic = 0L
    /** Reused between frames (only touched from the analysis thread): avoids ~14 MB of allocations per
     *  analysis at 720x1600 and reduces GC pressure. Released when capture stops. */
    private var paddedBitmap: Bitmap? = null
    private var pixelBuffer: IntArray? = null
    fun release() { paddedBitmap = null; pixelBuffer = null } // no recycle(): the analysis thread may still be using the bitmap; the GC frees it

    @Volatile private var calibrated: dw.up.digi.detection.GridDetection? = null
    @Volatile private var pending: dw.up.digi.detection.GridDetection? = null
    @Volatile private var pendingCount = 0
    /** While no calibration exists, [GridDetector] is still searching for edges and the overlay must
     *  not be in the image. Afterwards the bounds are fixed and the overlay may stay visible. */
    val isCalibrated: Boolean get() = calibrated != null
    fun resetCalibration(){ calibrated=null; pending=null; pendingCount=0; dw.up.digi.strategy.AutoMoveController.reset(); dw.up.digi.accessibility.DigiWorldAccessibilityService.instance?.clearCalibrationOverlay() }
    data class Result(val detected:Boolean,val confidence:Double,val output:String)

    private fun stable(candidate: dw.up.digi.detection.GridDetection): Boolean {
        val previous = pending
        val same = previous != null &&
            Math.abs(previous.bounds.left - candidate.bounds.left) <= BOUNDS_TOLERANCE &&
            Math.abs(previous.bounds.top - candidate.bounds.top) <= BOUNDS_TOLERANCE &&
            Math.abs(previous.bounds.right - candidate.bounds.right) <= BOUNDS_TOLERANCE &&
            Math.abs(previous.bounds.bottom - candidate.bounds.bottom) <= BOUNDS_TOLERANCE
        if (same) pendingCount++ else { pending = candidate; pendingCount = 1 }
        return pendingCount >= STABLE_FRAMES
    }

    fun analyze(context:Context,image:Image,width:Int,height:Int):Result?=runCatching{
        val plane=image.planes.first()
        val paddedWidth=plane.rowStride/plane.pixelStride
        var padded=paddedBitmap
        if(padded==null||padded.isRecycled||padded.width!=paddedWidth||padded.height!=height){
            padded?.recycle()
            padded=Bitmap.createBitmap(paddedWidth,height,Bitmap.Config.ARGB_8888)
            paddedBitmap=padded
        }
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        var pixels=pixelBuffer
        if(pixels==null||pixels.size!=width*height){pixels=IntArray(width*height);pixelBuffer=pixels}
        padded.getPixels(pixels,0,width,0,0,width,height)
        val diagnosticDue=android.os.SystemClock.elapsedRealtime()-lastDiagnostic>=DIAGNOSTIC_INTERVAL
        var detection=calibrated ?: GridDetector.detect(width,height,pixels)?.takeIf { it.confidence>=CANDIDATE_MIN }
        var cells=detection?.let { CellClassifier.classify(width,height,pixels,it.bounds) }
        if(calibrated==null&&detection!=null){
            val geometryStable=stable(detection)
            if(cells!=null&&CalibrationValidator.plausible(cells)&&geometryStable){
                val locked=detection.copy(confidence=maxOf(detection.confidence,.90),reason="Raster ueber $STABLE_FRAMES Frames stabil")
                calibrated=locked
                detection=locked
                android.util.Log.i("DigiWorldCapture","kalibriert bounds=${locked.bounds} rohkonfidenz=${locked.confidence}")
            } else { detection=null;cells=null }
        }
        val preview=detection?.let { PreviewClassifier.classify(width,height,pixels,it.bounds) } ?: emptyMap()
        val directory by lazy{File(context.getExternalFilesDir(null) ?: context.filesDir,"diagnostics").apply{mkdirs()}}
        var gameVisible=false
        if(detection!=null && cells!=null){
            val player=cells.maxByOrNull { it.value.player }
            val playerCell=player?.key
            val items=cells.filter { (cell,score) -> cell!=playerCell && score.item>.06 }.keys.sortedWith(compareBy({it.row},{it.col}))
            val obstacles=cells.filter { (cell,score) -> cell!=playerCell && score.obstacle() }.keys.sortedWith(compareBy({it.row},{it.col}))
            val detectedDashButton=dw.up.digi.detection.DashButtonLocator.locate(width,height,pixels,detection.bounds)
            val dashButton=detectedDashButton ?: if(dw.up.digi.strategy.AutomationState.dwsNavigationSettings.dashSpamUntilZero)
                dw.up.digi.detection.DashButtonLocator.relativeFallback(width,height,detection.bounds)
            else null
            val hud=dw.up.digi.detection.HudCounterReader.read(width,height,pixels,detection.bounds)
            // Aufgezeichnet sind bisher nur die Ziffern 1 und 2. Unbekannte Formen werden hier
            // ausgegeben, damit die fehlenden Vorlagen aus echten Spielstaenden ergaenzt werden koennen.
            if(hud.unknown.isNotEmpty())android.util.Log.i("DigiWorldHud","unbekannte Ziffer "+hud.unknown)
            if(diagnosticDue){
                val summary="confidence="+detection.confidence+"\nplayer="+player?.key+" score="+player?.value?.player+"\nitems="+items+"\nobstacles="+obstacles+"\npreview="+preview+"\ndash="+dashButton+"\nkrallen="+hud.claws+" dashvorrat="+hud.dash+"\n"
                File(directory,"latest_detection.txt").writeText(summary)
                android.util.Log.i("DigiWorldDetection",summary.replace("\n","; "))
            }
            gameVisible=CalibrationValidator.plausible(cells)
            if(gameVisible) AutoMoveController.onAnalysis(detection.confidence,detection.bounds,cells,preview,dashButton,hud)
            else AutoMoveController.onAnalysis(0.0,detection.bounds,emptyMap(),emptyMap(),null,hud)
        }
        // Der PNG-Export kostet bei 1080x2400 mehr als die gesamte Analyse. Nach der Kalibrierung
        // laeuft diese mit rund 3 Hz, deshalb wird der Diagnoseframe nur noch gedrosselt geschrieben.
        var outputPath=""
        if(diagnosticDue){
            lastDiagnostic=android.os.SystemClock.elapsedRealtime()
            val output=File(directory,"dynamic_grid.png")
            outputPath=output.absolutePath
            val diagnostic=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            diagnostic.setPixels(pixels,0,width,0,0,width,height)
            val canvas=Canvas(diagnostic)
            val paint=Paint().apply{color=Color.GREEN;style=Paint.Style.STROKE;strokeWidth=(width/180f).coerceAtLeast(3f)}
            detection?.bounds?.let{b->
                canvas.drawRect(b.left.toFloat(),b.top.toFloat(),b.right.toFloat(),b.bottom.toFloat(),paint)
                for(i in 1..4){
                    val x=b.left+(b.right-b.left)*i/5f
                    val y=b.top+(b.bottom-b.top)*i/5f
                    canvas.drawLine(x,b.top.toFloat(),x,b.bottom.toFloat(),paint)
                    canvas.drawLine(b.left.toFloat(),y,b.right.toFloat(),y,paint)
                }
            }
            FileOutputStream(output).use{diagnostic.compress(Bitmap.CompressFormat.PNG,90,it)}
            diagnostic.recycle()
        }
        Result(gameVisible,detection?.confidence?:0.0,outputPath)
    }.onFailure{ android.util.Log.e("DigiWorldCapture","analyze failed",it) }.getOrNull()
}
