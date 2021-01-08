package xyz.juncat.jccam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.widget.Button
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import xyz.juncat.jccam.camera.CameraGLSurfaceView
import java.util.*
import java.util.concurrent.Executors
import kotlin.collections.ArrayList


class MainActivity : AppCompatActivity() {

    private lateinit var cameraView: CameraGLSurfaceView
    private lateinit var btnCapture: Button
    private var cameraDevice: CameraDevice? = null
    private var previewSize = Size(1080, 1920)

    //1) get camera manager
    private val cameraManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val workerThreader = object : HandlerThread("") {
        lateinit var handler: Handler
        override fun start() {
            super.start()
            handler = Handler(looper)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraView = findViewById(R.id.gl_surface_view)
        btnCapture = findViewById(R.id.btn_capture)
        btnCapture.setOnClickListener {

        }
        workerThreader.start()
        cameraView.callback = object : CameraGLSurfaceView.Callback {
            override fun onSurfaceCreated(surfaceTexture: SurfaceTexture) {
                Log.i(TAG, "onSurfaceCreated: ")
//                surfaceTexture.setDefaultBufferSize(1080, 1920)
                initCamera()
                cameraDevice?.let {
                    previewCamera(it, surfaceTexture)
                }
            }

            override fun onSurfaceDestroy() {
            }

        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    private fun initCamera() {
        //2) get camera info
        if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        for (cid in cameraManager.cameraIdList) {
            val params = cameraManager.getCameraCharacteristics(cid)
            if (params != null && params.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                openCamera(cid)
                params.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.let { config ->
                    config.getOutputSizes(SurfaceTexture::class.java)
                }?.let { sizeArray ->
                    Log.i(TAG, "initCamera: ${cameraView.width}, ${cameraView.height}")
                    previewSize = getOptimalSize(sizeArray, cameraView.width, cameraView.height)
                    sizeArray.forEach {
                        Log.i(TAG, "Size w:h = ${it.width}:${it.height} ")
                    }
                }
                break
            }
        }

    }

    private fun previewCamera(camera: CameraDevice, surfaceTexture: SurfaceTexture) {
        //5)
        val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        val surface = Surface(surfaceTexture)
        surfaceTexture.setDefaultBufferSize(previewSize.height, previewSize.width)
        Log.i(TAG, "previewCamera: ${previewSize.width},${previewSize.height}")
        previewRequest.addTarget(surface)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            camera.createCaptureSession(
                    SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            listOf(OutputConfiguration(surface)),
                            Executors.newSingleThreadExecutor(),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    Log.i(TAG, "onConfigured: ")
                                    previewRequest.set(
                                            CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                    )
                                    previewRequest.set(
                                            CaptureRequest.CONTROL_AE_MODE,
                                            CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                                    )
                                    try {
                                        session.setRepeatingRequest(
                                                previewRequest.build(),
                                                null,
                                                workerThreader.handler
                                        )
                                    } catch (e: Exception) {
                                        Log.i(TAG, "onConfigured: ${e.message}")
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.i(TAG, "onConfigureFailed: ")
                                }

                            }
                    )
            )
        } else {
            camera.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            previewRequest.set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            try {
                                session.setRepeatingRequest(
                                        previewRequest.build(),
                                        null,
                                        workerThreader.handler
                                )
                            } catch (e: Exception) {
                                Log.i(TAG, "onConfigured: ${e.message}")
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                        }

                    },
                    workerThreader.handler
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(cid: String) {
        cameraManager.openCamera(cid, object : CameraDevice.StateCallback() {
            @RequiresApi(Build.VERSION_CODES.P)
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                Log.i(TAG, "onOpened: ")
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
                Log.i(TAG, "onDisconnected: ")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                Log.i(TAG, "onError: ")
            }

        }, workerThreader.handler)
    }

    //选择sizeMap中大于并且最接近width和height的size
    private fun getOptimalSize(sizeMap: Array<Size>, width: Int, height: Int): Size {
        val sizeList: MutableList<Size> = ArrayList()
        for (option in sizeMap) {
            if (width > height) {
                if (option.width > width && option.height > height) {
                    sizeList.add(option)
                }
            } else {
                if (option.width > height && option.height > width) {
                    sizeList.add(option)
                }
            }
        }
        return if (sizeList.size > 0) {
            Collections.min(sizeList) { lhs, rhs -> java.lang.Long.signum((lhs.width * lhs.height - rhs.width * rhs.height).toLong()) }
        } else sizeMap[0]
    }

    companion object {
        const val TAG = "MainActivity"
    }
}