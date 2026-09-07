package com.someday.helios44;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class CameraController {
    interface Listener {
        void onCameraInfo(int sensorOrientation, boolean front, String description);
        void onCameraError(String message);
    }

    private final CameraManager cameraManager;
    private final Listener listener;
    private final HandlerThread cameraThread;
    private final Handler cameraHandler;

    private SurfaceTexture surfaceTexture;
    private Surface cameraSurface;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private int desiredFacing = CameraCharacteristics.LENS_FACING_BACK;
    private boolean shouldBeOpen;

    CameraController(Context context, Listener listener) {
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.listener = listener;
        cameraThread = new HandlerThread("HeliosCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    void setSurfaceTexture(SurfaceTexture texture) {
        surfaceTexture = texture;
        if (shouldBeOpen) open();
    }

    void start() {
        shouldBeOpen = true;
        open();
    }

    void stop() {
        shouldBeOpen = false;
        cameraHandler.post(this::closeInternal);
    }

    void release() {
        shouldBeOpen = false;
        cameraHandler.post(() -> {
            closeInternal();
            cameraThread.quitSafely();
        });
    }

    void switchCamera() {
        desiredFacing = desiredFacing == CameraCharacteristics.LENS_FACING_BACK
                ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        cameraHandler.post(() -> {
            closeInternal();
            if (shouldBeOpen) openInternal();
        });
    }

    @SuppressLint("MissingPermission")
    private void open() {
        cameraHandler.post(this::openInternal);
    }

    @SuppressLint("MissingPermission")
    private void openInternal() {
        if (!shouldBeOpen || surfaceTexture == null || cameraDevice != null) return;
        try {
            String cameraId = chooseCameraId(desiredFacing);
            if (cameraId == null) {
                postError("No suitable camera found");
                return;
            }

            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            Integer orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            boolean isFront = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            int sensorOrientation = orientation == null ? (isFront ? 270 : 90) : orientation;

            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size previewSize = choosePreviewSize(map == null ? null : map.getOutputSizes(SurfaceTexture.class));
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            String description = (isFront ? "Front" : "Back") + " camera • " +
                    previewSize.getWidth() + "×" + previewSize.getHeight();
            listener.onCameraInfo(sensorOrientation, isFront, description);

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    if (!shouldBeOpen) {
                        camera.close();
                        return;
                    }
                    cameraDevice = camera;
                    createSession(chars);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    if (cameraDevice == camera) cameraDevice = null;
                    postError("Camera disconnected");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    if (cameraDevice == camera) cameraDevice = null;
                    postError("Camera error " + error);
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            postError("Could not open camera: " + e.getMessage());
        }
    }

    private void createSession(CameraCharacteristics chars) {
        CameraDevice camera = cameraDevice;
        if (camera == null || surfaceTexture == null) return;
        try {
            if (cameraSurface != null) cameraSurface.release();
            cameraSurface = new Surface(surfaceTexture);
            List<Surface> outputs = new ArrayList<>();
            outputs.add(cameraSurface);

            camera.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    if (cameraDevice == null || !shouldBeOpen) {
                        session.close();
                        return;
                    }
                    captureSession = session;
                    try {
                        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        builder.addTarget(cameraSurface);
                        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        applyAutoFocus(builder, chars);
                        applyStabilization(builder, chars);
                        session.setRepeatingRequest(builder.build(), null, cameraHandler);
                    } catch (CameraAccessException e) {
                        postError("Could not start camera preview: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    postError("Camera preview configuration failed");
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            postError("Could not configure camera: " + e.getMessage());
        }
    }

    private void applyAutoFocus(CaptureRequest.Builder builder, CameraCharacteristics chars) {
        int[] modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (contains(modes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        } else if (contains(modes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
    }

    private void applyStabilization(CaptureRequest.Builder builder, CameraCharacteristics chars) {
        int[] videoModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        if (contains(videoModes, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        }
    }

    private String chooseCameraId(int facingWanted) throws CameraAccessException {
        String fallback = null;
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (fallback == null) fallback = id;
            if (facing != null && facing == facingWanted) return id;
        }
        return fallback;
    }

    private static Size choosePreviewSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1920, 1080);
        for (Size size : sizes) {
            if (size.getWidth() == 1920 && size.getHeight() == 1080) return size;
        }
        return Arrays.stream(sizes)
                .filter(s -> s.getWidth() <= 1920 && s.getHeight() <= 1080)
                .filter(s -> Math.abs((s.getWidth() / (float) s.getHeight()) - (16f / 9f)) < 0.04f)
                .max(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()))
                .orElseGet(() -> Arrays.stream(sizes)
                        .min(Comparator.comparingLong(s -> Math.abs((long) s.getWidth() * s.getHeight() - 1920L * 1080L)))
                        .orElse(sizes[0]));
    }

    private void closeInternal() {
        if (captureSession != null) {
            try { captureSession.stopRepeating(); } catch (Exception ignored) {}
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (cameraSurface != null) {
            cameraSurface.release();
            cameraSurface = null;
        }
    }

    private void postError(String message) {
        listener.onCameraError(message);
    }

    private static boolean contains(int[] values, int value) {
        if (values == null) return false;
        for (int v : values) if (v == value) return true;
        return false;
    }
}
