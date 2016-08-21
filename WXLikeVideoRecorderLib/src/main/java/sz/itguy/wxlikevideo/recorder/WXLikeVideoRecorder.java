package sz.itguy.wxlikevideo.recorder;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.text.TextUtils;
import android.util.Log;

import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameFilter;
import org.bytedeco.javacv.FrameRecorder;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import sz.itguy.utils.FileUtil;
import sz.itguy.wxlikevideo.views.CameraPreviewView;

/**
 * 仿微信录像机
 *
 * @author Martin
 */
public class WXLikeVideoRecorder implements Camera.PreviewCallback, CameraPreviewView.PreviewEventListener {

    private static final String TAG = "InstantVideoRecorder";

    //录制回调
    private RecordCallback mRecordCallback;

    // 最长默认录制时间10秒
    private long mMaxRecordTime = 10 * 1000;
    // 帧率
    private static final int FRAME_RATE = 30;
    // 声音采样率
    private static final int SAMPLE_AUDIO_RATE_IN_HZ = 44100;

    private final Context mContext;
    // 输出文件目录
    private final String mFolder;
    // 输出文件路径
    private String strFinalPath;
    // 图片帧宽、高
    private int imageWidth = 320;
    private int imageHeight = 240;
    // 输出视频宽、高
    private int outputWidth = 320;
    private int outputHeight = 240;

    /* audio data getting thread */
    private AudioRecord mAudioRecord;
    private AudioRecordRunnable mAudioRecordRunnable;
    private Thread mAudioThread;
    volatile boolean isAudioRecording = true;

    private volatile FFmpegFrameRecorder mFFmpegFrameRcorder;
    private long startTime;  //录制开始时间
    private long stopTime;  //录制停止时间
    private boolean isRecording;

    final int RECORD_LENGTH = /*6*/0;    //录制是循环的次数，0表示不循环
    Frame[] images;
    long[] timestamps;
    ShortBuffer[] samples;
    int imagesIndex, samplesIndex;
    private Frame yuvImage = null;

    private FFmpegFrameFilter mFrameFilter; // 图片帧过滤器
    private CameraPreviewView mCameraPreviewView;  // 相机预览视图
    private String mFilters;  //帧数据处理配置

    public WXLikeVideoRecorder(Context context, String folder) {
        mContext = context;
        mFolder = folder;
    }


    public WXLikeVideoRecorder(Context context, String folder, RecordCallback callback) {
        mContext = context;
        mFolder = folder;
        mRecordCallback = callback;
    }

    public boolean isRecording() {
        return isRecording;
    }

    /**
     * 设置图片帧的大小
     *
     * @param width
     * @param height
     */
    public void setFrameSize(int width, int height) {
        imageWidth = width;
        imageHeight = height;
        Log.i(TAG, "----setFrameSize_width:" + imageWidth + "-height:" + imageHeight);
    }

    /**
     * 设置输出视频大小
     *
     * @param width
     * @param height
     */
    public void setOutputSize(int width, int height) {
        outputWidth = width;
        outputHeight = height;
        Log.i(TAG, "----setOutputSize:" + width + "-height:" + height);
    }

    public void setMaxRecordTime(long time) {
        mMaxRecordTime = time > 0 ? time : mMaxRecordTime;
    }

    /**
     * 获取开始时间
     *
     * @return
     */
    public long getStartTime() {
        return startTime;
    }

    /**
     * 获取停止时间
     *
     * @return
     */
    public long getStopTime() {
        return stopTime;
    }

