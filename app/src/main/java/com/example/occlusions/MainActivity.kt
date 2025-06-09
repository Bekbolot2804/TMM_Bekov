package com.example.occlusions

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlin.math.roundToInt
import com.example.occlusions.ui.HeadAxesView
import com.google.mlkit.vision.face.FaceLandmark

class MainActivity : AppCompatActivity() {

    private var savedFaceBitmap: Bitmap? = null
    private var savedFaceRect: RectF? = null
    private var savedFaceView: View? = null

    private lateinit var cameraProvider: ProcessCameraProvider
    private var currentCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

    private lateinit var previewView: PreviewView

    private lateinit var overlay: FrameLayout

    private lateinit var anglesTextView: TextView

    private lateinit var headAxesView: HeadAxesView

    // Переменные для отслеживания частоты изменения углов
    private var lastUpdateTime = System.currentTimeMillis()
    private var lastEulerAngles = Triple(0f, 0f, 0f)
    private var updateFrequency = 0f

    private var lastImageWidth = 0
    private var lastImageHeight = 0
    private var lastIsFrontCamera = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        anglesTextView = findViewById(R.id.anglesTextView)
        headAxesView = findViewById(R.id.headAxesView)

        // Запрос разрешения на камеру
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), 123
            )
        }

        // Added for camera switching
        overlay.setOnClickListener {
            currentCameraSelector = if (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            startCamera() // Re-start camera with new selector
        }
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val cameraSelector = currentCameraSelector

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                processImageProxy(imageProxy)
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 123 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            finish()
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        lastImageWidth = mediaImage.width
        lastImageHeight = mediaImage.height
        lastIsFrontCamera = (currentCameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)

        val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        // Распознавание лица
        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.15f)
                .build()
        )

        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                processFaces(faces)
            }
            .addOnFailureListener { e ->
                Log.e("CameraX", "Ошибка распознавания лица", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }

        val poseDetector = PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )
        poseDetector.process(image)
            .addOnSuccessListener { pose ->
                drawHand(pose, image.width, image.height)
            }
            .addOnFailureListener { e ->
                Log.e("CameraX", "Ошибка распознавания позы", e)
            }
    }

    private var faceRect: RectF? = null  // Глобальная переменная для хранения рамки лица

    private fun processFaces(faces: List<Face>) {
        if (faces.isEmpty()) {
            anglesTextView.text = "Лицо не обнаружено"
            return
        }

        val face = faces[0] // Берем первое обнаруженное лицо
        // Получаем углы Эйлера
        val eulerX = face.headEulerAngleX  // Наклон вверх-вниз
        val eulerY = face.headEulerAngleY  // Поворот влево-вправо
        val eulerZ = face.headEulerAngleZ  // Наклон влево-вправо

        // Получаем координаты носа
        val noseLandmark = face.getLandmark(FaceLandmark.NOSE_BASE)
        val nosePosition = noseLandmark?.position
        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()
        val imageWidth = lastImageWidth.toFloat()
        val imageHeight = lastImageHeight.toFloat()

        var centerX = viewWidth / 2f
        var centerY = viewHeight / 2f
        if (nosePosition != null) {
            // Переводим координаты landmark из image в view
            val scale = Math.min(viewWidth / imageWidth, viewHeight / imageHeight)
            val offsetX = (viewWidth - imageWidth * scale) / 2f
            val offsetY = (viewHeight - imageHeight * scale) / 2f
            var x = nosePosition.x * scale + offsetX
            val y = nosePosition.y * scale + offsetY
            // Для фронтальной камеры отражаем по горизонтали
            if (lastIsFrontCamera) {
                x = viewWidth - x
            }
            centerX = x
            centerY = y
        }

        // Обновляем оси в кастомном View (оси будут на носу)
        headAxesView.updateAxes(centerX, centerY, eulerY, eulerX, eulerZ)

        // Вычисляем частоту изменения углов
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - lastUpdateTime
        if (timeDiff > 0) {
            val angleDiff = calculateAngleDifference(
                lastEulerAngles,
                Triple(eulerX, eulerY, eulerZ)
            )
            updateFrequency = (angleDiff / timeDiff) * 1000 // углы в секунду
        }
        lastUpdateTime = currentTime
        lastEulerAngles = Triple(eulerX, eulerY, eulerZ)
        // Обновляем текст с углами и частотой
        val anglesText = """
            Углы Эйлера:
            X (вверх-вниз): ${eulerX.roundToInt()}°
            Y (влево-вправо): ${eulerY.roundToInt()}°
            Z (наклон): ${eulerZ.roundToInt()}°
            Частота изменения: ${updateFrequency.roundToInt()}°/с
        """.trimIndent()
        anglesTextView.text = anglesText
    }

    private fun calculateAngleDifference(
        last: Triple<Float, Float, Float>,
        current: Triple<Float, Float, Float>
    ): Float {
        val diffX = Math.abs(current.first - last.first)
        val diffY = Math.abs(current.second - last.second)
        val diffZ = Math.abs(current.third - last.third)
        return diffX + diffY + diffZ
    }

    private var handOverFaceFrames = 0
    private var noHandFrames = 0
    private var handVisibleFrames = 0
    private var handInvisibleFrames = 0
    private val NO_HAND_THRESHOLD = 5 // Количество кадров без руки для сохранения
    private val SHOW_SAVED_FACE_FRAMES = 10 // Количество кадров для показа сохранённой фотографии после убирания руки

    private fun drawHand(pose: Pose, imageWidth: Int, imageHeight: Int) {
        overlay.post {
            // Удаляем или комментируем код, связанный с handView
            /*
            var handView = overlay.findViewWithTag<View>("handView")
            if (handView == null) {
                handView = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(50, 50)
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                    tag = "handView"
                }
                overlay.addView(handView)
            }
            */

            // Получаем метки для левого и правого указательных пальцев
            val leftHandLandmark = pose.getPoseLandmark(PoseLandmark.LEFT_INDEX)
            val rightHandLandmark = pose.getPoseLandmark(PoseLandmark.RIGHT_INDEX)

            var handDetectedAndOverFace = false

            val detectedLandmarks = listOfNotNull(leftHandLandmark, rightHandLandmark)

            if (detectedLandmarks.isNotEmpty()) {
                for (landmark in detectedLandmarks) {
                    // Этот код будет выполнен для каждой обнаруженной руки (левой и/или правой)
                    // handView будет показывать положение последней обработанной руки из списка
                    val viewWidth = previewView.width.toFloat()
                    val viewHeight = previewView.height.toFloat()

                    val scaleX = viewWidth / imageHeight
                    val scaleY = viewHeight / imageWidth

                    val originalX = landmark.position.x * scaleX
                    val originalY = landmark.position.y * scaleY
                    val mirroredX = viewWidth - originalX

                    /*
                    handView.layoutParams = (handView.layoutParams as FrameLayout.LayoutParams).apply {
                        leftMargin = mirroredX.toInt() - 25
                        topMargin = originalY.toInt() - 25
                    }
                    handView.visibility = View.VISIBLE
                    */

                    val handPoint = PointF(mirroredX, originalY)
                    val handRadius = 100f
                    val handRect = RectF(
                        handPoint.x - handRadius,
                        handPoint.y - handRadius,
                        handPoint.x + handRadius,
                        handPoint.y + handRadius
                    )

                    faceRect?.let { face ->
                        if (RectF.intersects(face, handRect)) {
                            handDetectedAndOverFace = true // Флаг, что хотя бы одна рука над лицом
                        }
                    }
                }
            } else {
                // Ни одна рука не найдена, скрываем handView
                // overlay.findViewWithTag<View>("handView")?.visibility = View.GONE // Тоже комментируем, т.к. handView не создается
            }

            // Обновляем счетчики на основе флага handDetectedAndOverFace
            if (handDetectedAndOverFace) {
                // Рука перекрывает лицо
                handOverFaceFrames++
                noHandFrames = 0
                handVisibleFrames = SHOW_SAVED_FACE_FRAMES
                handInvisibleFrames = 0
            } else {
                // Рука не перекрывает лицо или не обнаружена
                handOverFaceFrames = 0
                noHandFrames++
                handInvisibleFrames++

                if (noHandFrames >= NO_HAND_THRESHOLD) {
                    previewView.bitmap?.let { bitmap ->
                        faceRect?.let { face -> // Убедимся, что faceRect не null
                            val safeLeft = face.left.toInt().coerceAtLeast(0)
                            val safeTop = face.top.toInt().coerceAtLeast(0)
                            val safeWidth = face.width().toInt().coerceAtMost(bitmap.width - safeLeft)
                            val safeHeight = face.height().toInt().coerceAtMost(bitmap.height - safeTop)

                            if (safeWidth > 0 && safeHeight > 0) { // Добавлена проверка на валидность размеров
                                savedFaceBitmap = Bitmap.createBitmap(bitmap, safeLeft, safeTop, safeWidth, safeHeight)
                                savedFaceRect = face

                                savedFaceView?.let { overlay.removeView(it) }
                                savedFaceView = null
                            }
                        }
                    }
                    noHandFrames = 0
                }
            }
            // Остальная логика показа сохраненного лица остается без изменений
            // Показывать сохранённое лицо, если рука только что пропала
            if (handVisibleFrames > 0) {
                if (savedFaceBitmap != null && faceRect != null) { // Убедимся, что есть что и куда рисовать
                    if (savedFaceView == null) { // Создаем, если еще не создан
                        savedFaceView = androidx.appcompat.widget.AppCompatImageView(this).apply {
                            setImageBitmap(savedFaceBitmap)
                            tag = "savedFace"
                            // layoutParams будут установлены ниже, чтобы применялись каждый кадр
                        }
                        overlay.addView(savedFaceView) // Добавляем в overlay только один раз при создании
                    }

                    // Всегда обновляем позицию и размер, если view существует и faceRect доступен
                    savedFaceView?.let { view ->
                        val newLayoutParams = FrameLayout.LayoutParams(faceRect!!.width().toInt(), faceRect!!.height().toInt()).apply {
                            leftMargin = faceRect!!.left.toInt()
                            topMargin = faceRect!!.top.toInt()
                        }
                        // Проверяем, изменились ли параметры, чтобы избежать лишних requestLayout
                        if (view.layoutParams == null || 
                            (view.layoutParams as FrameLayout.LayoutParams).width != newLayoutParams.width ||
                            (view.layoutParams as FrameLayout.LayoutParams).height != newLayoutParams.height ||
                            (view.layoutParams as FrameLayout.LayoutParams).leftMargin != newLayoutParams.leftMargin ||
                            (view.layoutParams as FrameLayout.LayoutParams).topMargin != newLayoutParams.topMargin) {
                            view.layoutParams = newLayoutParams
                            // view.requestLayout() // requestLayout может быть избыточен, если overlay сам перерисовывается.
                                                 // Если лицо все еще не двигается, раскомментировать.
                        }
                    }
                }
                handVisibleFrames--
            } else {
                if (handInvisibleFrames >= SHOW_SAVED_FACE_FRAMES) {
                    savedFaceView?.let {
                        overlay.removeView(it)
                        savedFaceView = null
                    }
                }
            }
        }
    }
}