package com.fzm.chat.utils;

import android.app.Activity;
import android.widget.Toast;


/**
 * ToastUtils
 *
 * @author
 */
public class ToastUtils {
    private static Toast mToast;

    public static void show(Activity context, int resId) {
        show(context, context.getResources().getText(resId), Toast.LENGTH_SHORT);
    }

    public static void show(Activity context, int resId, int duration) {
        show(context, context.getResources().getText(resId), duration);
    }

    public static void show(Activity context, CharSequence text) {
        show(context, text, Toast.LENGTH_SHORT);
    }

    public static void show(Activity context, String text) {
        if (context == null || context.isFinishing()){
            return;
        }
        if (mToast == null) {
            mToast = Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_SHORT);
        } else {
            mToast.cancel();
            mToast = Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_LONG);
            mToast.setDuration(Toast.LENGTH_SHORT);
        }
        mToast.show();
    }

    public static void showGravity(Activity context, CharSequence text, int gravity) {
        show(context, text, Toast.LENGTH_SHORT, gravity);
    }

    public static void show(Activity context, CharSequence text, int duration) {
        if (context == null || context.isFinishing()){
            return;
        }
        if (mToast == null) {
            mToast = Toast.makeText(context.getApplicationContext(), text, duration);
        } else {
            mToast.setText(text);
            mToast.setDuration(duration);
        }
        mToast.show();
    }

    public static void show(Activity context, CharSequence text, int duration, int gravity) {
        if (context == null || context.isFinishing()){
            return;
        }
        Toast toast = Toast.makeText(context.getApplicationContext(), null, duration);
        toast.setText(text);
        toast.setGravity(gravity, 0, 0);
        toast.show();
    }

    public static void show(Activity context, int resId, Object... args) {
        show(context, String.format(context.getResources().getString(resId), args), Toast.LENGTH_SHORT);
    }

    public static void show(Activity context, String format, Object... args) {
        show(context, String.format(format, args), Toast.LENGTH_SHORT);
    }

    public static void show(Activity context, int resId, int duration, Object... args) {
        show(context, String.format(context.getResources().getString(resId), args), duration);
    }

    public static void show(Activity context, String format, int duration, Object... args) {
        show(context, String.format(format, args), duration);
    }
}
