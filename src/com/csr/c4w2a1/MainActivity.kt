package com.csr.c4w2a1

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import com.csr.c4w2a1.databinding.MainBinding
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.util.*
import java.util.AbstractMap.SimpleEntry
import kotlin.collections.ArrayList
import kotlin.concurrent.thread

class MainActivity: Activity() {
    companion object {
        private const val TAG = "C2W2A1"
        private val images = arrayOf(
            "test_hand1.png", "test_hand2.png","test_hand3.png", "test_hand4.png",
            "test_hand5.png", "test_hand6.png","test_hand7.png", "test_hand8.png")
    }

    private lateinit var binding: MainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainBinding.inflate(layoutInflater)
        setContentView(binding.root)

    }

    override fun onDestroy() {
        super.onDestroy()
        tflite.close()
    }

    fun onConv2Click(v: View) {
        tfliteModel = FileUtil.loadMappedFile(this, "finger_model.tflite")
        runInference()
    }

    fun onResnetClick(v: View) {
        tfliteModel = FileUtil.loadMappedFile(this, "resnet_model.tflite")
        runInference()
    }

    fun runInference() {
        thread {
            val options = Interpreter.Options()
            if (false) {
                options.addDelegate(NnApiDelegate())
            } else {
                options.setNumThreads(1)
            }
            tflite = Interpreter(tfliteModel, options)

            outputs = Array(1) { FloatArray(6) }

            inferenceTime = 0
            firstFrame = true
            for(image in images) {
                convertBitmapToByteBuffer(getBitmap(image))

                startTime = SystemClock.uptimeMillis()
                tflite.run(imgData, outputs)
                printLabels()

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

    private fun printLabels() {
        val runtime = SystemClock.uptimeMillis() - startTime

        for (i in 0 until 6) {
            sortedLabels.add(SimpleEntry(i.toString(), outputs[0][i]))
            if (sortedLabels.size > RESULTS_TO_SHOW) {
                sortedLabels.poll()
            }
        }

        var text = ""
        for (i in 0 until sortedLabels.size) {
            val label = sortedLabels.poll()
            text = String.format("\n  %s: %f", label.key, label.value) + text
        }
        text = "Result:" + text

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

    private lateinit var tfliteModel: MappedByteBuffer
    private var imgData: ByteBuffer? = null
    private val intValues = IntArray(64 * 64)
    private val IMAGE_MEAN = 128.0f
    private val IMAGE_STD = 128.0f

    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        if (imgData == null) {
            imgData = ByteBuffer.allocateDirect(
                    1 * 64 * 64 * 3 * 4)
            imgData!!.order(ByteOrder.nativeOrder())
        }
        imgData!!.rewind()

        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until 64) {
            for (j in 0 until 64) {
                val v: Int = intValues.get(pixel++)
                imgData!!.putFloat(((v shr 16 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                imgData!!.putFloat(((v shr 8 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                imgData!!.putFloat(((v and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
            }
        }
    }

    private val RESULTS_TO_SHOW = 3

    private val sortedLabels = PriorityQueue<Map.Entry<String, Float>>(RESULTS_TO_SHOW)
        { o1, o2 -> o1.value.compareTo(o2.value) }

    private lateinit var tflite: Interpreter
    private lateinit var outputs: Array<FloatArray>


    private fun getBitmap(imageName: String): Bitmap {
        val stream = BitmapFactory.decodeStream(assets.open(imageName))
        runOnUiThread {
            binding.imageView.setImageBitmap(Bitmap.createScaledBitmap(stream, 480, 480, true))
        }
        return Bitmap.createScaledBitmap(stream, 64, 64, true)
    }
}
