package com.mordred.mordredrecorder;


import java.io.File;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Environment;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.Nullable;


public class RecorderService extends Service {
    private static final boolean DEBUG = true;
    private static final String TAG = "RecorderService";
    private static final String APP_DIR_NAME = "ScreenRecorder";

    String CHANNEL_ID = "1432";

    private static final String BASE = "com.mordred.mordredrecorder.RecorderService.";
    public static final String ACTION_START = BASE + "ACTION_START";
    public static final String ACTION_STOP = BASE + "ACTION_STOP";
    public static final String ACTION_QUERY_STATUS = BASE + "ACTION_QUERY_STATUS";
    public static final String ACTION_QUERY_STATUS_RESULT = BASE + "ACTION_QUERY_STATUS_RESULT";
    public static final String EXTRA_RESULT_CODE = BASE + "EXTRA_RESULT_CODE";
    public static final String EXTRA_QUERY_RESULT_RECORDING = BASE + "EXTRA_QUERY_RESULT_RECORDING";
    public static final String EXTRA_QUERY_RESULT_PAUSING = BASE + "EXTRA_QUERY_RESULT_PAUSING";
    private static final int NOTIFICATION = R.string.app_name;

    private MediaProjectionManager mMediaProjectionManager;
    private NotificationManager mNotificationManager;

    private static RecorderThread sMuxer;

    public RecorderService() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (DEBUG) Log.v(TAG, "onCreate:");
        mMediaProjectionManager = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mNotificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        showNotification(TAG);
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.v(TAG, "onDestroy:");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (DEBUG) Log.v(TAG, "onStartCommand:intent=" + intent);

        int result = START_STICKY;
        final String action = intent != null ? intent.getAction() : null;
        Log.v(TAG, "onStartCommand:intent=" + "action string: " + action);
        if (ACTION_START.equals(action)) {
            Log.v(TAG, "onStartCommand:intent=" + "start");
            startScreenRecord(intent);
            updateStatus();
        } else if (ACTION_STOP.equals(action) || TextUtils.isEmpty(action)) {
            Log.v(TAG, "onStartCommand:intent=" + "stop");
            stopScreenRecord();
            updateStatus();
            result = START_NOT_STICKY;
        } else if (ACTION_QUERY_STATUS.equals(action)) {
            Log.v(TAG, "onStartCommand:intent=" + "stopSelf");
            if (!updateStatus()) {
                stopSelf();
                result = START_NOT_STICKY;
            }
        }
        return result;
    }

    // TODO fixup this func
    private boolean updateStatus() {
        final boolean isRecording;
        isRecording = (sMuxer != null);
        final Intent result = new Intent();
        result.setAction(ACTION_QUERY_STATUS_RESULT);
        result.putExtra(EXTRA_QUERY_RESULT_RECORDING, isRecording);
        if (DEBUG) Log.v(TAG, "sendBroadcast:isRecording=" + isRecording);
        sendBroadcast(result);
        return isRecording;
    }

    private void startScreenRecord(final Intent intent) {
        if (sMuxer == null) {
            final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            // get MediaProjection
            final MediaProjection projection = mMediaProjectionManager.getMediaProjection(resultCode, intent);
            if (projection != null) {
                final DisplayMetrics metrics = getResources().getDisplayMetrics();
                int width = metrics.widthPixels;
                int height = metrics.heightPixels;
                if (width > height) {
                    // Horizontal
                    final float scale_x = width / 1920f;
                    final float scale_y = height / 1080f;
                    final float scale = Math.max(scale_x,  scale_y);
                    width = (int)(width / scale);
                    height = (int)(height / scale);
                } else {
                    // Vertical
                    final float scale_x = width / 1080f;
                    final float scale_y = height / 1920f;
                    final float scale = Math.max(scale_x,  scale_y);
                    width = (int)(width / scale);
                    height = (int)(height / scale);
                }
                if (DEBUG) Log.v(TAG, String.format("startRecording:(%d,%d)(%d,%d)", metrics.widthPixels, metrics.heightPixels, width, height));
                String outputtedFilePath = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "MyRecord.mp4";
                sMuxer = new RecorderThread(projection, outputtedFilePath, width, height,
                        calcBitRate(30, width, height));
                sMuxer.startRecording();
            }
        }
    }

    protected int calcBitRate(final int frameRate, int width, int height) {
        final int bitrate = (int)(0.25f * frameRate * width * height);
        Log.i(TAG, String.format("bitrate=%5.2f[Mbps]", bitrate / 1024f / 1024f));
        return bitrate;
    }

    private void stopScreenRecord() {
        if (sMuxer != null) {
            sMuxer.stopRecording();
        }
        stopForeground(true/*removeNotification*/);
        if (mNotificationManager != null) {
            mNotificationManager.cancel(NOTIFICATION);
            mNotificationManager = null;
        }
        stopSelf();
    }

    private void showNotification(final CharSequence text) {
        if (DEBUG) Log.v(TAG, "showNotification:" + text);
        final Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_notification_overlay)
                .setTicker(text)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(getText(R.string.app_name))
                .setContentText(text)
                .setContentIntent(createPendingIntent())
                .build();

        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "MyRecorderChannel",
                NotificationManager.IMPORTANCE_DEFAULT);
        mNotificationManager.createNotificationChannel(channel);

        // Send the notification.
        mNotificationManager.notify(NOTIFICATION, notification);

        startForeground(NOTIFICATION, notification);

    }

    protected PendingIntent createPendingIntent() {
        return PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);
    }

}