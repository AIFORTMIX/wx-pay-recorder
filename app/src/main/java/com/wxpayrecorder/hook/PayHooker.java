package com.wxpayrecorder.hook;

import android.app.Application;
import android.content.Context;

import com.wxpayrecorder.PayRecordProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 核心 Hooker。
 *
 * hook ot0.q(String) 解析 wcpayinfo 收款卡片，
 * 通过 ContentProvider 写入本模块数据库，
 * 同时通过心跳机制上报 hook 状态供 App 端验证。
 */
public class PayHooker {

    private static volatile Context appContext;
    private static volatile boolean hookInstalled = false;
    private static volatile int msgCount = 0;

    private static final List<String> RECORD_KEYWORDS =
            new ArrayList<>(java.util.Collections.singletonList("二维码收款"));

    private PayHooker() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        // 1) 捕获 Application Context
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            appContext = (Context) param.thisObject;
                            // 上报：模块已加载 + Application Context 就绪
                            sendHeartbeat(PayRecordProvider.KEY_HOOK_INSTALLED, "true");
                        }
                    });
        } catch (Throwable t) {
            // attach 失败不影响后续 hook
        }

        // 2) hook AppMsg 解析入口
        final ClassLoader cl = lpparam.classLoader;
        final String targetClass = "ot0.q";
        final String targetMethod = "v";

        Class<?> appMsg;
        try {
            appMsg = XposedHelpers.findClass(targetClass, cl);
        } catch (Throwable t) {
            // 类找不到 → 上报错误
            sendHeartbeat(PayRecordProvider.KEY_HOOK_ERROR,
                    "类 " + targetClass + " 未找到: " + t.getMessage());
            return;
        }

        sendHeartbeat(PayRecordProvider.KEY_HOOK_CLASS_FOUND, targetClass);

        // 查找目标方法
        Method foundMethod = null;
        for (Method m : appMsg.getDeclaredMethods()) {
            if (m.getName().equals(targetMethod) && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0] == String.class) {
                foundMethod = m;
                break;
            }
        }

        if (foundMethod == null) {
            // 退化：查找同名单参数方法
            for (Method m : appMsg.getDeclaredMethods()) {
                if (m.getName().equals(targetMethod) && m.getParameterTypes().length == 1) {
                    foundMethod = m;
                    break;
                }
            }
        }

        if (foundMethod == null) {
            sendHeartbeat(PayRecordProvider.KEY_HOOK_ERROR,
                    "方法 " + targetMethod + "(String) 在 " + targetClass + " 中未找到");
            return;
        }

        sendHeartbeat(PayRecordProvider.KEY_HOOK_METHOD_FOUND,
                targetMethod + "(" + foundMethod.getParameterTypes()[0].getSimpleName() + ")");
        hookInstalled = true;

        // hook 目标方法
        XposedHelpers.findAndHookMethod(appMsg, targetMethod,
                foundMethod.getParameterTypes(), new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object q = param.getResult();
                            if (q == null) return;

                            if (!isWcPayInfo(q)) return;

                            // 上报每次支付消息解析（含非收款）
                            String feedesc = str(get(q, "J0"));
                            sendHeartbeat(PayRecordProvider.KEY_LAST_MSG_TIME,
                                    String.valueOf(System.currentTimeMillis()));
                            sendHeartbeat(PayRecordProvider.KEY_LAST_MSG_TYPE,
                                    "feedesc=" + (feedesc == null ? "null" : feedesc));
                            sendHeartbeat(PayRecordProvider.KEY_LAST_MSG_AMOUNT,
                                    "amount=" + num(get(q, "Q0")) + "分");

                            if (!shouldRecord(q, cl)) return;
                            sendRecord(q, (String) param.args[0], cl);
                            msgCount++;
                            sendHeartbeat(PayRecordProvider.KEY_MSG_COUNT,
                                    String.valueOf(msgCount));
                        } catch (Throwable ignore) {
                        }
                    }
                });
    }

    // -------- 判定与提取 --------

    private static boolean isWcPayInfo(Object q) {
        return !isEmpty(str(get(q, "J0"))) || get(q, "I0") != null;
    }

    private static boolean shouldRecord(Object q, ClassLoader cl) {
        String feedesc = str(get(q, "J0"));
        if (RECORD_KEYWORDS.isEmpty()) return true;
        for (String kw : RECORD_KEYWORDS) {
            if (feedesc != null && feedesc.contains(kw)) return true;
        }
        return false;
    }

    private static void sendRecord(Object q, String rawXml, ClassLoader cl) {
        StringBuilder order = new StringBuilder();
        order.append(str(get(q, "K0")));
        if (order.length() == 0) order.append(str(get(q, "L0")));
        String orderNo = order.toString().trim();
        if (orderNo.length() == 0) {
            orderNo = "raw-" + Integer.toHexString(rawXml == null ? 0 : rawXml.hashCode());
        }

        long amountCent = num(get(q, "Q0"));
        String goods = firstNonEmpty(str(get(q, "J0")), str(get(q, "f")));
        String memo = str(get(q, "X1"));
        String sender = str(get(q, "O0"));
        int subType = (int) num(get(q, "I0"));

        android.os.Bundle b = new android.os.Bundle();
        b.putString("orderNo", orderNo);
        b.putLong("amountCent", amountCent);
        b.putLong("timeMillis", System.currentTimeMillis());
        b.putInt("paySubType", subType);
        b.putString("goodsName", goods);
        b.putString("memo", memo);
        b.putString("sender", sender);
        b.putString("feedesc", str(get(q, "J0")));
        b.putString("rawXml", rawXml);

        Context ctx = getContext();
        if (ctx == null) return;
        try {
            ctx.getContentResolver().call(PayRecordProvider.URI,
                    PayRecordProvider.METHOD_INSERT, null, b);
        } catch (Throwable ignore) {
        }
    }

    // -------- 心跳上报 --------

    private static void sendHeartbeat(String key, String value) {
        Context ctx = getContext();
        if (ctx == null) return;
        try {
            android.os.Bundle b = new android.os.Bundle();
            b.putString(key, value);
            ctx.getContentResolver().call(PayRecordProvider.URI,
                    PayRecordProvider.METHOD_HEARTBEAT, null, b);
        } catch (Throwable ignore) {
        }
    }

    // -------- 反射辅助 --------

    private static Context getContext() {
        return appContext;
    }

    private static Object get(Object obj, String field) {
        try {
            return XposedHelpers.getObjectField(obj, field);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long num(Object o) {
        if (o == null) return 0;
        try {
            if (o instanceof Number) return ((Number) o).longValue();
            return Long.parseLong(String.valueOf(o));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().length() == 0;
    }

    private static String firstNonEmpty(String... arr) {
        for (String s : arr) {
            if (!isEmpty(s)) return s;
        }
        return null;
    }
}
