package net.ypresto.androidtranscoder.example;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Utility class to ensure videos are playable while still having correct metadata
 * for thumbnail extraction
 */
public class PlayableVideoUtil {
    private static final String TAG = "PlayableVideoUtil";

    /**
     * Callback interface for video processing operations
     */
    public interface VideoProcessCallback {
        void onProgress(float progress);
        void onVideoProcessed(File outputFile, long durationMs, Bitmap thumbnail);
        void onError(Exception e);
    }

    /**
     * Process a video file to ensure it's playable and has correct metadata
     * Also extracts a thumbnail and duration in a single operation
     *
     * @param context Application context
     * @param inputFile Input video file that may have playback issues
     * @param callback Callback for results
     */
    public static void processVideo(Context context, File inputFile, VideoProcessCallback callback) {
        new VideoProcessingTask(context, inputFile, callback).execute();
    }

    private static class VideoProcessingTask extends AsyncTask<Void, Float, File> {
        private final Context context;
        private final File inputFile;
        private final VideoProcessCallback callback;
        private Exception error;
        private long videoDurationMs;
        private Bitmap thumbnail;

        VideoProcessingTask(Context context, File inputFile, VideoProcessCallback callback) {
            this.context = context;
            this.inputFile = inputFile;
            this.callback = callback;
        }

        @Override
        protected void onProgressUpdate(Float... values) {
            if (callback != null) {
                callback.onProgress(values[0]);
            }
        }

        @Override
        protected File doInBackground(Void... voids) {
            try {
                // Create output directory in cache
                File cacheDir = new File(context.getCacheDir(), "video_processing");
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs();
                }

                // Create output file
                final File outputFile = new File(cacheDir,
                        "playable_" + System.currentTimeMillis() + "_" + inputFile.getName());

                // Use remuxing approach for better reliability
                final boolean[] completed = new boolean[1];
                final Exception[] processingError = new Exception[1];

                EnhancedMP4Optimizer.fixVideo(inputFile, outputFile, new EnhancedMP4Optimizer.OptimizerCallback() {
                    @Override
                    public void onProgress(float progress) {
                        publishProgress(progress * 0.9f); // 90% of our task is optimization
                    }

                    @Override
                    public void onSuccess(File resultFile) {
                        synchronized (completed) {
                            completed[0] = true;
                            completed.notify();
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        synchronized (completed) {
                            processingError[0] = e;
                            completed[0] = true;
                            completed.notify();
                        }
                    }
                });

                // Wait for processing to complete
                synchronized (completed) {
                    while (!completed[0]) {
                        try {
                            completed.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Processing interrupted");
                        }
                    }
                }

                // Check for processing error
                if (processingError[0] != null) {
                    throw processingError[0];
                }

                // Extract metadata and thumbnail from processed file
                publishProgress(0.95f); // 95% done

                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(outputFile.getAbsolutePath());

                // Get video duration
                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                videoDurationMs = durationStr != null ? Long.parseLong(durationStr) : 0;

                // Extract thumbnail at 10% of video or 1 second, whichever is longer
                long frameTimeUs = Math.max(1000000, videoDurationMs * 100);
                thumbnail = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

                retriever.release();

                publishProgress(1.0f); // 100% done
                return outputFile;

            } catch (Exception e) {
                Log.e(TAG, "Error processing video", e);
                error = e;
                return null;
            }
        }

        @Override
        protected void onPostExecute(File result) {
            if (error != null || result == null) {
                if (callback != null) {
                    callback.onError(error != null ? error : new IOException("Unknown error processing video"));
                }
            } else {
                if (callback != null) {
                    callback.onVideoProcessed(result, videoDurationMs, thumbnail);
                }
            }
        }
    }

    /**
     * Test if a video file is playable
     *
     * @param videoFile The video file to test
     * @return true if playable, false otherwise
     */
    public static boolean isVideoPlayable(File videoFile) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoFile.getAbsolutePath());

            // Check for video track (this should throw an exception if not playable)
            String hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);

            // Test frame extraction
            Bitmap testFrame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

            boolean result = "yes".equals(hasVideo) && width != null && height != null && testFrame != null;

            if (testFrame != null) {
                testFrame.recycle();
            }

            return result;

        } catch (Exception e) {
            Log.w(TAG, "Video appears to be non-playable: " + e.getMessage());
            return false;
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                    // Ignore release exceptions
                }
            }
        }
    }
}
