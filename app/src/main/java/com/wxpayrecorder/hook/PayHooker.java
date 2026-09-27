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
    private static volatile boolean hookU1Ok = false;
    private static volatile boolean hookZcL = false;
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

        // 1) Hook ot0.q.v(String) — 用户打开聊天时解析 appmsg，能拿到真实字段
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
                                    // 用返回的 ot0.q 对象直接读真实字段
                                    Object q = param.getResult();
                                    if (q != null && isInterestingQ(q)) {
                                        recordFromQObject(q);
                                    }
                                    // 兜底：仍然把原始 XML 交给字符串解析
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

        // 3b) Hook com.tencent.mm.storage.f9.U1() — 聊天渲染卡片读取的 content getter
        //    zc(聊天appmsg card viewitem) 用 f9.U1() 拿完整 appmsg XML 再 ot0.q.v 解析。
        //    打开收款/转账聊天时必走，可用 XML 字符串解析提取真实 wcpayinfo 字段。
        try {
            Class<?> f9Class2 = XposedHelpers.findClass("com.tencent.mm.storage.f9", cl);
            XposedHelpers.findAndHookMethod(f9Class2, "U1", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    hookU1Ok = true;
                    hookInfo = "f9.U1()";
                    tryProcessXml((String) param.getResult());
                }
            });
            hookU1Ok = true;
            log("f9.U1() hooked");
        } catch (Throwable t) {
            log("f9.U1 hook FAIL (ok to ignore): " + t);
        }

        // 3c) Hook com.tencent.mm.ui.chatting.viewitems.zc.l() — 聊天 appmsg 卡片渲染入口
        //    无论 UI 如何渲染，只要打开聊天列表中含收款/转账卡片，l() 必被调用并解析 ot0.q。
        try {
            Class<?> zcCls = XposedHelpers.findClass("com.tencent.mm.ui.chatting.viewitems.zc", cl);
            java.lang.reflect.Method[] zcM = zcCls.getDeclaredMethods();
            int zcHooked = 0;
            for (java.lang.reflect.Method m : zcM) {
                if (m.getName().equals("l") && m.getParameterTypes().length == 4) {
                    Class<?>[] pts = m.getParameterTypes();
                    try {
                        XposedHelpers.findAndHookMethod(zcCls, "l",
                                pts[0], pts[1], pts[2], String.class, new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam param) {
                                        hookZcL = true;
                                        hookInfo = "zc.l(bindCard)";
                                        // 从参数 2 (Lrd5/d) 的 .d.a.b 提取 f9 消息对象
                                        try {
                                            Object bind = param.args[2];
                                            Object we5 = XposedHelpers.getObjectField(bind, "d");
                                            Object f9obj = XposedHelpers.getObjectField(we5, "b");
                                            if (f9obj != null) {
                                                String xml = strOf(XposedHelpers.callMethod(f9obj, "U1"));
                                                if (xml == null)
                                                    xml = strOf(XposedHelpers.callMethod(f9obj, "j"));
                                                if (xml != null && xml.contains("wcpayinfo")) {
                                                    tryProcessXml(xml);
                                                }
                                            }
                                        } catch (Throwable ignore) {
                                        }
                                    }
                                });
                        zcHooked++;
                    } catch (Throwable ignore) {
                    }
                }
            }
            if (zcHooked > 0) {
                hookZcL = true;
                log("zc.l() hooked, " + zcHooked + " overload");
            } else {
                log("zc: no l(g0,yb5/d,rd5/d,String) found");
            }
        } catch (Throwable t) {
            log("zc hook FAIL (ok to ignore): " + t);
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
                                        // 从通知文案中识别金额并直接记录收款（收款到账提示含明确金额）
                                        String all = (t == null ? "" : t) + " " + (txt == null ? "" : txt);
                                        java.util.regex.Matcher m = amountPat.matcher(all);
                                        if (m.find()) {
                                            sb.append("APPARENT_AMOUNT=").append(m.group()).append("\n");
                                            try {
                                                recordFromNotify(t, txt, m.group(), all);
                                            } catch (Throwable ignore) {
                                            }
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
                    + " f9.j=" + hookJOk + " f9.U1=" + hookU1Ok + " zc.l=" + hookZcL
                    + " aa.d=" + hookAaD
                    + " msgEntry=" + hookMsgEntry + " notify=" + hookNotify;
        }

        // 如果 context 已可用就立即上报，否则等 Application.attach
        flushBufferedStatus();
    }

    private static final java.util.regex.Pattern amountPat =
            java.util.regex.Pattern.compile("[¥￥]\\s?\\d{1,3}(?:[.,]\\d{1,2})?\\b|收款\\s?(\\d+(?:[,.]\\d{1,2})?)\\s?元");

    /**
     * 判断 ot0.q 对象是否是收款相关 appmsg（含 wcpayinfo 字段）。
     * 微信 appmsg 解析出的对象，若 transcationid/transferid/total_fee 任一存在即为支付消息。
     */
    private static boolean isInterestingQ(Object q) {
        if (q == null) return false;
        try {
            Object tid = XposedHelpers.getObjectField(q, "K0"); // transcationid
            Object fid = XposedHelpers.getObjectField(q, "L0"); // transferid
            Object totalFee = XposedHelpers.getObjectField(q, "Q0"); // total_fee
            if ((tid != null && !String.valueOf(tid).isEmpty())
                    || (fid != null && !String.valueOf(fid).isEmpty())
                    || (totalFee != null && ((Number) totalFee).intValue() > 0)) {
                return true;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /**
     * 从 ot0.q 对象读取真实收款字段并入库（合并/替换占位记录）。
     * 字段映射（来自微信 8.0.74 反编译）：
     *  K0=transcationid, L0=transferid, J0=feedesc, Q0=total_fee(分),
     *  X1=pay_memo, O0=payer_username, I0=paysubtype
     */
    private static void recordFromQObject(Object q) {
        try {
            String transcationid = strOf(XposedHelpers.getObjectField(q, "K0"));
            String transferid = strOf(XposedHelpers.getObjectField(q, "L0"));
            String feedesc = strOf(XposedHelpers.getObjectField(q, "J0"));
            String payMemo = strOf(XposedHelpers.getObjectField(q, "X1"));
            String payer = strOf(XposedHelpers.getObjectField(q, "O0"));
            long totalFeeCent = 0;
            int subType = 0;
            try {
                totalFeeCent = ((Number) XposedHelpers.getObjectField(q, "Q0")).longValue();
            } catch (Throwable ignore) {
            }
            try {
                subType = ((Number) XposedHelpers.getObjectField(q, "I0")).intValue();
            } catch (Throwable ignore) {
            }

            String orderNo = firstNonEmpty(transcationid, transferid);
            if (orderNo == null || orderNo.isEmpty()) orderNo = "raw-real-" + Integer.toHexString(String.valueOf(transcationid).hashCode());

            // 去重
            synchronized (processedOrders) {
                if (processedOrders.contains(orderNo)) return;
                processedOrders.add(orderNo);
                if (processedOrders.size() > 5000) processedOrders.clear();
            }

            log("ot0.q real: order=" + orderNo + " fee=" + totalFeeCent
                    + " feedesc=" + feedesc + " memo=" + payMemo + " payer=" + payer);

            Bundle b = new Bundle();
            b.putString("orderNo", orderNo);
            b.putLong("amountCent", totalFeeCent);
            b.putLong("timeMillis", System.currentTimeMillis());
            b.putInt("paySubType", subType);
            b.putString("goodsName", feedesc == null ? "" : feedesc);
            b.putString("memo", payMemo);
            b.putString("sender", payer);
            b.putString("feedesc", feedesc);
            b.putString("rawXml", "[ot0.q] order=" + orderNo + " fee=" + totalFeeCent
                    + " memo=" + payMemo);
            Context ctx = getContext();
            if (ctx == null) return;
            try {
                Bundle result = ctx.getContentResolver().call(
                        PayRecordProvider.URI, PayRecordProvider.METHOD_INSERT_REAL, null, b);
                boolean inserted = result != null && result.getBoolean("inserted", false);
                log("ot0.q insertReal inserted=" + inserted);
                if (inserted) {
                    recordCount++;
                    payMsgCount++;
                    sendHeartbeat(PayRecordProvider.KEY_MSG_COUNT, String.valueOf(recordCount));
                }
            } catch (Throwable ignore) {
            }
        } catch (Throwable t) {
            log("recordFromQObject FAIL: " + t);
        }
    }

    /**
     * 直接从通知栏文案解析并记录收款。
     * 收款到账通知文本示例：title=微信收款助手  text=微信支付收款0.01元(朋友到店)
     */
    private static void recordFromNotify(String title, String text, String matchedAmount, String all) {
        if (text == null) text = "";
        // 仅处理"收款"相关通知（二维码收款/朋友到店等收款到账）
        if (!text.contains("收款") && !text.contains("到账")) return;
        if (all == null) all = title + " " + text;

        // 金额：优先匹配"收款0.01元"格式
        long amountCent = 0;
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("收款\\s?(\\d+(?:[,.]\\d{1,2})?)\\s?元").matcher(text);
        String amountStr = null;
        if (m2.find()) {
            amountStr = m2.group(1);
        } else {
            java.util.regex.Matcher m = amountPat.matcher(all);
            if (m.find()) {
                amountStr = m.group().replaceAll("[¥￥元\\s]", "");
            }
        }
        if (amountStr == null) return;
        try {
            amountStr = amountStr.replace(",", ".");
            String[] parts = amountStr.split("\\.");
            long yuan = Long.parseLong(parts[0]);
            long fen = 0;
            if (parts.length > 1 && !parts[1].isEmpty()) {
                if (parts[1].length() == 1) fen = Long.parseLong(parts[1]) * 10;
                else if (parts[1].length() >= 2) fen = Long.parseLong(parts[1].substring(0, 2));
            }
            amountCent = yuan * 100 + fen;
        } catch (Exception e) {
            return; // 金额解析失败，放弃
        }
        if (amountCent <= 0) return;

        // 备注/商品名暂不填写（用户确认：通知里无法可靠区分，等 ot0.q 补录真实值）
        String remark = "";
        String goodsName = "";

        // 订单号：通知里没有，用时间戳生成（同一毫秒去重可接受）
        String orderNo = "notify-" + System.currentTimeMillis();

        // 去重
        synchronized (processedOrders) {
            if (processedOrders.contains(orderNo)) return;
            processedOrders.add(orderNo);
            if (processedOrders.size() > 5000) processedOrders.clear();
        }

        // 写入数据库（占位记录，保留金额/时间以便后续用真实单号合并）
        Bundle b = new Bundle();
        b.putString("orderNo", orderNo);
        b.putLong("amountCent", amountCent);
        b.putLong("timeMillis", System.currentTimeMillis());
        b.putInt("paySubType", 1); // 1=收款到账
        b.putString("goodsName", goodsName);
        b.putString("memo", remark);
        b.putString("sender", title == null ? "微信收款助手" : title);
        b.putString("feedesc", text);
        b.putString("rawXml", "[notification] " + text);

        Context ctx = getContext();
        if (ctx == null) return;
        try {
            Bundle result = ctx.getContentResolver().call(
                    PayRecordProvider.URI, PayRecordProvider.METHOD_INSERT, null, b);
            boolean inserted = result != null && result.getBoolean("inserted", false);
            log("notify insert orderNo=" + orderNo + " amount=" + amountCent
                    + " goods=" + goodsName + " inserted=" + inserted);
            if (inserted) {
                recordCount++;
                payMsgCount++;
                sendHeartbeat(PayRecordProvider.KEY_MSG_COUNT, String.valueOf(recordCount));
            }
        } catch (Throwable ignore) { }
    }

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
            // 有真实单号(transcationid/transferid)时走 insertReal 对账合并：
            // 把同金额相近时间的占位通知记录替换成真实单号+真实字段；
            // 否则(mesh 通知占位)走普通 insert。
            boolean hasRealOrder = !firstNonEmpty(transcationid, transferid).isEmpty();
            String method = hasRealOrder
                    ? PayRecordProvider.METHOD_INSERT_REAL
                    : PayRecordProvider.METHOD_INSERT;
            Bundle result = ctx.getContentResolver().call(
                    PayRecordProvider.URI, method, null, b);
            boolean inserted = result != null && result.getBoolean("inserted", false);
            boolean merged = result != null && result.getBoolean("merged", false);
            log("insert orderNo=" + orderNo + " amount=" + amountCent
                    + " real=" + hasRealOrder + " inserted=" + inserted + " merged=" + merged);
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
