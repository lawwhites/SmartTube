package com.liskovsoft.smartyoutubetv2.common.app.views;

import android.content.Intent;

public interface SplashView {
    Intent getNewIntent();
    void finishView();
    /** Shows boot progress (e.g. proxy node detection) on the splash screen. */
    void updateStatus(CharSequence message);
}
