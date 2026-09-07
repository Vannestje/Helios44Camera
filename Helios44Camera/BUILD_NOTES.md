# Build / implementation notes

The app intentionally avoids third-party camera or shader libraries. That keeps the prototype small and makes the rendered frame identical across preview, still capture, and video recording.

Pipeline:

1. Camera2 sends frames to a `SurfaceTexture` backed by a `GL_TEXTURE_EXTERNAL_OES` texture.
2. `HeliosRenderer` draws the camera texture through a fragment shader.
3. The shader keeps a tap-selected central zone relatively crisp and performs multiple angular/tangential samples toward the edges.
4. Still capture uses `glReadPixels()` from the processed preview and writes a JPEG via `MediaStore`.
5. Video creates an additional EGL window surface backed by `MediaRecorder.getSurface()`. The exact same shader is rendered into that surface with presentation timestamps, so the effect is baked into the H.264 video.

This is a prototype effect model rather than a physical lens simulation. The natural next quality upgrade is depth/person masking plus highlight-aware point-spread kernels.
