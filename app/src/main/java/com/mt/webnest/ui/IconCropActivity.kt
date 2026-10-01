package com.mt.webnest.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mt.webnest.R
import com.mt.webnest.ui.theme.WebNestTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

class IconCropActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WebNestTheme { CropScreen() } }
    }

    private class ImageTooLarge : Exception()

    private suspend fun loadImage(): Bitmap = withContext(Dispatchers.IO) {
        val uri = requireNotNull(intent.data)
        val bytes = contentResolver.openInputStream(uri)!!.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 32 * 1024 * 1024) throw ImageTooLarge()
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Couldn't read this image" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > 2048) inSampleSize = inSampleSize.coerceAtLeast(1) * 2
        }
        val image = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        if (matrix.isIdentity) image else Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true).also { image.recycle() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CropScreen() {
        var image by remember { mutableStateOf<Bitmap?>(null) }
        var failed by remember { mutableStateOf(false) }
        fun notify(text: String) { Toast.makeText(this@IconCropActivity, text, Toast.LENGTH_SHORT).show() }
        var zoom by rememberSaveable { mutableFloatStateOf(1f) }
        var centerX by rememberSaveable { mutableFloatStateOf(.5f) }
        var centerY by rememberSaveable { mutableFloatStateOf(.5f) }
        var saving by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { runCatching { loadImage()}.onSuccess { image = it }.onFailure { failed = true; notify(if (it is ImageTooLarge) "Choose an image under 32 MB" else "Couldn't read this image")}}
        @Composable
        fun Preview(modifier: Modifier) {
            BoxWithConstraints(modifier.padding(24.dp),
                contentAlignment = Alignment.Center) {
                val previewSide = minOf(maxWidth, maxHeight, 512.dp)
                val bitmap = image
                if (bitmap == null && !failed) CircularProgressIndicator()
                if (bitmap != null) {
                    val previewBackground = MaterialTheme.colorScheme.surfaceContainerHigh
                    val preview = remember(bitmap) { bitmap.asImageBitmap()}
                    Canvas(Modifier.size(previewSide).clipToBounds().semantics { contentDescription = "Square icon preview" }.pointerInput(bitmap, saving) {
                            if (!saving) detectTransformGestures { _, pan, scale, _ ->
                                val current = IconCrop.window(bitmap.width, bitmap.height, centerX, centerY, zoom)
                                centerX = (current.left + current.side / 2f - pan.x * current.side / size.width) / bitmap.width
                                centerY = (current.top + current.side / 2f - pan.y * current.side / size.height) / bitmap.height
                                zoom = (zoom * scale).coerceIn(1f, 6f)
                            }
                        }) {
                        val crop = IconCrop.window(bitmap.width, bitmap.height, centerX, centerY, zoom)
                        drawRect(previewBackground)
                        drawImage(preview, srcOffset = IntOffset(crop.left, crop.top), srcSize = IntSize(crop.side, crop.side), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                        for (division in 1..2) {
                            val position = size.width * division / 3f
                            val lines = listOf(Offset(position, 0f) to Offset(position, size.height), Offset(0f, position) to Offset(size.width, position))
                            for ((start, end) in lines) {
                                drawLine(Color.Black.copy(alpha = .4f), start, end, strokeWidth = 2.dp.toPx())
                                drawLine(Color.White.copy(alpha = .7f), start, end, strokeWidth = 1.dp.toPx())
                            }
                        }
                    }
                }
            }
        }
        @Composable
        fun UseIconButton() {
            Button(enabled = image != null && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                saving = true
                scope.launch {
                    try {
                        val bitmap = image!!
                        val crop = IconCrop.window(bitmap.width, bitmap.height, centerX, centerY, zoom)
                        val file = withContext(Dispatchers.IO) {
                            val square = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.side, crop.side)
                            val output = Bitmap.createScaledBitmap(square, 512, 512, true)
                            val directory = File(cacheDir, "icons").apply { mkdirs() }
                            File.createTempFile("icon-", ".png", directory).also { target ->
                                target.outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                if (output !== square) output.recycle()
                                if (square !== bitmap) square.recycle()
                            }
                        }
                        val uri = FileProvider.getUriForFile(this@IconCropActivity, "$packageName.files", file)
                        setResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        finish()
                    } catch (_: Exception) { saving = false; notify("Couldn't crop image") }
                }
            }) { Text(if (saving) "Saving…" else "Use icon") }
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text("Crop icon")}, navigationIcon = { IconButton(onClick = { finish()}, enabled = !saving) { Icon(painterResource(R.drawable.ic_back), "Back")}})
        }) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                if (maxHeight < 400.dp && maxWidth > maxHeight) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Preview(Modifier.weight(1f).fillMaxHeight())
                        Box(Modifier.width(176.dp).fillMaxHeight().padding(end = 24.dp), contentAlignment = Alignment.Center) { UseIconButton()}
                    }
                } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Preview(Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) { UseIconButton()}
                }
            }
        }
    }
}