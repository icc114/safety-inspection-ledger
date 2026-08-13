package cn.safetyledger.app.media

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cn.safetyledger.app.data.LedgerDao
import cn.safetyledger.app.data.MediaEntity
import cn.safetyledger.app.data.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@Composable
fun SignatureDialog(inspectionId: String, kind: MediaKind, title: String, dao: LedgerDao, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var strokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(Modifier.fillMaxWidth().height(230.dp).background(Color.White)) {
                Canvas(Modifier.fillMaxSize().onSizeChanged { canvasSize = it }.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { point -> strokes = strokes + listOf(listOf(point)) },
                        onDrag = { change, _ -> change.consume(); strokes = strokes.dropLast(1) + listOf(strokes.last() + change.position) },
                    )
                }) {
                    strokes.forEach { stroke -> stroke.zipWithNext().forEach { (a, b) -> drawLine(Color.Black, a, b, strokeWidth = 7f, cap = StrokeCap.Round) } }
                }
            }
        },
        confirmButton = { TextButton(enabled = strokes.any { it.size > 1 }, onClick = {
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { saveSignature(context.filesDir, inspectionId, kind, strokes, canvasSize).also { dao.saveMedia(it) } } }
                    .onSuccess { onSaved("签名已保存"); onDismiss() }
                    .onFailure { onSaved("签名保存失败：${it.message}") }
            }
        }) { Text("保存签名") } },
        dismissButton = { TextButton(onClick = { if (strokes.isNotEmpty()) strokes = emptyList() else onDismiss() }) { Text(if (strokes.isNotEmpty()) "清空" else "取消") } },
    )
}

private fun saveSignature(root: File, inspectionId: String, kind: MediaKind, strokes: List<List<Offset>>, sourceSize: IntSize): MediaEntity {
    require(sourceSize.width > 0 && sourceSize.height > 0)
    val width = 1400
    val height = 480
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(AndroidColor.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.BLACK; strokeWidth = 10f; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    val sx = width.toFloat() / sourceSize.width
    val sy = height.toFloat() / sourceSize.height
    strokes.forEach { stroke -> stroke.zipWithNext().forEach { (a, b) -> canvas.drawLine(a.x * sx, a.y * sy, b.x * sx, b.y * sy, paint) } }
    val dir = File(root, "media/$inspectionId").apply { mkdirs() }
    val file = File(dir, "signature-${UUID.randomUUID()}.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    return MediaEntity(UUID.randomUUID().toString(), inspectionId, kind = kind, localPath = file.absolutePath, sha256 = MediaImporter.sha256(file), capturedAt = System.currentTimeMillis())
}
