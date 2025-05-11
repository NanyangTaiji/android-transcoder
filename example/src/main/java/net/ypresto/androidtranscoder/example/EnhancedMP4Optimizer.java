package net.ypresto.androidtranscoder.example;


import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Enhanced MP4 optimizer that fixes issues with non-playable videos while preserving metadata
 * This implementation remuxes the video and audio tracks to ensure playability
 */
public class EnhancedMP4Optimizer {
    private static final String TAG = "EnhancedMP4Optimizer";
    private static final int BUFFER_SIZE = 1024 * 1024; // 1MB buffer

    // Callback interface for optimization progress
    public interface OptimizerCallback {
        void onProgress(float progress);
        void onSuccess(File outputFile);
        void onError(Exception e);
    }

    /**
     * Fix MP4 video to ensure it's playable while preserving metadata
     * Uses remuxing approach which is more reliable than atom manipulation
     *
     * @param inputFile Input MP4 file
     * @param outputFile Output fixed MP4 file
     * @param callback Progress callback
     */
    public static void fixVideo(final File inputFile, final File outputFile, final OptimizerCallback callback) {
        new Thread(() -> {
            try {
                // Make sure output directory exists
                if (!outputFile.getParentFile().exists()) {
                    outputFile.getParentFile().mkdirs();
                }

                // If output file exists, delete it
                if (outputFile.exists()) {
                    outputFile.delete();
                }

                // Check if the file is already optimized and playable
                if (isOptimizedAndPlayable(inputFile)) {
                    // Just copy the file as it's already good
                    copyFile(inputFile, outputFile);
                    if (callback != null) {
                        callback.onSuccess(outputFile);
                    }
                    return;
                }

                // Remux the video - this is the most reliable way to fix playability issues
                remuxVideo(inputFile, outputFile, callback);

            } catch (Exception e) {
                Log.e(TAG, "Error optimizing video", e);
                if (callback != null) {
                    callback.onError(e);
                }
            }
        }).start();
    }

    /**
     * Check if the MP4 file is already optimized and playable
     */
    private static boolean isOptimizedAndPlayable(File file) {
        try {
            // First check if MOOV atom is at beginning (fast check)
            boolean moovOptimized = MP4MoovOptimizer.isOptimized(file);
            if (!moovOptimized) {
                return false;
            }

            // Additional check - try to extract tracks which will verify playability
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(file.getAbsolutePath());
            int numTracks = extractor.getTrackCount();
            boolean hasVideoTrack = false;

            for (int i = 0; i < numTracks; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    hasVideoTrack = true;
                    break;
                }
            }

            extractor.release();
            return hasVideoTrack;

        } catch (Exception e) {
            Log.e(TAG, "Error checking if file is optimized and playable", e);
            return false;
        }
    }
    /**
     * Remux video by extracting and repackaging all tracks
     * This fixes most playability issues
     */
    private static void remuxVideo(File inputFile, File outputFile, OptimizerCallback callback) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;

        try {
            extractor.setDataSource(inputFile.getAbsolutePath());
            int trackCount = extractor.getTrackCount();

            if (trackCount == 0) {
                throw new IOException("No tracks found in input file");
            }

            // Create output muxer
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // Maps input track indices to output track indices
            int[] inputToOutputTrackMap = new int[trackCount];

            // Select and add all tracks to muxer
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                inputToOutputTrackMap[i] = muxer.addTrack(format);
            }

            // Start muxing
            muxer.start();

            // Buffer for reading samples
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);

            // Create a BufferInfo object to hold sample metadata
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            // Keep track of total frames for progress calculation
            long totalBytes = inputFile.length();
            long processedBytes = 0;

            // Process each track
            for (int trackIndex = 0; trackIndex < trackCount; trackIndex++) {
                // Reset extractor to beginning
                extractor.unselectTrack(trackIndex);
                extractor.selectTrack(trackIndex);

                // Prepare buffers and info objects
                int outputTrackIndex = inputToOutputTrackMap[trackIndex];
                int sampleSize;

                // Read all samples from current track
                while ((sampleSize = extractor.readSampleData(buffer, 0)) >= 0) {
                    // Update progress
                    processedBytes += sampleSize;
                    if (callback != null) {
                        float progress = (float) processedBytes / totalBytes;
                        callback.onProgress(Math.min(0.95f, progress)); // Cap at 95%
                    }

                    // Set sample info in the bufferInfo object
                    bufferInfo.size = sampleSize;
                    bufferInfo.presentationTimeUs = extractor.getSampleTime();
                    bufferInfo.flags = MediaCodec.BUFFER_FLAG_SYNC_FRAME;
                    bufferInfo.offset = 0;

                    // Write sample to muxer using the proper 3-argument method
                    muxer.writeSampleData(outputTrackIndex, buffer, bufferInfo);

                    // Advance to next sample
                    extractor.advance();
                }

                // Unselect track before moving to next one
                extractor.unselectTrack(trackIndex);
            }

            // Final progress update
            if (callback != null) {
                callback.onProgress(0.98f); // 98% complete
            }

            // Successfully completed
            if (callback != null) {
                callback.onSuccess(outputFile);
            }

        } finally {
            // Clean up resources
            extractor.release();
            if (muxer != null) {
                try {
                    muxer.stop();
                    muxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing muxer", e);
                }
            }
        }
    }

    /**
     * Copy a file (used when input is already optimized and playable)
     */
    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192]; // 8KB buffer
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }
}
