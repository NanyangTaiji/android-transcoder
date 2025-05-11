package net.ypresto.androidtranscoder.example;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for optimizing MP4 files by moving the MOOV atom to the beginning of the file.
 * This enables fast start streaming without re-encoding the video content.
 */
public class MP4MoovOptimizer {
    private static final String TAG = "MP4MoovOptimizer";
    private static final boolean DEBUG = true;

    // Listener for tracking optimization progress
    public interface OptimizerListener {
        void onProgress(float progress);
        void onSuccess(File outputFile);
        void onError(Exception e);
    }

    // Atom container class
    private static class Atom {
        String type;
        long position;  // Position of the atom data (after header)
        long size;      // Size of the atom data (not including header)
        long headerPosition; // Position of the atom header
        long headerSize; // Size of the header (8 or 16 bytes for extended size)

        Atom(String type, long headerPosition, long position, long size, long headerSize) {
            this.type = type;
            this.headerPosition = headerPosition;
            this.position = position;
            this.size = size;
            this.headerSize = headerSize;
        }

        long getTotalSize() {
            return size + headerSize;
        }

        @Override
        public String toString() {
            return "Atom{type='" + type + "', headerPos=" + headerPosition +
                    ", dataPos=" + position + ", size=" + size +
                    ", headerSize=" + headerSize + "}";
        }
    }

