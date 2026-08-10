package app.morphe.extension.twitter.safex;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import app.morphe.extension.twitter.safex.core.FeatureKey;
import app.morphe.extension.twitter.safex.core.FeatureRepository;
import app.morphe.extension.twitter.safex.core.FeatureStats;
import app.morphe.extension.twitter.safex.core.FeatureType;

import java.util.LinkedHashMap;
import java.util.Map;

final class AndroidFeatureRepository extends SQLiteOpenHelper implements FeatureRepository {
    private static final String DB_NAME = "safex.db";
    private static final int DB_VERSION = 1;

    AndroidFeatureRepository(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE features (" +
                "type TEXT NOT NULL," +
                "value TEXT NOT NULL," +
                "positive_weight REAL NOT NULL DEFAULT 0," +
                "negative_weight REAL NOT NULL DEFAULT 0," +
                "positive_docs INTEGER NOT NULL DEFAULT 0," +
                "negative_docs INTEGER NOT NULL DEFAULT 0," +
                "document_frequency INTEGER NOT NULL DEFAULT 0," +
                "last_seen_ms INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY(type,value))");
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
        db.execSQL("INSERT INTO meta(key,value) VALUES('document_count',0)");
        db.execSQL("CREATE TABLE observed_posts (" +
                "tweet_id INTEGER NOT NULL," +
                "label TEXT NOT NULL," +
                "created_at_ms INTEGER NOT NULL," +
                "PRIMARY KEY(tweet_id,label))");
        db.execSQL("CREATE TABLE blocked_posts (" +
                "tweet_id INTEGER PRIMARY KEY," +
                "source TEXT NOT NULL," +
                "created_at_ms INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    @Override
    public synchronized FeatureStats get(FeatureKey key) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("features",
                new String[]{"positive_weight","negative_weight","positive_docs","negative_docs","document_frequency","last_seen_ms"},
                "type=? AND value=?",
                new String[]{key.type.name(), key.value}, null, null, null)) {
            if (!c.moveToFirst()) return null;
            FeatureStats s = new FeatureStats();
            s.positiveWeight = c.getDouble(0);
            s.negativeWeight = c.getDouble(1);
            s.positiveDocs = c.getInt(2);
            s.negativeDocs = c.getInt(3);
            s.documentFrequency = c.getInt(4);
            s.lastSeenMs = c.getLong(5);
            return s;
        }
    }

    @Override
    public synchronized void put(FeatureKey key, FeatureStats stats) {
        ContentValues cv = new ContentValues();
        cv.put("type", key.type.name());
        cv.put("value", key.value);
        cv.put("positive_weight", stats.positiveWeight);
        cv.put("negative_weight", stats.negativeWeight);
        cv.put("positive_docs", stats.positiveDocs);
        cv.put("negative_docs", stats.negativeDocs);
        cv.put("document_frequency", stats.documentFrequency);
        cv.put("last_seen_ms", stats.lastSeenMs);
        getWritableDatabase().insertWithOnConflict("features", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public synchronized Map<FeatureKey, FeatureStats> listByType(FeatureType type) {
        LinkedHashMap<FeatureKey, FeatureStats> out = new LinkedHashMap<>();
        try (Cursor c = getReadableDatabase().query("features",
                new String[]{"value","positive_weight","negative_weight","positive_docs","negative_docs","document_frequency","last_seen_ms"},
                "type=?", new String[]{type.name()}, null, null, null)) {
            while (c.moveToNext()) {
                FeatureStats s = new FeatureStats();
                s.positiveWeight = c.getDouble(1);
                s.negativeWeight = c.getDouble(2);
                s.positiveDocs = c.getInt(3);
                s.negativeDocs = c.getInt(4);
                s.documentFrequency = c.getInt(5);
                s.lastSeenMs = c.getLong(6);
                out.put(new FeatureKey(type, c.getString(0)), s);
            }
        }
        return out;
    }

    @Override
    public synchronized long getDocumentCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT value FROM meta WHERE key='document_count'", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }

    @Override
    public synchronized void setDocumentCount(long count) {
        ContentValues cv = new ContentValues();
        cv.put("value", count);
        getWritableDatabase().update("meta", cv, "key='document_count'", null);
    }

    synchronized boolean markObserved(long tweetId, String label) {
        ContentValues cv = new ContentValues();
        cv.put("tweet_id", tweetId);
        cv.put("label", label);
        cv.put("created_at_ms", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(
                "observed_posts", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    synchronized void blockTweet(long tweetId, String source) {
        ContentValues cv = new ContentValues();
        cv.put("tweet_id", tweetId);
        cv.put("source", source);
        cv.put("created_at_ms", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "blocked_posts", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized boolean isTweetBlocked(long tweetId) {
        try (Cursor c = getReadableDatabase().query(
                "blocked_posts", new String[]{"tweet_id"}, "tweet_id=?",
                new String[]{Long.toString(tweetId)}, null, null, null, "1")) {
            return c.moveToFirst();
        }
    }
}
