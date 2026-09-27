package com.wxpayrecorder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.wxpayrecorder.db.DbHelper;

/**
 * 跨进程接收微信 hook 写入的收款记录和心跳状态。
 * authority: com.wxpayrecorder.provider
 */
public class PayRecordProvider extends android.content.ContentProvider {

    public static final String AUTHORITY = "com.wxpayrecorder.provider";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    public static final String METHOD_INSERT = "insert";
    public static final String METHOD_QUERY = "query";
    public static final String METHOD_COUNT = "count";
    public static final String METHOD_CLEAR = "clear";
    public static final String METHOD_HEARTBEAT = "heartbeat";
    public static final String METHOD_GET_STATUS = "getStatus";

    // 状态键
    public static final String KEY_HOOK_INSTALLED = "hook_installed";
    public static final String KEY_HOOK_CLASS_FOUND = "hook_class_found";
    public static final String KEY_HOOK_METHOD_FOUND = "hook_method_found";
    public static final String KEY_HOOK_ERROR = "hook_error";
    public static final String KEY_LAST_MSG_TIME = "last_msg_time";
    public static final String KEY_LAST_MSG_TYPE = "last_msg_type";
    public static final String KEY_LAST_MSG_AMOUNT = "last_msg_amount";
    public static final String KEY_MSG_COUNT = "msg_count";

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
            case METHOD_HEARTBEAT:
                // 心跳：微信进程 hook 代码上报状态
                if (extras != null) {
                    for (String key : extras.keySet()) {
                        db.putStatus(key, extras.getString(key));
                    }
                }
                result.putBoolean("ok", true);
                return result;
            case METHOD_GET_STATUS:
                // App 端读取全部状态
                result.putString(KEY_HOOK_INSTALLED, db.getStatus(KEY_HOOK_INSTALLED));
                result.putLong(KEY_HOOK_INSTALLED + "_time", db.getStatusTime(KEY_HOOK_INSTALLED));
                result.putString(KEY_HOOK_CLASS_FOUND, db.getStatus(KEY_HOOK_CLASS_FOUND));
                result.putString(KEY_HOOK_METHOD_FOUND, db.getStatus(KEY_HOOK_METHOD_FOUND));
                result.putString(KEY_HOOK_ERROR, db.getStatus(KEY_HOOK_ERROR));
                result.putLong(KEY_HOOK_ERROR + "_time", db.getStatusTime(KEY_HOOK_ERROR));
                result.putString(KEY_LAST_MSG_TIME, db.getStatus(KEY_LAST_MSG_TIME));
                result.putLong(KEY_LAST_MSG_TIME + "_time", db.getStatusTime(KEY_LAST_MSG_TIME));
                result.putString(KEY_LAST_MSG_TYPE, db.getStatus(KEY_LAST_MSG_TYPE));
                result.putString(KEY_LAST_MSG_AMOUNT, db.getStatus(KEY_LAST_MSG_AMOUNT));
                result.putString(KEY_MSG_COUNT, db.getStatus(KEY_MSG_COUNT));
                return result;
            default:
                return null;
        }
    }

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
