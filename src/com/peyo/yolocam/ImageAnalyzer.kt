package com.peyo.yolocam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias RecogListener = (results: List<DetectionResult>, inferenceTime: Long, width: Int, height: Int) -> Unit

class ImageAnalyzer(context: Context, private val listener: RecogListener) :
        ImageAnalysis.Analyzer {
    private var tfliteModel : MappedByteBuffer
    private val detector = YoloDetector()

    private lateinit var tflite: Interpreter
    private var initialized = false
    private val lock = ReentrantLock()

    init {
       tfliteModel = FileUtil.loadMappedFile(context, "yolo_int8.tflite")
    }

    private lateinit var executorService: ExecutorService

    fun tfInit(): Task<Void?> {
        lock.lock()
        val task = TaskCompletionSource<Void?>()
        if (initialized) {
            task.setResult(null)
        } else {
            executorService = Executors.newSingleThreadExecutor()
            executorService.execute {
                try {
                    val options = Interpreter.Options()
                    options.setUseNNAPI(true)
                    tflite = Interpreter(tfliteModel, options)
                    detector.outputScale = tflite.getOutputTensor(0).quantizationParams().getScale()
                    detector.outputZeroPoint = tflite.getOutputTensor(0).quantizationParams().getZeroPoint()
                    task.setResult(null)
                } catch (e: IOException) {
                    task.setException(e)
                }
            }
            initialized = true
        }
        lock.unlock()
        return task.task
    }

    fun tfClose() {
        lock.lock()
        if (initialized) {
            executorService.execute {
                tflite.close()
            }
            executorService.shutdownNow()
            initialized = false
        }
        lock.unlock()
    }

    fun classifyAsync(proxy: ImageProxy): Task<Void?> {
        lock.lock()
        if (initialized) {
            executorService.execute {
                val bitmap = getBitmap(proxy)
                if (bitmap == null) {
                    proxy.close()
                } else {
                    val inputs = detector.preprocessLetterbox(bitmap)
                    val outputs = ByteBuffer.allocateDirect(
                        detector.GRID_SIZE * detector.GRID_SIZE
                                * detector.ANCHORS_PER_GRID * detector.VALUES_PER_ANCHOR
                    )
                    outputs.order(ByteOrder.nativeOrder())

                    val startTime = SystemClock.uptimeMillis()
                    tflite.run(inputs, outputs)
                    val endTime = SystemClock.uptimeMillis()
                    val runtime = endTime - startTime

                    val result = detector.postProcess(outputs, bitmap.width, bitmap.height)

                    proxy.close()
                    val nonNullResults = result.filterNotNull()
                    listener(nonNullResults, runtime, bitmap.width, bitmap.height)
                }
            }
        } else {
            proxy.close()
        }
        val task = TaskCompletionSource<Void?>()
        task.setResult(null)
        lock.unlock()
        return task.task
    }

    override fun analyze(proxy: ImageProxy) {
        classifyAsync(proxy)
        //Thread.sleep(100)
    }

    private val yuvToRgbConverter = YuvToRgbConverter(context)
    private lateinit var bitmapBuffer: Bitmap

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun getBitmap(imageProxy: ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null

        if (!::bitmapBuffer.isInitialized) {
            bitmapBuffer = Bitmap.createBitmap(
                    imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
            )
        }
        yuvToRgbConverter.yuvToRgb(image, bitmapBuffer)
        return bitmapBuffer
    }
}