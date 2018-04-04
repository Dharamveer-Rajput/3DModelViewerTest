package rotation.com.a3dmodelviewertest;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContentResolverCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.google.vr.sdk.base.AndroidCompat;
import com.google.vr.sdk.base.Eye;
import com.google.vr.sdk.base.GvrActivity;
import com.google.vr.sdk.base.GvrView;
import com.google.vr.sdk.base.HeadTransform;
import com.google.vr.sdk.base.Viewport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.microedition.khronos.egl.EGLConfig;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import rotation.com.a3dmodelviewertest.Models.Light;
import rotation.com.a3dmodelviewertest.Models.Model;
import rotation.com.a3dmodelviewertest.Models.ModelSurfaceView;
import rotation.com.a3dmodelviewertest.Models.ModelViewerApplication;
import rotation.com.a3dmodelviewertest.Ply.PlyModel;
import rotation.com.a3dmodelviewertest.util.Util;

public class MainActivity extends GvrActivity implements GvrView.StereoRenderer  {

    private static final String TAG = "ModelGvrActivity";

    private float rotateAngleX;
    private float rotateAngleY;
    private float translateX;
    private float translateY;
    private float translateZ;

    @Nullable
    private Model model;
    private Light light = new Light(new float[] {0.0f, 0.0f, MODEL_BOUND_SIZE * 10, 1.0f});

    private final float[] viewMatrix = new float[16];

    private static final float MODEL_BOUND_SIZE = 5f;
    private static final float Z_NEAR = 0.1f;
    private static final float Z_FAR = MODEL_BOUND_SIZE * 4;

    private static final float YAW_LIMIT = 0.12f;
    private static final float PITCH_LIMIT = 0.12f;

    // Convenience vector for extracting the position from a matrix via multiplication.
    private static final float[] POS_MATRIX_MULTIPLY_VEC = {0, 0, 0, 1.0f};
    private static final float RAD2DEG = 57.29577951f;

    private float[] finalViewMatrix = new float[16];
    private float[] tempMatrix = new float[16];
    private float[] tempPosition = new float[4];
    private float[] headView = new float[16];
    private float[] inverseHeadView = new float[16];
    private float[] headRotation = new float[4];
    private float[] headEulerAngles = new float[3];
    private ViewGroup containerView;

    private Vibrator vibrator;
    private ModelViewerApplication app;
    @Nullable private ModelSurfaceView modelView;
    private static final int READ_PERMISSION_REQUEST = 100;
    private static final int OPEN_DOCUMENT_REQUEST = 101;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        app = ModelViewerApplication.getInstance();

        containerView = findViewById(R.id.container_view);

        checkReadPermissionThenOpen();

        GvrView gvrView = findViewById(R.id.gvr_view);
        gvrView.setEGLConfigChooser(8, 8, 8, 8, 16, 8);

        gvrView.setRenderer(this);
        gvrView.setTransitionViewEnabled(true);

        // Enable Cardboard-trigger feedback with Daydream headsets. This is a simple way of supporting
        // Daydream controller input for basic interactions using the existing Cardboard trigger API.
        gvrView.enableCardboardTriggerEmulation();

        //  gvrView.setOnCloseButtonListener(this::finish);

        if (gvrView.setAsyncReprojectionEnabled(true)) {
            // Async reprojection decouples the app framerate from the display framerate,
            // allowing immersive interaction even at the throttled clockrates set by
            // sustained performance mode.
            AndroidCompat.setSustainedPerformanceMode(this, true);
        }

      //  model = ModelViewerApplication.getInstance().getCurrentModel();

