package com.csr.c4w3a2;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 세그멘테이션 마스크를 영상 위에 반투명 오버레이로 그리는 커스텀 View.
 *
 * activity_main.xml에서 PlayerView와 같은 크기로 겹쳐 배치한다.
 * PlayerView가 영상을 letterbox 처리하는 경우, 마스크도 동일한 영역에
 * 정확히 맞게 스케일/오프셋 변환을 적용해 그린다.
 *
 * 레이아웃 예시:
 *   <FrameLayout>
 *       <androidx.media3.ui.PlayerView android:id="@+id/player_view" ... />
 *       <com.example.coursera.ui.common.OverlayView android:id="@+id/overlay_view"
 *           android:layout_width="match_parent"
 *           android:layout_height="match_parent" />
 *   </FrameLayout>
 */
public class OverlayView extends View {

    // 그릴 세그멘테이션 마스크 Bitmap (128×96, ARGB_8888)
    // null이면 onDraw()에서 아무것도 그리지 않음
    private Bitmap maskBitmap = null;

    /**
     * Paint 설정:
     * FILTER_BITMAP_FLAG: Bitmap 스케일 시 bilinear filtering 적용.
     * 마스크를 128×96에서 화면 크기로 확대할 때 계단 현상을 줄여줌.
     */
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // View XML 인플레이션을 위한 생성자들
    public OverlayView(Context context) { super(context); }
    public OverlayView(Context context, AttributeSet attrs) { super(context, attrs); }
    public OverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * 새 세그멘테이션 마스크를 설정하고 화면을 갱신한다.
     *
     * MainViewModel의 mask LiveData 콜백(MainActivity)에서 호출됨.
     * postInvalidate()를 사용하는 이유:
     * - 추론 결과는 백그라운드 스레드에서 오지만,
     *   View 갱신(invalidate)은 메인 스레드에서만 가능함.
     * - postInvalidate()는 메인 스레드에 invalidate 요청을 안전하게 전달함.
     *
     * @param mask 새 마스크 Bitmap
     */
    public void setMask(Bitmap mask) {
        this.maskBitmap = mask;
        postInvalidate();
    }



    /**
     * 마스크를 영상 영역에 정확히 맞게 변환해 Canvas에 그린다.
     *
     * PlayerView는 영상을 fit-center 방식으로 표시한다.
     * 즉, 영상 비율을 유지하면서 View 안에 최대한 크게 표시하고
     * 남는 공간은 검은색(letterbox)으로 채운다.
     *
     * 마스크도 동일한 letterbox 영역에 그려야 영상과 정확히 겹친다.
     * → 영상과 View의 aspect ratio를 비교해 실제 그려지는 영역(drawW, drawH)과
     *   오프셋(offsetX, offsetY)을 계산한 후 Matrix로 변환 적용.
     *
     * 변환 순서:
     *  1. postScale: 마스크(128×96) → 실제 그려지는 영상 영역 크기로 확대
     *  2. postTranslate: letterbox offset만큼 이동
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (maskBitmap == null) return; // 마스크 없으면 그리지 않음

        int viewW = getWidth();

        // 영상 비율: videoWidth/Height가 설정되지 않으면 마스크 비율 사용
        float videoAspect = (float) maskBitmap.getWidth() / maskBitmap.getHeight();

        float drawW, drawH, offsetX, offsetY;

            drawW   = viewW;
            drawH   = viewW / videoAspect;
            offsetX = 0;
            offsetY = 0;


        // Matrix로 마스크 변환:
        // 1. 마스크를 영상 그려지는 영역 크기(drawW×drawH)로 스케일
        // 2. letterbox offset(offsetX, offsetY)만큼 평행 이동
        Matrix matrix = new Matrix();
        matrix.postScale(drawW / maskBitmap.getWidth(), drawH / maskBitmap.getHeight());
        matrix.postTranslate(offsetX, offsetY);

        // 변환 적용해 마스크 그리기 (FILTER_BITMAP_FLAG로 부드럽게 확대)
        canvas.drawBitmap(maskBitmap, matrix, paint);
    }
}