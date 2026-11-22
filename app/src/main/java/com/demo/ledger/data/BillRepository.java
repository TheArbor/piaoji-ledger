package com.demo.ledger.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;

public class BillRepository {

    private final BillDbHelper helper;

    public BillRepository(Context context) {
        helper = BillDbHelper.get(context);
    }

    public long insert(Bill bill) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("amount_cents", bill.amountCents);
        values.put("merchant", bill.merchant);
        values.put("category", bill.category);
        values.put("occurred_at", bill.occurredAt);
        values.put("image_path", bill.imagePath);
        values.put("source", bill.source);
        values.put("raw_response", bill.rawResponse);
        values.put("created_at", bill.createdAt == 0 ? System.currentTimeMillis() : bill.createdAt);
        return db.insertOrThrow("bill", null, values);
    }

    public List<Bill> listByMonth(int year, int month) {
        long[] range = monthRange(year, month);
        return listByRange(range[0], range[1]);
    }

    public List<Bill> listByRange(long start, long end) {
        List<Bill> result = new ArrayList<>();
        Cursor cursor = helper.getReadableDatabase().query(
                "bill", null,
                "occurred_at >= ? AND occurred_at < ?",
                new String[]{String.valueOf(start), String.valueOf(end)},
                null, null, "occurred_at DESC, id DESC");
        try {
            while (cursor.moveToNext()) {
                result.add(read(cursor));
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    public long totalCents(int year, int month) {
        long[] range = monthRange(year, month);
        return totalCents(range[0], range[1]);
    }

    public long totalCents(long start, long end) {
        Cursor cursor = helper.getReadableDatabase().rawQuery(
                "SELECT SUM(amount_cents) FROM bill WHERE occurred_at >= ? AND occurred_at < ?",
                new String[]{String.valueOf(start), String.valueOf(end)});
        try {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : 0L;
        } finally {
            cursor.close();
        }
    }

    /**
     * @return 分类 -> [金额分, 笔数]，按金额降序
     */
    public LinkedHashMap<String, long[]> categoryTotals(int year, int month) {
        long[] range = monthRange(year, month);
        return categoryTotals(range[0], range[1]);
    }

    public LinkedHashMap<String, long[]> categoryTotals(long start, long end) {
        LinkedHashMap<String, long[]> result = new LinkedHashMap<>();
        Cursor cursor = helper.getReadableDatabase().rawQuery(
                "SELECT category, SUM(amount_cents), COUNT(*) FROM bill "
                        + "WHERE occurred_at >= ? AND occurred_at < ? "
                        + "GROUP BY category ORDER BY SUM(amount_cents) DESC",
                new String[]{String.valueOf(start), String.valueOf(end)});
        try {
            while (cursor.moveToNext()) {
                String category = cursor.getString(0);
                result.put(category == null ? Bill.CATEGORY_OTHER : category,
                        new long[]{cursor.getLong(1), cursor.getLong(2)});
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    /**
     * @return List of [商家, 金额分]，按金额降序
     */
    public List<String[]> topMerchants(int year, int month, int limit) {
        long[] range = monthRange(year, month);
        return topMerchants(range[0], range[1], limit);
    }

    public List<String[]> topMerchants(long start, long end, int limit) {
        List<String[]> result = new ArrayList<>();
        Cursor cursor = helper.getReadableDatabase().rawQuery(
                "SELECT merchant, SUM(amount_cents) AS s FROM bill "
                        + "WHERE occurred_at >= ? AND occurred_at < ? "
                        + "GROUP BY merchant ORDER BY s DESC LIMIT ?",
                new String[]{String.valueOf(start), String.valueOf(end), String.valueOf(limit)});
        try {
            while (cursor.moveToNext()) {
                result.add(new String[]{cursor.getString(0), String.valueOf(cursor.getLong(1))});
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    private Bill read(Cursor cursor) {
        Bill bill = new Bill();
        bill.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        bill.amountCents = cursor.getLong(cursor.getColumnIndexOrThrow("amount_cents"));
        bill.merchant = cursor.getString(cursor.getColumnIndexOrThrow("merchant"));
        bill.category = cursor.getString(cursor.getColumnIndexOrThrow("category"));
        bill.occurredAt = cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at"));
        bill.imagePath = cursor.getString(cursor.getColumnIndexOrThrow("image_path"));
        bill.source = cursor.getString(cursor.getColumnIndexOrThrow("source"));
        bill.rawResponse = cursor.getString(cursor.getColumnIndexOrThrow("raw_response"));
        bill.createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"));
        return bill;
    }

    private long[] monthRange(int year, int month) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month - 1, 1, 0, 0, 0);
        long start = calendar.getTimeInMillis();
        calendar.add(Calendar.MONTH, 1);
        return new long[]{start, calendar.getTimeInMillis()};
    }
}
