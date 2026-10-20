package cn.pocketai.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Synthetic ungraded practice: model inference, click, result and dismissal are tested separately. */
public class PracticeFixture extends Activity {
    private int page = 0, answers = 0, closes = 0;
    private String picked = "";
    private TextView result;
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(0xff243b36); view.setPadding(dp(18), dp(6), dp(18), dp(6)); return view;
    }
    private void gap(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    @Override public void onCreate(Bundle saved) { super.onCreate(saved); render(); }
    private void render() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff5f5f2); root.setKeepScreenOn(true);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom); return insets;
        });
        gap(root, 115);
        if (page >= 2) {
            root.addView(text("练习测试结束", 26));
            root.addView(text("回答 " + answers + " 次 · 关闭 " + closes + " 次", 22));
            root.addView(text("两道合成练习均正确作答并关闭。没有连接课程平台。", 18));
            setContentView(root); return;
        }
        Button close = new Button(this); close.setText("关闭");
        LinearLayout closeRow = new LinearLayout(this); closeRow.setGravity(Gravity.RIGHT);
        closeRow.addView(close, new LinearLayout.LayoutParams(dp(85), dp(44))); root.addView(closeRow);
        close.setOnClickListener(v -> { if (!picked.isEmpty()) { closes++; page++; picked = ""; render(); } });
        root.addView(text(page == 0 ? "单选题" : "判断题", 20)); gap(root, 10);
        root.addView(text(page == 0 ? "2 + 3 的计算结果是多少？" : "2 × 3 = 6，这个等式正确吗？", 22)); gap(root, 15);
        String[] options = page == 0 ? new String[]{"A  4", "B  5", "C  6"} : new String[]{"A  对", "B  错"};
        for (String option : options) {
            Button choice = new Button(this); choice.setText(option); choice.setTextSize(20);
            root.addView(choice, new LinearLayout.LayoutParams(-1, dp(62))); gap(root, 10);
            choice.setOnClickListener(v -> {
                if (picked.isEmpty()) { picked = option.substring(0, 1); answers++; result.setText("正确答案：" + (page == 0 ? "B" : "A")); }
            });
        }
        result = text("", 20); root.addView(result);
        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        root.addView(text("上一题  1 / 1  下一题", 17)); gap(root, 15);
        root.addView(text("弹题是为了帮助同学巩固知识点，不会影响到大家作业和考试的成绩。", 15)); gap(root, 20);
        setContentView(root);
    }
}
