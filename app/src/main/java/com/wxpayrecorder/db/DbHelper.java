package com.wxpayrecorder.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.wxpayrecorder.PayRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地 SQLite。
 * - pay_record: 收款记录，orderNo 唯一键去重
 * - hook_status: hook 心跳状态，供 App 端读取
 */
public class DbHelper extends SQLiteOpenHelper {

    public static final String DB = "records.db";
    public static final int VERSION = 3;

    public static final String TABLE = "pay_record";
    public static final String COL_ID = "_id";
    public static final String COL_ORDER = "order_no";
    public static final String COL_AMOUNT = "amount_cent";
    public static final String COL_TIME = "time_millis";
    public static final String COL_SUBTYPE = "pay_subtype";
    public static final String COL_GOODS = "goods_name";
    public static final String COL_MEMO = "memo";
    public static final String COL_SENDER = "sender";
    public static final String COL_FEEDESC = "feedesc";
    public static final String COL_RAW = "raw_xml";

    // hook_status 表
    public static final String TABLE_STATUS = "hook_status";
    public static final String COL_S_KEY = "key";
    public static final String COL_S_VALUE = "value";
    public static final String COL_S_TIME = "updated_at";

    public DbHelper(Context c) {
        super(c, DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COL_ORDER + " TEXT UNIQUE,"
                + COL_AMOUNT + " INTEGER NOT NULL,"
                + COL_TIME + " INTEGER NOT NULL,"
                + COL_SUBTYPE + " INTEGER DEFAULT 0,"
                + COL_GOODS + " TEXT,"
                + COL_MEMO + " TEXT,"
                + COL_SENDER + " TEXT,"
                + COL_FEEDESC + " TEXT,"
                + COL_RAW + " TEXT"
                + ")");
        db.execSQL("CREATE TABLE " + TABLE_STATUS + " ("
                + COL_S_KEY + " TEXT PRIMARY KEY,"
                + COL_S_VALUE + " TEXT,"
                + COL_S_TIME + " INTEGER DEFAULT 0"
                + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_MEMO + " TEXT");
            } catch (Throwable ignore) {
            }
        }
        if (oldV < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_STATUS + " ("
                    + COL_S_KEY + " TEXT PRIMARY KEY,"
                    + COL_S_VALUE + " TEXT,"
                    + COL_S_TIME + " INTEGER DEFAULT 0"
                    + ")");
        }
    }

    // -------- 收款记录 --------

    public long insert(PayRecord r) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        if (r.orderNo != null) v.put(COL_ORDER, r.orderNo);
        v.put(COL_AMOUNT, r.amountCent);
        v.put(COL_TIME, r.timeMillis);
        v.put(COL_SUBTYPE, r.paySubType);
        v.put(COL_GOODS, r.goodsName);
        v.put(COL_MEMO, r.memo);
        v.put(COL_SENDER, r.sender);
        v.put(COL_FEEDESC, r.feedesc);
        v.put(COL_RAW, r.rawXml);
        return db.insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public boolean existsOrder(String orderNo) {
        if (orderNo == null) return false;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE, new String[]{COL_ID}, COL_ORDER + "=?",
                new String[]{orderNo}, null, null, null, "1")) {
            return c.moveToFirst();
        }
    }

    public List<PayRecord> all() {
        List<PayRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE, null, null, null, null, null, COL_TIME + " DESC")) {
            while (c.moveToNext()) {
                list.add(readRow(c));
            }
        }
        return list;
    }

    public int deleteAll() {
        return getWritableDatabase().delete(TABLE, null, null);
    }

    private PayRecord readRow(Cursor c) {
        PayRecord r = new PayRecord();
        r.id = c.getLong(c.getColumnIndexOrThrow(COL_ID));
        r.orderNo = c.getString(c.getColumnIndexOrThrow(COL_ORDER));
        r.amountCent = c.getLong(c.getColumnIndexOrThrow(COL_AMOUNT));
        r.timeMillis = c.getLong(c.getColumnIndexOrThrow(COL_TIME));
        r.paySubType = c.getInt(c.getColumnIndexOrThrow(COL_SUBTYPE));
        r.goodsName = c.getString(c.getColumnIndexOrThrow(COL_GOODS));
        r.memo = c.getString(c.getColumnIndexOrThrow(COL_MEMO));
        r.sender = c.getString(c.getColumnIndexOrThrow(COL_SENDER));
        r.feedesc = c.getString(c.getColumnIndexOrThrow(COL_FEEDESC));
        r.rawXml = c.getString(c.getColumnIndexOrThrow(COL_RAW));
        return r;
    }

    // -------- hook 状态 --------

    public void putStatus(String key, String value) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(COL_S_KEY, key);
        v.put(COL_S_VALUE, value);
        v.put(COL_S_TIME, System.currentTimeMillis());
        db.insertWithOnConflict(TABLE_STATUS, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String getStatus(String key) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE_STATUS, new String[]{COL_S_VALUE},
                COL_S_KEY + "=?", new String[]{key}, null, null, null, "1")) {
            if (c.moveToFirst()) return c.getString(0);
        }
        return null;
    }

    public long getStatusTime(String key) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE_STATUS, new String[]{COL_S_TIME},
                COL_S_KEY + "=?", new String[]{key}, null, null, null, "1")) {
            if (c.moveToFirst()) return c.getLong(0);
        }
        return 0;
    }
}
