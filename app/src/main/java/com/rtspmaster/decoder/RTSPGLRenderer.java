package com.rtspmaster.decoder;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OpenGL ES OES Shader Renderer — Strategy B color correction.
 * Eliminates green frames by letting the GPU handle all YUV→RGB conversion
 * via samplerExternalOES. Works on ALL chipsets transparently.
 *
 * Flow: Hardware Decoder → SurfaceTexture (OES) → OpenGL Shader → Display
 */
public final class RTSPGLRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "RTSPGLRenderer";

    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec4 aTextureCoord;\n" +
        "uniform mat4 uSTMatrix;\n" +
        "varying vec2 vTextureCoord;\n" +
        "void main() {\n" +
        "  gl_Position = aPosition;\n" +
        "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
        "}\n";

    private static final String FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTextureCoord;\n" +
        "uniform samplerExternalOES sTexture;\n" +
        "void main() {\n" +
        "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
        "}\n";

    private static final float[] QUAD_VERTICES = {
        -1f, -1f, 0f,   1f, -1f, 0f,  -1f,  1f, 0f,   1f,  1f, 0f
    };
    private static final float[] TEX_COORDS = {
        0f, 0f,  1f, 0f,  0f, 1f,  1f, 1f
    };

    private FloatBuffer vertexBuf, texCoordBuf;
    private int program;
    private int aPositionLoc, aTexCoordLoc, uSTMatrixLoc;
    private int textureId;
    private SurfaceTexture surfaceTexture;
    private Surface decoderSurface;
    private final float[] stMatrix = new float[16];
    private volatile boolean frameAvailable = false;
    private OnSurfaceReadyListener surfaceReadyListener;

    public interface OnSurfaceReadyListener {
        void onDecoderSurfaceReady(Surface surface);
    }

    public void setOnSurfaceReadyListener(OnSurfaceReadyListener l) { this.surfaceReadyListener = l; }
    public Surface getDecoderSurface() { return decoderSurface; }
    public SurfaceTexture getSurfaceTexture() { return surfaceTexture; }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);

        // Create OES texture
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // Create SurfaceTexture → Surface for decoder
        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setOnFrameAvailableListener(this);
        decoderSurface = new Surface(surfaceTexture);

        // Compile shaders
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTextureCoord");
        uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix");
        Matrix.setIdentityM(stMatrix, 0);

        // Vertex buffers
        vertexBuf = createFloatBuffer(QUAD_VERTICES);
        texCoordBuf = createFloatBuffer(TEX_COORDS);

        Log.i(TAG, "GL surface created, decoder Surface ready");
        if (surfaceReadyListener != null) surfaceReadyListener.onDecoderSurfaceReady(decoderSurface);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (surfaceTexture == null) return;

        if (frameAvailable) {
            try {
                surfaceTexture.updateTexImage();
                surfaceTexture.getTransformMatrix(stMatrix);
            } catch (Exception e) {
                Log.w(TAG, "updateTexImage error", e);
                return;
            }
            frameAvailable = false;
        }

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0);

        GLES20.glEnableVertexAttribArray(aPositionLoc);
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, vertexBuf);
        GLES20.glEnableVertexAttribArray(aTexCoordLoc);
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPositionLoc);
        GLES20.glDisableVertexAttribArray(aTexCoordLoc);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        frameAvailable = true;
    }

    public void updateBufferSize(int width, int height) {
        if (surfaceTexture != null) {
            surfaceTexture.setDefaultBufferSize(width, height);
            Log.i(TAG, "Buffer size updated: " + width + "x" + height);
        }
    }

    public void release() {
        if (decoderSurface != null) { decoderSurface.release(); decoderSurface = null; }
        if (surfaceTexture != null) { surfaceTexture.release(); surfaceTexture = null; }
    }

    private static int createProgram(String vs, String fs) {
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }

    private static int loadShader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile error: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private static FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }
}