    /**
     * Optimize an MP4 file by moving the MOOV atom to the beginning.
     *
     * @param inputFile Input MP4 file
     * @param outputFile Output optimized MP4 file
     * @param listener Progress and completion listener
     */
    public static void optimize(final File inputFile, final File outputFile, final OptimizerListener listener) {
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

                // Check if file is already optimized
                if (isOptimized(inputFile)) {
                    // Just copy the file as it's already optimized
                    copyFile(inputFile, outputFile);
                    if (listener != null) {
                        listener.onSuccess(outputFile);
                    }
                    return;
                }

                // Parse the input file to find atoms
                List<Atom> atoms = parseAtoms(inputFile);

                if (DEBUG) {
                    Log.d(TAG, "Found atoms:");
                    for (Atom atom : atoms) {
                        Log.d(TAG, atom.toString());
                    }
                }

                // Create a map for easier atom lookup
                Map<String, Atom> atomMap = new HashMap<>();
                for (Atom atom : atoms) {
                    atomMap.put(atom.type, atom);
                }

                // Ensure we have the required atoms
                if (!atomMap.containsKey("moov")) {
                    throw new IOException("No moov atom found in the input file");
                }

                if (!atomMap.containsKey("mdat")) {
                    throw new IOException("No mdat atom found in the input file");
                }

                Atom ftypAtom = atomMap.get("ftyp");
                Atom moovAtom = atomMap.get("moov");
                Atom mdatAtom = atomMap.get("mdat");

                // Calculate new positions in the optimized file
                long currentPos = 0;

                // If we have ftyp, it comes first
                if (ftypAtom != null) {
                    currentPos += ftypAtom.getTotalSize();
                }

                // Then comes moov
                long moovOffset = currentPos;
                currentPos += moovAtom.getTotalSize();

                // Then mdat
                long mdatOffset = currentPos;

                // Calculate the shift for mdat from its original position
                long mdatShift = mdatOffset - mdatAtom.headerPosition;

                if (DEBUG) {
                    Log.d(TAG, "Original mdat position: " + mdatAtom.headerPosition);
                    Log.d(TAG, "New mdat position: " + mdatOffset);
                    Log.d(TAG, "Mdat shift: " + mdatShift);
                }

                // Create and manipulate a copy of the moov atom
                byte[] moovData = readAtomData(inputFile, moovAtom);

                // Update chunk offsets in moov
                ByteBuffer moovBuffer = ByteBuffer.wrap(moovData);
                moovBuffer.order(ByteOrder.BIG_ENDIAN);

                // Process moov contents
                updateChunkOffsets(moovBuffer, mdatShift);

                // Now write the output file
                try (RandomAccessFile inputRaf = new RandomAccessFile(inputFile, "r");
                     FileOutputStream outputStream = new FileOutputStream(outputFile)) {

                    // Track progress
                    long totalBytes = inputFile.length();
                    long bytesProcessed = 0;

                    // 1. Write ftyp atom if present
                    if (ftypAtom != null) {
                        byte[] ftypData = new byte[(int)ftypAtom.getTotalSize()];
                        inputRaf.seek(ftypAtom.headerPosition);
                        inputRaf.readFully(ftypData);
                        outputStream.write(ftypData);
                        bytesProcessed += ftypData.length;

                        // Report progress
                        if (listener != null) {
                            listener.onProgress((float)bytesProcessed / totalBytes);
                        }
                    }

                    // 2. Write the modified moov atom
                    // Write moov header
                    if (moovAtom.headerSize == 8) {
                        // Standard 32-bit size header
                        ByteBuffer moovHeader = ByteBuffer.allocate(8);
                        moovHeader.order(ByteOrder.BIG_ENDIAN);
                        moovHeader.putInt((int)(moovAtom.size + 8)); // Size including header
                        moovHeader.put("moov".getBytes());
                        moovHeader.flip();
                        outputStream.write(moovHeader.array());
                    } else {
                        // Extended 64-bit size header
                        ByteBuffer moovHeader = ByteBuffer.allocate(16);
                        moovHeader.order(ByteOrder.BIG_ENDIAN);
                        moovHeader.putInt(1); // Size marker for extended size
                        moovHeader.put("moov".getBytes());
                        moovHeader.putLong(moovAtom.size + 16); // 64-bit size including header
                        moovHeader.flip();
                        outputStream.write(moovHeader.array());
                    }

                    // Write moov data
                    outputStream.write(moovData);
                    bytesProcessed += moovAtom.getTotalSize();

                    // Report progress
                    if (listener != null) {
                        listener.onProgress((float)bytesProcessed / totalBytes);
                    }

                    // 3. Write mdat atom - copy it directly without modification
                    writeAtomWithContents(inputRaf, outputStream, mdatAtom);
                    bytesProcessed += mdatAtom.getTotalSize();

                    // Report progress
                    if (listener != null) {
                        listener.onProgress((float)bytesProcessed / totalBytes);
                    }

                    // 4. Write any remaining atoms that aren't ftyp, moov, or mdat
                    for (Atom atom : atoms) {
                        if (!"ftyp".equals(atom.type) && !"moov".equals(atom.type) && !"mdat".equals(atom.type)) {
                            writeAtomWithContents(inputRaf, outputStream, atom);
                            bytesProcessed += atom.getTotalSize();

                            // Report progress
                            if (listener != null) {
                                listener.onProgress((float)bytesProcessed / totalBytes);
                            }
                        }
                    }

                    // Final progress update
                    if (listener != null) {
                        listener.onProgress(1.0f);
                        listener.onSuccess(outputFile);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error optimizing MP4 file", e);
                if (listener != null) {
                    listener.onError(e);
                }
            }
        }).start();
    }

    /**
     * Write an atom and its contents to the output stream
     */
    private static void writeAtomWithContents(RandomAccessFile input, FileOutputStream output, Atom atom)
            throws IOException {
        input.seek(atom.headerPosition);

        // Read the header first
        byte[] header = new byte[(int)atom.headerSize];
        input.readFully(header);
        output.write(header);

        // Now read and write the data in chunks
        long remaining = atom.size;
        byte[] buffer = new byte[8192];

        while (remaining > 0) {
            int readSize = (int)Math.min(buffer.length, remaining);
            int bytesRead = input.read(buffer, 0, readSize);

            if (bytesRead == -1) break;

            output.write(buffer, 0, bytesRead);
            remaining -= bytesRead;
        }
    }

    /**
     * Read the raw data of an atom from the file
     */
    private static byte[] readAtomData(File file, Atom atom) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] data = new byte[(int)atom.size];
            raf.seek(atom.position);
            raf.readFully(data);
            return data;
        }
    }

    /**
     * Update all chunk offset tables (stco and co64) in the moov atom
     */
    private static void updateChunkOffsets(ByteBuffer buffer, long mdatShift) {
        if (DEBUG) {
            Log.d(TAG, "Updating chunk offsets with shift: " + mdatShift);
        }

        // Create a new buffer for easier navigation
        ByteBuffer moovData = buffer.duplicate();
        moovData.order(ByteOrder.BIG_ENDIAN);

        // Process all atoms in moov recursively
        processContainer(moovData, mdatShift, 0);
    }

    /**
     * Process atoms in a container atom
     * @param data Buffer containing the container data
     * @param mdatShift Shift to apply to chunk offsets
     * @param depth Recursion depth for better debug logging
     */
    private static void processContainer(ByteBuffer data, long mdatShift, int depth) {
        int containerStart = data.position();
        int containerEnd = data.limit();

        String indent = "";
        for (int i = 0; i < depth; i++) {
            indent += "  ";
        }

        // Process all atoms in this container
        while (data.position() < containerEnd - 8) {
            int atomStart = data.position();
            int atomSize = data.getInt();

            // Check for valid atom size
            if (atomSize < 8 || atomStart + atomSize > containerEnd) {
                if (DEBUG) {
                    Log.d(TAG, indent + "Invalid atom size: " + atomSize + " at pos " + atomStart);
                }
                break;
            }

            byte[] typeBytes = new byte[4];
            data.get(typeBytes);
            String atomType = new String(typeBytes);

            int atomEnd = atomStart + atomSize;

            if (DEBUG) {
                Log.d(TAG, indent + "Processing atom: " + atomType + " at pos " + atomStart +
                        ", size " + atomSize + ", end " + atomEnd);
            }

            // Handle each atom type
            if ("stco".equals(atomType)) {
                // Process 32-bit chunk offset table
                processStcoAtom(data, mdatShift, depth);
            } else if ("co64".equals(atomType)) {
                // Process 64-bit chunk offset table
                processCo64Atom(data, mdatShift, depth);
            } else if ("trak".equals(atomType)) {
                // For track atoms, we need to process all the way down
                int dataPos = data.position();
                ByteBuffer containerBuffer = data.duplicate();
                containerBuffer.order(ByteOrder.BIG_ENDIAN);
                containerBuffer.position(dataPos);
                containerBuffer.limit(atomEnd);
                processContainer(containerBuffer, mdatShift, depth + 1);
            } else if (isContainerAtom(atomType)) {
                // Recursively process other container atoms
                int dataPos = data.position();
                ByteBuffer containerBuffer = data.duplicate();
                containerBuffer.order(ByteOrder.BIG_ENDIAN);
                containerBuffer.position(dataPos);
                containerBuffer.limit(atomEnd);
                processContainer(containerBuffer, mdatShift, depth + 1);
            }

            // Move to next atom
            data.position(atomEnd);
        }
    }

    /**
     * Check if atom type is a container that needs recursion
     */
    private static boolean isContainerAtom(String type) {
        return "moov".equals(type) || "trak".equals(type) ||
                "mdia".equals(type) || "minf".equals(type) ||
                "stbl".equals(type) || "edts".equals(type) ||
                "mvex".equals(type) || "udta".equals(type);
    }

    /**
     * Process stco atom (32-bit chunk offset table)
     */
    private static void processStcoAtom(ByteBuffer data, long mdatShift, int depth) {
        String indent = "";
        for (int i = 0; i < depth; i++) {
            indent += "  ";
        }

        // Save current position
        int startPos = data.position();

        // Skip version & flags (4 bytes)
        data.position(startPos + 4);

        // Read entry count
        int entryCount = data.getInt();

        if (DEBUG) {
            Log.d(TAG, indent + "Processing stco atom with " + entryCount + " entries");
        }

        // Update each entry
        for (int i = 0; i < entryCount; i++) {
            int offset = data.getInt();

            // Calculate new offset
            long newOffset = offset + mdatShift;

            // Handle potential integer overflow
            if (newOffset > Integer.MAX_VALUE) {
                Log.e(TAG, indent + "Warning: Chunk offset exceeds 32-bit limit after shift");
                newOffset = Integer.MAX_VALUE;
            } else if (newOffset < 0) {
                Log.e(TAG, indent + "Warning: Negative chunk offset after shift");
                newOffset = 0;
            }

            data.position(data.position() - 4); // Go back 4 bytes
            data.putInt((int)newOffset);

            if (DEBUG && (i < 5 || i >= entryCount - 5)) {
                Log.d(TAG, indent + "Updated chunk offset " + i + " from " + offset + " to " + newOffset);
            }
        }
    }

    /**
     * Process co64 atom (64-bit chunk offset table)
     */
    private static void processCo64Atom(ByteBuffer data, long mdatShift, int depth) {
        String indent = "";
        for (int i = 0; i < depth; i++) {
            indent += "  ";
        }

        // Save current position
        int startPos = data.position();

        // Skip version & flags (4 bytes)
        data.position(startPos + 4);

        // Read entry count
        int entryCount = data.getInt();

        if (DEBUG) {
            Log.d(TAG, indent + "Processing co64 atom with " + entryCount + " entries");
        }

        // Update each entry
        for (int i = 0; i < entryCount; i++) {
            long offset = data.getLong();

            // Calculate new offset (with underflow check)
            long newOffset = offset + mdatShift;
            if (newOffset < 0) {
                Log.e(TAG, indent + "Warning: Negative chunk offset after shift");
                newOffset = 0;
            }

            data.position(data.position() - 8); // Go back 8 bytes
            data.putLong(newOffset);

            if (DEBUG && (i < 5 || i >= entryCount - 5)) {
                Log.d(TAG, indent + "Updated chunk offset " + i + " from " + offset + " to " + newOffset);
            }
        }
    }

    /**
     * Check if the MP4 file is already optimized (moov atom before mdat)
     */
    public static boolean isOptimized(File file) throws IOException {
        List<Atom> atoms = parseAtoms(file);

        long moovPos = -1;
        long mdatPos = -1;

        for (Atom atom : atoms) {
            if ("moov".equals(atom.type)) {
                moovPos = atom.headerPosition;
            } else if ("mdat".equals(atom.type)) {
                mdatPos = atom.headerPosition;
            }

            // If we have both positions, we can determine
            if (moovPos != -1 && mdatPos != -1) {
                return moovPos < mdatPos;
            }
        }

        // Default to not optimized
        return false;
    }

    /**
     * Parse all atoms in the MP4 file
     */
    private static List<Atom> parseAtoms(File file) throws IOException {
        List<Atom> atoms = new ArrayList<>();

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            ByteBuffer headerBuffer = ByteBuffer.allocate(16); // Large enough for extended size
            headerBuffer.order(ByteOrder.BIG_ENDIAN);

            long fileSize = raf.length();
            long position = 0;

            while (position < fileSize - 8) {
                raf.seek(position);

                // Read standard header (8 bytes)
                headerBuffer.clear();
                headerBuffer.limit(8);
                // Correct way to read to ByteBuffer using FileChannel
                int bytesRead = raf.getChannel().read(headerBuffer);
                if (bytesRead < 8) {
                    break; // Not enough data to read atom header
                }
                headerBuffer.flip();

                int atomSize = headerBuffer.getInt();
                byte[] typeBytes = new byte[4];
                headerBuffer.get(typeBytes);
                String atomType = new String(typeBytes);

                // Handle invalid atom
                if (atomSize < 8) {
                    if (DEBUG) {
                        Log.d(TAG, "Invalid atom size: " + atomSize + " at position " + position);
                    }
                    break;
                }

                long dataSize = atomSize - 8;
                long headerSize = 8;

                // Handle 64-bit size atoms
                if (atomSize == 1) {
                    // Extended size atom (64-bit)
                    headerBuffer.clear();
                    headerBuffer.limit(8);
                    // Correct way to read extended size
                    bytesRead = raf.getChannel().read(headerBuffer);
                    if (bytesRead < 8) {
                        break; // Not enough data for extended size
                    }
                    headerBuffer.flip();
                    long longSize = headerBuffer.getLong();

                    dataSize = longSize - 16; // 8 for normal header + 8 for extended size
                    headerSize = 16; // The header is now 16 bytes total

                    if (DEBUG) {
                        Log.d(TAG, "Extended size atom: " + atomType + " with 64-bit size: " + longSize);
                    }
                }

                // Add the atom to our list
                atoms.add(new Atom(atomType, position, position + headerSize, dataSize, headerSize));

                // Calculate next atom position
                if (atomSize == 0) {
                    // Atom extends to end of file
                    break;
                }

                // Use the total size (header + data) for the next position
                position += (headerSize + dataSize);

                // Sanity check
                if (position > fileSize) {
                    if (DEBUG) {
                        Log.d(TAG, "Atom extends beyond file end: " + atomType +
                                " at " + (position - headerSize - dataSize));
                    }
                    break;
                }
            }
        }

        return atoms;
    }

    /**
     * Copy a file (used when input is already optimized)
     */
    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            FileChannel inChannel = in.getChannel();
            FileChannel outChannel = out.getChannel();
            inChannel.transferTo(0, inChannel.size(), outChannel);
        }
    }
}