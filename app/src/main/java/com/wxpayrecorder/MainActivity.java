package com.wxpayrecorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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
 * 极简查看界面：列出收款记录，可导出 CSV、清空。
 */
public class MainActivity extends Activity {

    private DbHelper db;
    private TextView content;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new DbHelper(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(v -> refreshList());

        Button clear = new Button(this);
        clear.setText("清空");
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("确认删除全部收款记录？")
                .setPositiveButton("删除", (d, w) -> {
                    db.deleteAll();
                    refreshList();
                })
                .setNegativeButton("取消", null)
                .show());

        Button export = new Button(this);
        export.setText("导出 CSV");
        export.setOnClickListener(v -> exportCsv());

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.addView(refresh, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        btnRow.addView(clear, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        btnRow.addView(export, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        content = new TextView(this);
        content.setTextSize(14f);
        content.setPadding(0, 24, 0, 0);

        ScrollView sv = new ScrollView(this);
        sv.addView(content);

        root.addView(btnRow);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshList();
    }

    private void refreshList() {
        List<PayRecord> list = db.all();
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(list.size()).append(" 笔收款\n\n");
        double total = 0;
        for (PayRecord r : list) {
            total += r.amountYuan();
            sb.append(fmt.format(new Date(r.timeMillis))).append("\n");
            sb.append("金额: ¥").append(String.format(Locale.CHINA, "%.2f", r.amountYuan())).append("\n");
            if (r.goodsName != null && !r.goodsName.isEmpty()) sb.append("商品/描述: ").append(r.goodsName).append("\n");
            if (r.memo != null && !r.memo.isEmpty()) sb.append("对方备注: ").append(r.memo).append("\n");
            if (r.orderNo != null && !r.orderNo.isEmpty()) sb.append("单号: ").append(r.orderNo).append("\n");
            if (r.sender != null && !r.sender.isEmpty()) sb.append("付款方: ").append(r.sender).append("\n");
            sb.append("--------------------\n");
        }
        sb.insert(0, "累计: ¥" + String.format(Locale.CHINA, "%.2f", total) + "\n");
        content.setText(sb.toString());
    }

    private void exportCsv() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File file = new File(dir, "收款记录_" + System.currentTimeMillis() + ".csv");
            PrintWriter pw = new PrintWriter(new FileWriter(file));
            pw.println("时间,金额(元),商品/描述,对方备注,订单号,付款方,支付子类型");
            for (PayRecord r : db.all()) {
                pw.println(quote(fmt.format(new Date(r.timeMillis)))
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