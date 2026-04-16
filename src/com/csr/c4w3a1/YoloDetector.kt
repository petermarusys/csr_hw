package com.csr.c4w3a1

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class YoloDetector {
    companion object {
        private const val TAG = "YoloDetector"
    }

    var outputZeroPoint: Int = 0
    var outputScale: Float = 0f

    // ════════════════════════════════════════════════════════════════
    //  YOLO 모델 파라미터
    //
    //  INPUT_SIZE       : 모델 입력 해상도 (608×608)
    //  GRID_SIZE        : 입력을 19×19 격자로 나눔 → 608/19 ≈ 32px 셀 1개
    //  ANCHORS_PER_GRID : 셀 하나당 예측하는 앵커 박스 개수 (5개)
    //  NUM_CLASSES      : COCO 데이터셋 기준 80개 클래스
    //  VALUES_PER_ANCHOR: 앵커 하나당 출력 값 수 = 4(박스) + 1(객체도) + 80(클래스) = 85
    // ════════════════════════════════════════════════════════════════
    val INPUT_SIZE = 608
    val GRID_SIZE = 19
    val ANCHORS_PER_GRID = 5
    val NUM_CLASSES = 80
    val VALUES_PER_ANCHOR = 5 + NUM_CLASSES // 85

    /**
     * 앵커 박스 크기 (그리드 셀 단위)
     *
     * 각 쌍은 [width, height] (그리드 셀 기준 배율)
     * 예: {9.77, 9.16} → 그리드 셀의 약 10배 크기인 큰 박스
     */
    private val ANCHORS = arrayOf<FloatArray?>(
        floatArrayOf(0.57273f, 0.67738f),  // 아주 작은 박스
        floatArrayOf(1.87446f, 2.06253f),  // 작은 박스
        floatArrayOf(3.33843f, 5.47434f),  // 세로로 긴 박스 (사람 등)
        floatArrayOf(7.88282f, 3.52778f),  // 가로로 넓은 박스 (차량 등)
        floatArrayOf(9.77052f, 9.16828f) // 큰 정사각형 박스
    )
    // 신뢰도 임계값: objectness × class_conf 가 이 값 미만이면 탐지 무시
    private val CONF_THRESHOLD: Float = 0.4f
    // NMS IOU 임계값: 두 박스의 겹침 비율이 이 값 초과면 중복으로 판단해 제거
    private val NMS_THRESHOLD: Float = 0.4f
    // 최종 출력할 최대 탐지 개수
    private val MAX_DETECTIONS: Int = 15

    // ════════════════════════════════════════════════════════════════
    //  전처리: Letterbox 리사이즈
    // ════════════════════════════════════════════════════════════════
    /**
     * 원본 이미지를 608×608 입력 크기에 맞게 변환한다.
     *
     * [Letterbox란?]
     * 단순 stretch 리사이즈가 아닌, 원본 비율을 유지하면서
     * 빈 공간을 128(회색)로 패딩하는 방식.
     * → 가로/세로 비율이 달라도 객체 형태가 왜곡되지 않음
     *
     * 예) 1920×1080 이미지 → scale = 608/1920 ≈ 0.317
     * nw=608, nh=342 → 위아래 (608-342)/2=133px 패딩
     *
     * 출력 형식: uint8 [608×608×3] (R,G,B 순서, 값 범위 0~255)
     * → int8 양자화 모델이지만 입력은 uint8로 받는 경우가 일반적
     */
    fun preprocessLetterbox(bitmap: Bitmap): ByteBuffer {
        val srcW = bitmap.getWidth()
        val srcH = bitmap.getHeight()

        // 원본 비율을 유지하면서 608 안에 들어오는 최대 scale 계산
        val scale = min(INPUT_SIZE.toFloat() / srcW, INPUT_SIZE.toFloat() / srcH)
        val nw = (srcW * scale).toInt()
        val nh = (srcH * scale).toInt()

        // 이미지가 가운데 오도록 오프셋(패딩) 계산
        val ox: Int = (INPUT_SIZE - nw) / 2
        val oy: Int = (INPUT_SIZE - nh) / 2

        // 리사이즈 후 픽셀 배열로 추출
        val resized = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        val pixels = IntArray(nw * nh)
        resized.getPixels(pixels, 0, nw, 0, 0, nw, nh)
        resized.recycle() // 메모리 즉시 해제

        // 모델 입력 버퍼: 608×608×3 바이트 (RGB 채널)
        val buffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())
        buffer.rewind()

        // 608×608 전체를 순회하며 픽셀 채움
        for (y in 0..<INPUT_SIZE) {
            for (x in 0..<INPUT_SIZE) {
                val px = x - ox // 리사이즈 이미지 내 x 좌표
                val py = y - oy // 리사이즈 이미지 내 y 좌표

                if (px >= 0 && px < nw && py >= 0 && py < nh) {
                    // 유효 픽셀: ARGB에서 R, G, B 채널만 추출
                    val pixel = pixels[py * nw + px]
                    buffer.put(((pixel shr 16) and 0xFF).toByte()) // R
                    buffer.put(((pixel shr 8) and 0xFF).toByte()) // G
                    buffer.put((pixel and 0xFF).toByte()) // B
                } else {
                    // 패딩 영역: 중간 회색(128)으로 채움
                    // (0이나 255보다 128이 모델 입력 분포와 자연스럽게 어울림)
                    buffer.put(128.toByte())
                    buffer.put(128.toByte())
                    buffer.put(128.toByte())
                }
            }
        }
        return buffer
    }


    // ════════════════════════════════════════════════════════════════
    //  후처리: YOLO 출력 파싱 + 좌표 변환 + NMS
    // ════════════════════════════════════════════════════════════════
    /**
     * 모델 출력 버퍼(int8)를 파싱해 실제 바운딩 박스 좌표를 계산한다.
     *
     * [YOLO 출력 구조]
     * 총 19×19×5 = 1805개의 앵커 각각에 대해 85개 값:
     * index 0    : tx (박스 x 중심의 셀 내 오프셋, sigmoid 적용 전)
     * index 1    : ty (박스 y 중심의 셀 내 오프셋, sigmoid 적용 전)
     * index 2    : tw (앵커 대비 너비 로그 스케일)
     * index 3    : th (앵커 대비 높이 로그 스케일)
     * index 4    : objectness (이 셀에 객체가 있을 확률, sigmoid 적용 전)
     * index 5~84 : 각 클래스 확률 (sigmoid 적용 전)
     *
     * [좌표 역변환 공식 - YOLOv2]
     * bx = (sigmoid(tx) + col) × cellSize   ← 이미지 픽셀 단위
     * by = (sigmoid(ty) + row) × cellSize
     * bw = anchor_w × exp(tw) × cellSize
     * bh = anchor_h × exp(th) × cellSize
     *
     * @param buffer 모델 출력 (int8 양자화)
     * @param srcW   원본 이미지 너비 (좌표 역변환 기준)
     * @param srcH   원본 이미지 높이
     */
    fun postProcess(
        buffer: ByteBuffer,
        srcW: Int,
        srcH: Int
    ): MutableList<DetectionResult?> {
        val allBoxes: MutableList<DetectionResult> = ArrayList<DetectionResult>()
        buffer.rewind()
        // Letterbox 역변환에 필요한 파라미터
        val scale = min(INPUT_SIZE.toFloat() / srcW, INPUT_SIZE.toFloat() / srcH)
        val ox = (INPUT_SIZE - srcW * scale) / 2.0f
        val oy = (INPUT_SIZE - srcH * scale) / 2.0f
        val cellSize = INPUT_SIZE.toFloat() / GRID_SIZE

        // ── 19×19 그리드 전체 순회 ──────────────────────────────────
        for (row in 0..<GRID_SIZE) {
            for (col in 0..<GRID_SIZE) {
                // 현재 셀(row, col)의 버퍼 시작 인덱스
                val cellBase = (row * GRID_SIZE + col) * (ANCHORS_PER_GRID * VALUES_PER_ANCHOR)

                // ── 셀 내 5개 앵커 각각 처리 ────────────────────────
                for (a in 0..<ANCHORS_PER_GRID) {
                    val base = cellBase + a * VALUES_PER_ANCHOR

                    // ① Objectness(객체 존재 확률) 빠른 필터링
                    // 역양자화 후 sigmoid 적용 → 0~1 범위의 확률값
                    // 0.3 미만이면 나머지 계산 생략 (성능 최적화)
                    val objConf = sigmoid(dequantize(buffer.get(base + 4)))
                    if (objConf < 0.3f) continue

                    // ② 박스 좌표 원시값 읽기 (역양자화만, sigmoid/exp는 아래에서)
                    val tx = dequantize(buffer.get(base))
                    val ty = dequantize(buffer.get(base + 1))
                    val tw = dequantize(buffer.get(base + 2))
                    val th = dequantize(buffer.get(base + 3))

                    // ③ 클래스 확률 중 최댓값 탐색
                    // 80개 클래스 중 가장 높은 확률의 클래스를 선택
                    var maxClassConf = 0f
                    var classIdx = -1
                    for (c in 0..<NUM_CLASSES) {
                        val clConf = sigmoid(dequantize(buffer.get(base + 5 + c)))
                        if (clConf > maxClassConf) {
                            maxClassConf = clConf
                            classIdx = c
                        }
                    }

                    // ④ 최종 신뢰도 = objectness × 클래스 확률
                    // 임계값 미만이면 탐지 결과에서 제외
                    val score = objConf * maxClassConf
                    if (score < CONF_THRESHOLD) continue


                    // ⑤ YOLOv2 좌표 역변환 (모델 출력 → 이미지 픽셀 좌표)
                    //   bx: sigmoid(tx)로 0~1 범위 오프셋을 셀 위치(col)에 더함
                    //   by: 마찬가지로 row에 더함
                    //   bw/bh: 앵커 크기에 exp(tw/th) 배율 적용
                    val bx = (sigmoid(tx) + col) * cellSize
                    val by = (sigmoid(ty) + row) * cellSize
                    val bw = (ANCHORS[a]!![0] * exp(tw.toDouble())).toFloat() * cellSize
                    val bh = (ANCHORS[a]!![1] * exp(th.toDouble())).toFloat() * cellSize

                    // ⑥ Letterbox 역변환: 모델 좌표 → 원본 이미지 좌표
                    //   패딩(ox, oy)를 빼고 scale로 나눔
                    //   Math.max/min으로 이미지 경계를 벗어나지 않도록 클리핑
                    val x1 = max(0f, min((bx - bw / 2f - ox) / scale, srcW.toFloat()))
                    val y1 = max(0f, min((by - bh / 2f - oy) / scale, srcH.toFloat()))
                    val x2 = max(0f, min((bx + bw / 2f - ox) / scale, srcW.toFloat()))
                    val y2 = max(0f, min((by + bh / 2f - oy) / scale, srcH.toFloat()))

                    // ⑦ 유효하지 않은 박스 제거 (너비/높이가 0 이하인 경우)
                    if (x2 <= x1 || y2 <= y1) continue

                    // 좌표를 0~1 정규화된 비율로 저장
                    /*
                    ex.
                    왼쪽 박스: x1=100, x2=300  →  left=0.05, right=0.15
                    오른쪽 박스: x1=1600, x2=1800  →  left=0.83, right=0.93
                    * */
                    allBoxes.add(
                        DetectionResult(
                            RectF(x1 / srcW, y1 / srcH, x2 / srcW, y2 / srcH),
                            score, classIdx
                        )
                    )
                }
            }
        }

        // ⑧ NMS(Non-Maximum Suppression)로 중복 박스 제거 후 반환
        return runPerClassNMS(allBoxes)
    }


    /**
     * 클래스별 NMS(Non-Maximum Suppression) 실행
     *
     * [NMS가 필요한 이유]
     * YOLO는 같은 객체를 여러 앵커/셀에서 중복 탐지할 수 있다.
     * NMS는 같은 클래스의 박스들 중 겹침(IOU)이 심한 것을 제거한다.
     *
     * [클래스별 NMS]
     * 다른 클래스끼리는 비교하지 않음 (사람 박스가 차 박스를 제거하면 안 됨)
     * 같은 클래스 내에서만 중복 제거 수행
     */
    private fun runPerClassNMS(results: MutableList<DetectionResult>): MutableList<DetectionResult?> {
        // 클래스 인덱스별로 탐지 결과 분류
        val byClass: MutableMap<Int?, MutableList<DetectionResult?>> =
            HashMap<Int?, MutableList<DetectionResult?>>()
        for (r in results) {
            if (!byClass.containsKey(r.classIndex)) byClass.put(
                r.classIndex,
                java.util.ArrayList<DetectionResult?>()
            )
            byClass.get(r.classIndex)!!.add(r)
        }

        // 각 클래스별로 NMS 실행 후 결과 합산
        val out: MutableList<DetectionResult?> = java.util.ArrayList<DetectionResult?>()
        for (cls in byClass.values) out.addAll(runNMS(cls))

        // 신뢰도 높은 순으로 정렬 후 최대 개수 제한
        Collections.sort<DetectionResult?>(
            out,
            Comparator { a: DetectionResult?, b: DetectionResult? ->
                java.lang.Float.compare(
                    b!!.score,
                    a!!.score
                )
            })
        return if (out.size > MAX_DETECTIONS) java.util.ArrayList<DetectionResult?>(
            out.subList(
                0,
                MAX_DETECTIONS
            )
        ) else out
    }

    /**
     * 단일 클래스 내 NMS (Greedy NMS)
     *
     * [알고리즘]
     * 1. 신뢰도 내림차순 정렬
     * 2. 가장 높은 박스를 결과에 추가
     * 3. 남은 박스 중 결과 박스와 IOU > NMS_THRESHOLD인 것을 제거(suppressed)
     * 4. 제거되지 않은 박스 중 다음 최고점 박스로 반복
     *
     * 시간복잡도: O(n²) - 탐지 수가 적으므로 실용적
     */
    private fun runNMS(results: MutableList<DetectionResult?>): MutableList<DetectionResult?> {
        Collections.sort<DetectionResult?>(
            results,
            Comparator { a: DetectionResult?, b: DetectionResult? ->
                java.lang.Float.compare(
                    b!!.score,
                    a!!.score
                )
            })
        val sup = BooleanArray(results.size) // true = 억제(제거)된 박스
        val out: MutableList<DetectionResult?> = java.util.ArrayList<DetectionResult?>()

        for (i in results.indices) {
            if (sup[i]) continue  // 이미 제거된 박스는 건너뜀

            out.add(results.get(i)) // 살아남은 박스 결과에 추가

            // 현재 박스와 겹치는 박스들을 억제
            for (j in i + 1..<results.size) {
                if (!sup[j] && iou(
                        results.get(i)!!.boundingBox,
                        results.get(j)!!.boundingBox
                    ) > NMS_THRESHOLD
                ) sup[j] = true
            }
        }
        return out
    }

    /**
     * IOU (Intersection over Union) 계산
     *
     * 두 박스가 얼마나 겹치는지를 0~1로 표현:
     * IOU = 교집합 넓이 / 합집합 넓이
     *
     * IOU = 0   : 전혀 겹치지 않음
     * IOU = 1   : 완전히 같은 박스
     * IOU > 0.4 : 같은 객체를 탐지한 중복 박스로 판단 (NMS_THRESHOLD)
     *
     * @param a 첫 번째 박스 (RectF: left, top, right, bottom)
     * @param b 두 번째 박스
     * @return IOU 값 (0.0 ~ 1.0)
     */
    private fun iou(a: RectF?, b: RectF?): Float {
        // 교집합 영역 계산
        val iL = max(a!!.left, b!!.left)
        val iT = max(a.top, b.top)
        val iR = min(a.right, b.right)
        val iB = min(a.bottom, b.bottom)

        if (iR <= iL || iB <= iT) return 0f // 겹침 없음


        val inter = (iR - iL) * (iB - iT) // 교집합 넓이
        val areaA = (a.right - a.left) * (a.bottom - a.top) // 박스 A 넓이
        val areaB = (b.right - b.left) * (b.bottom - b.top) // 박스 B 넓이

        return inter / (areaA + areaB - inter) // IOU = 교집합 / 합집합
    }

    // ════════════════════════════════════════════════════════════════
    //  진단 로그: 출력 텐서의 objectness 분포 확인
    // ════════════════════════════════════════════════════════════════
    /**
     * 첫 프레임에서만 호출되는 진단 함수.
     * 모델이 올바르게 동작하는지 objectness 값의 분포를 확인한다.
     *
     * 정상 동작 시: 대부분 0에 가까운 낮은 값, 일부만 0.3~0.5 이상
     * 비정상 시: 모든 값이 동일하거나 너무 낮음 → 모델/입력 문제
     */
    fun printDiagnostics(buffer: ByteBuffer) {
        buffer.rewind()
        val total = GRID_SIZE * GRID_SIZE * ANCHORS_PER_GRID
        var above03 = 0
        var above05 = 0
        var minC = 1f
        var maxC = 0f
        var sumC = 0f

        for (i in 0..<total) {
            val conf: Float = sigmoid(dequantize(buffer.get(i * VALUES_PER_ANCHOR + 4)))
            sumC += conf
            if (conf < minC) minC = conf
            if (conf > maxC) maxC = conf
            if (conf > 0.3f) above03++
            if (conf > 0.5f) above05++
        }
        Log.i(TAG,
            ("━━ objConf 분포 ━━ min=" + f(minC) + " max=" + f(maxC)
                    + " avg=" + f(sumC / total) + " | >0.3:" + above03 + " >0.5:" + above05)
        )
        buffer.rewind()
    }


    // ════════════════════════════════════════════════════════════════
    //  유틸리티 함수들
    // ════════════════════════════════════════════════════════════════
    /**
     * 역양자화 (int8 → float)
     *
     * TFLite int8 양자화 모델은 float 값을 다음 공식으로 압축 저장:
     * quantized = round(float / scale) + zero_point
     * 따라서 역변환:
     * float = (quantized - zero_point) × scale
     *
     * @param v 모델이 출력한 int8 바이트 값 (Java에서는 signed byte)
     * @return 원래 float 값 (근사치)
     */
    private fun dequantize(v: Byte): Float {
        return (v - outputZeroPoint) * outputScale
    }

    /**
     * Sigmoid 함수: 임의의 실수 → 0~1 범위 확률로 변환
     *
     * σ(x) = 1 / (1 + e^(-x))
     *
     * YOLO에서 사용 용도:
     * - tx, ty → 셀 내 박스 중심 오프셋을 0~1로 제한 (셀을 벗어나지 않도록)
     * - objectness → 객체 존재 확률
     * - class conf → 각 클래스 확률
     */
    private fun sigmoid(x: Float): Float {
        return (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
    }

    private fun f(v: Float): String? {
        return String.format("%.4f", v)
    }
}