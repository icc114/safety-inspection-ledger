package cn.safetyledger.app.media

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cn.safetyledger.app.data.LedgerDao
import cn.safetyledger.app.data.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
fun MediaActions(inspectionId: String, kind: MediaKind, location: String, dao: LedgerDao, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    fun save(uri: Uri) = scope.launch {
        runCatching { withContext(Dispatchers.IO) { MediaImporter.import(context, uri, inspectionId, kind, location).also { dao.saveMedia(it) } } }
            .onSuccess { onMessage("照片已保存到本机") }
            .onFailure { onMessage("照片保存失败：${it.message}") }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(::save) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> uris.forEach(::save) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val file = File(context.cacheDir, "camera/${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs(); createNewFile() }
            cameraUri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            camera.launch(cameraUri)
        }) { Text("拍照") }
        OutlinedButton(onClick = { gallery.launch("image/*") }) { Text("从相册选择") }
    }
}
