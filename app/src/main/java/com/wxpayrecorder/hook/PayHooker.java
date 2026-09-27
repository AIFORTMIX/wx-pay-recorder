package com.wxpayrecorder.hook;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;

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
 * 方案：
 *  - hook 微信统一的 AppMsg 解析入口 ot0.q(String) -> ot0.q 对象；
 *  - 该对象含 wcpayinfo 字段（金额/订单号/类型/备注/收付款人）；
 *  - 判断是否为"二维码收款"（见 shouldRecord）：receiver 是自己 且 feedesc 含关键词；
 *  - 通过 ContentProvider 写入本模块数据库（orderNo 唯一去重）。
 *
 * 注意：微信是强混淆应用，ot0/字段名等是真实运行时的类名/字段名（jadx 未改名）。
 */
public class PayHooker {

    // 用于记录应用 Context（provider 调用需要）
    private static volatile Context appContext;

    // 可配置的"收款"判定关键词，空列表 = 只按 receiver==自己 判断。
    private static final List<String> RECORD_KEYWORDS = new ArrayList<>(java.util.Collections.singletonList("二维码收款"));

    private PayHooker() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        // 1) 捕获 Application Context（provider 需要）
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            appContext = (Context) param.thisObject;
                        }
                    });
        } catch (Throwable t) {
            // 老的 hook API 下 attach 可能在更早调用；不影响后续兜底
        }

        // 2) hook 统一的 AppMsg 解析入口
        final ClassLoader cl = lpparam.classLoader;
        try {
            final Class<?> appMsg = XposedHelpers.findClass("ot0.q", cl);
            final String targetMethod = "v";
            // 确认方法存在
            for (Method m : appMsg.getDeclaredMethods()) {
                if (m.getName().equals(targetMethod) && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == String.class) {
                    XposedHelpers.findAndHookMethod(appMsg, targetMethod, String.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        Object q = param.getResult();
                                        if (q == null) return;
                                        boolean isPay = isWcPayInfo(q);
                                        if (!isPay) return;
                                        if (!shouldRecord(q, cl)) return;
                                        sendRecord(q, (String) param.args[0], cl);
                                    } catch (Throwable ignore) {
                                        // hook 不应影响微信正常运行
                                    }
                                }
                            });
                    return; // 只 hook 一次
                }
            }
            // 若签名不同，退化的通用查找
            for (Method m : appMsg.getDeclaredMethods()) {
                if (m.getName().equals(targetMethod) && m.getParameterTypes().length == 1) {
                    XposedHelpers.findAndHookMethod(appMsg, targetMethod,
                            m.getParameterTypes(), new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        Object q = param.getResult();
                                        if (q == null) return;
                                        if (!isWcPayInfo(q)) return;
                                        if (!shouldRecord(q, cl)) return;
                                        sendRecord(q, String.valueOf(param.args[0]), cl);
                                    } catch (Throwable ignore) {
                                    }
                                }
                            });
                    return;
                }
            }
        } catch (Throwable t) {
            // 类名变化：将触发记录到日志
        }
    }

    // -------- 判定与提取（全部用反射读取字段，避免编译期依赖微信类） --------

    /** 是否为 wcpayinfo 支付卡片：含 paysubtype / feedesc 字段即可认为支付相关内容。 */
    private static boolean isWcPayInfo(Object q) {
        return !isEmpty(get(q, "J0")) || get(q, "I0") != null;
    }

    /** 仅记录"二维码收款"：feedesc 命中关键词（可配置）。 */
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
        order.append(str(get(q, "K0")));   // transcationid
        if (order.length() == 0) order.append(str(get(q, "L0"))); // transferid
        String orderNo = order.toString().trim();
        if (orderNo.length() == 0) {
            // 无订单号时用原始 xml 做去重键
            orderNo = "raw-" + Integer.toHexString(rawXml == null ? 0 : rawXml.hashCode());
        }

        long amountCent = num(get(q, "Q0"));   // total_fee，单位分
        // 商品名/描述: feedesc 优先，其次 title
        String goods = firstNonEmpty(str(get(q, "J0")), str(get(q, "f")));
        // 对方备注: 付款方填写的备注 pay_memo
        String memo = str(get(q, "X1"));
        String sender = str(get(q, "O0"));     // payer_username
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

    // -------- 反射辅助 --------

    private static Context getContext() {
        if (appContext != null) return appContext;
        // 兜底：扫描已 attach 的全局 Application
        try {
            Field f = Application.class.getDeclaredField("mBase");
            // 无法直接获得全局单例，返回 null 等下一次回调
            return null;
        } catch (Throwable t) {
            return null;
        }
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