package dev.codex.envpeer;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class PeerActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("Android Env Peer ready");
        setContentView(text);
    }
}
