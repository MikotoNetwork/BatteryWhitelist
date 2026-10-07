package com.batterywhitelist;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 零依赖的自定义 FileProvider，用于绕过 Android 7.0+ 的 FileUriExposedException。
 * 只实现 openFile 方法，专供安装 APK 使用。
 * @author MikotoNetwork
 * @version 1.30
 */
public class LegacyFileProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        // 将 uri 的 path 部分直接解析为文件路径 (例如: /storage/emulated/0/Android/data/包名/files/update.apk)
        File file = new File(uri.getPath());
        // 以只读方式打开，保证安全
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    // ---------- 以下方法必须实现，但安装 APK 场景用不到，留空即可 ----------

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}