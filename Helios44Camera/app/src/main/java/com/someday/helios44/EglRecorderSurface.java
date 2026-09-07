package com.someday.helios44;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLDisplay;
import android.opengl.EGLContext;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.view.Surface;

/**
 * Extra EGL window surface sharing the GLSurfaceView's current context.
 * Must be created, used, and released only on the GLSurfaceView render thread.
 */
final class EglRecorderSurface {
    private final EGLDisplay display;
    private final EGLSurface windowSurface;
    private final EGLContext context;
    private final EGLSurface restoreDraw;
    private final EGLSurface restoreRead;

    EglRecorderSurface(Surface surface) {
        display = EGL14.eglGetCurrentDisplay();
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("No current EGL display");
        }
        context = EGL14.eglGetCurrentContext();
        restoreDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
        restoreRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);

        int[] configId = new int[1];
        if (!EGL14.eglQuerySurface(display, restoreDraw, EGL14.EGL_CONFIG_ID, configId, 0)) {
            throw new RuntimeException("Could not read EGL config id");
        }
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        int[] choose = { EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE };
        if (!EGL14.eglChooseConfig(display, choose, 0, configs, 0, 1, count, 0) || count[0] < 1) {
            throw new RuntimeException("Could not resolve current EGL config");
        }

        int[] attrs = { EGL14.EGL_NONE };
        windowSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, attrs, 0);
        if (windowSurface == null || windowSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("Could not create MediaRecorder EGL surface: 0x" +
                    Integer.toHexString(EGL14.eglGetError()));
        }
    }

    void makeCurrent() {
        if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
            throw new RuntimeException("eglMakeCurrent(recording) failed");
        }
    }

    void setPresentationTime(long timeNanos) {
        EGLExt.eglPresentationTimeANDROID(display, windowSurface, timeNanos);
    }

    void swapBuffers() {
        if (!EGL14.eglSwapBuffers(display, windowSurface)) {
            throw new RuntimeException("eglSwapBuffers(recording) failed: 0x" +
                    Integer.toHexString(EGL14.eglGetError()));
        }
    }

    void restore() {
        if (!EGL14.eglMakeCurrent(display, restoreDraw, restoreRead, context)) {
            throw new RuntimeException("eglMakeCurrent(preview) failed");
        }
    }

    void release() {
        restore();
        EGL14.eglDestroySurface(display, windowSurface);
    }
}
