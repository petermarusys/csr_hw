package com.csr.c4w2a2

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import com.csr.c4w2a2.databinding.MainBinding
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.exp

class MainActivity: Activity() {
    companion object {
        private const val TAG = "C2W2A2"
        private val images = arrayOf(
            "test1.jpg", "test2.jpg", "test3.jpg", "test4.jpg", "test5.jpg",
            "test6.jpg", "test7.jpg", "test8.jpg", "test9.jpg", "test10.jpg")
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

    fun runInference() {
        thread {
            val options = Interpreter.Options()
            if (binding.nnapiToggle.isChecked()) {
                options.addDelegate(GpuDelegate())
            } else {
                options.setNumThreads(1)
            }
            val tflite = Interpreter(FileUtil.loadMappedFile(this, "alpaca_model.tflite"), options)

            inferenceTime = 0
            firstFrame = true
            for(image in images) {
                val inputs = convertBitmapToByteBuffer(getBitmap(image))
                var outputs = Array(1) { FloatArray(1) }

                startTime = SystemClock.uptimeMillis()
                tflite.run(inputs, outputs)
                printLabels(outputs)

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

    private fun printLabels(outputs: Array<FloatArray>) {
        val runtime = SystemClock.uptimeMillis() - startTime

        var logit = outputs[0][0]
        var text = "Result: " + (1 / (1 +exp(logit)))

        runOnUiThread {
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

    private var imgData: ByteBuffer = ByteBuffer.allocateDirect(160 * 160 * 3 * 4)
                                .order(ByteOrder.nativeOrder())
    private val intValues = IntArray(160 * 160)
    private val IMAGE_MEAN = 0f
    private val IMAGE_STD = 1f

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        imgData.rewind()

        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until 160) {
            for (j in 0 until 160) {
                val v: Int = intValues.get(pixel++)
                imgData.putFloat(((v shr 16 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                imgData.putFloat(((v shr 8 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                imgData.putFloat(((v and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
            }
        }
	return imgData
    }

    private fun getBitmap(imageName: String): Bitmap {
        val stream = BitmapFactory.decodeStream(assets.open(imageName))
        runOnUiThread {
            binding.imageView.setImageBitmap(Bitmap.createScaledBitmap(stream, 480, 480, true))
        }
        return Bitmap.createScaledBitmap(stream, 160, 160, true)
    }
}
