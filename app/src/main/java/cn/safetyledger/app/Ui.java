package cn.safetyledger.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

public final class Ui {
    public static final int BLUE = Color.rgb(30, 91, 216);
    public static final int BLUE_DARK = Color.rgb(21, 75, 183);
    public static final int BLUE_PALE = Color.rgb(235, 243, 255);
    public static final int TEXT = Color.rgb(20, 35, 61);
    public static final int MUTED = Color.rgb(100, 116, 139);
    public static final int LINE = Color.rgb(218, 226, 239);
    public static final int BG = Color.rgb(244, 247, 252);
    public static final int DANGER = Color.rgb(208, 56, 61);
    // Compatibility aliases used by settings/template screens.
    public static final int TEAL = BLUE, DARK = BLUE_DARK;

    private Ui() {}

    /** Force ordinary system-window layout so content never sits underneath the status bar. */
    public static void setupWindow(Activity activity) {
        Window window = activity.getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(BLUE_DARK);
        window.setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true);
        }
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static TextView text(Context context, String value, int sp, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(TEXT);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    public static Button button(Context context, String label) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setBackground(shape(context, BLUE, BLUE, 10));
        button.setMinHeight(dp(context, 46));
        button.setMinimumWidth(0);
        button.setStateListAnimator(null);
        return button;
    }

    public static Button secondaryButton(Context context, String label) {
        Button button = button(context, label);
        button.setTextColor(BLUE_DARK);
        button.setBackground(shape(context, Color.WHITE, Color.rgb(190, 207, 233), 10));
        return button;
    }

    public static Button compactButton(Context context, String label, boolean primary) {
        Button button = primary ? button(context, label) : secondaryButton(context, label);
        button.setTextSize(13);
        button.setMinHeight(dp(context, 36));
        button.setPadding(dp(context, 8), 0, dp(context, 8), 0);
        return button;
    }

    public static Button dangerButton(Context context, String label) {
        Button button = compactButton(context, label, false);
        button.setTextColor(DANGER);
        button.setBackground(shape(context, Color.WHITE, Color.rgb(244, 190, 190), 9));
        return button;
    }

    public static Button iconButton(Context context, String label) {
        Button button = secondaryButton(context, label);
        button.setTextSize(22);
        button.setMinHeight(dp(context, 42));
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    public static Button choiceButton(Context context, String label, boolean selected) {
        Button button = secondaryButton(context, label);
        styleChoice(context, button, selected);
        button.setTextSize(14);
        button.setMinHeight(dp(context, 38));
        return button;
    }

    public static void styleChoice(Context context, Button button, boolean selected) {
        button.setTextColor(selected ? Color.WHITE : TEXT);
        button.setBackground(shape(context, selected ? BLUE : Color.WHITE,
                selected ? BLUE : LINE, 8));
    }

    public static EditText input(Context context, String hint) {
        EditText editText = new EditText(context);
        editText.setHint(hint);
        editText.setHintTextColor(Color.rgb(148, 163, 184));
        editText.setTextColor(TEXT);
        editText.setTextSize(15);
        editText.setSingleLine(false);
        editText.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        editText.setBackground(shape(context, Color.WHITE, LINE, 10));
        return editText;
    }

    public static LinearLayout row(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    public static LinearLayout column(Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    public static LinearLayout card(Context context) {
        LinearLayout card = column(context);
        card.setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14));
        card.setBackground(shape(context, Color.WHITE, LINE, 16));
        return card;
    }

    public static TextView sectionTitle(Context context, String number, String title, String subtitle) {
        TextView text = text(context, number + "  " + title + (subtitle == null || subtitle.isBlank()
                ? "" : "\n     " + subtitle), 17, true);
        text.setTextColor(TEXT);
        text.setLineSpacing(0, 1.05f);
        return text;
    }

    public static LinearLayout.LayoutParams weight(float value) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, value);
    }

    public static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static View gap(Context context, int height) {
        Space space = new Space(context);
        space.setLayoutParams(new ViewGroup.LayoutParams(1, dp(context, height)));
        return space;
    }

    public static View horizontalGap(Context context, int width) {
        Space space = new Space(context);
        space.setLayoutParams(new ViewGroup.LayoutParams(dp(context, width), 1));
        return space;
    }

    public static View divider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(LINE);
        divider.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        return divider;
    }

    public static GradientDrawable border(Context context, int color) {
        return shape(context, Color.WHITE, color, 8);
    }

    public static GradientDrawable shape(Context context, int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(context, 1), stroke);
        drawable.setCornerRadius(dp(context, radius));
        return drawable;
    }

    public static TextView titleBar(Activity activity, String title) {
        LinearLayout bar = row(activity);
        bar.setPadding(dp(activity, 8), dp(activity, 5), dp(activity, 8), dp(activity, 5));
        bar.setBackgroundColor(BLUE);
        TextView back = text(activity, "‹", 34, true);
        back.setTextColor(Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(view -> activity.finish());
        TextView text = text(activity, title, 21, true);
        text.setTextColor(Color.WHITE);
        bar.addView(back, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 52)));
        bar.addView(text, weight(1));
        activity.setContentView(bar);
        return text;
    }

    public static LinearLayout appBar(Activity activity, String title) {
        LinearLayout bar = row(activity);
        bar.setPadding(dp(activity, 8), dp(activity, 5), dp(activity, 10), dp(activity, 5));
        bar.setBackgroundColor(BLUE);
        Button back = iconButton(activity, "‹");
        back.setOnClickListener(view -> activity.finish());
        TextView text = text(activity, title, 20, true);
        text.setTextColor(Color.WHITE);
        bar.addView(back, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));
        bar.addView(text, weight(1));
        return bar;
    }

    public static void start(Context context, Class<?> destination) {
        context.startActivity(new Intent(context, destination));
    }

    /** Opens Android's photo grid instead of the generic document/security browser. */
    public static Intent photoPickerIntent() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new Intent(MediaStore.ACTION_PICK_IMAGES)
                    .setType("image/*")
                    .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,
                            Math.min(50, MediaStore.getPickImagesMaxLimit()));
        }
        return new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                .setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    }

    public static void previewPhoto(Activity activity, String path) {
        activity.startActivity(new Intent(activity, PhotoPreviewActivity.class)
                .putExtra("photo_path", path));
    }

    public static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
