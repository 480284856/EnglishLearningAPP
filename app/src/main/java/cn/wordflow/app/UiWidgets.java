package cn.wordflow.app;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Shared native-widget styling; contains no data or playback behavior. */
final class UiWidgets {
  private final Context context;

  UiWidgets(Context context) {
    this.context = context;
  }

  int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  TextView text(String value, int size, int color) {
    TextView v = new TextView(context);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setPadding(0, dp(3), 0, dp(3));
    return v;
  }

  Button button(String title, View.OnClickListener click) {
    Button b = new Button(context);
    b.setText(title);
    b.setAllCaps(false);
    b.setTextSize(13);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setOnClickListener(click);
    return b;
  }

  LinearLayout row() {
    LinearLayout v = new LinearLayout(context);
    v.setOrientation(LinearLayout.HORIZONTAL);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  LinearLayout.LayoutParams weighted() {
    return new LinearLayout.LayoutParams(0, -2, 1);
  }

  GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }
}
