package android.print;

import java.util.concurrent.CountDownLatch;

/**
 * Test-only recorders for the print adapter callbacks, whose constructors are package-private
 * (hence this package). Each records the last result and counts down {@link #done}.
 */
public final class RecordingPrintCallbacks {
    private RecordingPrintCallbacks() {}

    public static final class Layout extends PrintDocumentAdapter.LayoutResultCallback {
        public final CountDownLatch done = new CountDownLatch(1);
        public volatile String result;
        public volatile PrintDocumentInfo info;
        public volatile boolean changed;

        @Override
        public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
            this.info = info;
            this.changed = changed;
            result = "finished";
            done.countDown();
        }

        @Override
        public void onLayoutFailed(CharSequence error) {
            result = "failed";
            done.countDown();
        }

        @Override
        public void onLayoutCancelled() {
            result = "cancelled";
            done.countDown();
        }
    }

    public static final class Write extends PrintDocumentAdapter.WriteResultCallback {
        public final CountDownLatch done = new CountDownLatch(1);
        public volatile String result;
        public volatile PageRange[] pages;

        @Override
        public void onWriteFinished(PageRange[] pages) {
            this.pages = pages;
            result = "finished";
            done.countDown();
        }

        @Override
        public void onWriteFailed(CharSequence error) {
            result = "failed";
            done.countDown();
        }

        @Override
        public void onWriteCancelled() {
            result = "cancelled";
            done.countDown();
        }
    }
}
