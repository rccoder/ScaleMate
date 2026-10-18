package app.scalemate.nutritionprobe;

import android.app.Activity;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.content.pm.PackageManager;
import android.health.connect.*;
import android.health.connect.datatypes.*;
import android.health.connect.datatypes.units.*;
import android.widget.*;
import java.time.*;
import java.util.List;

public class MainActivity extends Activity {
    static final String PERMISSION = "android.permission.health.WRITE_NUTRITION";
    static final String ID = "scalemate-nutrition-probe-20261017";
    TextView status;
    HealthConnectManager manager;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        manager = getSystemService(HealthConnectManager.class);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(1); layout.setPadding(48,96,48,48);
        status = new TextView(this); status.setTextSize(20);
        status.setText("测试数据（非真实饮食）\nScaleMate 测试午餐\n500 千卡 · 蛋白质 30 g\n碳水 50 g · 脂肪 20 g\n\n点击写入，同一条记录不会重复增加。");
        layout.addView(status);
        Button write = new Button(this); write.setText("授权并写入测试午餐");
        write.setOnClickListener(v -> { if(checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) write(); else requestPermissions(new String[]{PERMISSION},1); });
        layout.addView(write);
        Button delete = new Button(this); delete.setText("删除这条测试数据");
        delete.setOnClickListener(v -> delete()); layout.addView(delete);
        Button read = new Button(this); read.setText("授权并检测可读取的数据");
        read.setOnClickListener(v -> authorizeRead()); layout.addView(read);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        if(getIntent().getBooleanExtra("details",false)) new DetailReader(this).start();
        else if(getIntent().getBooleanExtra("read",false)) authorizeRead();
    }
    void report(String message) { status.setText(message); android.util.Log.i("NutritionProbe",message); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request == 2) { scan(); return; }
        if(request == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) write();
        else report("尚未获得营养写入权限");
    }
    void write() {
        try {
            var prefs = getPreferences(0);
            long timestamp = prefs.getLong("time",0);
            if(timestamp == 0) { timestamp = System.currentTimeMillis(); prefs.edit().putLong("time",timestamp).commit(); }
            Instant end = Instant.ofEpochMilli(timestamp);
            ZoneOffset offset = ZoneId.systemDefault().getRules().getOffset(end);
            Metadata meta = new Metadata.Builder().setClientRecordId(ID).setClientRecordVersion(1)
                .setRecordingMethod(Metadata.RECORDING_METHOD_MANUAL_ENTRY).build();
            NutritionRecord record = new NutritionRecord.Builder(meta,end.minusSeconds(60),end)
                .setStartZoneOffset(offset).setEndZoneOffset(offset)
                .setMealName("ScaleMate 测试午餐（非真实饮食）").setMealType(MealType.MEAL_TYPE_LUNCH)
                .setEnergy(Energy.fromCalories(500_000)).setProtein(Mass.fromGrams(30))
                .setTotalCarbohydrate(Mass.fromGrams(50)).setTotalFat(Mass.fromGrams(20)).build();
            manager.insertRecords(List.of(record),getMainExecutor(),new OutcomeReceiver<InsertRecordsResponse,HealthConnectException>() {
                public void onResult(InsertRecordsResponse result) { report("写入成功\n" + end.atZone(ZoneId.systemDefault()) + "\n500 千卡 · 蛋白质 30 g\n碳水 50 g · 脂肪 20 g\nID: " + result.getRecords().get(0).getMetadata().getId()); }
                public void onError(HealthConnectException e) { report("写入失败：" + e); }
            });
        } catch(Exception e) { report("写入失败：" + e); }
    }
    void delete() {
        try {
            manager.deleteRecords(List.of(RecordIdFilter.fromClientRecordId(NutritionRecord.class,ID)),getMainExecutor(),new OutcomeReceiver<Void,HealthConnectException>() {
                public void onResult(Void result) { report("测试数据已从 Health Connect 删除"); }
                public void onError(HealthConnectException e) { report("删除失败：" + e); }
            });
        } catch(Exception e) { report("删除失败：" + e); }
    }

    static final String[][] TYPES = {
        {"Weight","WEIGHT","体重"},
        {"Height","HEIGHT","身高"},
        {"BodyFat","BODY_FAT","体脂率"},
        {"LeanBodyMass","LEAN_BODY_MASS","瘦体重"},
        {"BoneMass","BONE_MASS","骨量"},
        {"BodyWaterMass","BODY_WATER_MASS","身体水分重量"},
        {"BasalMetabolicRate","BASAL_METABOLIC_RATE","基础代谢"},
        {"Nutrition","NUTRITION","饮食营养"},
        {"Hydration","HYDRATION","饮水"},
        {"Steps","STEPS","步数"},
        {"StepsCadence","STEPS","步频"},
        {"Distance","DISTANCE","距离"},
        {"FloorsClimbed","FLOORS_CLIMBED","楼层"},
        {"ElevationGained","ELEVATION_GAINED","爬升"},
        {"ActiveCaloriesBurned","ACTIVE_CALORIES_BURNED","活动消耗"},
        {"TotalCaloriesBurned","TOTAL_CALORIES_BURNED","总消耗"},
        {"ExerciseSession","EXERCISE","运动"},
        {"Speed","SPEED","速度"},
        {"Power","POWER","功率"},
        {"CyclingPedalingCadence","EXERCISE","骑行踏频"},
        {"SleepSession","SLEEP","睡眠"},
        {"HeartRate","HEART_RATE","心率"},
        {"RestingHeartRate","RESTING_HEART_RATE","静息心率"},
        {"HeartRateVariabilityRmssd","HEART_RATE_VARIABILITY","心率变异性"},
        {"RespiratoryRate","RESPIRATORY_RATE","呼吸频率"},
        {"OxygenSaturation","OXYGEN_SATURATION","血氧"},
        {"BloodPressure","BLOOD_PRESSURE","血压"},
        {"BloodGlucose","BLOOD_GLUCOSE","血糖"},
        {"BodyTemperature","BODY_TEMPERATURE","体温"},
        {"BasalBodyTemperature","BASAL_BODY_TEMPERATURE","基础体温"},
        {"MenstruationPeriod","MENSTRUATION","经期"},
        {"MenstruationFlow","MENSTRUATION","经量"},
        {"IntermenstrualBleeding","INTERMENSTRUAL_BLEEDING","经间出血"},
        {"OvulationTest","OVULATION_TEST","排卵测试"},
        {"CervicalMucus","CERVICAL_MUCUS","宫颈黏液"},
        {"SexualActivity","SEXUAL_ACTIVITY","性活动"},
        {"SkinTemperature","SKIN_TEMPERATURE","皮肤温度"},
        {"MindfulnessSession","MINDFULNESS","正念"},
        {"ActivityIntensity","ACTIVITY_INTENSITY","活动强度"},
        {"PlannedExerciseSession","PLANNED_EXERCISE","训练计划"}
    };
    void authorizeRead() {
        java.util.Set<String> needed = new java.util.LinkedHashSet<>();
        for(String[] type:TYPES) {
            String permission="android.permission.health.READ_"+type[1];
            try {
                getPackageManager().getPermissionInfo(permission,0);
                if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED) needed.add(permission);
            } catch(Exception ignored) {}
        }
        String history="android.permission.health.READ_HEALTH_DATA_HISTORY";
        try { getPackageManager().getPermissionInfo(history,0); if(checkSelfPermission(history)!=PackageManager.PERMISSION_GRANTED) needed.add(history); } catch(Exception ignored) {}
        if(needed.isEmpty()) scan(); else requestPermissions(needed.toArray(new String[0]),2);
    }
    StringBuilder summary;
    Instant scanEnd;
    void scan() {
        scanEnd=Instant.now();
        summary=new StringBuilder("Health Connect 实机读取概览\n检测时间："+scanEnd.atZone(ZoneId.systemDefault())+"\n历史读取权限："+(checkSelfPermission("android.permission.health.READ_HEALTH_DATA_HISTORY")==PackageManager.PERMISSION_GRANTED)+"\n查询范围：1970 至检测时刻；未获历史权限时平台会限制历史范围\n");
        readType(0);
    }
    void saveSummary() {
        status.setText(summary.toString());
        try(var out=openFileOutput("read-summary.txt",MODE_PRIVATE)) {out.write(summary.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));} catch(Exception ignored) {}
    }
    void readType(int index) {
        saveSummary();
        if(index>=TYPES.length) { summary.append("\n检测完成\n");saveSummary();return; }
        String[] type=TYPES[index];
        if(checkSelfPermission("android.permission.health.READ_"+type[1])!=PackageManager.PERMISSION_GRANTED) {
            summary.append(type[2]+"：未授权或系统不支持\n"); readType(index+1); return;
        }
        try {
            Class<? extends android.health.connect.datatypes.Record> cls=Class.forName("android.health.connect.datatypes."+type[0]+"Record").asSubclass(android.health.connect.datatypes.Record.class);
            readPage(index,cls,-1,0,new java.util.TreeMap<>(),null,null);
        } catch(Exception e) { summary.append(type[2]+"：不支持/失败 "+e+"\n"); readType(index+1); }
    }
    <T extends android.health.connect.datatypes.Record> void readPage(int index,Class<T> cls,long token,long count,java.util.TreeMap<String,Long> origins,Instant earliest,Instant latest) {
        var builder=new ReadRecordsRequestUsingFilters.Builder<T>(cls)
            .setTimeRangeFilter(new TimeInstantRangeFilter.Builder().setStartTime(Instant.EPOCH).setEndTime(scanEnd).build())
            .setPageSize(1000);
        if(token!=-1) builder.setPageToken(token);
        manager.readRecords(builder.build(),getMainExecutor(),new OutcomeReceiver<ReadRecordsResponse<T>,HealthConnectException>() {
            public void onResult(ReadRecordsResponse<T> response) {
                Instant lo=earliest,hi=latest;
                for(T r:response.getRecords()) {
                    origins.merge(r.getMetadata().getDataOrigin().getPackageName(),1L,Long::sum);
                    Instant start=r instanceof InstantRecord ? ((InstantRecord)r).getTime() : ((IntervalRecord)r).getStartTime();
                    Instant end=r instanceof InstantRecord ? start : ((IntervalRecord)r).getEndTime();
                    if(lo==null || start.isBefore(lo)) lo=start;
                    if(hi==null || end.isAfter(hi)) hi=end;
                }
                long total=count+response.getRecords().size();
                if(response.getNextPageToken()!=-1) {readPage(index,cls,response.getNextPageToken(),total,origins,lo,hi);return;}
                summary.append(TYPES[index][2]+"："+total+" 条");
                if(total>0) summary.append("\n  时间："+lo.atZone(ZoneId.systemDefault())+" ～ "+hi.atZone(ZoneId.systemDefault())+"\n  来源："+origins);
                summary.append("\n"); readType(index+1);
            }
            public void onError(HealthConnectException e) {summary.append(TYPES[index][2]+"：读取失败 "+e+"\n");readType(index+1);}
        });
    }
}
