package com.wxpayrecorder.hook;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import com.wxpayrecorder.PayRecordProvider;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 多路 Hook 策略：
 * 1. ot0.q.v(String) — 用户打开聊天时解析 appmsg
 * 2. f9.S1() — 获取消息原始内容（消息入库/通知时被调用）
 * 3. f9.j() — 获取消息处理后的内容
 *
 * 三路同时监控，只要任意一路拿到含 wcpayinfo 的 XML 就提取收款数据。
 * 直接用字符串匹配解析 XML，不依赖微信内部类。
 */
public class PayHooker {

    private static volatile Context appContext;
    private static volatile int hookCount = 0;
    private static volatile int payMsgCount = 0;
    private static volatile int recordCount = 0;
    private static volatile String lastFeedesc = null;
    private static volatile String lastError = null;
    private static volatile boolean hookQvOk = false;
    private static volatile boolean hookS1Ok = false;
    private static volatile boolean hookJOk = false;
    private static volatile boolean hookAaD = false;
    private static volatile boolean hookMsgEntry = false;
    private static volatile boolean hookNotify = false;
    private static volatile String hookInfo = "";

    // 缓存调试消息，等 appContext 就绪后补发
    private static final java.util.List<String[]> pendingDebug = new java.util.ArrayList<>();

    // 缓存的状态，等拿到 context 后统一上报
    private static volatile String cachedClassFound = null;
    private static volatile String cachedMethodFound = null;
    private static volatile String cachedError = null;
    private static volatile boolean statusFlushed = false;

    // 去重：已处理的订单号
    private static final Set<String> processedOrders = new HashSet<>();

    private PayHooker() {
    }

