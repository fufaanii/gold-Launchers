package cz.zlatylauncher;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Zlato-černá česká klávesnice (QWERTZ) s bílým textem.
 * Dlouhé podržení písmene zobrazí háčky a čárky v horní řadě (místo číslic).
 */
public class GoldIME extends InputMethodService {

    private static final int GOLD = 0xFFD4AF37;
    private static final int GOLD_LIGHT = 0xFFF3D98B;
    private static final int GOLD_DARK = 0xFF8A6D1D;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final Locale CS = new Locale("cs", "CZ");

    private LinearLayout outer;
    private LinearLayout keys;
    private LinearLayout digitsRow;
    private LinearLayout variantsRow;
    private EditorInfo ei;
    private boolean shift;
    private boolean caps;
    private boolean symbols;
    private boolean page2;
    private boolean variantsOpen;
    private long lastShiftTap;
    private float density;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable repeatDel = new Runnable() {
        @Override
        public void run() {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
            h.postDelayed(this, 55);
        }
    };

    // ------------------------------------------------------------------ životní cyklus

    @Override
    public View onCreateInputView() {
        density = getResources().getDisplayMetrics().density;
        outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF000000, 0xFF150E00}));

        View line = new View(this);
        line.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{GOLD_DARK, GOLD_LIGHT, GOLD_DARK}));
        outer.addView(line, new LinearLayout.LayoutParams(MATCH, dp(2)));

        keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.VERTICAL);
        keys.setPadding(dp(3), dp(2), dp(3), dp(8));
        outer.addView(keys, new LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT));

        build();
        return outer;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        ei = info;
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        symbols = cls == InputType.TYPE_CLASS_NUMBER
                || cls == InputType.TYPE_CLASS_PHONE
                || cls == InputType.TYPE_CLASS_DATETIME;
        page2 = false;
        caps = false;
        InputConnection ic = getCurrentInputConnection();
        shift = ic != null && ic.getCursorCapsMode(info.inputType) != 0;
        if (keys != null) build();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        h.removeCallbacks(repeatDel);
        super.onFinishInputView(finishingInput);
    }

    // ------------------------------------------------------------------ stavba klávesnice

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(46));
        lp.topMargin = dp(4);
        return lp;
    }

    private LinearLayout.LayoutParams kp(float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, MATCH, weight);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private Drawable keyBg(int style) {
        int fill;
        int stroke;
        switch (style) {
            case 0:
                fill = 0xFF1A1A1A;
                stroke = 0xFF5E4A14;
                break;
            case 1:
                fill = 0xFF6B5416;
                stroke = GOLD_DARK;
                break;
            case 2:
                fill = 0xFFB8922A;
                stroke = GOLD_LIGHT;
                break;
            default:
                fill = 0xFF121212;
                stroke = GOLD_DARK;
                break;
        }
        GradientDrawable n = new GradientDrawable();
        n.setColor(fill);
        n.setCornerRadius(dp(9));
        n.setStroke(dp(1), stroke);
        GradientDrawable p = new GradientDrawable();
        p.setColor(0xFF9A7B1C);
        p.setCornerRadius(dp(9));
        p.setStroke(dp(1), GOLD_LIGHT);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, p);
        s.addState(new int[]{}, n);
        return s;
    }

    /** style: 0 písmeno, 1 speciální (tmavé zlato), 2 zvýrazněná (zlato), 3 mezerník */
    private TextView mk(String text, int style) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(style == 0 ? 21 : 16);
        t.setTypeface(Typeface.DEFAULT, style == 0 ? Typeface.NORMAL : Typeface.BOLD);
        t.setIncludeFontPadding(false);
        t.setBackground(keyBg(style));
        t.setHapticFeedbackEnabled(true);
        return t;
    }

    private void build() {
        keys.removeAllViews();
        variantsOpen = false;

        // horní řada: číslice (a po dlouhém stisku varianty písmen)
        FrameLayout top = new FrameLayout(this);
        digitsRow = row();
        for (String d : new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"}) {
            digitsRow.addView(charKey(d, 1f), kp(1f));
        }
        variantsRow = row();
        variantsRow.setVisibility(View.GONE);
        top.addView(digitsRow, new FrameLayout.LayoutParams(MATCH, MATCH));
        top.addView(variantsRow, new FrameLayout.LayoutParams(MATCH, MATCH));
        keys.addView(top, rowLp());

        if (!symbols) {
            keys.addView(letterRow(new String[]{"q", "w", "e", "r", "t", "z", "u", "i", "o", "p"}, false), rowLp());
            keys.addView(letterRow(new String[]{"a", "s", "d", "f", "g", "h", "j", "k", "l"}, true), rowLp());

            LinearLayout r3 = row();
            TextView sh = mk(caps ? "⇪" : "⇧", (shift || caps) ? 2 : 1);
            sh.setOnClickListener(v -> {
                hideVariants();
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                long now = System.currentTimeMillis();
                if (now - lastShiftTap < 350) {
                    caps = true;
                    shift = true;
                } else if (caps) {
                    caps = false;
                    shift = false;
                } else {
                    shift = !shift;
                }
                lastShiftTap = now;
                build();
            });
            r3.addView(sh, kp(1.5f));
            for (String k : new String[]{"y", "x", "c", "v", "b", "n", "m"}) {
                r3.addView(charKey(k, 1f), kp(1f));
            }
            r3.addView(backspaceKey(), kp(1.5f));
            keys.addView(r3, rowLp());
        } else {
            String[] s1 = page2
                    ? new String[]{"[", "]", "{", "}", "<", ">", "^", "~", "|", "\\"}
                    : new String[]{"@", "#", "€", "_", "&", "-", "+", "(", ")", "/"};
            String[] s2 = page2
                    ? new String[]{"$", "£", "¥", "§", "°", "%", "="}
                    : new String[]{"*", "\"", "'", ":", ";", "!", "?"};
            LinearLayout r2 = row();
            for (String k : s1) r2.addView(charKey(k, 1f), kp(1f));
            keys.addView(r2, rowLp());

            LinearLayout r3 = row();
            TextView pg = mk(page2 ? "1/2" : "2/2", 1);
            pg.setOnClickListener(v -> {
                hideVariants();
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                page2 = !page2;
                build();
            });
            r3.addView(pg, kp(1.5f));
            for (String k : s2) r3.addView(charKey(k, 1f), kp(1f));
            r3.addView(backspaceKey(), kp(1.5f));
            keys.addView(r3, rowLp());
        }

        // spodní řada
        LinearLayout r4 = row();
        TextView mode = mk(symbols ? "ABC" : "?123", 1);
        mode.setOnClickListener(v -> {
            hideVariants();
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            symbols = !symbols;
            page2 = false;
            build();
        });
        r4.addView(mode, kp(1.5f));
        r4.addView(charKey(",", 1f), kp(1f));

        TextView space = mk("✦  Zlatá  ✦", 3);
        space.setTextSize(12);
        space.setOnClickListener(v -> {
            hideVariants();
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            commit(" ");
            afterCommit();
        });
        r4.addView(space, kp(5f));
        r4.addView(charKey(".", 1f), kp(1f));

        TextView enter = mk(enterLabel(), 2);
        enter.setOnClickListener(v -> {
            hideVariants();
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            doEnter();
            afterCommit();
        });
        r4.addView(enter, kp(1.5f));
        keys.addView(r4, rowLp());
    }

    private LinearLayout letterRow(String[] letters, boolean pad) {
        LinearLayout r = row();
        if (pad) r.addView(new View(this), new LinearLayout.LayoutParams(0, MATCH, 0.5f));
        for (String k : letters) r.addView(charKey(k, 1f), kp(1f));
        if (pad) r.addView(new View(this), new LinearLayout.LayoutParams(0, MATCH, 0.5f));
        return r;
    }

    private boolean upper() {
        return shift || caps;
    }

    private String cased(String s) {
        if (!upper() || s.equals("ß")) return s;
        return s.toUpperCase(CS);
    }

    private TextView charKey(final String raw, float weight) {
        final String label = cased(raw);
        TextView t = mk(label, 0);
        t.setOnClickListener(v -> {
            hideVariants();
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            commit(label);
            afterCommit();
        });
        final String[] vs = variantsOf(raw);
        if (vs != null) {
            t.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                showVariants(vs);
                return true;
            });
        }
        return t;
    }

    private TextView backspaceKey() {
        TextView t = mk("⌫", 1);
        t.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    hideVariants();
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                    h.postDelayed(repeatDel, 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    h.removeCallbacks(repeatDel);
                    afterCommit();
                    return true;
                default:
                    return true;
            }
        });
        return t;
    }

    // ------------------------------------------------------------------ varianty (háčky, čárky)

    private void showVariants(String[] vs) {
        variantsRow.removeAllViews();
        float used = 0;
        for (String v : vs) {
            final String val = cased(v);
            TextView t = mk(val, 2);
            t.setTextSize(21);
            t.setOnClickListener(x -> {
                x.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                commit(val);
                hideVariants();
                afterCommit();
            });
            variantsRow.addView(t, kp(1.2f));
            used += 1.2f;
        }
        variantsRow.addView(new View(this), new LinearLayout.LayoutParams(0, MATCH, Math.max(0.1f, 10f - used)));
        digitsRow.setVisibility(View.GONE);
        variantsRow.setVisibility(View.VISIBLE);
        variantsOpen = true;
    }

    private void hideVariants() {
        if (!variantsOpen) return;
        variantsRow.setVisibility(View.GONE);
        digitsRow.setVisibility(View.VISIBLE);
        variantsOpen = false;
    }

    private static String[] variantsOf(String k) {
        switch (k) {
            case "a":
                return new String[]{"á", "ä", "à", "â"};
            case "c":
                return new String[]{"č", "ć", "ç"};
            case "d":
                return new String[]{"ď"};
            case "e":
                return new String[]{"é", "ě", "ë", "è"};
            case "i":
                return new String[]{"í", "î", "ï"};
            case "l":
                return new String[]{"ľ", "ĺ", "ł"};
            case "n":
                return new String[]{"ň", "ń", "ñ"};
            case "o":
                return new String[]{"ó", "ö", "ô", "ő"};
            case "r":
                return new String[]{"ř", "ŕ"};
            case "s":
                return new String[]{"š", "ś", "ß"};
            case "t":
                return new String[]{"ť"};
            case "u":
                return new String[]{"ú", "ů", "ü", "ű"};
            case "y":
                return new String[]{"ý", "ÿ"};
            case "z":
                return new String[]{"ž", "ź", "ż"};
            case ".":
                return new String[]{"?", "!", ":", ";", "…"};
            case ",":
                return new String[]{"\"", "'", "-", "_"};
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ vstup

    private void commit(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
    }

    /** Po vložení znaku: automatická velká písmena na začátku věty. */
    private void afterCommit() {
        if (caps) return;
        InputConnection ic = getCurrentInputConnection();
        boolean old = shift;
        shift = ic != null && ei != null && ic.getCursorCapsMode(ei.inputType) != 0;
        if (old != shift) build();
    }

    private String enterLabel() {
        if (ei == null) return "⏎";
        boolean multi = (ei.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        if (multi || (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "⏎";
        switch (ei.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_SEARCH:
                return "🔍";
            case EditorInfo.IME_ACTION_SEND:
                return "➤";
            case EditorInfo.IME_ACTION_GO:
                return "➜";
            case EditorInfo.IME_ACTION_NEXT:
                return "→";
            case EditorInfo.IME_ACTION_DONE:
                return "✓";
            default:
                return "⏎";
        }
    }

    private void doEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        int a = ei == null ? 0 : (ei.imeOptions & EditorInfo.IME_MASK_ACTION);
        boolean multi = ei != null && (ei.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        boolean noAction = ei != null && (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (multi || noAction || a == EditorInfo.IME_ACTION_NONE || a == EditorInfo.IME_ACTION_UNSPECIFIED) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        } else {
            ic.performEditorAction(a);
        }
    }
}
