package sz.itguy.recordvideodemo;

import android.app.Activity;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import sz.itguy.utils.FileUtil;
import sz.itguy.wxlikevideo.camera.CameraHelper;
import sz.itguy.wxlikevideo.recorder.RecordCallback;
import sz.itguy.wxlikevideo.recorder.WXLikeVideoRecorder;
import sz.itguy.wxlikevideo.views.CameraPreviewView;

/**
 * 新视频录制页面
 *
 * @author Martin
 */
public class NewRecordVideoActivity extends Activity implements View.OnTouchListener, RecordCallback {

    private static final String TAG = "NewRecordVideoActivity";

    // 输出宽度
    private static final int OUTPUT_WIDTH = 720;
    // 输出高度
    private static final int OUTPUT_HEIGHT = 400;
    // 宽高比
    private static final float RATIO = 1f * OUTPUT_WIDTH / OUTPUT_HEIGHT;

    private Camera mCamera;

    private WXLikeVideoRecorder mRecorder;

    private LinearLayout ll_progress;

    private static final int CANCEL_RECORD_OFFSET = -100;
    private float mDownX, mDownY;
    private boolean isCancelRecord = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int cameraId = CameraHelper.getDefaultCameraID();
        // Create an instance of Camera
        mCamera = CameraHelper.getCameraInstance(cameraId);
        if (null == mCamera) {
            Toast.makeText(this, "打开相机失败！", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        // 初始化录像机
        mRecorder = new WXLikeVideoRecorder(this, FileUtil.MEDIA_FILE_DIR, this);
        mRecorder.setOutputSize(OUTPUT_WIDTH, OUTPUT_HEIGHT);

        setContentView(R.layout.activity_new_recorder);
        CameraPreviewView preview = (CameraPreviewView) findViewById(R.id.camera_preview);
        preview.setCamera(mCamera, cameraId);

        mRecorder.setCameraPreviewView(preview);

        findViewById(R.id.button_start).setOnTouchListener(this);
        ll_progress = (LinearLayout) findViewById(R.id.ll_progress);
        ((TextView) findViewById(R.id.filePathTextView)).setText("请在" + FileUtil.MEDIA_FILE_DIR + "查看录制的视频文件");


    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mRecorder != null) {
            boolean recording = mRecorder.isRecording();
            // 页面不可见就要停止录制
            mRecorder.stopRecording();
            // 录制时退出，直接舍弃视频
            if (recording) {
                FileUtil.deleteFile(mRecorder.getFilePath());
            }
        }
        releaseCamera();              // release the camera immediately on pause event
        finish();
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            // 释放前先停止预览
            mCamera.stopPreview();
            mCamera.release();        // release the camera for other applications
            mCamera = null;
        }
    }

    /**
     * 开始录制
     */
    private void startRecord() {
        if (mRecorder.isRecording()) {
            Toast.makeText(this, "正在录制中…", Toast.LENGTH_SHORT).show();
            return;
        }

        // initialize video camera
        if (prepareVideoRecorder()) {
            // 录制视频
            if (!mRecorder.startRecording())
                Toast.makeText(this, "录制失败…", Toast.LENGTH_SHORT).show();


        }
    }


    /**
     * 准备视频录制器
     *
     * @return
     */
    private boolean prepareVideoRecorder() {
        if (!FileUtil.isSDCardMounted()) {
            Toast.makeText(this, "SD卡不可用！", Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    /**
     * 停止录制
     */
    private void stopRecord() {
        mRecorder.stopRecording();
        String videoPath = mRecorder.getFilePath();
        // 没有录制视频
        if (null == videoPath) {
            return;
        }
        // 若取消录制，则删除文件，否则通知宿主页面发送视频
        if (isCancelRecord) {
            FileUtil.deleteFile(videoPath);
        } else {
            // 告诉宿主页面录制视频的路径
            startActivity(new Intent(this, PlayVideoActiviy.class).putExtra(PlayVideoActiviy.KEY_FILE_PATH, videoPath));
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isCancelRecord = false;
                mDownX = event.getX();
                mDownY = event.getY();
                startRecord();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mRecorder.isRecording())
                    return false;

                float y = event.getY();
                if (y - mDownY < CANCEL_RECORD_OFFSET) {
                    if (!isCancelRecord) {
                        // cancel record
                        isCancelRecord = true;
                        Toast.makeText(this, "cancel record", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    isCancelRecord = false;
                }
                break;
            case MotionEvent.ACTION_UP:
                stopRecord();
                break;
        }

        return true;
    }

    @Override
    public void onRecordStart() {
        Log.i(TAG, "------onRecordStart");
    }

    RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(-1, 20);

    @Override
    public void onRecording(long recordTime) {
        Log.i(TAG, "------onRecording:" + recordTime);
        layoutParams.width = (int) (recordTime / 1000 * 30);
        ll_progress.setLayoutParams(layoutParams);
    }

    @Override
    public void onRecordStop() {
        Log.i(TAG, "------onRecordStop");
    }

    @Override
    public void onRecordFailed() {
        Log.i(TAG, "------onRecordFailed");
    }


}
