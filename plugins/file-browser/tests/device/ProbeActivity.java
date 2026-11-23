package xiao.bu.filebrowserprobe;

import android.app.Activity;
import android.os.Bundle;

/** Isolated test window; never shipped in the channel plugin. */
public final class ProbeActivity extends Activity {
    public void onCreate(Bundle state) { super.onCreate(state); getWindow().addFlags(128); }
}
