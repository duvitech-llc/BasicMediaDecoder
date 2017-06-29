/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.basicmediadecoder;


import android.animation.TimeAnimator;
import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;

import com.example.android.common.media.MediaCodecWrapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

/**
 * This activity uses a {@link android.view.TextureView} to render the frames of a video decoded using
 * {@link android.media.MediaCodec} API.
 */
public class MainActivity extends Activity {
    static String TAG = "MediaCodec";
    private TextureView mPlaybackView;
    private TimeAnimator mTimeAnimator = new TimeAnimator();

    // A utility that wraps up the underlying input and output buffer processing operations
    // into an east to use API.
    private MediaCodecWrapper mCodecWrapper;
    private MediaExtractor mExtractor = new MediaExtractor();
    TextView mAttribView = null;
    private Surface mReaderSurface;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private ImageListener mImageListener;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sample_main);
        mPlaybackView = (TextureView) findViewById(R.id.PlaybackView);
        mAttribView =  (TextView)findViewById(R.id.AttribView);
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mImageListener = new ImageListener();

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.action_menu, menu);
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if(mTimeAnimator != null && mTimeAnimator.isRunning()) {
            mTimeAnimator.end();
        }

        if (mCodecWrapper != null ) {
            mCodecWrapper.stopAndRelease();
            mExtractor.release();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_play) {
            mAttribView.setVisibility(View.VISIBLE);
            startPlayback();
            item.setEnabled(false);
        }
        return true;
    }


    public void startPlayback() {

        // Construct a URI that points to the video resource that we want to play
        Uri videoUri = Uri.parse("android.resource://"
                + getPackageName() + "/"
                + R.raw.vid_bigbuckbunny);

        try {

            // BEGIN_INCLUDE(initialize_extractor)
            mExtractor.setDataSource(this, videoUri, null);
            int nTracks = mExtractor.getTrackCount();

            // Begin by unselecting all of the tracks in the extractor, so we won't see
            // any tracks that we haven't explicitly selected.
            for (int i = 0; i < nTracks; ++i) {
                mExtractor.unselectTrack(i);
            }


            // Find the first video track in the stream. In a real-world application
            // it's possible that the stream would contain multiple tracks, but this
            // sample assumes that we just want to play the first one.
            for (int i = 0; i < nTracks; ++i) {
                // Try to create a video codec for this track. This call will return null if the
                // track is not a video track, or not a recognized video format. Once it returns
                // a valid MediaCodecWrapper, we can break out of the loop.
                final Surface temp = new Surface(mPlaybackView.getSurfaceTexture());
                final ImageReader mImgReader = ImageReader.newInstance(640, 400, ImageFormat.YUV_420_888, 5);
                mReaderSurface = mImgReader.getSurface();
                mImgReader.setOnImageAvailableListener(mImageListener, mHandler);

                mCodecWrapper = MediaCodecWrapper.fromVideoFormat(mExtractor.getTrackFormat(i),
                      temp);
                if (mCodecWrapper != null) {
                    mExtractor.selectTrack(i);
                    break;
                }
            }
            // END_INCLUDE(initialize_extractor)




            // By using a {@link TimeAnimator}, we can sync our media rendering commands with
            // the system display frame rendering. The animator ticks as the {@link Choreographer}
            // receives VSYNC events.
            mTimeAnimator.setTimeListener(new TimeAnimator.TimeListener() {
                @Override
                public void onTimeUpdate(final TimeAnimator animation,
                                         final long totalTime,
                                         final long deltaTime) {

                    boolean isEos = ((mExtractor.getSampleFlags() & MediaCodec
                            .BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM);

                    // BEGIN_INCLUDE(write_sample)
                    if (!isEos) {
                        // Try to submit the sample to the codec and if successful advance the
                        // extractor to the next available sample to read.
                        boolean result = mCodecWrapper.writeSample(mExtractor, false,
                                mExtractor.getSampleTime(), mExtractor.getSampleFlags());

                        if (result) {
                            // Advancing the extractor is a blocking operation and it MUST be
                            // executed outside the main thread in real applications.
                            mExtractor.advance();
                        }
                    }
                    // END_INCLUDE(write_sample)

                    // Examine the sample at the head of the queue to see if its ready to be
                    // rendered and is not zero sized End-of-Stream record.
                    MediaCodec.BufferInfo out_bufferInfo = new MediaCodec.BufferInfo();
                    mCodecWrapper.peekSample(out_bufferInfo);

                    // BEGIN_INCLUDE(render_sample)
                    if (out_bufferInfo.size <= 0 && isEos) {
                        mTimeAnimator.end();
                        mCodecWrapper.stopAndRelease();
                        mExtractor.release();
                    } else if (out_bufferInfo.presentationTimeUs / 1000 < totalTime) {
                        // Pop the sample off the queue and send it to {@link Surface}
                        mCodecWrapper.popSample(true);
                    }
                    // END_INCLUDE(render_sample)

                }
            });

            // We're all set. Kick off the animator to process buffers and render video frames as
            // they become available
            mTimeAnimator.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * <p>Read data from all planes of an Image into a contiguous unpadded, unpacked
     * 1-D linear byte array, such that it can be write into disk, or accessed by
     * software conveniently. It supports YUV_420_888/NV21/YV12 and JPEG input
     * Image format.</p>
     *
     * <p>For YUV_420_888/NV21/YV12/Y8/Y16, it returns a byte array that contains
     * the Y plane data first, followed by U(Cb), V(Cr) planes if there is any
     * (xstride = width, ystride = height for chroma and luma components).</p>
     *
     * <p>For JPEG, it returns a 1-D byte array contains a complete JPEG image.</p>
     */
    public static byte[] getDataFromImage(Image image) {
        assertNotNull("Invalid image:", image);
        int format = image.getFormat();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride, pixelStride;
        byte[] data = null;
        // Read image data
        Image.Plane[] planes = image.getPlanes();
        assertTrue("Fail to get image planes", planes != null && planes.length > 0);
        // Check image validity
        //checkAndroidImageFormat(image);
        ByteBuffer buffer = null;
        // JPEG doesn't have pixelstride and rowstride, treat it as 1D buffer.
        if (format == ImageFormat.JPEG) {
            buffer = planes[0].getBuffer();
            assertNotNull("Fail to get jpeg ByteBuffer", buffer);
            data = new byte[buffer.capacity()];
            buffer.get(data);
            return data;
        }
        int offset = 0;
        data = new byte[width * height * ImageFormat.getBitsPerPixel(format) / 8];
        byte[] rowData = new byte[planes[0].getRowStride()];

        Log.v(TAG, "get data from " + planes.length + " planes");

        for (int i = 0; i < planes.length; i++) {
            buffer = planes[i].getBuffer();
            assertNotNull("Fail to get bytebuffer from plane", buffer);
            rowStride = planes[i].getRowStride();
            assertTrue("rowStride should be no less than width", rowStride >= width);
            pixelStride = planes[i].getPixelStride();
            assertTrue("pixel stride " + pixelStride + " is invalid", pixelStride > 0);

            Log.v(TAG, "pixelStride " + pixelStride);
            Log.v(TAG, "rowStride " + rowStride);
            Log.v(TAG, "width " + width);
            Log.v(TAG, "height " + height);

            // For multi-planar yuv images, assuming yuv420 with 2x2 chroma subsampling.
            int w = (i == 0) ? width : width / 2;
            int h = (i == 0) ? height : height / 2;
            assertTrue("rowStride " + rowStride + " should be >= width " + w , rowStride >= w);
            for (int row = 0; row < h; row++) {
                int bytesPerPixel = ImageFormat.getBitsPerPixel(format) / 8;
                if (pixelStride == bytesPerPixel) {
                    // Special case: optimized read of the entire row
                    int length = w * bytesPerPixel;
                    buffer.get(data, offset, length);
                    // Advance buffer the remainder of the row stride
                    buffer.position(buffer.position() + rowStride - length);
                    offset += length;
                } else {
                    // Generic case: should work for any pixelStride but slower.
                    // Use intermediate buffer to avoid read byte-by-byte from
                    // DirectByteBuffer, which is very bad for performance
                    buffer.get(rowData, 0, rowStride);
                    for (int col = 0; col < w; col++) {
                        data[offset++] = rowData[col * pixelStride];
                    }
                }
            }

            Log.v(TAG, "Finished reading data from plane " + i);
        }
        return data;
    }


    private static class ImageListener implements ImageReader.OnImageAvailableListener {
        private final LinkedBlockingQueue<Image> mQueue =
                new LinkedBlockingQueue<Image>();
        @Override
        public void onImageAvailable(ImageReader reader) {
            final Image img = reader.acquireLatestImage();
            if(img != null){
                //YuvImage yuv = new YuvImage(img.getPlanes()[0].getBuffer().array(), img.getFormat(), img.getWidth(), img.getHeight(), null);
                //getDataFromImage(img);
                img.close();
            }



        }
        /**
         * Get an image from the image reader.
         *
         * @param timeout Timeout value for the wait.
         * @return The image from the image reader.
         */
        public Image getImage(long timeout) throws InterruptedException {
            Image image = mQueue.poll(timeout, TimeUnit.MILLISECONDS);
            assertNotNull("Wait for an image timed out in " + timeout + "ms", image);
            return image;
        }
    }
}
