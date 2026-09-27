package com.wxpayrecorder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wxpayrecorder.db.DbHelper;

/**
 * 跨进程接收微信 hook 写入的收款记录。
 * authority: com.wxpayrecorder.provider
 *
 * 调用约定（由微信进程里的 Hooker 使用）：
 *   ContentResolver.call(uri, "insert", null, bundle)
 *   bundle 键：orderNo, amountCent, timeMillis, paySubType, goodsName, sender, feedesc, rawXml
 *   bundle 键：query 时传入 "start"/"limit"
 */
public class PayRecordProvider extends android.content.ContentProvider {

    public static final String AUTHORITY = "com.wxpayrecorder.provider";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    public static final String METHOD_INSERT = "insert";
    public static final String METHOD_QUERY = "query";
    public static final String METHOD_COUNT = "count";
    public static final String METHOD_CLEAR = "clear";

    private DbHelper db;

    @Override
    public boolean onCreate() {
        db = new DbHelper(getContext());
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        Bundle result = new Bundle();
        switch (method) {
            case METHOD_INSERT:
                PayRecord r = new PayRecord();
                r.orderNo = extras.getString("orderNo");
                r.amountCent = extras.getLong("amountCent");
                r.timeMillis = extras.getLong("timeMillis");
                r.paySubType = extras.getInt("paySubType");
                r.goodsName = extras.getString("goodsName");
                r.memo = extras.getString("memo");
                r.sender = extras.getString("sender");
                r.feedesc = extras.getString("feedesc");
                r.rawXml = extras.getString("rawXml");
                long row = db.insert(r);
                // rowId == -1 表示 orderNo 已存在，忽略
                result.putBoolean("inserted", row != -1);
                return result;
            case METHOD_COUNT:
                result.putInt("count", db.all().size());
                return result;
            case METHOD_CLEAR:
                result.putInt("deleted", db.deleteAll());
                return result;
            default:
                return null;
        }
    }

    /** 供 MainActivity 直接读库（同进程）。 */
    public static Cursor queryAll(Context context) {
        return context.getContentResolver().query(
                Uri.parse(AUTHORITY + "/records"), null, null, null, null);
    }

    // 以下 CRUD 本模块不使用，实现为空以符合抽象类要求。
    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return db.getReadableDatabase().query(DbHelper.TABLE, projection, selection,
                selectionArgs, null, null, sortOrder);
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) { return null; }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
                      @Nullable String[] selectionArgs) { return db.deleteAll(); }

    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) { return null; }
}