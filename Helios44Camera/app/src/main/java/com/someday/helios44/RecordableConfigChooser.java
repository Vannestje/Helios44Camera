package com.someday.helios44;

import android.opengl.GLSurfaceView;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/** Chooses an ES2 EGL config that can also render into MediaRecorder surfaces. */
final class RecordableConfigChooser implements GLSurfaceView.EGLConfigChooser {
    private static final int EGL_OPENGL_ES2_BIT = 4;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    @Override
    public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
        int[] attrs = new int[] {
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL10.EGL_NONE
        };

        EGLConfig result = find(egl, display, attrs);
        if (result != null) return result;

        // Some older vendors do not advertise the Android recordable bit correctly.
        int[] fallback = new int[] {
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_NONE
        };
        result = find(egl, display, fallback);
        if (result == null) throw new IllegalArgumentException("No usable RGBA8888 ES2 EGL config");
        return result;
    }

    private EGLConfig find(EGL10 egl, EGLDisplay display, int[] attrs) {
        int[] count = new int[1];
        if (!egl.eglChooseConfig(display, attrs, null, 0, count) || count[0] <= 0) return null;
        EGLConfig[] configs = new EGLConfig[count[0]];
        if (!egl.eglChooseConfig(display, attrs, configs, configs.length, count)) return null;
        return configs[0];
    }
}
