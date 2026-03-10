package com.csr.dogcat

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import com.csr.dogcat.databinding.MainBinding
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import kotlin.concurrent.thread

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
            val tflite = Interpreter(FileUtil.loadMappedFile(this, "dogcat_model.tflite"), options)

            val inputShape = tflite.getInputTensor(0).shape()
            imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(inputShape.get(1), inputShape.get(2), ResizeOp.ResizeMethod.BILINEAR))
                .build()

            val outputShape = tflite.getOutputTensor(0).shape()

            inferenceTime = 0
            firstFrame = true
            for(image in images) {
                val inputs = convertBitmapToByteBuffer(getBitmap(image))

 		var outputs = Array(outputShape[0]) { FloatArray(outputShape[1]) }

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

        var text = "Result: " + outputs[0][0]

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

    private lateinit var imageProcessor: ImageProcessor
    private lateinit var tensorImage: TensorImage

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)
        return tensorImage.buffer
    }

    private fun getBitmap(imageName: String): Bitmap {
        val stream = BitmapFactory.decodeStream(assets.open(imageName))
        val bitmap = Bitmap.createScaledBitmap(stream, 480, 480, true)
        runOnUiThread {
            binding.imageView.setImageBitmap(bitmap)
        }
        return bitmap
    }
}
