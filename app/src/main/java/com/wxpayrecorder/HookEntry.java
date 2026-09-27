package com.wxpayrecorder;

import com.wxpayrecorder.hook.PayHooker;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块入口。仅在微信进程 (com.tencent.mm) 中激活。
 */
public class HookEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.tencent.mm".equals(lpparam.packageName)) {
            return;
        }
        PayHooker.install(lpparam);
    }
}