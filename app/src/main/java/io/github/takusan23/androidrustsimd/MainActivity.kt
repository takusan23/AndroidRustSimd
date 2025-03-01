package io.github.takusan23.androidrustsimd

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.contentValuesOf
import io.github.takusan23.akaricore.audio.AudioEncodeDecodeProcessor
import io.github.takusan23.akaricore.common.toAkariCoreInputOutputData
import io.github.takusan23.androidrustsimd.ui.theme.AndroidRustSimdTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uniffi.android_rust_uniffi.uniffiSubTwoBytearray
import java.io.File
import kotlin.system.measureTimeMillis

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidRustSimdTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {

                        AudioDecodeMenu()

                        HorizontalDivider()

                        val c = remember { mutableStateOf(byteArrayOf()) }
                        val timeList = remember { mutableStateOf(listOf<String>()) }
                        BenchmarkMenu(
                            onRustJniClick = { a, b ->
                                val time = measureTimeMillis { c.value = jniSubTwoByteArray(a, b) }
                                println(time)
                                timeList.value += "Rust+JNI $time ms"
                            },
                            onRustUniffiClick = { a, b ->
                                val time = measureTimeMillis { c.value = uniffiSubTwoBytearray(a, b) }
                                println(time)
                                timeList.value += "Rust+UniFFI $time ms"
                            },
                            onKotlinClick = { a, b ->
                                val time = measureTimeMillis { c.value = kotlinSubTwoByteArray(a, b) }
                                println(time)
                                timeList.value += "Kotlin $time ms"
                            },
                            onRustJniWithoutSimdClick = { a, b ->
                                val time = measureTimeMillis { c.value = jniWithoutSimdSubTwoByteArray(a, b) }
                                println(time)
                                timeList.value += "Rust+JNI (SIMD 無し) $time ms"
                            }
                        )

                        HorizontalDivider()

                        TimeResultMenu(timeList = timeList.value)

                        HorizontalDivider()

                        AudioEncodeMenu(subByteArray = c.value)
                    }
                }
            }
        }
    }

    private external fun jniSubTwoByteArray(a: ByteArray, b: ByteArray): ByteArray
    private external fun jniWithoutSimdSubTwoByteArray(a: ByteArray, b: ByteArray): ByteArray

    private fun kotlinSubTwoByteArray(a: ByteArray, b: ByteArray): ByteArray {
        val size = minOf(a.size, b.size)
        val result = ByteArray(size)
        repeat(size) { i ->
            result[i] = (a[i] - b[i]).toByte()
        }
        return result
    }

    companion object {
        init {
            System.loadLibrary("android_jni")
            System.loadLibrary("android_jni_without_simd")
        }
    }
}

private const val PCM_VOCAL_FILE = "vocal"
private const val PCM_KARAOKE_FILE = "karaoke"

@Composable
private fun BenchmarkMenu(
    modifier: Modifier = Modifier,
    onRustJniClick: (ByteArray, ByteArray) -> Unit,
    onRustUniffiClick: (ByteArray, ByteArray) -> Unit,
    onKotlinClick: (ByteArray, ByteArray) -> Unit,
    onRustJniWithoutSimdClick: (ByteArray, ByteArray) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val a = remember { mutableStateOf(byteArrayOf()) }
    val b = remember { mutableStateOf(byteArrayOf()) }

    Column(modifier = modifier) {
        Text(
            text = "2つの音声を引き算する",
            fontSize = 20.sp
        )

        if (a.value.isEmpty() || b.value.isEmpty()) {
            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    a.value = context.getExternalFilesDir(null)!!.resolve(PCM_VOCAL_FILE).readBytes()
                    b.value = context.getExternalFilesDir(null)!!.resolve(PCM_KARAOKE_FILE).readBytes()
                }
            }) { Text(text = "デコードした PCM をロード") }
        } else {

            Text("バイト配列 サイズ = ${minOf(a.value.size, b.value.size)}")

            Row {
                Button(onClick = { onRustJniClick(a.value, b.value) }) {
                    Text(text = "Rust+JNI")
                }
                Button(onClick = { onRustUniffiClick(a.value, b.value) }) {
                    Text(text = "Rust+UniFFI")
                }
            }
            Row {
                Button(onClick = { onKotlinClick(a.value, b.value) }) {
                    Text(text = "Kotlin")
                }
                Button(onClick = { onRustJniWithoutSimdClick(a.value, b.value) }) {
                    Text(text = "Rust+JNI (SIMD 無し)")
                }
            }
        }
    }
}

