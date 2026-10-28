package com.openclock;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.Toast;
import androidx.wear.widget.DismissibleFrameLayout;
import androidx.wear.widget.SwipeDismissFrameLayout;

/** Back navigation never dismisses or snoozes the ringing occurrence. */
final class RingingNavigation {
    static View wrap(Activity activity, View screen) {
        SwipeDismissFrameLayout root = new SwipeDismissFrameLayout(activity);
        root.setSwipeable(true);
        root.setBackButtonDismissible(false);
        root.addView(screen);
        root.registerCallback(new DismissibleFrameLayout.Callback() {
            @Override public void onDismissFinished(DismissibleFrameLayout layout) {
                if (layout.isAttachedToWindow() && layout.hasWindowFocus() && !activity.isFinishing())
                    activity.onBackPressed();
            }
        });
        return root;
    }

    static boolean backToListIfRoot(Activity activity) {
        if (!activity.isTaskRoot()) return false;
        try {
            activity.startActivity(new Intent(activity, MainActivity.class));
            activity.finish();
        } catch (RuntimeException unavailable) {
            Toast.makeText(activity, "Could not open the alarm list", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private RingingNavigation() { }
}
