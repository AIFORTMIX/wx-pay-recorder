package com.wxpayrecorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.wxpayrecorder.db.DbHelper;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：上方显示 hook 状态面板，下方显示收款记录列表，自动刷新。
 */
public class MainActivity extends Activity {

    private DbHelper db;
    private TextView statusView;
    private TextView contentView;
    private TextView debugView;
    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
    private final SimpleDateFormat fmtFull = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshAll();
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new DbHelper(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        // ===== 状态面板 =====
        statusView = new TextView(this);
        statusView.setTextSize(13f);
        statusView.setPadding(16, 16, 16, 16);
        statusView.setBackgroundColor(0xFF1A1A1A);
        statusView.setTextColor(0xFFCCCCCC);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, 0, 0, 16);
        root.addView(statusView, statusLp);

        // ===== 按钮行 =====
        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(v -> refreshAll());

        Button clear = new Button(this);
        clear.setText("清空");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("确认删除全部收款记录？")
                .setPositiveButton("删除", (d, w) -> {
                    db.deleteAll();
                    refreshAll();
                })
                .setNegativeButton("取消", null)
                .show());

        Button export = new Button(this);
        export.setText("导出CSV");
        export.setOnClickListener(v -> exportCsv());

        Button clearDebug = new Button(this);
        clearDebug.setText("清消息流");
        clearDebug.setOnClickListener(v -> {
            db.clearDebug();
            refreshAll();
        });

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.addView(refresh, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        btnRow.addView(clear, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        btnRow.addView(export, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        btnRow.addView(clearDebug, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(btnRow);

        // ===== 调试消息流（最近收到的微信消息，用于定位收款消息） =====
        debugView = new TextView(this);
        debugView.setTextSize(12f);
        debugView.setPadding(8, 12, 8, 8);
        debugView.setBackgroundColor(0xFF001020);
        debugView.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(debugView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ===== 收款记录列表 =====
        contentView = new TextView(this);
        contentView.setTextSize(13f);
        contentView.setPadding(0, 16, 0, 0);

        ScrollView sv = new ScrollView(this);
        sv.addView(contentView);

        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
    }

    private void refreshAll() {
        refreshStatus();
        refreshDebug();
        refreshList();
    }

    private void refreshDebug() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 调试消息流 (最近接收的微信消息) ===\n");
        java.util.List<String[]> msgs = db.getDebugMessages(20);
        if (msgs.isEmpty()) {
            sb.append("(无捕获消息。请付款/收发消息后刷新)\n");
        }
        for (String[] m : msgs) {
            long t = Long.parseLong(m[0]);
            sb.append(fmt.format(new Date(t)));
            sb.append("  type=").append(m[2] == null ? "?" : m[2]);
            sb.append("  talker=").append(m[1] == null ? "?" : m[1]);
            sb.append("\n  ").append(m[3] == null ? "" : m[3].replace("\u0000", " ") ).append("\n--\n");
        }
        debugView.setText(sb.toString());
    }

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();

        // 读取心跳状态
        String hookInstalled = db.getStatus(PayRecordProvider.KEY_HOOK_INSTALLED);
        long installedTime = db.getStatusTime(PayRecordProvider.KEY_HOOK_INSTALLED);
        String classFound = db.getStatus(PayRecordProvider.KEY_HOOK_CLASS_FOUND);
        String methodFound = db.getStatus(PayRecordProvider.KEY_HOOK_METHOD_FOUND);
        String hookError = db.getStatus(PayRecordProvider.KEY_HOOK_ERROR);
        long errorTime = db.getStatusTime(PayRecordProvider.KEY_HOOK_ERROR);
        String lastMsgTime = db.getStatus(PayRecordProvider.KEY_LAST_MSG_TIME);
        long lastMsgTimestamp = db.getStatusTime(PayRecordProvider.KEY_LAST_MSG_TIME);
        String lastMsgType = db.getStatus(PayRecordProvider.KEY_LAST_MSG_TYPE);
        String lastMsgAmount = db.getStatus(PayRecordProvider.KEY_LAST_MSG_AMOUNT);
        String msgCount = db.getStatus(PayRecordProvider.KEY_MSG_COUNT);

        // 模块激活状态
        if (hookInstalled != null && "true".equals(hookInstalled)) {
            sb.append("✓ 模块已激活 (微信进程内运行)\n");
            sb.append("  激活时间: ").append(installedTime > 0
                    ? fmt.format(new Date(installedTime)) : "未知").append("\n");
        } else {
            sb.append("✗ 模块未激活\n");
            sb.append("  请在 LSPosed 中启用并勾选微信作用域\n");
        }

        // Hook 状态
        if (hookError != null) {
            sb.append("✗ Hook 失败: ").append(hookError).append("\n");
            if (errorTime > 0) sb.append("  时间: ").append(fmt.format(new Date(errorTime))).append("\n");
        } else if (classFound != null && methodFound != null) {
            sb.append("✓ Hook 就绪: ").append(classFound).append(".").append(methodFound).append("\n");
        } else if (classFound != null) {
            sb.append("△ 类已找到但方法未找到: ").append(classFound).append("\n");
        } else {
            sb.append("? Hook 状态未知 (微信未启动或未触发)\n");
        }

        // 消息处理状态
        if (lastMsgTime != null) {
            sb.append("最后收到消息: ");
            if (lastMsgTimestamp > 0) {
                long ago = System.currentTimeMillis() - lastMsgTimestamp;
                sb.append(fmt.format(new Date(lastMsgTimestamp)));
                sb.append(" (").append(timeAgo(ago)).append("前)\n");
            }
            if (lastMsgType != null) sb.append("  ").append(lastMsgType).append("\n");
            if (lastMsgAmount != null) sb.append("  ").append(lastMsgAmount).append("\n");
        } else {
            sb.append("最后收到消息: 无 (微信未打开聊天或无支付消息)\n");
        }

        sb.append("已记录收款: ").append(msgCount != null ? msgCount : "0").append(" 笔\n");

        // 实时性提示
        if (hookInstalled != null && lastMsgTime != null && lastMsgTimestamp > 0) {
            long ago = System.currentTimeMillis() - lastMsgTimestamp;
            if (ago < 30000) {
                sb.append("\n[实时] 收款信息正在实时更新\n");
            } else {
                sb.append("\n[等待] 最近30秒无新消息\n");
            }
        }

        statusView.setText(sb.toString());
    }

    private String timeAgo(long ms) {
        if (ms < 1000) return "刚刚";
        long s = ms / 1000;
        if (s < 60) return s + "秒";
        long m = s / 60;
        if (m < 60) return m + "分";
        return (m / 60) + "时" + (m % 60) + "分";
    }

    private void refreshList() {
        List<PayRecord> list = db.all();
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(list.size()).append(" 笔收款\n\n");
        double total = 0;
        for (PayRecord r : list) {
            total += r.amountYuan();
            sb.append(fmt.format(new Date(r.timeMillis))).append("\n");
            sb.append("  ¥").append(String.format(Locale.CHINA, "%.2f", r.amountYuan())).append("\n");
            if (r.goodsName != null && !r.goodsName.isEmpty())
                sb.append("  商品: ").append(r.goodsName).append("\n");
            if (r.memo != null && !r.memo.isEmpty())
                sb.append("  备注: ").append(r.memo).append("\n");
            if (r.orderNo != null && !r.orderNo.isEmpty())
                sb.append("  单号: ").append(r.orderNo).append("\n");
            sb.append("---\n");
        }
        sb.insert(0, "累计: ¥" + String.format(Locale.CHINA, "%.2f", total) + "\n");
        contentView.setText(sb.toString());
    }

    private void exportCsv() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File file = new File(dir, "收款记录_" + System.currentTimeMillis() + ".csv");
            PrintWriter pw = new PrintWriter(new FileWriter(file));
            pw.println("时间,金额(元),商品/描述,对方备注,订单号,付款方,支付子类型");
            for (PayRecord r : db.all()) {
                pw.println(quote(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(r.timeMillis)))
                        + "," + String.format(Locale.CHINA, "%.2f", r.amountYuan())
                        + "," + quote(r.goodsName)
                        + "," + quote(r.memo)
                        + "," + quote(r.orderNo)
                        + "," + quote(r.sender)
                        + "," + r.paySubType);
            }
            pw.flush();
            pw.close();
            Toast.makeText(this, "已导出: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