@Composable
private fun TimeResultMenu(modifier: Modifier = Modifier, timeList: List<String>) {
    Column(modifier = modifier) {
        Text(
            text = "計算時間",
            fontSize = 20.sp
        )

        LazyColumn(modifier = Modifier.height(200.dp)) {
            itemsIndexed(timeList) { index, text ->
                Text(text = "$index: $text")
            }
        }
    }
}

@Composable
private fun AudioEncodeMenu(modifier: Modifier = Modifier, subByteArray: ByteArray) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isProgressDecode = remember { mutableStateOf(false) }
    fun encode() {
        scope.launch {
            isProgressDecode.value = true
            try {
                // PCM を AAC に
                val encodeFile = context.getExternalFilesDir(null)!!.resolve("vocal_only_${System.currentTimeMillis()}.aac")
                AudioEncodeDecodeProcessor.encode(
                    input = subByteArray.toAkariCoreInputOutputData(),
                    output = encodeFile.toAkariCoreInputOutputData(),
                    samplingRate = 44_100
                )
                // 音楽フォルダへ
                val contentResolver = context.contentResolver
                val contentValues = contentValuesOf(
                    MediaStore.Audio.Media.DISPLAY_NAME to encodeFile.name,
                    MediaStore.Audio.Media.RELATIVE_PATH to "${Environment.DIRECTORY_MUSIC}/AndroidRustSimd"
                )
                val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)!!
                encodeFile.inputStream().use { inputStream ->
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                encodeFile.delete()
            } finally {
                isProgressDecode.value = false
            }
        }
    }

    Column(modifier = modifier) {
        Text(
            text = "エンコードメニュー",
            fontSize = 20.sp
        )

        if (isProgressDecode.value) {
            CircularProgressIndicator()
        } else {
            Row {
                Button(onClick = { encode() }) {
                    Text(text = "エンコードする")
                }
            }
        }
    }
}

@Composable
private fun AudioDecodeMenu(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isProgressDecode = remember { mutableStateOf(false) }
    fun decode(audioUri: Uri, pcmFile: File) {
        scope.launch {
            isProgressDecode.value = true
            try {
                AudioEncodeDecodeProcessor.decode(
                    input = audioUri.toAkariCoreInputOutputData(context),
                    output = pcmFile.toAkariCoreInputOutputData()
                )
            } finally {
                isProgressDecode.value = false
            }
        }
    }

    val vocalPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            decode(
                audioUri = uri ?: return@rememberLauncherForActivityResult,
                pcmFile = context.getExternalFilesDir(null)!!.resolve(PCM_VOCAL_FILE)
            )
        }
    )
    val karaokePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            decode(
                audioUri = uri ?: return@rememberLauncherForActivityResult,
                pcmFile = context.getExternalFilesDir(null)!!.resolve(PCM_KARAOKE_FILE)
            )
        }
    )

    Column(modifier = modifier) {
        Text(
            text = "デコードメニュー",
            fontSize = 20.sp
        )

        if (isProgressDecode.value) {
            CircularProgressIndicator()
        } else {
            Row {
                Button(onClick = { vocalPicker.launch(arrayOf("audio/*")) }) {
                    Text(text = "ボーカル曲")
                }
                Button(onClick = { karaokePicker.launch(arrayOf("audio/*")) }) {
                    Text(text = "カラオケ曲")
                }
            }
        }
    }
}