package cn.crid.next.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

/** Decorative glyphs inherit the active theme; their parent action supplies the accessible label. */
@Composable
internal fun AppGlyph(id: String, tint: Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val factor = size.minDimension / 24f
        withTransform({ scale(factor, factor, Offset.Zero) }) {
            val stroke = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x: Float, y: Float, ex: Float, ey: Float) = drawLine(tint, Offset(x,y), Offset(ex,ey), strokeWidth=1.8f, cap=StrokeCap.Round)
            fun path(vararg coordinates: Float, closed: Boolean = false) {
                val shape = Path().apply {
                    moveTo(coordinates[0], coordinates[1])
                    var i=2
                    while(i<coordinates.size) { lineTo(coordinates[i],coordinates[i+1]); i+=2 }
                    if(closed)close()
                }
                drawPath(shape,tint,style=stroke)
            }
            fun roundRect(x:Float,y:Float,w:Float,h:Float,r:Float=2f) = drawRoundRect(tint,Offset(x,y),Size(w,h),CornerRadius(r),style=stroke)
            when(id) {
                "add" -> { line(12f,4f,12f,20f); line(4f,12f,20f,12f) }
                "person" -> { drawCircle(tint,3.5f,Offset(12f,7f),style=stroke); drawArc(tint,180f,180f,false,Offset(4f,13f),Size(16f,14f),style=stroke) }
                "location" -> { drawPath(Path().apply {moveTo(12f,22f);cubicTo(10f,19f,4f,13f,4f,9f);cubicTo(4f,-1f,20f,-1f,20f,9f);cubicTo(20f,13f,14f,19f,12f,22f);close()},tint,style=stroke); drawCircle(tint,2.6f,Offset(12f,8.5f),style=stroke) }
                "course" -> { path(12f,5f,12f,21f); path(12f,5f,8f,3f,2f,3f,2f,18f,8f,18f,12f,21f); path(12f,5f,16f,3f,22f,3f,22f,18f,16f,18f,12f,21f) }
                "close" -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
                "check" -> path(4f,12f,9f,17f,20f,6f)
                "sync", "refresh" -> { drawArc(tint,35f,285f,false,Offset(4f,4f),Size(16f,16f),style=stroke); path(15f,3f,20f,5f,19f,10f) }
                "info", "about" -> { drawCircle(tint,9f,Offset(12f,12f),style=stroke); drawCircle(tint,1f,Offset(12f,7.5f)); line(12f,11f,12f,17f) }
                "back", "previous" -> path(15f,5f,8f,12f,15f,19f)
                "next" -> path(9f,5f,16f,12f,9f,19f)
                "import" -> { path(4f,14f,4f,20f,20f,20f,20f,14f); line(12f,3f,12f,15f); path(7f,10f,12f,15f,17f,10f) }
                "export" -> { path(4f,14f,4f,20f,20f,20f,20f,14f); line(12f,15f,12f,3f); path(7f,8f,12f,3f,17f,8f) }
                "edit" -> { path(4f,16f,15f,5f,19f,9f,8f,20f,4f,20f,4f,16f); line(13f,7f,17f,11f); path(15f,5f,17f,3f,21f,7f,19f,9f) }
                "delete" -> { path(6f,7f,7f,21f,17f,21f,18f,7f); line(4f,7f,20f,7f); path(9f,7f,9f,3f,15f,3f,15f,7f); line(10f,11f,10f,17f); line(14f,11f,14f,17f) }
                "folder" -> path(3f,6f,9f,6f,11f,9f,21f,9f,21f,20f,3f,20f,3f,6f)
                "open" -> { path(10f,4f,4f,4f,4f,20f,20f,20f,20f,14f); path(14f,3f,21f,3f,21f,10f); line(11f,13f,21f,3f) }
                "share" -> {
                    line(7f,11f,17f,6f); line(7f,13f,17f,18f)
                    listOf(Offset(5f,12f),Offset(19f,5f),Offset(19f,19f)).forEach { drawCircle(tint,2.5f,it,style=stroke) }
                }
                "calendar", "week" -> { roundRect(3f,5f,18f,16f); line(3f,10f,21f,10f); line(8f,2f,8f,7f); line(16f,2f,16f,7f); roundRect(7f,13f,4f,4f,1f) }
                "holiday" -> { roundRect(3f,5f,18f,16f); line(3f,10f,21f,10f); line(8f,2f,8f,7f); line(16f,2f,16f,7f); line(9f,13f,15f,18f); line(15f,13f,9f,18f) }
                "makeup" -> { roundRect(3f,5f,18f,16f); line(3f,10f,21f,10f); line(8f,2f,8f,7f); line(16f,2f,16f,7f); line(12f,13f,12f,18f); line(9f,15.5f,15f,15.5f) }
                "time", "pending" -> { drawCircle(tint,9f,Offset(12f,12f),style=stroke); path(12f,6f,12f,12f,16f,14f) }
                "sun", "today" -> {
                    drawCircle(tint,4f,Offset(12f,12f),style=stroke)
                    repeat(8) { i -> val a=Math.PI*i/4; line((12+7*kotlin.math.cos(a)).toFloat(),(12+7*kotlin.math.sin(a)).toFloat(),(12+10*kotlin.math.cos(a)).toFloat(),(12+10*kotlin.math.sin(a)).toFloat()) }
                }
                "moon" -> {
                    drawPath(Path().apply {moveTo(20f,15f);cubicTo(14f,17f,7f,10f,9f,4f);cubicTo(-2f,8f,7f,28f,20f,15f);close()},tint,style=stroke)
                }
                "system" -> { roundRect(3f,4f,18f,14f); line(12f,18f,12f,21f); line(8f,21f,16f,21f) }
                "language" -> { drawCircle(tint,9f,Offset(12f,12f),style=stroke); drawOval(tint,Offset(8f,3f),Size(8f,18f),style=stroke); line(3f,12f,21f,12f) }
                "notification" -> { path(5f,17f,7f,14f,7f,9f); drawArc(tint,180f,180f,false,Offset(7f,4f),Size(10f,10f),style=stroke); path(17f,9f,17f,14f,19f,17f,5f,17f); drawArc(tint,0f,180f,false,Offset(10f,18f),Size(4f,4f),style=stroke) }
                "overlap", "plans", "copy" -> { roundRect(7f,3f,14f,14f); roundRect(3f,7f,14f,14f) }
                else -> { listOf(6f,12f,18f).forEachIndexed { i,y -> line(3f,y,21f,y); drawCircle(tint,2f,Offset(if(i==1)15f else 8f,y)) } }
            }
        }
    }
}
