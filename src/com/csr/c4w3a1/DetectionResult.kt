package com.csr.c4w3a1

import android.graphics.RectF

class DetectionResult(@JvmField var boundingBox: RectF?, @JvmField var score: Float, @JvmField var classIndex: Int)