    //---------------------------------------
    // initialize ffmpeg_recorder
    //---------------------------------------
    private void initRecorder() {
        Log.w(TAG, "init mFFmpegFrameRcorder");

        if (RECORD_LENGTH > 0) {
            imagesIndex = 0;
            images = new Frame[RECORD_LENGTH * FRAME_RATE];
            timestamps = new long[images.length];
            for (int i = 0; i < images.length; i++) {
                images[i] = new Frame(imageWidth, imageHeight, Frame.DEPTH_UBYTE, 2);
                timestamps[i] = -1;
            }
        } else if (yuvImage == null) {
            yuvImage = new Frame(imageWidth, imageHeight, Frame.DEPTH_UBYTE, 2);
            Log.i(TAG, "create yuvImage");
        }

        RecorderParameters recorderParameters = RecorderParameters.getRecorderParameter(Constants.RESOLUTION_HIGH_VALUE);
        strFinalPath = FileUtil.createFilePath(mFolder, null, Long.toString(System.currentTimeMillis()));
//        mFFmpegFrameRcorder = new FFmpegFrameRecorder(strFinalPath, imageWidth, imageHeight, 1);
        // 初始化时设置录像机的目标视频大小
        mFFmpegFrameRcorder = new FFmpegFrameRecorder(strFinalPath, outputWidth, outputHeight, 1);
        mFFmpegFrameRcorder.setFormat(recorderParameters.getVideoOutputFormat());
        mFFmpegFrameRcorder.setAudioBitrate(recorderParameters.getAudioBitrate());
        mFFmpegFrameRcorder.setVideoQuality(recorderParameters.getVideoQuality());
        mFFmpegFrameRcorder.setVideoBitrate(recorderParameters.getVideoBitrate());
        mFFmpegFrameRcorder.setSampleRate(recorderParameters.getAudioSamplingRate());
        // Set in the surface changed method
        mFFmpegFrameRcorder.setFrameRate(FRAME_RATE);

        Log.i(TAG, "mFFmpegFrameRcorder initialize success");

        mAudioRecordRunnable = new AudioRecordRunnable();
        mAudioThread = new Thread(mAudioRecordRunnable);
        isAudioRecording = true;
    }

//    private void initVideoRecorder() {
//        strVideoPath = Util.createFilePath(mFolder, "tmp", Long.toString(System.currentTimeMillis()));
//        RecorderParameters recorderParameters = Util.getRecorderParameter(currentResolution);
//        fileVideoPath = new File(strVideoPath);
//        mFFmpegFrameRcorder = new FFmpegFrameRecorder(strVideoPath, 320, 240, 1);
//        mFFmpegFrameRcorder.setFormat(recorderParameters.getVideoOutputFormat());
//        mFFmpegFrameRcorder.setSampleRate(recorderParameters.getAudioSamplingRate());
//        mFFmpegFrameRcorder.setFrameRate(recorderParameters.getVideoFrameRate());
//        mFFmpegFrameRcorder.setVideoCodec(recorderParameters.getVideoCodec());
//        mFFmpegFrameRcorder.setVideoQuality(recorderParameters.getVideoQuality());
//        mFFmpegFrameRcorder.setAudioQuality(recorderParameters.getVideoQuality());
//        mFFmpegFrameRcorder.setAudioCodec(recorderParameters.getAudioCodec());
//        mFFmpegFrameRcorder.setVideoBitrate(1000000);
//        mFFmpegFrameRcorder.setAudioBitrate(64000);
//    }

    /**
     * 设置帧图像数据处理参数
     *
     * @param filters
     */
    public void setFilters(String filters) {
        mFilters = filters;
    }

    /**
     * 生成处理配置
     *
     * @param w         裁切宽度
     * @param h         裁切高度
     * @param x         裁切起始x坐标
     * @param y         裁切起始y坐标
     * @param transpose 图像旋转参数
     * @return 帧图像数据处理参数
     */
    public static String generateFilters(int w, int h, int x, int y, String transpose) {
        return String.format("crop=w=%d:h=%d:x=%d:y=%d,transpose=%s", w, h, x, y, transpose);
    }

    /**
     * 初始化帧过滤器
     */
    private void initFrameFilter() {
        if (TextUtils.isEmpty(mFilters)) {
            mFilters = generateFilters((int) (1f * outputHeight / outputWidth * imageHeight), imageHeight, 0, 0, "clock");
        }
        mFrameFilter = new FFmpegFrameFilter(mFilters, imageWidth, imageHeight);
        mFrameFilter.setPixelFormat(org.bytedeco.javacpp.avutil.AV_PIX_FMT_NV21); // default camera format on Android
    }

    /**
     * 释放帧过滤器
     */
    private void releaseFrameFilter() {
        if (null != mFrameFilter) {
            try {
                mFrameFilter.release();
            } catch (FrameFilter.Exception e) {
                e.printStackTrace();
            }
        }
        mFrameFilter = null;
    }

