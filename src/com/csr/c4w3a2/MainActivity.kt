package com.csr.c4w3a2

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import com.csr.c4w3a2.databinding.MainBinding
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import kotlin.concurrent.thread


class MainActivity: Activity() {
    companion object {
        private const val TAG = "C2W3A2"
        private val images = arrayOf(
            "test0.png", "test1.png", "test2.png", "test3.png", "test4.png",
            "test5.png","test6.png", "test7.png", "test8.png", "test9.png")
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
                options.addDelegate(NnApiDelegate())
            } else {
                options.setNumThreads(1)
            }
            val tflite = Interpreter(FileUtil.loadMappedFile(this, "unet.tflite"), options)

            val inputShape = tflite.getInputTensor(0).shape()
            imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(inputShape.get(1), inputShape.get(2), ResizeOp.ResizeMethod.BILINEAR))
                .build()

            val outputShape = tflite.getOutputTensor(0).shape()
            val outH = outputShape[1] // 96
            val outW = outputShape[2] // 128
            val outC = outputShape[3] // 23 (클래스 수)

            inferenceTime = 0
            firstFrame = true
            for(image in images) {
                val inputs = convertBitmapToByteBuffer(getBitmap(image))

                // int8 출력 버퍼 할당: [batch=1][height][width][classes]
                // TFLite quantized 모델의 출력은 int8(-128~127) 범위
                val outputs = Array<Array<Array<ByteArray?>?>?>(1) {
                    Array<Array<ByteArray?>?>(outH) {
                        Array<ByteArray?>(outW) { ByteArray(outC) }
                    }
                }

                startTime = SystemClock.uptimeMillis()
                tflite.run(inputs, outputs)

                val maskBitmap = outputToBitmap(outputs, outH, outW, outC)
                printLabels(maskBitmap)

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


    private fun printLabels(result: Bitmap) {
        val runtime = SystemClock.uptimeMillis() - startTime

        var text = "Result: "

        runOnUiThread {
            binding.overlayView.setMask(result)

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

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        var tensorImage = TensorImage(DataType.UINT8)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)
        return tensorImage.buffer
    }

    private fun getBitmap(imageName: String): Bitmap {
        val stream = BitmapFactory.decodeStream(assets.open(imageName))
        val bitmap = Bitmap.createScaledBitmap(stream, 640, 480, true)
        runOnUiThread {
            binding.imageView.setImageBitmap(bitmap)
        }
        return stream
    }


    val CLASS_COLORS: IntArray = intArrayOf(
        0x00000000,  // 0:  배경        (투명 - 원본 영상 표시)
        -0x55010000,  // 1:  빨강
        -0x55ff0100,  // 2:  초록
        -0x55ffff01,  // 3:  파랑
        -0x55000100,  // 4:  노랑
        -0x5500ff01,  // 5:  마젠타
        -0x55ff0001,  // 6:  시안
        -0x55008000,  // 7:  주황
        -0x557fff01,  // 8:  보라
        -0x55ff7f01,  // 9:  하늘
        -0x5500ff80,  // 10: 핫핑크 => Car
        -0x557f0100,  // 11: 연두
        -0x55ff0080,  // 12: 민트
        -0x557fc000,  // 13: 갈색
        -0x55bf8000,  // 14: 올리브
        -0x55ffbf80,  // 15: 남색
        -0x557fffc0,  // 16: 와인
        -0x55ff7fc0,  // 17: 청록
        -0x55bfff80,  // 18: 남보라
        -0x55007f80,  // 19: 연빨강
        -0x557f0080,  // 20: 연초록
        -0x557f7f01,  // 21: 연파랑
        -0x55000080,  // 22: 연노랑
    )
    private fun outputToBitmap(
        output: Array<Array<Array<ByteArray?>?>?>,
        h: Int,
        w: Int,
        c: Int
    ): Bitmap {
        // 결과 마스크 Bitmap 생성 (128×96, ARGB_8888)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        for (y in 0..<h) {
            for (x in 0..<w) {
                // 현재 픽셀의 클래스 점수 중 최댓값 탐색 (argmax)

                var maxClass = 0
                var maxVal = output[0]!![y]!![x]!![0] // 0번 클래스를 초기 최댓값으로

                for (cls in 1..<c) {
                    if (output[0]!![y]!![x]!![cls] > maxVal) {
                        maxVal = output[0]!![y]!![x]!![cls]
                        maxClass = cls // 더 큰 점수의 클래스로 갱신
                    }
                }

                // 클래스 인덱스 → 팔레트 색상 매핑
                // 범위 초과 시 투명으로 처리 (방어 코드)
                val color: Int =
                    (if (maxClass < CLASS_COLORS.size)
                        CLASS_COLORS[maxClass]
                    else
                        android.graphics.Color.TRANSPARENT)!!

                bmp.setPixel(x, y, color)
            }
        }

        return bmp
    }
}
