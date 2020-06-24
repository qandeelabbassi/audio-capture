package com.qandeelabbassi.audiocapture;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.qandeelabbassi.audiocapture.MainActivity.BROADCAST_EXTRA_DATA;
import static com.qandeelabbassi.audiocapture.MainActivity.BROADCAST_WAVEFORM;

public class RecordingService extends Service {
    public static final String NOTIFICATION_CHANNEL_RECORDING = "channel_recording";
    public static final int NOTIFICATION_ID = 1000;

    private final static String ACTION_STOP = "com.qandeelabbassi.audiocapture.stop";
    private final static int REQUEST_STOP = 1;
    private final static int REQUEST_OPEN_ACTIVITY = 2;

    public final static String EXTRA_DATA = "data";
    public final static String EXTRA_CODE = "code";

    private static int AUDIO_SAMPLE_RATE = 44100;
    private final static int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private final static int AUDIO_CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO;

    AudioRecord audioRecord;
    MediaProjectionManager projectionManager;
    MediaProjection projection;

    Intent data;
    int code = 114;

    boolean run = false;

    @Override
    public void onCreate() {
        super.onCreate();

        // To get preferred buffer size and sampling rate.
        AudioManager audioManager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            String rate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            AUDIO_SAMPLE_RATE = Integer.parseInt(rate);
        }

