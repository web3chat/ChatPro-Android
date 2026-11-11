package com.fzm.chat.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;


import com.fzm.chat.R;

import java.lang.ref.WeakReference;

/**
 * Created by ZX on 2018/6/29.
 */

public class ClipboardUtils {

    public static void clip(AppCompatActivity appCompatActivity, String string) {
        WeakReference<AppCompatActivity> weakReference = new WeakReference<AppCompatActivity>(appCompatActivity);
        ClipboardManager cm = (ClipboardManager) weakReference.get()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData mClipData = ClipData.newPlainText("Label", string);
        cm.setPrimaryClip(mClipData);
        ToastUtils.show(weakReference.get(), appCompatActivity.getString(R.string.copy_success));
    }
    public static void clipHide(AppCompatActivity appCompatActivity, String string) {
        WeakReference<AppCompatActivity> weakReference = new WeakReference<AppCompatActivity>(appCompatActivity);
        ClipboardManager cm = (ClipboardManager) weakReference.get()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData mClipData = ClipData.newPlainText("Label", string);
        cm.setPrimaryClip(mClipData);
    }
}
