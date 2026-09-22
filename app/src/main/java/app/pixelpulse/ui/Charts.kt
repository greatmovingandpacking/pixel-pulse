package app.pixelpulse.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.pixelpulse.monitor.RatePoint

@Composable
fun RateTimeline(
    points: List<RatePoint>,
    modifier: Modifier = Modifier,
    downloadColor: Color = MaterialTheme.colorScheme.primary,
    uploadColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        drawRoundRect(color = track.copy(alpha = 0.35f))
        if (points.size < 2) return@Canvas
        val maxRate = points.maxOf { (it.rxBytesPerSec + it.txBytesPerSec).coerceAtLeast(1) }.toFloat()
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)
        fun path(selector: (RatePoint) -> Long): Path {
            val p = Path()
            points.forEachIndexed { index, point ->
                val x = index * stepX
                val y = size.height - (selector(point) / maxRate) * (size.height * 0.92f)
                if (index == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p
        }
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        drawPath(path { it.rxBytesPerSec }, color = downloadColor, style = stroke)
        drawPath(path { it.txBytesPerSec }, color = uploadColor, style = stroke)
        drawLine(
            color = track,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

@Composable
fun CpuSparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .then(modifier),
    ) {
        drawRoundRect(color = track.copy(alpha = 0.35f))
        if (values.size < 2) return@Canvas
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height - (value.coerceIn(0f, 100f) / 100f) * (size.height * 0.9f)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun StackedMemoryBar(
    fractions: List<Pair<Color, Float>>,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(18.dp),
    ) {
        var x = 0f
        val gap = 2.dp.toPx()
        val usable = size.width - gap * (fractions.size - 1).coerceAtLeast(0)
        fractions.forEach { (color, fraction) ->
            val w = (usable * fraction.coerceIn(0f, 1f)).coerceAtLeast(0f)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, 0f),
                size = androidx.compose.ui.geometry.Size(w, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
            )
            x += w + gap
        }
    }
}
