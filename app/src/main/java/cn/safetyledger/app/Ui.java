package cn.safetyledger.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

public final class Ui {
    public static final int TEAL = Color.rgb(14,116,144), DARK = Color.rgb(21,94,117), BG = Color.rgb(248,250,252);
    private Ui() {}
    public static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
    public static TextView text(Context c, String s, int sp, boolean bold) {
        TextView v = new TextView(c); v.setText(s); v.setTextSize(sp); v.setTextColor(Color.rgb(15,23,42));
        v.setGravity(Gravity.CENTER_VERTICAL); v.setPadding(dp(c,10),dp(c,8),dp(c,10),dp(c,8));
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v;
    }
    public static Button button(Context c, String s) {
        Button b = new Button(c); b.setText(s); b.setTextSize(15); b.setTextColor(Color.WHITE); b.setAllCaps(false);
        GradientDrawable g = new GradientDrawable(); g.setColor(TEAL); g.setCornerRadius(dp(c,10)); b.setBackground(g);
        b.setMinHeight(dp(c,48)); return b;
    }
    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c); e.setHint(hint); e.setTextSize(16); e.setSingleLine(false);
        e.setPadding(dp(c,12),dp(c,9),dp(c,12),dp(c,9)); return e;
    }
    public static LinearLayout row(Context c) { LinearLayout r=new LinearLayout(c); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); return r; }
    public static LinearLayout column(Context c) { LinearLayout r=new LinearLayout(c); r.setOrientation(LinearLayout.VERTICAL); return r; }
    public static LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,w); }
    public static View gap(Context c,int h){ Space s=new Space(c);s.setLayoutParams(new ViewGroup.LayoutParams(1,dp(c,h)));return s; }
    public static TextView titleBar(Activity a, String title) {
        LinearLayout bar=row(a); bar.setPadding(dp(a,8),dp(a,6),dp(a,8),dp(a,6)); bar.setBackgroundColor(DARK);
        TextView back=text(a,"‹",34,true); back.setTextColor(Color.WHITE); back.setGravity(Gravity.CENTER); back.setOnClickListener(v->a.finish());
        TextView t=text(a,title,21,true); t.setTextColor(Color.WHITE); bar.addView(back,new LinearLayout.LayoutParams(dp(a,52),dp(a,52)));bar.addView(t,weight(1));
        a.setContentView(bar); return t;
    }
    public static void start(Context c, Class<?> clz){ c.startActivity(new Intent(c,clz)); }
    public static void toast(Context c,String s){Toast.makeText(c,s,Toast.LENGTH_LONG).show();}
    public static GradientDrawable border(Context c,int color){GradientDrawable g=new GradientDrawable();g.setColor(Color.WHITE);g.setStroke(dp(c,1),color);g.setCornerRadius(dp(c,8));return g;}
}
