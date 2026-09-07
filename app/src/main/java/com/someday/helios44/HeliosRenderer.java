package com.someday.helios44;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class HeliosRenderer implements GLSurfaceView.Renderer {
    interface CameraSurfaceListener {
        void onCameraSurfaceReady(SurfaceTexture texture);
    }

    interface SnapshotCallback {
        void onPixels(IntBuffer rgbaPixels, int width, int height);
        void onError(String message);
    }

    interface ErrorListener {
        void onRendererError(String message);
    }

    private static final float[] VERTICES = {
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
    };

    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vUv;\n" +
            "void main(){\n" +
            "  gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
            "  vUv = aTexCoord;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision highp float;\n" +
            "varying vec2 vUv;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "uniform mat4 uStMatrix;\n" +
            "uniform vec2 uCenter;\n" +
            "uniform float uStrength;\n" +
            "uniform float uSharp;\n" +
            "uniform float uVignette;\n" +
            "uniform float uAspect;\n" +
            "uniform float uRotation;\n" +
            "uniform float uMirror;\n" +
            "\n" +
            "vec2 orientUv(vec2 uv){\n" +
            "  vec2 q = uv;\n" +
            "  if (uRotation > 0.5 && uRotation < 1.5) q = vec2(1.0 - uv.y, uv.x);\n" +
            "  else if (uRotation >= 1.5 && uRotation < 2.5) q = vec2(1.0 - uv.x, 1.0 - uv.y);\n" +
            "  else if (uRotation >= 2.5) q = vec2(uv.y, 1.0 - uv.x);\n" +
            "  if (uMirror > 0.5) q.x = 1.0 - q.x;\n" +
            "  return q;\n" +
            "}\n" +
            "\n" +
            "vec4 cameraSample(vec2 uv){\n" +
            "  uv = clamp(uv, vec2(0.003), vec2(0.997));\n" +
            "  vec2 o = orientUv(uv);\n" +
            "  vec4 t = uStMatrix * vec4(o, 0.0, 1.0);\n" +
            "  return texture2D(uTexture, t.xy);\n" +
            "}\n" +
            "\n" +
            "vec2 helioOffset(vec2 uv, float k, float span){\n" +
            "  vec2 p = uv - uCenter;\n" +
            "  vec2 pa = vec2(p.x * uAspect, p.y);\n" +
            "  float radius = max(length(pa), 0.0001);\n" +
            "  vec2 radial = pa / radius;\n" +
            "  vec2 tangent = vec2(-radial.y, radial.x);\n" +
            "  vec2 tangentUv = vec2(tangent.x / max(uAspect, 0.001), tangent.y);\n" +
            "  vec2 radialUv = vec2(radial.x / max(uAspect, 0.001), radial.y);\n" +
            "  float curve = k * k * span * 0.10;\n" +
            "  return uv + tangentUv * (k * span) - radialUv * curve;\n" +
            "}\n" +
            "\n" +
            "void main(){\n" +
            "  vec2 p = vUv - uCenter;\n" +
            "  vec2 pa = vec2(p.x * uAspect, p.y);\n" +
            "  float r = length(pa);\n" +
            "\n" +
            "  vec4 base = cameraSample(vUv);\n" +
            "\n" +
            "  // Protect the tapped subject area and only build the Helios character toward the frame edge.\n" +
            "  float start = uSharp + 0.035;\n" +
            "  float end = min(0.56, uSharp + 0.22);\n" +
            "  float mask = smoothstep(start, end, r);\n" +
            "  float edgeFalloff = smoothstep(0.18, 0.50, r);\n" +
            "  float effect = mask * edgeFalloff;\n" +
            "\n" +
            "  // Short anisotropic arc blur: enough to bend bokeh, not enough to turn objects into streaks.\n" +
            "  float span = (0.00055 + 0.0072 * uStrength) * effect * (0.72 + 0.72 * r);\n" +
            "  vec4 blur = base * 0.46;\n" +
            "  blur += cameraSample(helioOffset(vUv, -1.35, span)) * 0.055;\n" +
            "  blur += cameraSample(helioOffset(vUv, -0.95, span)) * 0.075;\n" +
            "  blur += cameraSample(helioOffset(vUv, -0.60, span)) * 0.090;\n" +
            "  blur += cameraSample(helioOffset(vUv, -0.30, span)) * 0.100;\n" +
            "  blur += cameraSample(helioOffset(vUv,  0.30, span)) * 0.100;\n" +
            "  blur += cameraSample(helioOffset(vUv,  0.60, span)) * 0.090;\n" +
            "  blur += cameraSample(helioOffset(vUv,  0.95, span)) * 0.075;\n" +
            "  blur += cameraSample(helioOffset(vUv,  1.35, span)) * 0.055;\n" +
            "\n" +
            "  // Keep most of the real camera image. The effect should read as optical bokeh, not motion blur.\n" +
            "  float blend = clamp(effect * (0.12 + 0.44 * uStrength), 0.0, 0.54);\n" +
            "  vec3 color = mix(base.rgb, blur.rgb, blend);\n" +
            "\n" +
            "  // Slight highlight bloom toward the outside, where Helios bokeh is most obvious.\n" +
            "  float hi = max(max(blur.r, blur.g), blur.b);\n" +
            "  color += vec3(max(hi - 0.78, 0.0) * 0.032 * uStrength * effect);\n" +
            "\n" +
            "  float vig = smoothstep(0.31, 0.55, r);\n" +
            "  color *= 1.0 - (uVignette * 0.22) * vig * vig;\n" +
            "  gl_FragColor = vec4(color, 1.0);\n" +
            "}\n";

    private final FloatBuffer vertexBuffer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean frameAvailable = new AtomicBoolean(false);
    private final float[] surfaceMatrix = new float[16];

    private CameraSurfaceListener cameraSurfaceListener;
    private ErrorListener errorListener;
    private Runnable frameCallback;
    private SurfaceTexture surfaceTexture;
    private int oesTextureId;
    private int program;
    private int surfaceWidth = 1080;
    private int surfaceHeight = 1920;
    private long latestTimestampNanos;

    private volatile float strength = 0.72f;
    private volatile float sharpRadius = 0.22f;
    private volatile float vignette = 0.28f;
    private volatile float centerX = 0.5f;
    private volatile float centerY = 0.46f;
    private volatile float rotationSteps = 1f;
    private volatile float mirror = 0f;

    private SnapshotCallback pendingSnapshot;
    private EglRecorderSurface recorderSurface;
    private int recorderWidth;
    private int recorderHeight;
    private boolean recorderEnabled;
    private long lastRecorderTimestampNanos;

    HeliosRenderer() {
        ByteBuffer bytes = ByteBuffer.allocateDirect(VERTICES.length * 4).order(ByteOrder.nativeOrder());
        vertexBuffer = bytes.asFloatBuffer();
        vertexBuffer.put(VERTICES).position(0);
        for (int i = 0; i < 16; i++) surfaceMatrix[i] = (i % 5 == 0) ? 1f : 0f;
    }

    void setCameraSurfaceListener(CameraSurfaceListener listener) { this.cameraSurfaceListener = listener; }
    void setErrorListener(ErrorListener listener) { this.errorListener = listener; }
    void setFrameCallback(Runnable callback) { this.frameCallback = callback; }

    void setStrength(int value) { strength = clamp(value / 100f, 0f, 1f); }
    void setVignette(int value) { vignette = clamp(value / 100f, 0f, 1f); }
    void setSharpZone(int value) {
        float t = clamp(value / 100f, 0f, 1f);
        sharpRadius = 0.08f + t * 0.44f;
    }

    void setCenter(float x, float y) {
        centerX = clamp(x, 0.05f, 0.95f);
        centerY = clamp(y, 0.05f, 0.95f);
    }

    void setCameraOrientation(int sensorOrientation, boolean isFront) {
        int normalized = ((sensorOrientation % 360) + 360) % 360;
        int sensorSteps = (normalized / 90) % 4;
        // We transform texture lookup coordinates, so use the inverse of the sensor rotation.
        rotationSteps = (4 - sensorSteps) % 4;
        mirror = isFront ? 1f : 0f;
    }

    void requestSnapshot(SnapshotCallback callback) {
        pendingSnapshot = callback;
    }

    void attachRecorderSurface(Surface inputSurface, int width, int height, Runnable onReady, Runnable onError) {
        try {
            if (recorderSurface != null) recorderSurface.release();
            recorderSurface = new EglRecorderSurface(inputSurface);
            recorderWidth = width;
            recorderHeight = height;
            recorderEnabled = false;
            lastRecorderTimestampNanos = 0L;
            if (onReady != null) mainHandler.post(onReady);
        } catch (Throwable t) {
            recorderSurface = null;
            reportError("Video surface: " + t.getMessage());
            if (onError != null) mainHandler.post(onError);
        }
    }

    void setRecorderEnabled(boolean enabled) {
        recorderEnabled = enabled && recorderSurface != null;
    }

    void detachRecorderSurface(Runnable onDone) {
        recorderEnabled = false;
        if (recorderSurface != null) {
            try { recorderSurface.release(); } catch (Throwable ignored) {}
            recorderSurface = null;
        }
        if (onDone != null) mainHandler.post(onDone);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        try {
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            oesTextureId = createExternalTexture();
            surfaceTexture = new SurfaceTexture(oesTextureId);
            surfaceTexture.setOnFrameAvailableListener(texture -> {
                frameAvailable.set(true);
                Runnable cb = frameCallback;
                if (cb != null) cb.run();
            }, mainHandler);
            CameraSurfaceListener listener = cameraSurfaceListener;
            if (listener != null) mainHandler.post(() -> listener.onCameraSurfaceReady(surfaceTexture));
            GLES20.glClearColor(0f, 0f, 0f, 1f);
        } catch (Throwable t) {
            reportError("OpenGL setup: " + t.getMessage());
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceWidth = Math.max(1, width);
        surfaceHeight = Math.max(1, height);
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (program == 0 || surfaceTexture == null) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }

        try {
            if (frameAvailable.getAndSet(false)) {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(surfaceMatrix);
                latestTimestampNanos = surfaceTexture.getTimestamp();
            }

            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            drawFrame(surfaceWidth, surfaceHeight);

            SnapshotCallback snapshot = pendingSnapshot;
            if (snapshot != null) {
                pendingSnapshot = null;
                readSnapshot(snapshot, surfaceWidth, surfaceHeight);
            }

            if (recorderEnabled && recorderSurface != null) {
                recorderSurface.makeCurrent();
                GLES20.glViewport(0, 0, recorderWidth, recorderHeight);
                drawFrame(recorderWidth, recorderHeight);
                long pts = latestTimestampNanos > 0 ? latestTimestampNanos : System.nanoTime();
                if (pts <= lastRecorderTimestampNanos) pts = lastRecorderTimestampNanos + 1_000_000L;
                lastRecorderTimestampNanos = pts;
                recorderSurface.setPresentationTime(pts);
                recorderSurface.swapBuffers();
                recorderSurface.restore();
                GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
            }
        } catch (Throwable t) {
            reportError("Render: " + t.getMessage());
        }
    }

    private void drawFrame(int width, int height) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        int pos = GLES20.glGetAttribLocation(program, "aPosition");
        int tex = GLES20.glGetAttribLocation(program, "aTexCoord");
        int stride = 4 * 4;
        vertexBuffer.position(0);
        GLES20.glEnableVertexAttribArray(pos);
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer);
        vertexBuffer.position(2);
        GLES20.glEnableVertexAttribArray(tex);
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uStMatrix"), 1, false, surfaceMatrix, 0);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uCenter"), centerX, centerY);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uStrength"), strength);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSharp"), sharpRadius);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uVignette"), vignette);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uAspect"), width / (float) height);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uRotation"), rotationSteps);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uMirror"), mirror);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pos);
        GLES20.glDisableVertexAttribArray(tex);
    }

    private void readSnapshot(SnapshotCallback callback, int width, int height) {
        try {
            ByteBuffer bytes = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
            IntBuffer pixels = bytes.asIntBuffer();
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            pixels.position(0);
            callback.onPixels(pixels, width, height);
        } catch (Throwable t) {
            callback.onError(t.getMessage() == null ? "Could not capture frame" : t.getMessage());
        }
    }

    private int createExternalTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int id = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return id;
    }

    private int buildProgram(String vertex, String fragment) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("Shader link failed: " + log);
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return p;
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return shader;
    }

    private void reportError(String message) {
        ErrorListener listener = errorListener;
        if (listener != null) mainHandler.post(() -> listener.onRendererError(message));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
