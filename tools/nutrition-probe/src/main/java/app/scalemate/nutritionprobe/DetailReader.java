package app.scalemate.nutritionprobe;

import android.health.connect.*;
import android.health.connect.datatypes.*;
import android.os.OutcomeReceiver;
import org.json.*;
import java.time.*;
import java.io.*;

// Explicitly invoked, local-only export for the authorized health analysis.
class DetailReader {
    final MainActivity activity;
    final HealthConnectManager manager;
    final Instant end = Instant.now();
    final Instant start = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(14).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
    final Class<?>[] types = {SleepSessionRecord.class,HeartRateRecord.class,HeartRateVariabilityRmssdRecord.class,ExerciseSessionRecord.class,StepsRecord.class};
    FileOutputStream output;
    DetailReader(MainActivity activity) {this.activity=activity;manager=activity.manager;}
    void start() {
        try {
            output=activity.openFileOutput("health-details.jsonl",0);
            emit(new JSONObject().put("type","window").put("start",start.toString()).put("end",end.toString()));
            next(0);
        } catch(Exception e) {activity.report("详细读取失败："+e);}
    }
    void emit(JSONObject item) throws Exception {output.write((item.toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    @SuppressWarnings("unchecked") void next(int index) {
        if(index==types.length) {
            try {emit(new JSONObject().put("type","complete"));output.close();activity.report("详细读取完成：睡眠、心率、HRV、运动、步数。结果仅保存在本机。");} catch(Exception e) {activity.report("导出失败："+e);} return;
        }
        activity.report("正在读取："+types[index].getSimpleName());
        page(index,(Class<? extends android.health.connect.datatypes.Record>)types[index],-1);
    }
    <T extends android.health.connect.datatypes.Record> void page(int index,Class<T> type,long token) {
        var request=new ReadRecordsRequestUsingFilters.Builder<T>(type).setPageSize(1000)
            .setTimeRangeFilter(new TimeInstantRangeFilter.Builder().setStartTime(start).setEndTime(end).build());
        if(token!=-1) request.setPageToken(token);
        try {
            manager.readRecords(request.build(),activity.getMainExecutor(),new OutcomeReceiver<ReadRecordsResponse<T>,HealthConnectException>() {
                public void onResult(ReadRecordsResponse<T> result) {
                    try {
                        for(T r:result.getRecords()) {
                            JSONObject obj=new JSONObject().put("type",type.getSimpleName()).put("id",r.getMetadata().getId()).put("source",r.getMetadata().getDataOrigin().getPackageName());
                            if(r instanceof IntervalRecord interval) obj.put("start",interval.getStartTime().toString()).put("end",interval.getEndTime().toString());
                            if(r instanceof InstantRecord instant) obj.put("time",instant.getTime().toString());
                            if(r instanceof SleepSessionRecord sleep) {
                                JSONArray stages=new JSONArray();
                                for(var stage:sleep.getStages()) stages.put(new JSONObject().put("start",stage.getStartTime().toString()).put("end",stage.getEndTime().toString()).put("stage",stage.getType()));
                                obj.put("stages",stages);
                            }
                            if(r instanceof HeartRateRecord heart) {
                                JSONArray samples=new JSONArray();
                                for(var sample:heart.getSamples()) samples.put(new JSONObject().put("time",sample.getTime().toString()).put("bpm",sample.getBeatsPerMinute()));
                                obj.put("samples",samples);
                            }
                            if(r instanceof HeartRateVariabilityRmssdRecord hrv) obj.put("rmssd_ms",hrv.getHeartRateVariabilityMillis());
                            if(r instanceof ExerciseSessionRecord exercise) obj.put("exercise_type",exercise.getExerciseType());
                            if(r instanceof StepsRecord steps) obj.put("count",steps.getCount());
                            emit(obj);
                        }
                        if(result.getNextPageToken()!=-1) page(index,type,result.getNextPageToken()); else next(index+1);
                    } catch(Exception e) {activity.report("导出失败："+e);try {output.close();} catch(Exception ignored) {}}
                }
                public void onError(HealthConnectException e) {fail(index,e);}
            });
        } catch(Exception e) {fail(index,e);}
    }
    void fail(int index,Exception e) {
        try {emit(new JSONObject().put("type","error").put("record",types[index].getSimpleName()).put("error",e.toString()));} catch(Exception ignored) {}
        next(index+1);
    }
}
