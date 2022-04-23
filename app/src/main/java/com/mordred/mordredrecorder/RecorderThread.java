package com.mordred.mordredrecorder;

import android.annotation.SuppressLint;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

public class RecorderThread implements Runnable {

    private static final String TAG = "RecorderThread";

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private Surface surface;
    private MediaCodec audioEncoder;
    private MediaCodec videoEncoder;
    private AudioRecord audioRecord;
    private MediaMuxer muxer;
    private boolean muxerStarted;
    private boolean startTimestampInitialized;
    private long startTimestampUs;
    private Handler handler;
    private Thread audioRecordThread;

    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;


    private String outputFilePath;
    private String videoMime;
    private int videoWidth;
    private int videoHeight;
    private int videoBitrate;
    private int frameRate;
    private int timeLapse;
    private int sampleRate;
    private Thread recordingThread;

    private volatile boolean stopped = false;
    private volatile boolean audioStopped;
    private volatile boolean asyncError = false;

    private VirtualDisplay.Callback displayCallback = new VirtualDisplay.Callback() {
        @Override
        public void onPaused() {
            super.onPaused();
            Log.v(TAG, "Display projection paused");
        }

        @Override
        public void onStopped() {
            super.onStopped();
            if (!stopped) {
                asyncError = true;
            }
        }
    };

    public RecorderThread(MediaProjection mediaProjection, String outputFilePath,
                          int videoWidth, int videoHeight, int videoBitrate) {
        this.mediaProjection = mediaProjection;
        handler = new Handler();
        this.outputFilePath = outputFilePath;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.videoBitrate = videoBitrate;
        this.sampleRate = 44100;
        this.timeLapse = 1;
        this.frameRate = 30;
        videoMime = MediaFormat.MIMETYPE_VIDEO_AVC;
    }

    public void startRecording() {
        stopped = false;
        recordingThread = new Thread(this);
        recordingThread.start();
    }

    public void stopRecording() {
        stopped = true;
    }

    public boolean isRecordingContinue() {
        return stopped;
    }

    private void setupVideoCodec() throws IOException {
        MediaFormat encoderFormat = MediaFormat.createVideoFormat(videoMime, videoWidth, videoHeight);
        encoderFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
        encoderFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        encoderFormat.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000);
        encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