        final NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Notification related to recording.");
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        final NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }

        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_STOP);
        registerReceiver(stopBroadcastReceiver, filter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(stopBroadcastReceiver);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        data = intent.getParcelableExtra(EXTRA_DATA);
        code = intent.getIntExtra(EXTRA_CODE, 114);

        final Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);
        startRecording();
        return START_STICKY;
    }

    private Notification createNotification() {
        final Intent parentIntent = new Intent(this, MainActivity.class);
        parentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Intent targetIntent = new Intent(this, MainActivity.class);

        final Intent disconnect = new Intent(ACTION_STOP);
        final PendingIntent disconnectAction = PendingIntent.getBroadcast(this, REQUEST_STOP, disconnect, PendingIntent.FLAG_UPDATE_CURRENT);

        // both activities above have launchMode="singleTask" in the AndroidManifest.xml file, so if the task is already running, it will be resumed
        final PendingIntent pendingIntent = PendingIntent.getActivities(this, REQUEST_OPEN_ACTIVITY, new Intent[]{parentIntent, targetIntent}, PendingIntent.FLAG_UPDATE_CURRENT);
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_RECORDING);
        builder.setContentIntent(pendingIntent);
        builder.setPriority(NotificationCompat.PRIORITY_MAX);
        builder.setContentTitle("Audio Recording").setContentText("Recording is in progress!");
        builder.setSmallIcon(R.drawable.ic_notifcation_record);
        builder.setColor(ContextCompat.getColor(this, R.color.colorRecording));
        builder.setOnlyAlertOnce(true).setShowWhen(true).setDefaults(0).setAutoCancel(true).setOngoing(true);
        builder.addAction(new NotificationCompat.Action(R.drawable.ic_notifcation_stop, "Stop Recording", disconnectAction));

        return builder.build();
    }

    @SuppressLint("NewApi")
    @TargetApi(Build.VERSION_CODES.O)
    void startRecording() {
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        if (projectionManager == null)
            return;

        projection = projectionManager.getMediaProjection(code, data);

        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build();

        audioRecord = new AudioRecord.Builder().setAudioFormat(
                new AudioFormat.Builder()
                        .setEncoding(AUDIO_ENCODING)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(AUDIO_CHANNEL_MASK)
                        .build())
                .setAudioPlaybackCaptureConfig(config)
                .build();

        audioRecord.startRecording();

        run = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Run whatever background code you want here.
                try {
                    saveFile();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void stopRecording() {
        try {
            run = false;
            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING)
                audioRecord.stop();
            if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED)
                audioRecord.release();
            stopSelf();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveFile() throws IOException {
        final int BUFFER_SIZE = 2 * AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_MASK, AUDIO_ENCODING);

        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        long total = 0;

        File wavFile = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "recording_" + System.currentTimeMillis() / 1000 + ".wav");
        wavFile.getParentFile().mkdir();
        wavFile.createNewFile();

        Log.d("test", "files created: " + wavFile.getAbsolutePath());
        FileOutputStream wavOut = new FileOutputStream(wavFile);
        // Write out the wav file header
        writeWavHeader(wavOut, AUDIO_CHANNEL_MASK, AUDIO_SAMPLE_RATE, AUDIO_ENCODING);

        while (run) {
            read = audioRecord.read(buffer, 0, buffer.length);

            // WAVs cannot be > 4 GB due to the use of 32 bit unsigned integers.
            if (total + read > 4294967295L) {
                // Write as many bytes as we can before hitting the max size
                for (int i = 0; i < read && total <= 4294967295L; i++, total++) {
                    wavOut.write(buffer[i]);
                }
                run = false;
            } else {
                // Write out the entire read buffer
                wavOut.write(buffer, 0, read);
                total += read;
            }
        }
        try {
            wavOut.close();
        } catch (IOException ex) {
            //
            ex.printStackTrace();
        }
        updateWavHeader(wavFile);
        // measurement broadcast
        final Intent broadcast = new Intent(BROADCAST_WAVEFORM);
        broadcast.putExtra(BROADCAST_EXTRA_DATA, wavFile);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    /**
     * Writes the proper 44-byte RIFF/WAVE header to/for the given stream
     * Two size fields are left empty/null since we do not yet know the final stream size
     *
     * @param out         The stream to write the header to
     * @param channelMask An AudioFormat.CHANNEL_* mask
     * @param sampleRate  The sample rate in hertz
     * @param encoding    An AudioFormat.ENCODING_PCM_* value
     * @throws IOException
     */
    private static void writeWavHeader(OutputStream out, int channelMask, int sampleRate, int encoding) throws IOException {
        short channels;
        switch (channelMask) {
            case AudioFormat.CHANNEL_IN_MONO:
                channels = 1;
                break;
            case AudioFormat.CHANNEL_IN_STEREO:
                channels = 2;
                break;
            default:
                throw new IllegalArgumentException("Unacceptable channel mask");
        }

        short bitDepth;
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT:
                bitDepth = 8;
                break;
            case AudioFormat.ENCODING_PCM_16BIT:
                bitDepth = 16;
                break;
            case AudioFormat.ENCODING_PCM_FLOAT:
                bitDepth = 32;
                break;
            default:
                throw new IllegalArgumentException("Unacceptable encoding");
        }

        writeWavHeader(out, channels, sampleRate, bitDepth);
    }

    /**
     * Writes the proper 44-byte RIFF/WAVE header to/for the given stream
     * Two size fields are left empty/null since we do not yet know the final stream size
     *
     * @param out        The stream to write the header to
     * @param channels   The number of channels
     * @param sampleRate The sample rate in hertz
     * @param bitDepth   The bit depth
     * @throws IOException
     */
    private static void writeWavHeader(OutputStream out, short channels, int sampleRate, short bitDepth) throws IOException {
        // Convert the multi-byte integers to raw bytes in little endian format as required by the spec
        byte[] littleBytes = ByteBuffer
                .allocate(14)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(channels)
                .putInt(sampleRate)
                .putInt(sampleRate * channels * (bitDepth / 8))
                .putShort((short) (channels * (bitDepth / 8)))
                .putShort(bitDepth)
                .array();

        // Not necessarily the best, but it's very easy to visualize this way
        out.write(new byte[]{
                // RIFF header
                'R', 'I', 'F', 'F', // ChunkID
                0, 0, 0, 0, // ChunkSize (must be updated later)
                'W', 'A', 'V', 'E', // Format
                // fmt subchunk
                'f', 'm', 't', ' ', // Subchunk1ID
                16, 0, 0, 0, // Subchunk1Size
                1, 0, // AudioFormat
                littleBytes[0], littleBytes[1], // NumChannels
                littleBytes[2], littleBytes[3], littleBytes[4], littleBytes[5], // SampleRate
                littleBytes[6], littleBytes[7], littleBytes[8], littleBytes[9], // ByteRate
                littleBytes[10], littleBytes[11], // BlockAlign
                littleBytes[12], littleBytes[13], // BitsPerSample
                // data subchunk
                'd', 'a', 't', 'a', // Subchunk2ID
                0, 0, 0, 0, // Subchunk2Size (must be updated later)
        });
    }

    /**
     * Updates the given wav file's header to include the final chunk sizes
     *
     * @param wav The wav file to update
     * @throws IOException
     */
    private static void updateWavHeader(File wav) throws IOException {
        byte[] sizes = ByteBuffer
                .allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                // There are probably a bunch of different/better ways to calculate
                // these two given your circumstances. Cast should be safe since if the WAV is
                // > 4 GB we've already made a terrible mistake.
                .putInt((int) (wav.length() - 8)) // ChunkSize
                .putInt((int) (wav.length() - 44)) // Subchunk2Size
                .array();

        RandomAccessFile accessWave = null;
        //noinspection CaughtExceptionImmediatelyRethrown
        try {
            accessWave = new RandomAccessFile(wav, "rw");
            // ChunkSize
            accessWave.seek(4);
            accessWave.write(sizes, 0, 4);

            // Subchunk2Size
            accessWave.seek(40);
            accessWave.write(sizes, 4, 4);
        } catch (IOException ex) {
            // Rethrow but we still close accessWave in our finally
            throw ex;
        } finally {
            if (accessWave != null) {
                try {
                    accessWave.close();
                } catch (IOException ex) {
                    //
                }
            }
        }
    }
    /**
     * This broadcast receiver listens for {@link #ACTION_STOP} that may be fired by pressing Stop action button on the notification.
     */
    private final BroadcastReceiver stopBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            stopRecording();
        }
    };
}
