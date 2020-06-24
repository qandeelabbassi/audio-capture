package com.qandeelabbassi.audiocapture;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.qandeelabbassi.audiocapture.visualizer.SoundWaveView;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import static com.qandeelabbassi.audiocapture.RecordingService.ACTION_STOP;

public class MainActivity extends AppCompatActivity {
    private final static boolean DEBUG = true;
    public static String BROADCAST_WAVEFORM = "com.qandeelabbassi.audiocapture.waveform";
    public static String BROADCAST_EXTRA_DATA = "com.qandeelabbassi.audiocapture.waveform_data";
    private SoundWaveView audioVisualizerView;
    private Button btnStart;
    private boolean isRecording = false;
    private TextView txtFilePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        audioVisualizerView = findViewById(R.id.visualizer);
        txtFilePath = findViewById(R.id.txtFilePath);
        btnStart = findViewById(R.id.btnStart);

        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, makeIntentFilter());

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isRecording) {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.RECORD_AUDIO},
                                1000);
                    } else {
                        MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                        if (mediaProjectionManager != null) {
                            startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), 2000);
                        }
                    }
                } else {
                    final Intent broadcast = new Intent(ACTION_STOP);
                    sendBroadcast(broadcast);
                    btnStart.setText(R.string.start_recording);
                    isRecording = false;
                }
            }
        });
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK && requestCode == 2000) {
            if (data != null) {
                Intent intent = new Intent(this, RecordingService.class);
                intent.putExtra(RecordingService.EXTRA_CODE, resultCode);
                intent.putExtra(RecordingService.EXTRA_DATA, data);

                ContextCompat.startForegroundService(this, intent);

                btnStart.setText(R.string.stop_recording);
                isRecording = true;
            }
        }
    }

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (BROADCAST_WAVEFORM.equals(action) && intent.getExtras() != null) {
                final File file = (File) intent.getExtras().getSerializable(BROADCAST_EXTRA_DATA);
                if(file == null)
                    return;

                if (DEBUG)
                    txtFilePath.setText(String.format("File path: %s", file.getAbsolutePath()));

                try {
                    Uri uri = FileProvider.getUriForFile(MainActivity.this, getApplicationContext().getPackageName() + ".provider", file);
                    audioVisualizerView.addAudioFileUri(uri);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    };

    private static IntentFilter makeIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BROADCAST_WAVEFORM);
        return intentFilter;
    }

    public static byte[] fileToBytes(File file) {
        int size = (int) file.length();
        byte[] bytes = new byte[size];
        try {
            BufferedInputStream buf = new BufferedInputStream(new FileInputStream(file));
            buf.read(bytes, 0, bytes.length);
            buf.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return bytes;
    }

}
