package app.scalemate.healthseed;

import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.InsertRecordsResponse;
import android.health.connect.RecordIdFilter;
import android.health.connect.datatypes.*;
import android.health.connect.datatypes.units.*;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Synthetic records only. Installed over the existing emulator seed source. */
public final class SeedActivity extends android.app.Activity {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(8);
    private static final String PREFIX = "quarter-20261026-";
    private final Map<String, Integer> counts = new TreeMap<>();
    private int samples;
    private long revision;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.widget.TextView text = new android.widget.TextView(this);
        text.setText("Preparing synthetic quarter data");
        setContentView(text);
        File result = new File(getFilesDir(), "quarter-result.json");
        result.delete();
        String action = getIntent().getStringExtra("action");
        new Thread(() -> {
            JSONObject output = new JSONObject();
            try {
                if (!android.os.Build.MODEL.contains("sdk_gphone")) throw new IllegalStateException("Emulator only");
                HealthConnectManager manager = getSystemService(HealthConnectManager.class);
                String saved = getPreferences(0).getString("quarterEnd", "");
                LocalDate end = saved.isEmpty() ? LocalDate.now(ZONE) : LocalDate.parse(saved);
                LocalDate start = end.minusMonths(3);
                revision = Math.max(System.currentTimeMillis(), getPreferences(0).getLong("revision", 1) + 1);
                getPreferences(0).edit().putLong("revision", revision).commit();
                if ("seed".equals(action)) {
                    getPreferences(0).edit().putString("quarterEnd", end.toString()).commit();
                    for (LocalDate day = start; day.isBefore(end); day = day.plusDays(1)) {
                        List<android.health.connect.datatypes.Record> rows = day(day);
                        for (android.health.connect.datatypes.Record row : rows) counts.merge(row.getClass().getSimpleName(), 1, Integer::sum);
                        insert(manager, rows);
                    }
                } else if ("change".equals(action)) {
                    LocalDate recent = end.minusDays(1);
                    LocalDate old = end.minusDays(45);
                    Instant time = at(recent, 12 * 60);
                    List<android.health.connect.datatypes.Record> changed = List.of(
                        new StepsRecord.Builder(meta("steps-" + recent + "-144", 2), time, time.plusSeconds(300), 1000).setStartZoneOffset(OFFSET).setEndZoneOffset(OFFSET).build(),
                        nutrition(old.minusDays(1), 12 * 60, 730, "番茄牛腩饭", meta("meal-" + old + "-1", 2)),
                        new NutritionRecord.Builder(meta("extra-yogurt", 2), Instant.now().minusSeconds(240), Instant.now().minusSeconds(180)).setEnergy(Energy.fromCalories(160000)).setMealName("加餐酸奶").setStartZoneOffset(OFFSET).setEndZoneOffset(OFFSET).build()
                    );
                    insert(manager, changed);
                    delete(manager, List.of(RecordIdFilter.fromClientRecordId(DistanceRecord.class, PREFIX + "distance-" + recent + "-48")));
                    output.put("upserts", 3).put("deletes", 1);
                } else if ("cleanup".equals(action)) {
                    for (LocalDate day = start; day.isBefore(end); day = day.plusDays(1)) {
                        List<RecordIdFilter> filters = new ArrayList<>();
                        for (android.health.connect.datatypes.Record row : day(day)) filters.add(RecordIdFilter.fromClientRecordId(row.getClass(), row.getMetadata().getClientRecordId()));
                        delete(manager, filters);
                    }
                    delete(manager, List.of(RecordIdFilter.fromClientRecordId(NutritionRecord.class, PREFIX + "extra-yogurt")));
                } else throw new IllegalArgumentException("Unknown action");
                output.put("ok", true).put("action", action).put("start", start).put("endExclusive", end)
                    .put("days", java.time.temporal.ChronoUnit.DAYS.between(start, end)).put("records", new JSONObject(counts)).put("heartSamples", samples);
            } catch (Throwable error) {
                try { output.put("ok", false).put("error", error.toString()); } catch (Exception ignored) {}
            }
            try { Files.write(result.toPath(), output.toString().getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
            runOnUiThread(() -> text.setText(output.toString()));
        }, "quarter-seed").start();
    }

    private Metadata meta(String id, long version) {
        return new Metadata.Builder().setClientRecordId(PREFIX + id).setClientRecordVersion(Math.max(version, revision))
            .setRecordingMethod(Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED).build();
    }
    private Instant at(LocalDate day, int minute) { return day.atStartOfDay(ZONE).plusMinutes(minute).toInstant(); }
    private List<android.health.connect.datatypes.Record> day(LocalDate day) {
        List<android.health.connect.datatypes.Record> rows = new ArrayList<>();
        int n = day.getDayOfYear();
        Instant sleepEnd = at(day, 7 * 60);
        Instant sleepStart = sleepEnd.minusSeconds(7 * 3600);
        rows.add(new SleepSessionRecord.Builder(meta("sleep-" + day, 1), sleepStart, sleepEnd).setStages(List.of(
            new SleepSessionRecord.Stage(sleepStart, sleepStart.plusSeconds(600), 1),
            new SleepSessionRecord.Stage(sleepStart.plusSeconds(600), sleepStart.plusSeconds(6600), 5),
            new SleepSessionRecord.Stage(sleepStart.plusSeconds(6600), sleepEnd.minusSeconds(4800), 4),
            new SleepSessionRecord.Stage(sleepEnd.minusSeconds(4800), sleepEnd, 6)
        )).setStartZoneOffset(OFFSET).setEndZoneOffset(OFFSET).build());
        for (int hour = 0; hour < 24; hour++) {
            List<HeartRateRecord.HeartRateSample> points = new ArrayList<>();
            for (int minute = 0; minute < 60; minute++) points.add(new HeartRateRecord.HeartRateSample((hour < 7 ? 58 : 76) + (n + minute) % 11, at(day, hour * 60 + minute)));
            rows.add(new HeartRateRecord.Builder(meta("heart-" + day + "-" + hour, 1), at(day, hour * 60), at(day, (hour + 1) * 60), points).setStartZoneOffset(OFFSET).setEndZoneOffset(OFFSET).build());
            samples += points.size();
        }
        for (int point = 0; point < 36; point++) rows.add(new HeartRateVariabilityRmssdRecord.Builder(meta("hrv-" + day + "-" + point, 1), at(day, 30 + point * 10), 35 + (n + point) % 25).setZoneOffset(OFFSET).build());
        for (int bucket = 0; bucket < 288; bucket++) {
            Instant start = at(day, bucket * 5), end = start.plusSeconds(300);
            long steps = bucket < 84 ? 0 : 8 + (bucket + n) % 75;
            rows.add(new StepsRecord.Builder(meta("steps-" + day + "-" + bucket, 1), start, end, steps).setStartZoneOffset(OFFSET).setEndZoneOffset(OFFSET).build());
        }
        for (int bucket = 0; bucket < 96; bucket++) {
            Instant start = at(day, bucket * 15), end = start.plusSeconds(900);
            rows.add(new DistanceRecord.Builder(meta("distance-" + day + "-" + bucket, 1), start, end, Length.fromMeters(bucket < 28 ? 0 : 30 + (bucket + n) % 100)).setStartZoneOffset(OFFSET).setEndZoneOffset(OFFSET).build());
            rows.add(new ActiveCaloriesBurnedRecord.Builder(meta("active-" + day + "-" + bucket, 1), start, end, Energy.fromCalories((bucket < 28 ? 1 : 4 + (bucket + n) % 8) * 1000)).setStartZoneOffset(OFFSET).setEndZoneOffset(OFFSET).build());
        }
        rows.add(new WeightRecord.Builder(meta("weight-" + day, 1), at(day, 7 * 60 + 15), Mass.fromGrams(72000 + (n % 15) * 20)).setZoneOffset(OFFSET).build());
        rows.add(nutrition(day, 8 * 60, 420, "牛油果鸡蛋吐司", meta("meal-" + day + "-0", 1)));
        rows.add(nutrition(day, 12 * 60, 650, "番茄牛腩饭", meta("meal-" + day + "-1", 1)));
        rows.add(nutrition(day, 19 * 60, 580, "三文鱼藜麦碗", meta("meal-" + day + "-2", 1)));
        if (n % 2 == 0) rows.add(new ExerciseSessionRecord.Builder(meta("run-" + day, 1), at(day, 18 * 60), at(day, 18 * 60 + 35), ExerciseSessionType.EXERCISE_SESSION_TYPE_RUNNING).setTitle("晚间跑步").setStartZoneOffset(OFFSET).setEndZoneOffset(OFFSET).build());
        return rows;
    }
    private NutritionRecord nutrition(LocalDate day, int minute, double kcal, String name, Metadata metadata) {
        Instant end = at(day, minute);
        return new NutritionRecord.Builder(metadata, end.minusSeconds(60), end).setEnergy(Energy.fromCalories(kcal * 1000))
            .setProtein(Mass.fromGrams(30)).setTotalCarbohydrate(Mass.fromGrams(65)).setTotalFat(Mass.fromGrams(18)).setMealName(name).setStartZoneOffset(OFFSET).setEndZoneOffset(OFFSET).build();
    }
    private void insert(HealthConnectManager manager, List<android.health.connect.datatypes.Record> rows) throws Exception {
        for (int i = 0; i < rows.size(); i += 100) {
            CountDownLatch latch = new CountDownLatch(1); Throwable[] failure = new Throwable[1];
            manager.insertRecords(rows.subList(i, Math.min(i + 100, rows.size())), Runnable::run, new OutcomeReceiver<InsertRecordsResponse, HealthConnectException>() {
                @Override public void onResult(InsertRecordsResponse value) { latch.countDown(); }
                @Override public void onError(HealthConnectException error) { failure[0] = error; latch.countDown(); }
            });
            if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Insert timeout");
            if (failure[0] != null) throw new IllegalStateException(failure[0]);
        }
    }
    private void delete(HealthConnectManager manager, List<RecordIdFilter> rows) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); Throwable[] failure = new Throwable[1];
        manager.deleteRecords(rows, Runnable::run, new OutcomeReceiver<Void, HealthConnectException>() {
            @Override public void onResult(Void value) { latch.countDown(); }
            @Override public void onError(HealthConnectException error) { failure[0] = error; latch.countDown(); }
        });
        if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Delete timeout");
        if (failure[0] != null) throw new IllegalStateException(failure[0]);
    }
}
