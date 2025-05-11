package net.ypresto.androidtranscoder.example;


import android.Manifest;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import net.ypresto.androidtranscoder.MediaTranscoder;
import net.ypresto.androidtranscoder.format.MediaFormatStrategy;
import android.media.MediaFormat;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Future;

public class MoovOptimizerActivity extends AppCompatActivity {
    private static final String TAG = "MoovOptimizer";
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    private Button mSelectButton;
    private Button mOptimizeButton;
    private TextView mStatusText;
    private ProgressBar mProgressBar;
    private Uri mSelectedVideoUri;
    private Future<Void> mTranscodeFuture;

    private final ActivityResultLauncher<String> mGetContent = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    mSelectedVideoUri = uri;
                    mStatusText.setText("Selected: " + getFileNameFromUri(uri));
                    mOptimizeButton.setEnabled(true);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_moov_optimizer);

        mSelectButton = findViewById(R.id.select_button);
        mOptimizeButton = findViewById(R.id.optimize_button);
        mStatusText = findViewById(R.id.status_text);
        mProgressBar = findViewById(R.id.progress_bar);

        mSelectButton.setOnClickListener(v -> checkPermissionsAndSelectVideo());
        mOptimizeButton.setOnClickListener(v -> optimizeSelectedVideo());
        mOptimizeButton.setEnabled(false);
    }

    private void checkPermissionsAndSelectVideo() {
        if (hasRequiredPermissions()) {
            selectVideo();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS);
        }
    }

    private boolean hasRequiredPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void selectVideo() {
        mGetContent.launch("video/mp4");
    }

    private String getFileNameFromUri(Uri uri) {
        String result = uri.getLastPathSegment();
        if (result != null) {
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result != null ? result : "unknown";
    }

    private void optimizeSelectedVideo() {
        if (mSelectedVideoUri == null) {
            Toast.makeText(this, "Please select a video first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create output file
        File outputDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "MP4Optimizer");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            Toast.makeText(this, "Failed to create output directory", Toast.LENGTH_SHORT).show();
            return;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File outputFile = new File(outputDir, "optimized_" + timestamp + ".mp4");

        // Update UI
        mProgressBar.setProgress(0);
        mProgressBar.setVisibility(View.VISIBLE);
        mOptimizeButton.setEnabled(false);
        mStatusText.setText("Optimizing... 0%");

        try {
            // Get input file descriptor
            final ParcelFileDescriptor  parcelFileDescriptor = getContentResolver().openFileDescriptor(mSelectedVideoUri, "r");

            final FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();

            // Start transcoding
            mTranscodeFuture = MediaTranscoder.getInstance().transcodeVideo(
                    fileDescriptor,
                    outputFile.getAbsolutePath(),
                    new PassthroughFormatStrategy(),
                    new MediaTranscoder.Listener() {
                        @Override
                        public void onTranscodeProgress(double progress) {
                            runOnUiThread(() -> {
                                int progressPercent = (int) (progress * 100);
                                mProgressBar.setProgress(progressPercent);
                                mStatusText.setText("Optimizing... " + progressPercent + "%");
                            });
                        }

                        @Override
                        public void onTranscodeCompleted() {
                            runOnUiThread(() -> {
                                mProgressBar.setVisibility(View.GONE);
                                mOptimizeButton.setEnabled(true);
                                mStatusText.setText("Optimization completed: " + outputFile.getAbsolutePath());

                                // Notify media scanner to make the file visible in gallery
                                MediaScannerHelper.scanFile(MoovOptimizerActivity.this, outputFile);

                                Toast.makeText(MoovOptimizerActivity.this,
                                        "MP4 optimized successfully!", Toast.LENGTH_LONG).show();
                            });
                        }

                        @Override
                        public void onTranscodeCanceled() {
                            runOnUiThread(() -> {
                                mProgressBar.setVisibility(View.GONE);
                                mOptimizeButton.setEnabled(true);
                                mStatusText.setText("Optimization canceled");
                            });
                        }

                        @Override
                        public void onTranscodeFailed(Exception exception) {
                            runOnUiThread(() -> {
                                mProgressBar.setVisibility(View.GONE);
                                mOptimizeButton.setEnabled(true);
                                mStatusText.setText("Optimization failed: " + exception.getMessage());
                                Log.e(TAG, "Transcoding failed", exception);
                            });
                        }
                    });
        } catch (IOException e) {
            mProgressBar.setVisibility(View.GONE);
            mOptimizeButton.setEnabled(true);
            mStatusText.setText("Error: " + e.getMessage());
            Log.e(TAG, "Error setting up transcoder", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasRequiredPermissions()) {
                selectVideo();
            } else {
                Toast.makeText(this, "Permissions required to access videos", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cancel ongoing operation if activity is destroyed
        if (mTranscodeFuture != null && !mTranscodeFuture.isDone()) {
            mTranscodeFuture.cancel(true);
        }
    }

    /**
     * Custom format strategy that preserves original video and audio formats
     * but ensures the MOOV atom is moved to the beginning of the file
     */
    private static class PassthroughFormatStrategy implements MediaFormatStrategy {
        @Override
        public MediaFormat createVideoOutputFormat(MediaFormat inputFormat) {
            // Return the input format unchanged to avoid re-encoding
            return inputFormat;
        }

        @Override
        public MediaFormat createAudioOutputFormat(MediaFormat inputFormat) {
            // Return the input format unchanged to avoid re-encoding
            return inputFormat;
        }
    }

    /**
     * Helper class to notify the media scanner about the new file
     */
    private static class MediaScannerHelper {
        public static void scanFile(AppCompatActivity activity, File file) {
            Uri contentUri = Uri.fromFile(file);
            android.media.MediaScannerConnection.scanFile(
                    activity,
                    new String[]{file.getAbsolutePath()},
                    null,
                    (path, uri) -> Log.i(TAG, "Media scan completed: " + path)
            );
        }
    }
}
