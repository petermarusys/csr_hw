package com.csr.c4w3a1

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import com.csr.c4w3a1.databinding.MainBinding
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread


class MainActivity: Activity() {
    companion object {
        private const val TAG = "C2W3A1"
        private val images = arrayOf(
            "test0.jpg", "test1.jpg", "test2.jpg", "test3.jpg", "test4.jpg",
            "test5.jpg","test6.jpg", "test7.jpg", "test8.jpg", "test9.jpg")
    }
    private lateinit var binding: MainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    fun onComputeClick(v: View) {
        runInference()
    }

    fun onNNApiClick(v: View) {
    }


    private val detector: YoloDetector = YoloDetector()

    fun runInference() {
        thread {
            val options = Interpreter.Options()
            if (binding.nnapiToggle.isChecked()) {
                options.addDelegate(NnApiDelegate())
            } else {
                options.setNumThreads(1)
            }
            val tflite = Interpreter(FileUtil.loadMappedFile(this, "yolo_int8.tflite"), options)

            detector.outputScale = tflite.getOutputTensor(0).quantizationParams().getScale()
            detector.outputZeroPoint = tflite.getOutputTensor(0).quantizationParams().getZeroPoint()

            inferenceTime = 0
            firstFrame = true
            for(image in images) {
                val inputs = detector.preprocessLetterbox(getBitmap(image))

                val outputs = ByteBuffer.allocateDirect(
                    detector.GRID_SIZE * detector.GRID_SIZE
                            * detector.ANCHORS_PER_GRID * detector.VALUES_PER_ANCHOR
                )
                outputs.order(ByteOrder.nativeOrder())

                startTime = SystemClock.uptimeMillis()
                tflite.run(inputs, outputs)

                if (firstFrame) {
                     detector.printDiagnostics(outputs)
                }

                val result = detector.postProcess(outputs, 608, 608)

                printLabels(result)

                Thread.sleep(3000)
            }

            runOnUiThread {
                binding.textView1.text = "Summary: \n\t Average Inference time (ms): " +
                        "${inferenceTime / (images.size - 1)}"
                binding.textView2.text = ""
            }
            tflite.close()
        }
     }

    private fun printLabels(result: MutableList<DetectionResult?>) {
        val runtime = SystemClock.uptimeMillis() - startTime

        var text = "Result: " + result.size

        runOnUiThread {
            binding.overlayView.setResults(result)

            binding.textView1.text = "Inference time (ms): " + runtime
            if (firstFrame) {
                firstFrame = false
            } else {
                inferenceTime += runtime
            }

            binding.textView2.text = text
        }
    }

    private var startTime: Long = 0
    private var inferenceTime : Long = 0
    private var firstFrame : Boolean = true

    private fun getBitmap(imageName: String): Bitmap {
        val stream = BitmapFactory.decodeStream(assets.open(imageName))
        val bitmap = Bitmap.createScaledBitmap(stream, 960, 540, true)
        runOnUiThread {
            binding.imageView.setImageBitmap(bitmap)
            binding.overlayView.setResults(null)
        }
        return stream
    }
}
