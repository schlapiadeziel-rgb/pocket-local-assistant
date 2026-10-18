package cn.pocketai.fixture;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** Isolated visual regression fixture. No course service or learning records. */
public class DirectoryFixture extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private int seconds, lesson;
    private boolean blocked;
    private long controlsUntil;
    private TextView clock, poster;
    private final TextView[] rows = new TextView[4];
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(0xff1b3834); return view;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE); page.setKeepScreenOn(true);
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        FrameLayout player = new FrameLayout(this); player.setBackgroundColor(0xfffff8d8);
        poster = text("本地目录播放器\n轻触左侧显示进度", 22); poster.setGravity(Gravity.CENTER);
        player.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        clock = text("", 21); clock.setPadding(dp(12), dp(4), dp(12), dp(4));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        player.addView(clock, cp);
        player.setOnClickListener(v -> { controlsUntil = SystemClock.elapsedRealtime() + 5500; update(); });
        page.addView(player, new LinearLayout.LayoutParams(-1, dp(165)));
        LinearLayout tabs = new LinearLayout(this); tabs.setPadding(dp(10), dp(12), dp(10), dp(12));
        tabs.setBackgroundColor(0xfff0e7ff); tabs.addView(text("教程", 17));
        TextView reminder = text("课程提醒", 17); reminder.setPadding(dp(26), 0, 0, 0); tabs.addView(reminder); page.addView(tabs);
        TextView title = text("1.1 Unit · 仅测试用目录", 17); title.setPadding(dp(20), dp(12), dp(12), dp(8)); page.addView(title);
        for (int i = 0; i < rows.length; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this); item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(22), dp(8), dp(12), dp(8));
            rows[i] = text("1.1." + (i + 1) + " Test lesson " + (i + 1), 18); item.addView(rows[i]);
            item.addView(text("时长 00:12", 13));
            item.setOnClickListener(v -> { if (index == lesson + 1 && seconds == 12 && !blocked) { lesson = index; seconds = 0; controlsUntil = 0; update(); } });
            page.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
        Button warning = new Button(this); warning.setText("显示验证提示");
        warning.setOnClickListener(v -> { blocked = !blocked; update(); }); page.addView(warning);
        TextView footer = text("资料            缓存", 16); footer.setGravity(Gravity.CENTER); page.addView(footer);
        setContentView(page);
        main.post(new Runnable() {
            @Override public void run() { if (!blocked && seconds < 12) seconds++; update(); main.postDelayed(this, 1000); }
        });
        update();
    }
    private void update() {
        clock.setText(SystemClock.elapsedRealtime() < controlsUntil ? String.format(Locale.ROOT, "00:%02d / 00:12", seconds) : "");
        poster.setText(blocked ? "安全验证：请本人手动处理" : "本地目录播放器\n轻触左侧显示进度");
        for (int i = 0; i < rows.length; i++) rows[i].setTextColor(i == lesson ? 0xff34b993 : 0xff8a8a8a);
    }
    @Override public void onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy(); }
}
