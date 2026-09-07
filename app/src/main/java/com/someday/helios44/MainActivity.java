package com.someday.helios44;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.IntBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements CameraController.Listener {
    private static final int REQUEST_PERMISSIONS = 44;
    private static final int VIDEO_WIDTH = 1080;
    private static final int VIDEO_HEIGHT = 1920;

    private GLSurfaceView glView;
    private HeliosRenderer renderer;
    private CameraController cameraController;
    private FocusOverlayView focusOverlay;
    private TextView statusText;
    private Button photoModeButton;
    private Button videoModeButton;
    private Button shutterButton;

    private boolean glSurfaceReady;
    private boolean resumed;
    private boolean videoMode;
    private boolean recording;
    private boolean startingRecording;

    private MediaRecorder mediaRecorder;
    private Surface recorderInputSurface;
    private ParcelFileDescriptor videoFileDescriptor;
    private Uri videoUri;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        renderer = new HeliosRenderer();
        renderer.setStrength(72);
        renderer.setSharpZone(32);
        renderer.setVignette(28);
        renderer.setErrorListener(message -> setStatus(message));
        renderer.setCameraSurfaceListener(texture -> {
            glSurfaceReady = true;
            cameraController.setSurfaceTexture(texture);
            maybeStartCamera();
        });

        cameraController = new CameraController(this, this);
        buildUi();

        renderer.setFrameCallback(() -> {
            if (glView != null) glView.requestRender();
        });

        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSIONS);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        AspectRatioFrameLayout previewHolder = new AspectRatioFrameLayout(this);
        FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(previewHolder, previewParams);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(new RecordableConfigChooser());
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        glView.getHolder().setFixedSize(VIDEO_WIDTH, VIDEO_HEIGHT);
        previewHolder.addView(glView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        focusOverlay = new FocusOverlayView(this);
        previewHolder.addView(focusOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        glView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float x = clamp(event.getX() / Math.max(1f, v.getWidth()), 0f, 1f);
                float yTop = clamp(event.getY() / Math.max(1f, v.getHeight()), 0f, 1f);
                renderer.setCenter(x, 1f - yTop);
                focusOverlay.setPoint(event.getX(), event.getY());
                setStatus("Sharp zone moved");
                glView.requestRender();
                return true;
            }
            return true;
        });

        addHeader(root);
        addControls(root);
    }

    private void addHeader(FrameLayout root) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(10), dp(12), dp(10));
        header.setBackgroundColor(0x77000000);

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("HELIOS 44-2", 18, true);
        statusText = text("Tap the frame to place the sharp zone", 12, false);
        statusText.setTextColor(0xFFCFCFCF);
        titleStack.addView(title);
        titleStack.addView(statusText);
        header.addView(titleStack, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button flip = button("⇄");
        flip.setContentDescription("Switch front or back camera");
        flip.setOnClickListener(v -> {
            if (recording || startingRecording) return;
            cameraController.switchCamera();
            setStatus("Switching camera…");
        });
        header.addView(flip, new LinearLayout.LayoutParams(dp(56), dp(48)));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(header, params);
    }

    private void addControls(FrameLayout root) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(14));
        panel.setBackgroundColor(0xD9000000);

        panel.addView(sliderRow("Swirl", 72, value -> {
            renderer.setStrength(value);
            glView.requestRender();
        }));
        panel.addView(sliderRow("Sharp zone", 32, value -> {
            renderer.setSharpZone(value);
            glView.requestRender();
        }));
        panel.addView(sliderRow("Vignette", 28, value -> {
            renderer.setVignette(value);
            glView.requestRender();
        }));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, dp(6), 0, 0);

        photoModeButton = button("PHOTO");
        videoModeButton = button("VIDEO");
        shutterButton = button("●");
        shutterButton.setTextSize(32);
        shutterButton.setContentDescription("Take photo");

        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(0, dp(54), 1f);
        modeParams.setMargins(dp(3), 0, dp(3), 0);
        actions.addView(photoModeButton, modeParams);
        LinearLayout.LayoutParams shutterParams = new LinearLayout.LayoutParams(dp(74), dp(74));
        shutterParams.setMargins(dp(10), 0, dp(10), 0);
        actions.addView(shutterButton, shutterParams);
        actions.addView(videoModeButton, modeParams);
        panel.addView(actions);

        photoModeButton.setOnClickListener(v -> {
            if (!recording && !startingRecording) selectVideoMode(false);
        });
        videoModeButton.setOnClickListener(v -> {
            if (!recording && !startingRecording) selectVideoMode(true);
        });
        shutterButton.setOnClickListener(v -> {
            if (videoMode) {
                if (recording || startingRecording) stopVideo(); else startVideo();
            } else {
                takePhoto();
            }
        });
        selectVideoMode(false);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(panel, params);
    }

    private View sliderRow(String label, int initial, ValueListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(1), 0, dp(1));

        TextView name = text(label, 13, false);
        name.setTextColor(0xFFE8E8E8);
        row.addView(name, new LinearLayout.LayoutParams(dp(92), dp(44)));
        name.setGravity(Gravity.CENTER_VERTICAL);

        SeekBar seek = new SeekBar(this);
        seek.setMax(100);
        seek.setProgress(initial);
        row.addView(seek, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView value = text(String.valueOf(initial), 13, false);
        value.setGravity(Gravity.CENTER);
        row.addView(value, new LinearLayout.LayoutParams(dp(40), dp(44)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(String.valueOf(progress));
                listener.onValue(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        return row;
    }

    private void selectVideoMode(boolean video) {
        videoMode = video;
        photoModeButton.setAlpha(video ? 0.55f : 1f);
        videoModeButton.setAlpha(video ? 1f : 0.55f);
        shutterButton.setContentDescription(video ? "Start video recording" : "Take photo");
        updateShutterStyle();
        setStatus(video ? "Video • effect is baked into recording" : "Photo • tap the frame to place the sharp zone");
    }

    private void updateShutterStyle() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (recording || startingRecording) {
            bg.setColor(0xFFE43D3D);
            bg.setStroke(dp(4), Color.WHITE);
            shutterButton.setText("■");
        } else if (videoMode) {
            bg.setColor(0xFFE43D3D);
            bg.setStroke(dp(5), Color.WHITE);
            shutterButton.setText("●");
        } else {
            bg.setColor(Color.WHITE);
            bg.setStroke(dp(5), 0xFFBDBDBD);
            shutterButton.setTextColor(Color.BLACK);
            shutterButton.setText("●");
        }
        if (videoMode) shutterButton.setTextColor(Color.WHITE);
        shutterButton.setBackground(bg);
    }

    private void takePhoto() {
        if (!hasCameraPermission() || !glSurfaceReady) {
            setStatus("Camera is not ready yet");
            return;
        }
        setStatus("Capturing Helios frame…");
        glView.queueEvent(() -> renderer.requestSnapshot(new HeliosRenderer.SnapshotCallback() {
            @Override
            public void onPixels(IntBuffer rgbaPixels, int width, int height) {
                ioExecutor.execute(() -> savePhoto(rgbaPixels, width, height));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> setStatus("Photo failed: " + message));
            }
        }));
        glView.requestRender();
    }

    private void savePhoto(IntBuffer rgbaPixels, int width, int height) {
        Bitmap bitmap = null;
        try {
            int[] source = new int[width * height];
            int[] argb = new int[width * height];
            rgbaPixels.position(0);
            rgbaPixels.get(source);

            for (int y = 0; y < height; y++) {
                int srcRow = (height - 1 - y) * width;
                int dstRow = y * width;
                for (int x = 0; x < width; x++) {
                    int rgba = source[srcRow + x];
                    int r = rgba & 0xFF;
                    int g = (rgba >> 8) & 0xFF;
                    int b = (rgba >> 16) & 0xFF;
                    int a = (rgba >>> 24) & 0xFF;
                    argb[dstRow + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }

            bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
            String name = "HEL44_" + timestamp() + ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Helios44");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);

            ContentResolver resolver = getContentResolver();
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Could not create photo in MediaStore");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                    resolver.delete(uri, null, null);
                    throw new IllegalStateException("Could not write JPEG");
                }
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, ready, null, null);
            runOnUiThread(() -> {
                setStatus("Saved to Pictures/Helios44");
                Toast.makeText(this, "Helios photo saved", Toast.LENGTH_SHORT).show();
            });
        } catch (Throwable t) {
            runOnUiThread(() -> setStatus("Photo failed: " + safeMessage(t)));
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private void startVideo() {
        if (!hasCameraPermission() || !glSurfaceReady || recording || startingRecording) {
            setStatus("Camera is not ready yet");
            return;
        }
        startingRecording = true;
        updateShutterStyle();
        setStatus("Preparing video…");

        try {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "HEL44_" + timestamp() + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Helios44");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (videoUri == null) throw new IllegalStateException("Could not create video in MediaStore");
            videoFileDescriptor = resolver.openFileDescriptor(videoUri, "w");
            if (videoFileDescriptor == null) throw new IllegalStateException("Could not open video output");

            boolean audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            MediaRecorder recorder = new MediaRecorder();
            if (audio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(12_000_000);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (audio) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioChannels(1);
                recorder.setAudioSamplingRate(48_000);
                recorder.setAudioEncodingBitRate(128_000);
            }
            recorder.setOutputFile(videoFileDescriptor.getFileDescriptor());
            recorder.setOrientationHint(0);
            recorder.prepare();

            mediaRecorder = recorder;
            recorderInputSurface = recorder.getSurface();
            Surface input = recorderInputSurface;
            glView.queueEvent(() -> renderer.attachRecorderSurface(input, VIDEO_WIDTH, VIDEO_HEIGHT, () -> {
                if (!startingRecording || mediaRecorder != recorder) return;
                try {
                    recorder.start();
                    recording = true;
                    startingRecording = false;
                    glView.queueEvent(() -> renderer.setRecorderEnabled(true));
                    setStatus("REC • Helios effect is being recorded");
                    shutterButton.setContentDescription("Stop video recording");
                    updateShutterStyle();
                } catch (Throwable t) {
                    glView.queueEvent(() -> renderer.detachRecorderSurface(() ->
                            failVideo("Could not start video: " + safeMessage(t))));
                }
            }, () -> failVideo("Could not attach the video encoder")));
        } catch (Throwable t) {
            failVideo("Video failed: " + safeMessage(t));
        }
    }

    private void stopVideo() {
        if (!recording && !startingRecording) return;
        startingRecording = false;
        recording = false;
        updateShutterStyle();
        setStatus("Finishing video…");
        glView.queueEvent(() -> renderer.detachRecorderSurface(() -> finishVideoRecorder(true)));
    }

    private void finishVideoRecorder(boolean keep) {
        MediaRecorder recorder = mediaRecorder;
        mediaRecorder = null;
        boolean success = keep;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Throwable t) {
                success = false;
            }
            try { recorder.reset(); } catch (Throwable ignored) {}
            try { recorder.release(); } catch (Throwable ignored) {}
        }
        if (recorderInputSurface != null) {
            try { recorderInputSurface.release(); } catch (Throwable ignored) {}
            recorderInputSurface = null;
        }
        if (videoFileDescriptor != null) {
            try { videoFileDescriptor.close(); } catch (Throwable ignored) {}
            videoFileDescriptor = null;
        }

        Uri uri = videoUri;
        videoUri = null;
        if (uri != null) {
            if (success) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(uri, ready, null, null);
                setStatus("Saved to Movies/Helios44");
                Toast.makeText(this, "Helios video saved", Toast.LENGTH_SHORT).show();
            } else {
                getContentResolver().delete(uri, null, null);
                setStatus("Video was not saved");
            }
        }
        recording = false;
        startingRecording = false;
        shutterButton.setContentDescription("Start video recording");
        updateShutterStyle();
    }

    private void failVideo(String message) {
        startingRecording = false;
        recording = false;
        try {
            if (mediaRecorder != null) {
                mediaRecorder.reset();
                mediaRecorder.release();
            }
        } catch (Throwable ignored) {}
        mediaRecorder = null;
        if (recorderInputSurface != null) {
            try { recorderInputSurface.release(); } catch (Throwable ignored) {}
            recorderInputSurface = null;
        }
        if (videoFileDescriptor != null) {
            try { videoFileDescriptor.close(); } catch (Throwable ignored) {}
            videoFileDescriptor = null;
        }
        if (videoUri != null) {
            try { getContentResolver().delete(videoUri, null, null); } catch (Throwable ignored) {}
            videoUri = null;
        }
        setStatus(message);
        updateShutterStyle();
    }

    private void maybeStartCamera() {
        if (resumed && glSurfaceReady && hasCameraPermission()) cameraController.start();
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        glView.onResume();
        maybeStartCamera();
    }

    @Override
    protected void onPause() {
        if (recording || startingRecording) stopVideo();
        cameraController.stop();
        resumed = false;
        glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cameraController.release();
        ioExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasCameraPermission()) {
                maybeStartCamera();
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    setStatus("Camera ready • video will record without audio");
                }
            } else {
                setStatus("Camera permission is required");
            }
        }
    }

    @Override
    public void onCameraInfo(int sensorOrientation, boolean front, String description) {
        renderer.setCameraOrientation(sensorOrientation, front);
        runOnUiThread(() -> setStatus(description + " • Helios live"));
    }

    @Override
    public void onCameraError(String message) {
        runOnUiThread(() -> setStatus(message));
    }

    private void setStatus(String message) {
        if (statusText != null) statusText.setText(message);
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x55000000);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0x66FFFFFF);
        b.setBackground(bg);
        return b;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private interface ValueListener {
        void onValue(int value);
    }
}