    private static void log(String msg) {
        try {
            XposedBridge.log("[WxPayRecorder] " + msg);
        } catch (Throwable ignore) {
        }
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;

        // 0) 捕获 Application Context
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            appContext = (Context) param.thisObject;
                            log("Application.attach fired, context=" + appContext);
                            // 先上报激活，再把缓存的 hook 状态冲出去
                            sendHeartbeat(PayRecordProvider.KEY_HOOK_INSTALLED, "true");
                            flushBufferedStatus();
                        }
                    });
            log("Application.attach hook ok");
        } catch (Throwable t) {
            log("Application.attach hook FAIL: " + t);
            cachedError = "Application.attach hook失败: " + t.getMessage();
        }

        // 1) Hook ot0.q.v(String) — 原始方案
        try {
            Class<?> appMsgClass = XposedHelpers.findClass("ot0.q", cl);
            boolean found = false;
            for (Method m : appMsgClass.getDeclaredMethods()) {
                if (m.getName().equals("v") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == String.class) {
                    // 参数类型逐个传入，不能包成数组
                    XposedHelpers.findAndHookMethod(appMsgClass, "v",
                            String.class, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    hookQvOk = true;
                                    hookInfo = "ot0.q.v(String)";
                                    tryProcessXml((String) param.args[0]);
                                }
                            });
                    found = true;
                    break;
                }
            }
            if (found) {
                cachedClassFound = "ot0.q";
                cachedMethodFound = "v(String)";
                log("ot0.q.v(String) hooked");
            } else {
                cachedError = "ot0.q 存在但未找到 v(String) 方法";
                log("ot0.q found but v(String) not found");
            }
        } catch (Throwable t) {
            log("ot0.q hook FAIL: " + t);
            cachedError = "ot0.q hook失败: " + t.getMessage();
        }

        // 2) Hook com.tencent.mm.storage.f9.S1() — 原始内容 getter
        try {
            Class<?> f9Class = XposedHelpers.findClass("com.tencent.mm.storage.f9", cl);
            XposedHelpers.findAndHookMethod(f9Class, "S1", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    hookS1Ok = true;
                    hookInfo = "f9.S1()";
                    tryProcessXml((String) param.getResult());
                }
            });
            hookS1Ok = true;
            log("f9.S1() hooked");
        } catch (Throwable t) {
            log("f9.S1 hook FAIL (ok to ignore): " + t);
        }

        // 3) Hook com.tencent.mm.storage.f9.j() — 处理后内容 getter
        try {
            Class<?> f9Class = XposedHelpers.findClass("com.tencent.mm.storage.f9", cl);
            XposedHelpers.findAndHookMethod(f9Class, "j", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    hookJOk = true;
                    hookInfo = "f9.j()";
                    tryProcessXml((String) param.getResult());
                }
            });
            hookJOk = true;
            log("f9.j() hooked");
        } catch (Throwable t) {
            log("f9.j hook FAIL (ok to ignore): " + t);
        }

        // 4) Hook com.tencent.mm.sdk.platformtools.aa.d(...) — 底层 XML 解析器
        //    这是微信解析任何消息 XML 的必经静态方法。遍历所有名为 d 的方法，
        //    只要参数里有 String（XML），回调时就检查是否含 wcpayinfo。
        try {
            Class<?> aaClass = XposedHelpers.findClass("com.tencent.mm.sdk.platformtools.aa", cl);
            int aaHooked = 0;
            for (Method m : aaClass.getDeclaredMethods()) {
                if (!m.getName().equals("d")) continue;
                boolean hasStringParam = false;
                for (Class<?> pt : m.getParameterTypes()) {
                    if (pt == String.class) { hasStringParam = true; break; }
                }
                if (!hasStringParam) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            hookAaD = true;
                            hookInfo = "aa.d(all)";
                            // 检查所有 String 参数
                            for (Object o : param.args) {
                                if (o instanceof String && ((String) o).contains("wcpayinfo")) {
                                    tryProcessXml((String) o);
                                }
                            }
                            // 也检查返回值（有的重载返回解析后的 Map）
                            Object r = param.getResult();
                            if (r instanceof String && ((String) r).contains("wcpayinfo")) {
                                tryProcessXml((String) r);
                            }
                        }
                    });
                    aaHooked++;
                } catch (Throwable ignore) {
                }
            }
            if (aaHooked > 0) {
                hookAaD = true;
                log("aa.d hooked, " + aaHooked + " overloads");
            } else {
                log("aa.d: no d(String) method found");
            }
        } catch (Throwable t) {
            log("aa.d hook FAIL: " + t);
        }

        // 5) HOOK 消息接收入口 ww1.c2.b(f9, j4) — 捕获所有新到消息
        try {
            Class<?> f9cls = XposedHelpers.findClass("com.tencent.mm.storage.f9", cl);
            Class<?> j4cls = XposedHelpers.findClass("r45.j4", cl);
            Class<?> c2cls = XposedHelpers.findClass("ww1.c2", cl);
            XposedHelpers.findAndHookMethod(c2cls, "b", f9cls, j4cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    hookMsgEntry = true;
                    hookInfo = "ww1.c2.b(f9,j4)";
                    captureF9Message(param.args[0]);
                }
            });
            hookMsgEntry = true;
            log("ww1.c2.b(f9,j4) hooked");
        } catch (Throwable t) {
            log("ww1.c2.b hook FAIL (ok to ignore): " + t);
        }

        // 6) HOOK NotificationManager.notify — Android 框架层通知，后台收到
        //    每条需要提示的新消息/收款都会走这里，是最可靠的真实触发点。
        try {
            Class<?> nmClass = XposedHelpers.findClass("android.app.NotificationManager", cl);
            XposedHelpers.findAndHookMethod(nmClass, "notify", String.class, int.class,
                    android.app.Notification.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            hookNotify = true;
                            hookInfo = "NotificationManager.notify";
                            try {
                                android.app.Notification n = (android.app.Notification) param.args[2];
                                if (n != null) {
                                    StringBuilder sb = new StringBuilder();
                                    try {
                                        android.content.Context ctx = getContext();
                                        String t = null, txt = null;
                                        if (android.os.Build.VERSION.SDK_INT >= 19) {
                                            t = n.extras == null ? null
                                                    : String.valueOf(n.extras.getCharSequence("android.title"));
                                            txt = n.extras == null ? null
                                                    : String.valueOf(n.extras.getCharSequence("android.text"));
                                        }
                                        sb.append("title=").append(t).append("\n");
                                        sb.append("text=").append(txt).append("\n");
                                        // 从通知文案中识别金额（收款到账提示常含金额）
                                        String all = (t == null ? "" : t) + (txt == null ? "" : txt);
                                        java.util.regex.Matcher m = amountPat.matcher(all);
                                        if (m.find()) {
                                            sb.append("APPARENT_AMOUNT=").append(m.group()).append("\n");
                                        }
                                    } catch (Throwable ignore) {
                                    }
                                    sendDebugMsg("notification", "notify", sb.toString());
                                }
                            } catch (Throwable ignore) {
                            }
                        }
                    });
            hookNotify = true;
            log("NotificationManager.notify hooked");
        } catch (Throwable t) {
            log("NotificationManager.notify hook FAIL: " + t);
        }

        // 汇总 hook 状态（如果有更详细的信息则覆盖）
        if (cachedError == null) {
            cachedClassFound = "ot0.q";
            cachedMethodFound = "q.v=" + hookQvOk + " f9.S1=" + hookS1Ok
                    + " f9.j=" + hookJOk + " aa.d=" + hookAaD
                    + " msgEntry=" + hookMsgEntry + " notify=" + hookNotify;
        }

        // 如果 context 已可用就立即上报，否则等 Application.attach
        flushBufferedStatus();
    }

    private static final java.util.regex.Pattern amountPat =
            java.util.regex.Pattern.compile("[¥￥]\\s?\\d{1,3}(:?[.,]\\d{1,2})?\\b");

    /**
     * 从消息对象 f9 中提取 talker/type/内容，写入调试消息流。
     */
    private static void captureF9Message(Object f9) {
        if (f9 == null) return;
        try {
            String talker = strOf(XposedHelpers.callMethod(f9, "Q0"));
            int type = 0;
            try {
                type = ((Integer) XposedHelpers.callMethod(f9, "getType"));
            } catch (Throwable ignore) {
            }
            String content = null;
            try {
                content = strOf(XposedHelpers.callMethod(f9, "j"));
            } catch (Throwable ignore) {
            }
            if (content == null) {
                try {
                    content = strOf(XposedHelpers.callMethod(f9, "S1"));
                } catch (Throwable ignore) {
                }
            }
            if (content != null && content.contains("wcpayinfo")) {
                tryProcessXml(content);
            }
            String summary = content;
            if (summary != null && summary.length() > 300) {
                summary = summary.substring(0, 300);
            }
            sendDebugMsg(talker, String.valueOf(type), summary);
        } catch (Throwable t) {
            log("captureF9Message FAIL: " + t);
        }
    }

    private static String strOf(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static void sendDebugMsg(String talker, String type, String summary) {
        Context ctx = getContext();
        if (ctx == null) {
            // 缓冲到 pendingDebug 里，等 context 就绪再发
            synchronized (pendingDebug) {
                String[] s = new String[]{talker == null ? "" : talker,
                        type == null ? "" : type, summary == null ? "" : summary};
                if (pendingDebug.size() < 200) pendingDebug.add(s);
            }
            return;
        }
        try {
            Bundle b = new Bundle();
            b.putString("talker", talker);
            b.putString("type", type);
            b.putString("summary", summary);
            ctx.getContentResolver().call(PayRecordProvider.URI,
                    PayRecordProvider.METHOD_LOG_MSG, null, b);
        } catch (Throwable ignore) {
        }
    }

    /**
     * 把缓存的 hook 状态上报到 ContentProvider。
     * 只有拿到 appContext 后才会真正发送。
     */
    private static void flushBufferedStatus() {
        if (appContext == null) return;
        if (statusFlushed) return;
        statusFlushed = true;
        if (cachedError != null) {
            sendHeartbeat(PayRecordProvider.KEY_HOOK_ERROR, cachedError);
        } else {
            if (cachedClassFound != null)
                sendHeartbeat(PayRecordProvider.KEY_HOOK_CLASS_FOUND, cachedClassFound);
            if (cachedMethodFound != null)
                sendHeartbeat(PayRecordProvider.KEY_HOOK_METHOD_FOUND, cachedMethodFound);
        }
        // 补发排队中的调试消息
        java.util.List<String[]> copy;
        synchronized (pendingDebug) {
            copy = new java.util.ArrayList<>(pendingDebug);
            pendingDebug.clear();
        }
        for (String[] s : copy) {
            sendDebugMsg(s[0], s[1], s[2]);
        }
    }

    /**
     * 检查 XML 是否包含 wcpayinfo，如果是则提取收款数据。
     */
    private static void tryProcessXml(String xml) {
        if (xml == null || xml.length() < 20) return;
        if (!xml.contains("wcpayinfo")) return;

        hookCount++;
        log("wcpayinfo hit via " + hookInfo + ", xml len=" + xml.length());
        sendHeartbeat(PayRecordProvider.KEY_LAST_MSG_TIME,
                String.valueOf(System.currentTimeMillis()));
        sendHeartbeat(PayRecordProvider.KEY_LAST_MSG_TYPE,
                "wcpayinfo 命中 (hook=" + hookInfo + ")");

        // 提取支付字段
        String feedesc = extractTag(xml, "feedesc");
        String transcationid = extractTag(xml, "transcationid");
        String transferid = extractTag(xml, "transferid");
        String totalFee = extractTag(xml, "total_fee");
        String payMemo = extractTag(xml, "pay_memo");
        String payerUsername = extractTag(xml, "payer_username");
        String paysubtype = extractTag(xml, "paysubtype");

        lastFeedesc = feedesc;
        sendHeartbeat(PayRecordProvider.KEY_LAST_MSG_AMOUNT,
                "feedesc=" + feedesc + " total_fee=" + totalFee + "分 memo=" + payMemo);

        // 订单号
        String orderNo = firstNonEmpty(transcationid, transferid);
        if (orderNo == null || orderNo.isEmpty()) {
            orderNo = "raw-" + Integer.toHexString(xml.hashCode());
        }

        // 去重
        synchronized (processedOrders) {
            if (processedOrders.contains(orderNo)) return;
            processedOrders.add(orderNo);
            if (processedOrders.size() > 5000) processedOrders.clear();
        }

        // 金额（分）
        long amountCent = 0;
        if (totalFee != null && !totalFee.isEmpty()) {
            try { amountCent = Long.parseLong(totalFee); } catch (Exception e) { }
        }
        // 如果 total_fee 为 0，尝试从 feedesc 提取金额
        if (amountCent == 0 && feedesc != null) {
            amountCent = extractAmountFromText(feedesc);
        }

        // 支付子类型
        int subType = 0;
        if (paysubtype != null && !paysubtype.isEmpty()) {
            try { subType = Integer.parseInt(paysubtype); } catch (Exception e) { }
        }

        // 商品/描述
        String goodsName = firstNonEmpty(feedesc, extractTag(xml, "title"));
        // 对方备注
        String memo = payMemo;
        // 付款方
        String sender = payerUsername;

        // 写入数据库
        Bundle b = new Bundle();
        b.putString("orderNo", orderNo);
        b.putLong("amountCent", amountCent);
        b.putLong("timeMillis", System.currentTimeMillis());
        b.putInt("paySubType", subType);
        b.putString("goodsName", goodsName);
        b.putString("memo", memo);
        b.putString("sender", sender);
        b.putString("feedesc", feedesc);
        b.putString("rawXml", xml.length() > 5000 ? xml.substring(0, 5000) : xml);

        Context ctx = getContext();
        if (ctx == null) return;
        try {
            Bundle result = ctx.getContentResolver().call(
                    PayRecordProvider.URI, PayRecordProvider.METHOD_INSERT, null, b);
            boolean inserted = result != null && result.getBoolean("inserted", false);
            log("insert orderNo=" + orderNo + " amount=" + amountCent + " inserted=" + inserted);
            if (inserted) {
                recordCount++;
                payMsgCount++;
                sendHeartbeat(PayRecordProvider.KEY_MSG_COUNT, String.valueOf(recordCount));
            }
        } catch (Throwable ignore) { }
    }

    // -------- XML 提取辅助 --------

    /**
     * 从 XML 文本中提取 <tag>value</tag> 的值。
     * 支持自闭合标签和嵌套。
     */
    private static String extractTag(String xml, String tag) {
        if (xml == null || tag == null) return null;
        String openTag = "<" + tag + ">";
        String closeTag = "</" + tag + ">";
        String selfClose = "</" + tag + ">"; // same as close

        int start = xml.indexOf(openTag);
        if (start < 0) {
            // 尝试带属性的标签 <tag ...>
            int lt = xml.indexOf("<" + tag + " ");
            if (lt >= 0) {
                int gt = xml.indexOf(">", lt);
                if (gt > lt) {
                    int end = xml.indexOf("</" + tag + ">", gt);
                    if (end > gt) {
                        return xml.substring(gt + 1, end).trim();
                    }
                }
            }
            return null;
        }
        int valueStart = start + openTag.length();
        int end = xml.indexOf(closeTag, valueStart);
        if (end < 0) return null;
        return xml.substring(valueStart, end).trim();
    }

    /**
     * 从文本中提取金额（分）。
     * 例如 "¥1.23" → 123, "0.01" → 1
     */
    private static long extractAmountFromText(String text) {
        if (text == null) return 0;
        // 找到数字部分
        int start = -1;
        int end = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') {
                if (start < 0) start = i;
                end = i;
            } else if (start >= 0) {
                break;
            }
        }
        if (start < 0 || end < 0) return 0;
        String numStr = text.substring(start, end + 1);
        try {
            double d = Double.parseDouble(numStr);
            return Math.round(d * 100);
        } catch (Exception e) {
            return 0;
        }
    }

    // -------- 心跳 --------

    private static void sendHeartbeat(String key, String value) {
        Context ctx = getContext();
        if (ctx == null) return;
        try {
            Bundle b = new Bundle();
            b.putString(key, value);
            ctx.getContentResolver().call(PayRecordProvider.URI,
                    PayRecordProvider.METHOD_HEARTBEAT, null, b);
        } catch (Throwable ignore) { }
    }

    private static Context getContext() {
        return appContext;
    }

    private static String firstNonEmpty(String... arr) {
        for (String s : arr) {
            if (s != null && !s.trim().isEmpty()) return s;
        }
        return null;
    }
}
