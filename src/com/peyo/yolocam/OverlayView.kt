package com.peyo.yolocam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private var results: List<DetectionResult> = emptyList()

    // ★ 영상 원본 크기 (MainActivity에서 setVideoSize로 설정)
    private var videoWidth = 0
    private var videoHeight = 0

    private val boxPaint = Paint()
    private val textPaint = Paint()
    private val textBgPaint = Paint()

    private val LABELS = arrayOf<String?>(
        "person",
        "bicycle",
        "car",
        "motorcycle",
        "airplane",
        "bus",
        "train",
        "truck",
        "boat",
        "traffic light",
        "fire hydrant",
        "stop sign",
        "parking meter",
        "bench",
        "bird",
        "cat",
        "dog",
        "horse",
        "sheep",
        "cow",
        "elephant",
        "bear",
        "zebra",
        "giraffe",
        "backpack",
        "umbrella",
        "handbag",
        "tie",
        "suitcase",
        "frisbee",
        "skis",
        "snowboard",
        "sports ball",
        "kite",
        "baseball bat",
        "baseball glove",
        "skateboard",
        "surfboard",
        "tennis racket",
        "bottle",
        "wine glass",
        "cup",
        "fork",
        "knife",
        "spoon",
        "bowl",
        "banana",
        "apple",
        "sandwich",
        "orange",
        "broccoli",
        "carrot",
        "hot dog",
        "pizza",
        "donut",
        "cake",
        "chair",
        "couch",
        "potted plant",
        "bed",
        "dining table",
        "toilet",
        "tv",
        "laptop",
        "mouse",
        "remote",
        "keyboard",
        "cell phone",
        "microwave",
        "oven",
        "toaster",
        "sink",
        "refrigerator",
        "book",
        "clock",
        "vase",
        "scissors",
        "teddy bear",
        "hair drier",
        "toothbrush"
    )

    private val COLORS = intArrayOf(
        -0xbbbc, -0xbb00bc, -0xbbbb01, -0x100, -0xff0001,
        -0xff01, -0x7800, -0x77ff01, -0xff0078, -0xff78
    )

    init {
        boxPaint.setStyle(Paint.Style.STROKE)
        boxPaint.setStrokeWidth(4f)
        boxPaint.setAntiAlias(true)

        textPaint.setColor(Color.WHITE)
        textPaint.setTextSize(34f)
        textPaint.setAntiAlias(true)
        textPaint.setStyle(Paint.Style.FILL)

        textBgPaint.setStyle(Paint.Style.FILL)
        textBgPaint.setAlpha(180)
    }

    /**
     * ★ MainActivity에서 반드시 호출: 영상 원본 해상도 설정
     * ExoPlayer의 videoSize 콜백에서 받은 값을 넘겨주세요.
     */
    fun setVideoSize(width: Int, height: Int) {
        this.videoWidth = width
        this.videoHeight = height
        invalidate()
    }

    fun setResults(results: List<DetectionResult>?) {
        this.results = results ?: emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewW = getWidth().toFloat() // 이 오버레이 View의 실제 픽셀 너비
        val viewH = getHeight().toFloat() // 이 오버레이 View의 실제 픽셀 높이
        if (viewW == 0f || viewH == 0f || results.isEmpty()) return

        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // [문제 상황]
        // ExoPlayer의 PlayerView는 영상을 뷰 전체에 stretch하지 않고,
        // 영상 원본 비율(aspect ratio)을 유지하면서 letterbox/pillarbox
        // 방식으로 뷰 안에 맞춥니다.
        //
        // 예) 16:9 영상을 4:3 뷰에 표시 → 위아래에 검은 여백(letterbox)
        //     4:3 영상을 16:9 뷰에 표시 → 좌우에 검은 여백(pillarbox)
        //
        // [결과]
        // YoloDetector가 반환한 0~1 정규화 좌표는 "영상 프레임 전체" 기준입니다.
        // 그런데 실제 영상이 뷰 전체가 아닌 일부 영역에만 그려지므로,
        // 박스 좌표도 그 실제 렌더링 영역 기준으로 다시 매핑해야 합니다.
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        // ── 1. 영상 원본 비율 계산 ──────────────────────────────────
        // videoWidth, videoHeight는 ExoPlayer에서 onVideoSizeChanged()로 받아온 값
        val videoAspect: Float
        if (videoWidth > 0 && videoHeight > 0) {
            videoAspect = videoWidth.toFloat() / videoHeight
        } else {
            // 영상 크기 정보가 아직 없으면 일반적인 16:9로 가정
            videoAspect = 16f / 9f
        }

        val viewAspect = viewW / viewH // 현재 뷰의 비율

        // ── 2. 뷰 안에서 영상이 실제로 그려지는 영역 계산 ────────────
        // ExoPlayer는 영상 비율 vs 뷰 비율을 비교해 두 가지 방식 중 하나로 배치:
        //   - 영상이 더 넓을 때(videoAspect > viewAspect): 좌우를 뷰 끝까지 채우고
        //     남는 높이는 위아래로 균등 분배 → renderY가 여백 시작점
        //   - 영상이 더 좁을 때: 위아래를 뷰 끝까지 채우고
        //     남는 너비는 좌우로 균등 분배 → renderX가 여백 시작점
        val renderW: Float
        val renderH: Float
        val renderX: Float
        val renderY: Float
        if (videoAspect > viewAspect) {
            // Letterbox: 좌우 꽉 참 / 위아래 여백
            renderW = viewW
            renderH = viewW / videoAspect // 영상 비율을 유지한 높이
            renderX = 0f
            renderY = (viewH - renderH) / 2f // 위아래 여백을 절반씩 나눔
        } else {
            // Pillarbox: 위아래 꽉 참 / 좌우 여백
            renderH = viewH
            renderW = viewH * videoAspect // 영상 비율을 유지한 너비
            renderX = (viewW - renderW) / 2f // 좌우 여백을 절반씩 나눔
            renderY = 0f
        }

        // ── 3. 탐지 결과마다 박스 및 라벨 그리기 ──────────────────────
        for (res in results) {
            if (res.boundingBox == null) continue

            // 0~1 정규화 좌표 → 뷰 내 실제 픽셀 좌표 변환
            //
            // 공식: renderX + (정규화 좌표 × 실제 렌더링 영역 크기)
            //   - renderX/renderY: 영상 렌더링 시작점 오프셋 (여백 보정)
            //   - × renderW/renderH: 정규화 비율을 실제 픽셀로 스케일
            //
            // 예) 정규화 left=0.3, renderX=40, renderW=600 → 40 + 0.3×600 = 220px
            val left = renderX + res.boundingBox!!.left * renderW
            val top = renderY + res.boundingBox!!.top * renderH
            val right = renderX + res.boundingBox!!.right * renderW
            val bottom = renderY + res.boundingBox!!.bottom * renderH

            // 클래스 인덱스를 색상 배열에 순환 매핑 (클래스마다 고유 색상)
            val color = COLORS[abs(res.classIndex) % COLORS.size]
            boxPaint.setColor(color)
            textBgPaint.setColor(color)

            // 바운딩 박스 사각형 그리기
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // ── 4. 클래스 라벨 텍스트 그리기 ──────────────────────────
            if (res.classIndex >= 0 && res.classIndex < LABELS.size) {
                // 표시할 텍스트: "person 92%" 형태
                val label = LABELS[res.classIndex] + " " + String.format("%.0f%%", res.score * 100)
                val tw = textPaint.measureText(label) // 텍스트 픽셀 너비
                val th = textPaint.getTextSize() // 텍스트 픽셀 높이

                // 라벨 위치 결정:
                //   기본: 박스 상단 바로 위에 배치 (top - 높이 - 여백 6px)
                //   박스가 화면 상단에 너무 붙어있으면 (top - th - 6 < 0)
                //   박스 하단 안쪽으로 내려서 배치 (잘리지 않도록)
                val labelTop = if (top - th - 6 >= 0) top - th - 6 else bottom

                // 텍스트 배경 사각형 (가독성을 위해 박스 색상으로 채움)
                canvas.drawRect(left, labelTop, left + tw + 8, labelTop + th + 4, textBgPaint)

                // 텍스트 그리기 (배경 위에 흰색 등 대비색으로)
                canvas.drawText(label, left + 4, labelTop + th, textPaint)
            }
        }
    }
}