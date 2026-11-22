package com.demo.ledger.util;

import android.app.Activity;
import android.transition.Fade;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;

/**
 * 页面切换的流体动效：进入淡入、退出快速淡出，曲线与 Material 缓动一致。
 */
public final class Motion {

    private Motion() {
    }

    public static void applyWindow(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }

        Fade enter = new Fade(Fade.IN);
        enter.setDuration(260);
        enter.setInterpolator(new DecelerateInterpolator(1.6f));

        Fade exit = new Fade(Fade.OUT);
        exit.setDuration(200);
        exit.setInterpolator(new DecelerateInterpolator(1.6f));

        window.setEnterTransition(enter);
        window.setReenterTransition(enter);
        window.setExitTransition(exit);
        window.setReturnTransition(exit);
    }
}
