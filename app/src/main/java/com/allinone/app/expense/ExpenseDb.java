package com.allinone.app.expense;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class ExpenseDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "expenses.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "expenses";

    public ExpenseDb(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + "(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "category TEXT," +
            "amount REAL," +
            "note TEXT," +
            "date_ms INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public long insert(Expense e) {
        ContentValues cv = new ContentValues();
        cv.put("category", e.category);
        cv.put("amount", e.amount);
        cv.put("note", e.note);
        cv.put("date_ms", e.dateMillis);
        return getWritableDatabase().insert(TABLE, null, cv);
    }

    public void delete(long id) {
        getWritableDatabase().delete(TABLE, "id=?", new String[]{String.valueOf(id)});
    }

    public List<Expense> query(long fromMs, long toMs) {
        List<Expense> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE, null,
            "date_ms >= ? AND date_ms <= ?",
            new String[]{String.valueOf(fromMs), String.valueOf(toMs)},
            null, null, "date_ms DESC");
        while (c.moveToNext()) {
            list.add(fromCursor(c));
        }
        c.close();
        return list;
    }

    public double sumAmount(long fromMs, long toMs) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT SUM(amount) FROM " + TABLE + " WHERE date_ms >= ? AND date_ms <= ?",
            new String[]{String.valueOf(fromMs), String.valueOf(toMs)});
        double sum = 0;
        if (c.moveToFirst() && !c.isNull(0)) sum = c.getDouble(0);
        c.close();
        return sum;
    }

    public List<Expense> queryAll() {
        List<Expense> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE, null, null, null, null, null, "date_ms ASC");
        while (c.moveToNext()) list.add(fromCursor(c));
        c.close();
        return list;
    }

    private Expense fromCursor(Cursor c) {
        return new Expense(
            c.getLong(c.getColumnIndexOrThrow("id")),
            c.getString(c.getColumnIndexOrThrow("category")),
            c.getDouble(c.getColumnIndexOrThrow("amount")),
            c.getString(c.getColumnIndexOrThrow("note")),
            c.getLong(c.getColumnIndexOrThrow("date_ms"))
        );
    }
}