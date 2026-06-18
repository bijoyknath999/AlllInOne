package com.allinone.app.reminder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class ReminderDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "reminders.db";
    private static final int DB_VERSION = 2;
    private static final String TABLE = "reminders";

    public ReminderDb(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + "(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "title TEXT," +
            "message TEXT," +
            "interval_type INTEGER," +
            "interval_value INTEGER," +
            "interval_ms INTEGER," +
            "enabled INTEGER DEFAULT 1," +
            "next_fire_ms INTEGER," +
            "fire_hour INTEGER DEFAULT 8," +
            "fire_minute INTEGER DEFAULT 0," +
            "sound INTEGER DEFAULT 1," +
            "vibrate INTEGER DEFAULT 1," +
            "image_uri TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN fire_hour INTEGER DEFAULT 8");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN fire_minute INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN sound INTEGER DEFAULT 1");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN vibrate INTEGER DEFAULT 1");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN image_uri TEXT");
        }
    }

    public long insert(Reminder r) {
        return getWritableDatabase().insert(TABLE, null, toContentValues(r));
    }

    public void update(Reminder r) {
        getWritableDatabase().update(TABLE, toContentValues(r), "id=?",
            new String[]{String.valueOf(r.id)});
    }

    public void updateNextFire(long id, long nextFireMs) {
        ContentValues cv = new ContentValues();
        cv.put("next_fire_ms", nextFireMs);
        getWritableDatabase().update(TABLE, cv, "id=?", new String[]{String.valueOf(id)});
    }

    public void setEnabled(long id, boolean enabled) {
        ContentValues cv = new ContentValues();
        cv.put("enabled", enabled ? 1 : 0);
        getWritableDatabase().update(TABLE, cv, "id=?", new String[]{String.valueOf(id)});
    }

    public void delete(long id) {
        getWritableDatabase().delete(TABLE, "id=?", new String[]{String.valueOf(id)});
    }

    public List<Reminder> getAll() {
        List<Reminder> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE, null, null, null, null, null, "id DESC");
        while (c.moveToNext()) list.add(fromCursor(c));
        c.close();
        return list;
    }

    public List<Reminder> getEnabled() {
        List<Reminder> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE, null, "enabled=1", null, null, null, null);
        while (c.moveToNext()) list.add(fromCursor(c));
        c.close();
        return list;
    }

    public Reminder getById(long id) {
        Cursor c = getReadableDatabase().query(TABLE, null, "id=?",
            new String[]{String.valueOf(id)}, null, null, null);
        Reminder r = null;
        if (c.moveToFirst()) r = fromCursor(c);
        c.close();
        return r;
    }

    private ContentValues toContentValues(Reminder r) {
        ContentValues cv = new ContentValues();
        cv.put("title", r.title);
        cv.put("message", r.message);
        cv.put("interval_type", r.intervalType);
        cv.put("interval_value", r.intervalValue);
        cv.put("interval_ms", r.intervalMs);
        cv.put("enabled", r.enabled ? 1 : 0);
        cv.put("next_fire_ms", r.nextFireMs);
        cv.put("fire_hour", r.fireHour);
        cv.put("fire_minute", r.fireMinute);
        cv.put("sound", r.sound ? 1 : 0);
        cv.put("vibrate", r.vibrate ? 1 : 0);
        cv.put("image_uri", r.imageUri);
        return cv;
    }

    private Reminder fromCursor(Cursor c) {
        Reminder r = new Reminder();
        r.id           = c.getLong(c.getColumnIndexOrThrow("id"));
        r.title        = c.getString(c.getColumnIndexOrThrow("title"));
        r.message      = c.getString(c.getColumnIndexOrThrow("message"));
        r.intervalType = c.getInt(c.getColumnIndexOrThrow("interval_type"));
        r.intervalValue= c.getInt(c.getColumnIndexOrThrow("interval_value"));
        r.intervalMs   = c.getLong(c.getColumnIndexOrThrow("interval_ms"));
        r.enabled      = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1;
        r.nextFireMs   = c.getLong(c.getColumnIndexOrThrow("next_fire_ms"));
        int iH = c.getColumnIndex("fire_hour");
        int iM = c.getColumnIndex("fire_minute");
        int iS = c.getColumnIndex("sound");
        int iV = c.getColumnIndex("vibrate");
        int iI = c.getColumnIndex("image_uri");
        r.fireHour   = iH >= 0 ? c.getInt(iH) : 8;
        r.fireMinute = iM >= 0 ? c.getInt(iM) : 0;
        r.sound      = iS < 0 || c.getInt(iS) == 1;
        r.vibrate    = iV < 0 || c.getInt(iV) == 1;
        r.imageUri   = iI >= 0 ? c.getString(iI) : null;
        return r;
    }
}