package com.peyo.yolocam

import android.graphics.RectF

class DetectionResult(@JvmField var boundingBox: RectF?, @JvmField var score: Float, @JvmField var classIndex: Int)