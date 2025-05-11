package net.ypresto.androidtranscoder.example;


import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MP4OptimizerActivity extends AppCompatActivity {
    private static final String TAG = "MP4OptimizerActivity";
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

    private String fileName=null;

    private final ActivityResultLauncher<String> mGetContent = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    mSelectedVideoUri = uri;
                    fileName=getFileNameFromUri(uri);
                    mStatusText.setText("Selected: " +fileName );
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
        // For Android 10+ (API 29+), we don't need WRITE_EXTERNAL_STORAGE for app-specific files
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }

        // For older Android versions, check both permissions
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

        // Create a temp file to store the input
        File inputFile;
        try {
            inputFile = createTempFileFromUri(mSelectedVideoUri);
            if (inputFile == null) {
                Toast.makeText(this, "Failed to create temporary file", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (IOException e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Error creating temp file", e);
            return;
        }

        // Create output file
        File outputDir;
        File outputFile;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10+, we'll use MediaStore API to save files
            outputDir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MP4Optimizer");
        } else {
            // For older versions, we'll save to public directory
            outputDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), "MP4Optimizer");
        }

        if (!outputDir.exists() && !outputDir.mkdirs()) {
            Toast.makeText(this, "Failed to create output directory", Toast.LENGTH_SHORT).show();
            return;
        }

      //  String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
      //  String NfileName = fileName + "_"+timestamp + ".mp4";
        outputFile = new File(outputDir, fileName);

        // Update UI
        mProgressBar.setProgress(0);
        mProgressBar.setVisibility(View.VISIBLE);
        mOptimizeButton.setEnabled(false);
        mStatusText.setText("Checking file...");

        try {
            // Check if file is already optimized
            boolean isOptimized = MP4MoovOptimizer.isOptimized(inputFile);
            if (isOptimized) {
                mStatusText.setText("File is already optimized!");
                mProgressBar.setVisibility(View.GONE);
                mOptimizeButton.setEnabled(true);
                return;
            }

            mStatusText.setText("Optimizing... 0%");

            // Start optimization
            MP4MoovOptimizer.optimize(inputFile, outputFile, new MP4MoovOptimizer.OptimizerListener() {
                @Override
                public void onProgress(float progress) {
                    runOnUiThread(() -> {
                        int progressPercent = (int) (progress * 100);
                        mProgressBar.setProgress(progressPercent);
                        mStatusText.setText("Optimizing... " + progressPercent + "%");
                    });
                }

                @Override
                public void onSuccess(File output) {
                    runOnUiThread(() -> {
                        mProgressBar.setVisibility(View.GONE);
                        mOptimizeButton.setEnabled(true);

                        // Add to media library
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            // For Android 10+, use MediaStore
                            addToMediaStoreQ(output);
                            mStatusText.setText("Optimization completed!");
                        } else {
                            // For older Android versions
                            scanFile(output);
                            mStatusText.setText("Optimization completed: " + output.getAbsolutePath());
                        }

                        Toast.makeText(MP4OptimizerActivity.this,
                                "MP4 optimized successfully!", Toast.LENGTH_LONG).show();

                        // Delete the temp input file
                        if (inputFile.exists()) {
                            inputFile.delete();
                        }
                    });
                }

                @Override
                public void onError(Exception e) {
                    runOnUiThread(() -> {
                        mProgressBar.setVisibility(View.GONE);
                        mOptimizeButton.setEnabled(true);
                        mStatusText.setText("Optimization failed: " + e.getMessage());
                        Log.e(TAG, "Optimization failed", e);

                        // Delete the temp input file
                        if (inputFile.exists()) {
                            inputFile.delete();
                        }
                    });
                }
            });

        } catch (IOException e) {
            mProgressBar.setVisibility(View.GONE);
            mOptimizeButton.setEnabled(true);
            mStatusText.setText("Error: " + e.getMessage());
            Log.e(TAG, "Error checking file", e);

            // Delete the temp input file
            if (inputFile.exists()) {
                inputFile.delete();
            }
        }
    }

    /**
     * Create a temporary file from the selected URI
     */
    private File createTempFileFromUri(Uri uri) throws IOException {
        File tempFile = File.createTempFile("input_video", ".mp4", getCacheDir());
        try (InputStream is = getContentResolver().openInputStream(uri);
             FileOutputStream os = new FileOutputStream(tempFile)) {

            if (is == null) {
                throw new IOException("Failed to open input stream");
            }

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.flush();
        }
        return tempFile;
    }

    /**
     * Add the file to MediaStore on Android 10+
     */
    private void addToMediaStoreQ(File file) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, file.getName());
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + File.separator + "MP4Optimizer");

        ContentResolver resolver = getContentResolver();
        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

        if (uri != null) {
            try (OutputStream os = resolver.openOutputStream(uri);
                 FileInputStream is = new FileInputStream(file)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
                os.flush();

                // Delete the original file after copying to media store
                file.delete();
            } catch (IOException e) {
                Log.e(TAG, "Error adding to MediaStore", e);
            }
        }
    }

    /**
     * Scan file to add to media library on older Android versions
     */
    private void scanFile(File file) {
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        Uri contentUri = Uri.fromFile(file);
        mediaScanIntent.setData(contentUri);
        sendBroadcast(mediaScanIntent);
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
}