        if (getIntent().getData() != null && savedInstanceState == null) {
            beginLoadModel(getIntent().getData());
        }
        setGvrView(gvrView);


        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        loadSampleModel();
    }



    private void beginLoadModel(@NonNull Uri uri) {
        new ModelLoadTask().execute(uri);
    }


    private void loadSampleModel() {
        try {
            //InputStream stream = getApplicationContext().getAssets().open(SAMPLE_MODELS[sampleModelIndex++ % SAMPLE_MODELS.length]);
            InputStream stream = getApplicationContext().getAssets().open("happy.ply");


            setCurrentModel(new PlyModel(stream));
            stream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setCurrentModel(@NonNull Model model) {
        createNewModelView(model);
        Toast.makeText(getApplicationContext(), R.string.open_model_success, Toast.LENGTH_SHORT).show();
        setTitle(model.getTitle());
    }




    private class ModelLoadTask extends AsyncTask<Uri, Integer, Model> {
        protected Model doInBackground(Uri... file) {
            InputStream stream = null;
            try {
                Uri uri = file[0];
                ContentResolver cr = getApplicationContext().getContentResolver();
                String fileName = getFileName(cr, uri);

                if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                    OkHttpClient client = new OkHttpClient();
                    Request request = new Request.Builder().url(uri.toString()).build();
                    Response response = client.newCall(request).execute();

                    // TODO: figure out how to NOT need to read the whole file at once.
                    stream = new ByteArrayInputStream(response.body().bytes());
                } else {
                    stream = cr.openInputStream(uri);
                }

                if (stream != null) {
                    Model model;
                    if (!TextUtils.isEmpty(fileName)) {

                        if (fileName.toLowerCase().endsWith(".ply")) {
                            model = new PlyModel(stream);
                        } else {
                            // assume it's STL.
                            model = new PlyModel(stream);
                        }
                        model.setTitle(fileName);
                    } else {
                        // assume it's STL.
                        // TODO: autodetect file type by reading contents?
                        model = new PlyModel(stream);
                    }
                    return model;
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                Util.closeSilently(stream);
            }
            return null;
        }

        protected void onProgressUpdate(Integer... progress) {
        }

        protected void onPostExecute(Model model) {
            if (isDestroyed()) {
                return;
            }
            if (model != null) {
                setCurrentModel(model);
            } else {
                Toast.makeText(getApplicationContext(), R.string.open_model_error, Toast.LENGTH_SHORT).show();
            }
        }

        @Nullable
        private String getFileName(@NonNull ContentResolver cr, @NonNull Uri uri) {
            if ("content".equals(uri.getScheme())) {
                String[] projection = {MediaStore.MediaColumns.DISPLAY_NAME};
                Cursor metaCursor = ContentResolverCompat.query(cr, uri, projection, null, null, null, null);
                if (metaCursor != null) {
                    try {
                        if (metaCursor.moveToFirst()) {
                            return metaCursor.getString(0);
                        }
                    } finally {
                        metaCursor.close();
                    }
                }
            }
            return uri.getLastPathSegment();
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        switch (requestCode) {
            case READ_PERMISSION_REQUEST:
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    beginOpenModel();
                } else {
                    Toast.makeText(this, R.string.read_permission_failed, Toast.LENGTH_SHORT).show();
                }
                break;
            default:
                break;
        }
    }

    private void beginOpenModel() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        startActivityForResult(intent, OPEN_DOCUMENT_REQUEST);
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        if (requestCode == OPEN_DOCUMENT_REQUEST && resultCode == RESULT_OK && resultData.getData() != null) {
            Uri uri = resultData.getData();
            grantUriPermission(getPackageName(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            beginLoadModel(uri);
        }
    }

    private void checkReadPermissionThenOpen() {
        if ((ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    READ_PERMISSION_REQUEST);
        } else {
            beginOpenModel();
        }
    }



    @Override
    protected void onStart() {
        super.onStart();
        createNewModelView(app.getCurrentModel());
        if (app.getCurrentModel() != null) {
            setTitle(app.getCurrentModel().getTitle());
        }
    }

    private void createNewModelView(@Nullable Model model) {
        if (modelView != null) {
            containerView.removeView(modelView);
        }
        ModelViewerApplication.getInstance().setCurrentModel(model);
        modelView = new ModelSurfaceView(this, model);
        containerView.addView(modelView, 0);
    }


    @Override
    protected void onPause() {
        super.onPause();
        if (modelView != null) {
            modelView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (modelView != null) {
            modelView.onResume();
        }
    }
    @Override
    public void onRendererShutdown() {
        Log.i(TAG, "onRendererShutdown");
    }

    @Override
    public void onSurfaceChanged(int width, int height) {
        Log.i(TAG, "onSurfaceChanged");

        // initialize the view matrix
        rotateAngleX = 0;
        rotateAngleY = 0;
        translateX = 0f;
        translateY = 0f;
        translateZ = -MODEL_BOUND_SIZE;
        updateViewMatrix();

        // Set light matrix before doing any other transforms on the view matrix
        light.applyViewMatrix(viewMatrix);
    }

    @Override
    public void onSurfaceCreated(EGLConfig config) {
        Log.i(TAG, "onSurfaceCreated");

        GLES20.glClearColor(0.2f, 0.2f, 0.2f, 1f);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        if (model != null) {
            model.init(MODEL_BOUND_SIZE);
        }
        Util.checkGLError("onSurfaceCreated");
    }

    private void updateViewMatrix() {
        Matrix.setLookAtM(viewMatrix, 0, 0, 0, translateZ, 0f, 0f, 0f, 0f, 1.0f, 0.0f);
        Matrix.translateM(viewMatrix, 0, -translateX, -translateY, 0f);
        Matrix.rotateM(viewMatrix, 0, rotateAngleX, 1f, 0f, 0f);
        Matrix.rotateM(viewMatrix, 0, rotateAngleY, 0f, 1f, 0f);
    }

    @Override
    public void onNewFrame(HeadTransform headTransform) {
        headTransform.getHeadView(headView, 0);
        Matrix.invertM(inverseHeadView, 0, headView, 0);

        headTransform.getQuaternion(headRotation, 0);
        headTransform.getEulerAngles(headEulerAngles, 0);
        headEulerAngles[0] *= RAD2DEG;
        headEulerAngles[1] *= RAD2DEG;
        headEulerAngles[2] *= RAD2DEG;

        updateViewMatrix();
        Util.checkGLError("onNewFrame");
    }

    @Override
    public void onDrawEye(Eye eye) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // Keep the model in front of the camera by applying the inverse of the head matrix
        Matrix.multiplyMM(finalViewMatrix, 0, inverseHeadView, 0, viewMatrix, 0);

        // Apply the eye transformation to the final view
        Matrix.multiplyMM(finalViewMatrix, 0, eye.getEyeView(), 0, finalViewMatrix, 0);

        // Rotate based on Euler angles, so the user can look around the model.
        Matrix.rotateM(finalViewMatrix, 0, headEulerAngles[0], 1.0f, 0.0f, 0.0f);
        Matrix.rotateM(finalViewMatrix, 0, -headEulerAngles[1], 0.0f, 1.0f, 0.0f);
        Matrix.rotateM(finalViewMatrix, 0, headEulerAngles[2], 0.0f, 0.0f, 1.0f);

        float[] perspective = eye.getPerspective(Z_NEAR, Z_FAR);

        if (model != null) {
            model.draw(finalViewMatrix, perspective, light);
        }
        Util.checkGLError("onDrawEye");
    }

    @Override
    public void onFinishFrame(Viewport viewport) {}

    @Override
    public void onCardboardTrigger() {
        Log.i(TAG, "onCardboardTrigger");
        // TODO: use for something

        vibrator.vibrate(50);
    }

    // TODO: use for something.
    private boolean isLookingAtObject() {
        if (model == null) {
            return false;
        }
        // Convert object space to camera space. Use the headView from onNewFrame.
        Matrix.multiplyMM(tempMatrix, 0, headView, 0, model.getModelMatrix(), 0);
        Matrix.multiplyMV(tempPosition, 0, tempMatrix, 0, POS_MATRIX_MULTIPLY_VEC, 0);

        float pitch = (float) Math.atan2(tempPosition[1], -tempPosition[2]);
        float yaw = (float) Math.atan2(tempPosition[0], -tempPosition[2]);
        return Math.abs(pitch) < PITCH_LIMIT && Math.abs(yaw) < YAW_LIMIT;
    }
}
