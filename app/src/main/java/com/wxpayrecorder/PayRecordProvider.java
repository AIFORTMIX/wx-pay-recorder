package com.wxpayrecorder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.wxpayrecorder.db.DbHelper;

/**
 * 跨进程接收微信 hook 写入的收款记录。
 * authority: com.wxpayrecorder.provider
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

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
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

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return db.getReadableDatabase().query(DbHelper.TABLE, projection, selection,
                selectionArgs, null, null, sortOrder);
    }

    @Override
    public String getType(Uri uri) { return null; }

    @Override
    public int update(Uri uri, ContentValues values,
                      String selection, String[] selectionArgs) { return 0; }

    @Override
    public int delete(Uri uri, String selection,
                      String[] selectionArgs) { return db.deleteAll(); }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }
}
