package org.opensagetv.vibe.ffmpeg;

import java.io.IOException;
import sage.DVDStreamTransform;
import sage.DVDStreamTransformProvider;
import sage.DVDStreamTransformRequest;

/** Legacy optional-Core SPI adapter; process ownership is stock-loadable. */
public class MimDVDStreamTransformProvider extends StockDvdStreamService
        implements DVDStreamTransformProvider {
    public MimDVDStreamTransformProvider() { super(); }

    protected MimDVDStreamTransformProvider(String transport, String mode, String format) {
        super(transport, mode, format);
    }

    public DVDStreamTransform open(DVDStreamTransformRequest request) throws IOException {
        if (request == null || !getTransportId().equals(request.getTransportId()))
            throw new IOException("unsupported DVD transform transport");
        final StockDvdStreamService.Session session = super.open(request.getVideoBitrate());
        return new DVDStreamTransform() {
            public void write(byte[] data, int offset, int length) throws IOException {
                session.write(data, offset, length);
            }
            public byte[] pollOutput(long timeoutMillis) throws IOException {
                return session.pollOutput(timeoutMillis);
            }
            public boolean isOutputEnded() { return session.isOutputEnded(); }
            public void closeInput() { session.closeInput(); }
            public void close() { session.close(); }
        };
    }
}
