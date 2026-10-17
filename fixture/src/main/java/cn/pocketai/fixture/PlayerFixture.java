package cn.pocketai.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** Local test screen only. No media, course server, credentials or learning records. */
public class PlayerFixture extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, clock, title;
    private int lesson = 1, seconds = 0;
    private boolean blocked = false;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!blocked && seconds < 12) seconds++;
            update(); handler.postDelayed(this, 1000);
        }
    };
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(0xff1b3834); view.setPadding(25, 24, 25, 24); return view;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_VERTICAL); page.setPadding(30, 50, 30, 50);
        page.setBackgroundColor(0xfff3f5f2); page.setKeepScreenOn(true);
        page.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(30 + bars.left, 30 + bars.top, 30 + bars.right, 30 + bars.bottom); return insets;
        });
        page.addView(text("屏幕识别与点击测试", 26));
        page.addView(text("这是本地模拟播放器，不连接任何课程平台。", 16));
        title = text("", 26); page.addView(title);
        clock = text("", 38); page.addView(clock);
        status = text("", 24); page.addView(status);
        Button next = new Button(this); next.setText("下一节"); next.setTextSize(26);
        next.setOnClickListener(v -> { if (seconds >= 12 && !blocked) { lesson++; seconds = 0; update(); } });
        next.setMinHeight((int) (64 * getResources().getDisplayMetrics().density));
        page.addView(next, new LinearLayout.LayoutParams(-1, -2));
        Button verify = new Button(this); verify.setText("显示验证提示"); verify.setTextSize(18);
        verify.setOnClickListener(v -> { blocked = !blocked; update(); }); page.addView(verify);
        setContentView(page); update(); handler.postDelayed(tick, 1000);
    }
    private void update() {
        title.setText("模拟小节 " + lesson);
        clock.setText(String.format(Locale.ROOT, "00:%02d / 00:12", seconds));
        status.setText(blocked ? "安全验证：请本人手动处理" : seconds == 12 ? "播放结束" : "正在播放");
    }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy(); }
}
