package com.allinone.app.fb;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Combines a separate video file and audio file into one MP4 using Android's own
 * {@link MediaMuxer}.
 *
 * <p>Preferred over shelling out to ffmpeg. The bundled ffmpeg is a dynamically-linked stub
 * whose libraries are unpacked at runtime, so invoking it depends on getting a child
 * process's environment exactly right — and when that fails it fails opaquely, mid-download.
 * {@code MediaMuxer} is part of the framework: no binary, no environment, no unpacking, and
 * its errors are ordinary Java exceptions.
 *
 * <p>This copies encoded samples across without re-encoding, so it is as fast as ffmpeg's
 * {@code -c copy} and equally lossless. The tradeoff is codec coverage: MP4 output supports
 * H.264/H.265 video with AAC audio, which is what Facebook serves in the overwhelming
 * majority of cases, but not every exotic combination. Callers should keep ffmpeg as a
 * fallback for the rest.
 */
public final class FbMuxer {

    private FbMuxer() {}

    /** Fallback copy buffer when a track does not declare its maximum sample size. */
    private static final int DEFAULT_BUFFER = 1024 * 1024;

    /**
     * Muxes {@code video} and {@code audio} into {@code dest}.
     *
     * @throws IOException if either track is missing or the codec combination is not
     *                     supported by the MP4 muxer.
     */
    public static void mux(File video, File audio, File dest) throws IOException {
        MediaExtractor videoEx = new MediaExtractor();
        MediaExtractor audioEx = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        long videoSamples = 0;
        long audioSamples = 0;

        try {
            videoEx.setDataSource(video.getAbsolutePath());
            audioEx.setDataSource(audio.getAbsolutePath());

            int videoTrack = findTrack(videoEx, "video/");
            if (videoTrack < 0) throw new IOException("no video track in downloaded video file");
            int audioTrack = findTrack(audioEx, "audio/");
            if (audioTrack < 0) throw new IOException("no audio track in downloaded audio file");

            MediaFormat videoFormat = videoEx.getTrackFormat(videoTrack);
            MediaFormat audioFormat = audioEx.getTrackFormat(audioTrack);

            muxer = new MediaMuxer(dest.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int outVideo, outAudio;
            try {
                outVideo = muxer.addTrack(videoFormat);
                outAudio = muxer.addTrack(audioFormat);
            } catch (Exception e) {
                // MP4 output cannot carry VP9 or Opus, which is exactly what Facebook's
                // second DASH ladder uses. Name the codecs so this is not a mystery.
                throw new IOException("MP4 muxer rejected the tracks (video="
                        + videoFormat.getString(MediaFormat.KEY_MIME) + ", audio="
                        + audioFormat.getString(MediaFormat.KEY_MIME) + "): " + e, e);
            }
            muxer.start();
            started = true;

            videoSamples = copyTrack(videoEx, videoTrack, muxer, outVideo, bufferSize(videoFormat));
            audioSamples = copyTrack(audioEx, audioTrack, muxer, outAudio, bufferSize(audioFormat));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // MediaMuxer reports unsupported codecs as IllegalStateException / IAE.
            throw new IOException("MediaMuxer: " + e, e);
        } finally {
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (Exception ignored) {
                    // A stop() failure still needs release() to run below.
                }
                try { muxer.release(); } catch (Exception ignored) {}
            }
            videoEx.release();
            audioEx.release();
        }

        if (!dest.exists() || dest.length() == 0) {
            throw new IOException("MediaMuxer produced an empty file");
        }

        // A track the extractor could not parse yields no samples and no error: the muxer
        // happily writes an empty track and reports success, producing a file that looks
        // merged but plays silent. Treat that as a failure so the ffmpeg fallback gets its
        // turn rather than handing over a silent video.
        if (audioSamples == 0) {
            throw new IOException("audio track produced no samples — "
                    + "the downloaded audio file could not be parsed");
        }
        if (videoSamples == 0) {
            throw new IOException("video track produced no samples — "
                    + "the downloaded video file could not be parsed");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int findTrack(MediaExtractor ex, String mimePrefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    private static int bufferSize(MediaFormat format) {
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            int size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
            if (size > 0) return size;
        }
        return DEFAULT_BUFFER;
    }

    /**
     * Streams every encoded sample of one track into the muxer, timestamps intact.
     *
     * @return how many samples were written — 0 means the extractor could not read the
     *         track, which the caller must treat as a failure.
     */
    private static long copyTrack(MediaExtractor ex, int inTrack,
                                  MediaMuxer muxer, int outTrack, int bufferSize) {
        ex.selectTrack(inTrack);
        ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long samples = 0;

        while (true) {
            int size = ex.readSampleData(buffer, 0);
            if (size < 0) break;

            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;

            muxer.writeSampleData(outTrack, buffer, info);
            samples++;
            ex.advance();
        }
        ex.unselectTrack(inTrack);
        return samples;
    }
}