    /**
     * 获取视频文件路径
     *
     * @return
     */
    public String getFilePath() {
        return strFinalPath;
    }

    /**
     * 开始录制
     *
     * @return
     */
    public boolean startRecording() {
        boolean started = true;
        initRecorder();
        initFrameFilter();
        try {
            mFFmpegFrameRcorder.start();
            mFrameFilter.start();
            startTime = System.currentTimeMillis();
            isRecording = true;
            mAudioThread.start();
            if (mRecordCallback!=null) {
                mRecordCallback.onRecordStart();
            }
        } catch (Exception e) {
            e.printStackTrace();
            started = false;
            if (mRecordCallback!=null) {
                mRecordCallback.onRecordFailed();
            }
        }

        return started;
    }

    public void stopRecording() {
        if (!isRecording)
            return;

        stopTime = System.currentTimeMillis();

        isAudioRecording = false;
        try {
            mAudioThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mAudioRecordRunnable = null;
        mAudioThread = null;

        if (mFFmpegFrameRcorder != null && isRecording) {
            if (RECORD_LENGTH > 0) {
                Log.v(TAG, "Writing frames");
                try {
                    int firstIndex = imagesIndex % samples.length;
                    int lastIndex = (imagesIndex - 1) % images.length;
                    if (imagesIndex <= images.length) {
                        firstIndex = 0;
                        lastIndex = imagesIndex - 1;
                    }
                    if ((startTime = timestamps[lastIndex] - RECORD_LENGTH * 1000000L) < 0) {
                        startTime = 0;
                    }
                    if (lastIndex < firstIndex) {
                        lastIndex += images.length;
                    }
                    for (int i = firstIndex; i <= lastIndex; i++) {
                        long t = timestamps[i % timestamps.length] - startTime;
                        if (t >= 0) {
                            if (t > mFFmpegFrameRcorder.getTimestamp()) {
                                mFFmpegFrameRcorder.setTimestamp(t);
                            }
                            recordFrame(images[i % images.length]);
                        }
                    }

                    firstIndex = samplesIndex % samples.length;
                    lastIndex = (samplesIndex - 1) % samples.length;
                    if (samplesIndex <= samples.length) {
                        firstIndex = 0;
                        lastIndex = samplesIndex - 1;
                    }
                    if (lastIndex < firstIndex) {
                        lastIndex += samples.length;
                    }
                    for (int i = firstIndex; i <= lastIndex; i++) {
                        mFFmpegFrameRcorder.recordSamples(samples[i % samples.length]);
                    }
                } catch (Exception e) {
                    Log.v(TAG, e.getMessage());
                    e.printStackTrace();
                }
            }

            isRecording = false;
            Log.v(TAG, "Finishing isRecording, calling stop and release on mFFmpegFrameRcorder");
            try {
                mFFmpegFrameRcorder.stop();
                mFFmpegFrameRcorder.release();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
            mFFmpegFrameRcorder = null;

            // 释放帧过滤器
            releaseFrameFilter();
            if (mRecordCallback != null) {
                mRecordCallback.onRecordStop();
            }
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        Log.v(TAG, "---onPreviewFrame");
        try {
            // 去掉必须录制音频的限制，可以录制无声视频
//            if (mAudioRecord == null || mAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
//                startTime = System.currentTimeMillis();
//                return;
//            }
            if (RECORD_LENGTH > 0) {
                int i = imagesIndex++ % images.length;
                yuvImage = images[i];
                timestamps[i] = 1000 * (System.currentTimeMillis() - startTime);
            }
            /* get video data */
            if (yuvImage != null && isRecording) {
                ((ByteBuffer) yuvImage.image[0].position(0)).put(data);

                if (RECORD_LENGTH <= 0) {
                    try {
                        Log.v(TAG, "Writing Frame");
                        long pastTime = System.currentTimeMillis() - startTime;
                        if (pastTime >= mMaxRecordTime) {
                            stopRecording();
                            return;
                        }
                        long t = 1000 * pastTime;
                        if (t > mFFmpegFrameRcorder.getTimestamp()) {
                            mFFmpegFrameRcorder.setTimestamp(t);
                            Log.i(TAG, "-----pastTime:" + pastTime);
                            if (mRecordCallback!=null) {
                                mRecordCallback.onRecording(pastTime);
                            }
                        }
                        recordFrame(yuvImage);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } finally {
            camera.addCallbackBuffer(data);
        }
    }

    /**
     * 录制帧
     *
     * @throws FrameRecorder.Exception
     */
    private void recordFrame(Frame frame) throws FrameRecorder.Exception, FrameFilter.Exception {
        mFrameFilter.push(frame);
        Frame filteredFrame;
        while ((filteredFrame = mFrameFilter.pull()) != null) {
            mFFmpegFrameRcorder.record(filteredFrame);
        }
    }

    /**
     * 设置相机预览视图
     *
     * @param cameraPreviewView
     */
    public void setCameraPreviewView(CameraPreviewView cameraPreviewView) {
        mCameraPreviewView = cameraPreviewView;
        mCameraPreviewView.addPreviewEventListener(this);
        mCameraPreviewView.setViewWHRatio(1f * outputWidth / outputHeight);
    }

    @Override
    public void onPrePreviewStart() {
        Camera camera = mCameraPreviewView.getCamera();
        Camera.Parameters parameters = camera.getParameters();
        Camera.Size size = parameters.getPreviewSize();
        // 设置Recorder处理的的图像帧大小
        setFrameSize(size.width, size.height);

        camera.setPreviewCallbackWithBuffer(this);
        camera.addCallbackBuffer(new byte[size.width * size.height * ImageFormat.getBitsPerPixel(parameters.getPreviewFormat()) / 8]);
    }

    @Override
    public void onPreviewStarted() {
    }

    @Override
    public void onPreviewFailed() {
    }

    @Override
    public void onAutoFocusComplete(boolean success) {
    }

    //---------------------------------------------
    // audio thread, gets and encodes audio data
    //---------------------------------------------
    class AudioRecordRunnable implements Runnable {

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            // Audio
            int bufferSize;
            ShortBuffer audioData;
            int bufferReadResult;

            bufferSize = AudioRecord.getMinBufferSize(SAMPLE_AUDIO_RATE_IN_HZ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_AUDIO_RATE_IN_HZ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            if (RECORD_LENGTH > 0) {
                samplesIndex = 0;
                samples = new ShortBuffer[RECORD_LENGTH * SAMPLE_AUDIO_RATE_IN_HZ * 2 / bufferSize + 1];
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = ShortBuffer.allocate(bufferSize);
                }
            } else {
                audioData = ShortBuffer.allocate(bufferSize);
            }

            Log.d(TAG, "mAudioRecord.startRecording()");
            mAudioRecord.startRecording();

            /* ffmpeg_audio encoding loop */
            while (isAudioRecording) {
                if (RECORD_LENGTH > 0) {
                    audioData = samples[samplesIndex++ % samples.length];
                    audioData.position(0).limit(0);
                }
                //Log.v(LOG_TAG,"isRecording? " + isRecording);
                bufferReadResult = mAudioRecord.read(audioData.array(), 0, audioData.capacity());
                audioData.limit(bufferReadResult);
                if (bufferReadResult > 0) {
                    Log.v(TAG, "bufferReadResult: " + bufferReadResult);
                    // If "isRecording" isn't true when start this thread, it never get's set according to this if statement...!!!
                    // Why?  Good question...
                    if (isRecording) {
                        if (RECORD_LENGTH <= 0) try {
                            mFFmpegFrameRcorder.recordSamples(audioData);
                            //Log.v(LOG_TAG,"isRecording " + 1024*i + " to " + 1024*i+1024);
                        } catch (FFmpegFrameRecorder.Exception e) {
                            Log.v(TAG, e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
            Log.v(TAG, "AudioThread Finished, release mAudioRecord");

            /* encoding finish, release mFFmpegFrameRcorder */
            if (mAudioRecord != null) {
                mAudioRecord.stop();
                mAudioRecord.release();
                mAudioRecord = null;
                Log.v(TAG, "mAudioRecord released");
            }
        }
    }

}
