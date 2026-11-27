package app.venkat.offkit.openwith;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Plays a file manager: stores {@link #EXTRA_BYTES} as {@link #EXTRA_NAME} in its provider and
 * fires {@code ACTION_VIEW} (type {@code application/pdf}, no NEW_TASK) at Offkit's
 * {@code MainActivity} from the test package's own uid, with
 * {@code FLAG_GRANT_READ_URI_PERMISSION} unless {@link #EXTRA_GRANT} is false, then finishes,
 * unless {@link #EXTRA_STAY} is true: it then stays as the app the user came from, showing
 * {@link #LABEL} (Back finishes it).
 */
public final class ViewTrampolineActivity extends Activity {
    public static final String EXTRA_BYTES = "bytes";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_GRANT = "grant";
    public static final String EXTRA_STAY = "stay";
    /** The text shown while it stays. */
    public static final String LABEL = "Trampoline caller";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        boolean stay = in.getBooleanExtra(EXTRA_STAY, false);
        if (stay) {
            TextView label = new TextView(this);
            label.setText(LABEL);
            label.setBackgroundColor(Color.WHITE);
            label.setTextColor(Color.BLACK);
            setContentView(label);
        }
        String name = in.getStringExtra(EXTRA_NAME);
        byte[] bytes = in.getByteArrayExtra(EXTRA_BYTES);
        try {
            File dir = FixtureProvider.dir(this);
            dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                out.write(bytes);
            }
            Uri uri = new Uri.Builder().scheme("content").authority(FixtureProvider.AUTHORITY).appendPath(name).build();
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/pdf")
                    .setComponent(new ComponentName("app.venkat.offkit", "app.venkat.offkit.MainActivity"));
            if (in.getBooleanExtra(EXTRA_GRANT, true)) view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(view);
        } catch (IOException | RuntimeException e) {
            Log.e("ViewTrampoline", "cannot fire VIEW", e);
        }
        if (!stay) finish();
    }
}
