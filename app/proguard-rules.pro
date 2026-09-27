# Keep Xposed entry - it's loaded reflectively by the framework
-keep class com.wxpayrecorder.HookEntry {
    <init>();
}
-keep class com.wxpayrecorder.hook.** { *; }

# Keep provider and db classes for DBOpenHelper reflection safety
-keep class com.wxpayrecorder.db.** { *; }
-keep class com.wxpayrecorder.PayRecord { *; }