        videoEncoder = MediaCodec.createEncoderByType(videoMime);
        videoEncoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        surface = videoEncoder.createInputSurface();
        videoEncoder.start();
    }

    private void setupVirtualDisplay() {
        virtualDisplay = mediaProjection.createVirtualDisplay("Android Recorder",
                videoWidth, videoHeight, DisplayMetrics.DENSITY_HIGH,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, displayCallback, handler);
    }

    private void setupAudioCodec() throws IOException {
        // TODO set channelCount 2 try stereo quality
        MediaFormat encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1);
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, 64000);

        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioEncoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEncoder.start();
    }


    @SuppressLint("MissingPermission")
    private void setupAudioRecord() {
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig,
                AudioFormat.ENCODING_PCM_16BIT);

        AudioFormat.Builder audioFormatBuilder = new AudioFormat.Builder();
        AudioFormat newAudioFormat = audioFormatBuilder.setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();

        AudioRecord.Builder audioRecordBuilder = new AudioRecord.Builder();
        AudioPlaybackCaptureConfiguration apcc = new AudioPlaybackCaptureConfiguration.Builder(this.mediaProjection).addMatchingUsage(AudioAttributes.USAGE_MEDIA).build();
        audioRecord = audioRecordBuilder.setAudioFormat(newAudioFormat).setBufferSizeInBytes(4 * minBufferSize).setAudioPlaybackCaptureConfig(apcc).build();
    }

    private void startAudioRecord() {
        audioRecordThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
                try {
                    audioRecord.startRecording();
                } catch (Exception e) {
                    asyncError = true;
                    e.printStackTrace();
                    return;
                }
                try {
                    while (!audioStopped) {
                        int index = audioEncoder.dequeueInputBuffer(10000);
                        if (index < 0) {
                            continue;
                        }
                        ByteBuffer inputBuffer = audioEncoder.getInputBuffer(index);
                        if (inputBuffer == null) {
                            if (!stopped) {
                                asyncError = true;
                            }
                            return;
                        }
                        inputBuffer.clear();
                        int read = audioRecord.read(inputBuffer, inputBuffer.capacity());
                        if (read < 0) {
                            if (!stopped) {
                                asyncError = true;
                            }
                            break;
                        }
                        audioEncoder.queueInputBuffer(index, 0, read, getPresentationTimeUs(), 0);
                    }
                } catch (Exception e) {
                    if (!stopped) {
                        Log.e(TAG, "Audio error", e);
                        asyncError = true;
                        e.printStackTrace();
                    }
                } finally {
                    audioRecord.stop();
                    audioRecord.release();
                    audioRecord = null;
                }
            }
        });
        audioRecordThread.start();
    }

    private long getPresentationTimeUs() {
        if (!startTimestampInitialized) {
            startTimestampUs = System.nanoTime() / 1000;
            startTimestampInitialized = true;
        }
        return (System.nanoTime() / 1000 - startTimestampUs) / timeLapse;
    }

    private void startMuxerIfSetUp() {
        if (audioTrackIndex >= 0 && videoTrackIndex >= 0) {
            muxer.start();
            muxerStarted = true;
        }
    }

    @Override
    public void run() {
        try {
            setupVideoCodec();
            setupVirtualDisplay();

            setupAudioCodec();
            setupAudioRecord();

            muxer = new MediaMuxer(this.outputFilePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            startAudioRecord();

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            long lastAudioTimestampUs = -1;

            while (!stopped && !asyncError) {
                int encoderStatus;

                encoderStatus = audioEncoder.dequeueOutputBuffer(bufferInfo, 0);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Log.w(TAG, "audio encoder.dequeueOutputBuffer: try again");
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (audioTrackIndex > 0) {
                        Log.e(TAG, "audioTrackIndex less than zero");
                        break;
                    }
                    audioTrackIndex = muxer.addTrack(audioEncoder.getOutputFormat());
                    startMuxerIfSetUp();
                } else if (encoderStatus < 0) {
                    Log.w(TAG, "Unexpected result from audio encoder.dequeueOutputBuffer: " + encoderStatus);
                } else {
                    ByteBuffer encodedData = audioEncoder.getOutputBuffer(encoderStatus);
                    if (encodedData == null) {
                        Log.e(TAG, "encodedData null");
                        break;
                    }

                    if (bufferInfo.presentationTimeUs > lastAudioTimestampUs
                            && muxerStarted && bufferInfo.size != 0 && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        lastAudioTimestampUs = bufferInfo.presentationTimeUs;
                        muxer.writeSampleData(audioTrackIndex, encodedData, bufferInfo);
                    }

                    audioEncoder.releaseOutputBuffer(encoderStatus, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.e(TAG, "buffer EOS");
                        break;
                    }
                }

                if (videoTrackIndex >= 0 && audioTrackIndex < 0)
                    continue;

                encoderStatus = videoEncoder.dequeueOutputBuffer(bufferInfo, 10000);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Log.w(TAG, "encoder.dequeueOutputBuffer: try again");
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (videoTrackIndex > 0) {
                        break;
                    }
                    videoTrackIndex = muxer.addTrack(videoEncoder.getOutputFormat());
                    startMuxerIfSetUp();
                } else if (encoderStatus < 0) {
                    Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                } else {
                    ByteBuffer encodedData = videoEncoder.getOutputBuffer(encoderStatus);
                    if (encodedData == null) {
                        Log.w(TAG, "encodedData null");
                        break;
                    }

                    if (bufferInfo.size != 0 && (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        bufferInfo.presentationTimeUs = getPresentationTimeUs();
                        muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                    }

                    videoEncoder.releaseOutputBuffer(encoderStatus, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.w(TAG, "buffer EOS");
                        break;
                    }
                }
            }
        } catch (Exception mainException) {
            mainException.printStackTrace();
        } finally {
            try {
                if (muxer != null) {
                    muxer.stop();
                    muxer.release();
                    muxer = null;
                }

                if (virtualDisplay != null) {
                    virtualDisplay.release();
                    virtualDisplay = null;
                }

                if (surface != null) {
                    surface.release();
                    surface = null;
                }

                if (videoEncoder != null) {
                    videoEncoder.stop();
                    videoEncoder.release();
                    videoEncoder = null;
                }

                if (audioRecordThread != null) {
                    audioStopped = true;
                    audioRecordThread.join();
                }

                if (audioEncoder != null) {
                    audioEncoder.stop();
                    audioEncoder.release();
                    audioEncoder = null;
                }
            } catch (Exception e) {
                Log.w(TAG, "Error while releasing resources", e);
                e.printStackTrace();
            }
        }
    }
}