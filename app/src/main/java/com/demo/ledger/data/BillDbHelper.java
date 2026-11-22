package com.demo.ledger.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class BillDbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "ledger.db";
    private static final int DB_VERSION = 1;

    private static volatile BillDbHelper instance;

    public static BillDbHelper get(Context context) {
        if (instance == null) {
            synchronized (BillDbHelper.class) {
                if (instance == null) {
                    instance = new BillDbHelper(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private BillDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE bill ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "amount_cents INTEGER NOT NULL,"
                + "merchant TEXT,"
                + "category TEXT,"
                + "occurred_at INTEGER NOT NULL,"
                + "image_path TEXT,"
                + "source TEXT,"
                + "raw_response TEXT,"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_bill_occurred ON bill(occurred_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 单表 MVP，暂无升级路径
    }
}
