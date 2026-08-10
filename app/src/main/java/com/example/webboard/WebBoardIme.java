package com.example.webboard;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/**
 * WebBoard: QWERTZ keyboard with an integrated browser.
 *
 * By default only the keyboard rows are shown (compact, normal keyboard
 * height). Tapping the globe key toggles a real fullscreen browser: the
 * IME's own window is resized to fill the screen (no second Activity/app is
 * ever started), the URL bar and WebView appear, and the key rows stay
 * visible at the bottom so the URL/search field can still be typed into
 * with WebBoard's own keys. Tapping the globe key again (or the small
 * keyboard-icon button in the browser bar) closes the browser and shrinks
 * the window back down to a normal keyboard.
 *
 * The window is explicitly resized on every toggle and reset every time the
 * keyboard is shown/hidden, so the browser reliably reappears at full size
 * every time -- not just the first time.
 *
 * Visual appearance (colors, corner radius, spacing, key size, font size,
 * press effect) is fully configurable through {@link SettingsActivity} and
 * stored via {@link KeyboardTheme}. Changes are picked up live through a
 * SharedPreferences listener and are always re-applied when the keyboard
 * is shown.
 */
public class WebBoardIme extends InputMethodService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    /** Base height (dp) of the key rows area at the default size scale (1.0). */
    private static final int BASE_KEYS_HEIGHT_DP = 238;

    private LinearLayout root, keys;
    private View browserBar;
    private WebView web;
    private EditText url;
    private Button goButton, backButton, forwardButton, closeBrowserButton;
    private ImageButton settingsButton;
    private boolean shift = false;
    private boolean symbols = false;
    private boolean browserVisible = false;

    private KeyboardTheme theme;

    @Override public void onCreate() {
        super.onCreate();
        Window w = getWindow().getWindow();
        if (w != null) {
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        theme = KeyboardTheme.load(this);
        KeyboardTheme.prefs(this).registerOnSharedPreferenceChangeListener(this);
    }

    @Override public void onDestroy() {
        KeyboardTheme.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }

    /** Live-preview hook: settings changes are applied immediately while the keyboard is visible. */
    @Override public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        theme = KeyboardTheme.load(this);
        applyTheme();
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        // Always start compact; the browser is opened explicitly via the globe key.
        // This also guarantees the window is correctly sized every single time the
        // keyboard is shown, instead of relying on whatever size it happened to be
        // left at, which is what previously made the browser "disappear" on reopen.
        setBrowserFullscreen(false);
        // Always reload in case the theme changed while the keyboard was hidden.
        theme = KeyboardTheme.load(this);
        applyTheme();
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        // Collapse back to a normal-sized keyboard as soon as it's hidden so the
        // enlarged window is never left behind.
        setBrowserFullscreen(false);
        super.onFinishInputView(finishingInput);
    }

    @Override public View onCreateInputView() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(4), dp(4), dp(4), dp(2));

        browserBar = buildBrowserBar();
        root.addView(browserBar, new LinearLayout.LayoutParams(-1, dp(46)));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        web.setWebViewClient(new WebViewClient());
        web.setBackgroundColor(Color.WHITE);
        web.loadUrl("https://www.google.com/");

        // When the browser is toggled on, it gets all remaining space between the
        // bar and the keys -- the window itself is resized to fill the screen at
        // that point, so this is a genuine fullscreen browser, not just the space
        // left over inside a small keyboard.
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Compact by default: browser bar and WebView start hidden.
        browserBar.setVisibility(View.GONE);
        web.setVisibility(View.GONE);

        keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.VERTICAL);
        root.addView(keys, new LinearLayout.LayoutParams(-1, keysHeightPx()));

        applyTheme();
        return root;
    }

    private int keysHeightPx() {
        return dp(Math.round(BASE_KEYS_HEIGHT_DP * theme.sizeScale));
    }

    /**
     * Shows or hides the fullscreen browser. This is the fix for the browser
     * "disappearing" on reopen: rather than always embedding a WebView inside
     * whatever height the IME window happened to get (undefined once a
     * weight=1 child sits in a wrap-content window), the window is explicitly
     * resized every time this is called, so the result is deterministic and
     * repeatable. No separate Activity/app is ever launched -- this simply
     * grows WebBoard's own IME window.
     */
    private void setBrowserFullscreen(boolean fullscreen) {
        browserVisible = fullscreen;

        if (browserBar != null) browserBar.setVisibility(fullscreen ? View.VISIBLE : View.GONE);
        if (web != null) web.setVisibility(fullscreen ? View.VISIBLE : View.GONE);
        if (closeBrowserButton != null) closeBrowserButton.setVisibility(fullscreen ? View.VISIBLE : View.GONE);

        if (root != null) {
            ViewGroup.LayoutParams rp = root.getLayoutParams();
            int height = fullscreen ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (rp == null) {
                root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
            } else {
                rp.height = height;
                root.setLayoutParams(rp);
            }
        }

        Window w = getWindow().getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    fullscreen ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        if (keys != null) buildKeys();
    }

    private void toggleBrowserFullscreen() {
        setBrowserFullscreen(!browserVisible);
        if (browserVisible && url != null) {
            url.requestFocus();
            url.setSelection(url.length());
        }
    }

    /** Re-applies the current theme to every part of the UI without rebuilding the URL field. */
    private void applyTheme() {
        if (root == null) return;
        root.setBackgroundColor(KeyboardTheme.withAlpha(theme.backgroundColor, theme.backgroundAlpha));

        styleUrlField();
        styleSmallButton(goButton);
        styleSmallButton(backButton);
        styleSmallButton(forwardButton);
        styleSmallButton(closeBrowserButton);
        styleSettingsButton();

        if (keys != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) keys.getLayoutParams();
            if (lp != null) {
                lp.height = keysHeightPx();
                keys.setLayoutParams(lp);
            }
            buildKeys();
        }
    }

    private View buildBrowserBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(2), dp(2), dp(2), dp(2));
        bar.setGravity(Gravity.CENTER_VERTICAL);

        url = new EditText(this);
        url.setSingleLine(true);
        url.setText("https://www.google.com/");
        url.setTextSize(14);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setShowSoftInputOnFocus(false);
        url.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                url.setSelection(url.length());
            }
        });
        url.setOnEditorActionListener((v, actionId, event) -> { navigate(); return true; });
        url.setPadding(dp(14), 0, dp(10), 0);
        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        urlParams.setMarginEnd(dp(4));
        bar.addView(url, urlParams);

        goButton = smallButton("GO");
        goButton.setOnClickListener(v -> navigate());
        bar.addView(goButton, barButtonParams(dp(46)));

        backButton = smallButton("‹");
        backButton.setOnClickListener(v -> { if (web != null && web.canGoBack()) web.goBack(); });
        bar.addView(backButton, barButtonParams(dp(38)));

        forwardButton = smallButton("›");
        forwardButton.setOnClickListener(v -> { if (web != null && web.canGoForward()) web.goForward(); });
        bar.addView(forwardButton, barButtonParams(dp(38)));

        closeBrowserButton = smallButton("⌨");
        closeBrowserButton.setContentDescription(getString(R.string.btn_close_browser));
        closeBrowserButton.setOnClickListener(v -> setBrowserFullscreen(false));
        closeBrowserButton.setVisibility(View.GONE);
        bar.addView(closeBrowserButton, barButtonParams(dp(38)));

        settingsButton = new ImageButton(this);
        settingsButton.setImageResource(R.drawable.ic_settings);
        settingsButton.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        settingsButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        settingsButton.setContentDescription(getString(R.string.ime_settings));
        settingsButton.setOnClickListener(v -> openSettings());
        bar.addView(settingsButton, barButtonParams(dp(38)));

        return bar;
    }

    private LinearLayout.LayoutParams barButtonParams(int widthPx) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(widthPx, dp(38));
        p.setMarginStart(dp(4));
        return p;
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void styleUrlField() {
        if (url == null) return;
        // Pill-shaped search-bar look, matching the rounded, high-contrast
        // style of the reference keyboard.
        url.setBackground(KeyboardTheme.roundedRect(this, Color.WHITE, 19f));
        url.setTextColor(theme.textColor);
    }

    private void styleSmallButton(Button b) {
        if (b == null) return;
        // Small round icon buttons instead of plain rectangles.
        b.setBackground(KeyboardTheme.keyBackground(this, theme.specialKeyColor, 19f));
        b.setTextColor(theme.textColor);
        attachPressAnimation(b);
    }

    private void styleSettingsButton() {
        if (settingsButton == null) return;
        settingsButton.setBackground(KeyboardTheme.keyBackground(this, theme.specialKeyColor, 19f));
        settingsButton.setImageTintList(android.content.res.ColorStateList.valueOf(theme.textColor));
        attachPressAnimation(settingsButton);
    }

    private void navigate() {
        if (url == null || web == null) return;
        String q = url.getText().toString().trim();
        if (q.isEmpty()) return;
        if (!q.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            q = "https://www.google.com/search?q=" + android.net.Uri.encode(q);
        }
        web.loadUrl(q);
        url.clearFocus();
    }

    private void buildKeys() {
        keys.removeAllViews();
        if (symbols) {
            addRow("1234567890", null);
            addRow("@#$%&*+-=/", null);
            LinearLayout r = row();
            addKey(r, "ABC", 1.25f, v -> { symbols = false; buildKeys(); }, KeyboardTheme.KeyKind.SPECIAL);
            addKey(r, "()[]{}", 2.0f, v -> type("()[]{}"), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "!?;:'", 2.0f, v -> type("!?;:'"), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "⌫", 1.25f, v -> delete(), KeyboardTheme.KeyKind.BACKSPACE);
            keys.addView(r, new LinearLayout.LayoutParams(-1, 0, 1f));
        } else {
            // Top row shows small number hints, like the reference keyboard.
            addRow("qwertzuiopü", "1234567890");
            LinearLayout row2 = addRow("asdfghjklöä", null);
            int inset = dp(14);
            row2.setPadding(inset, 0, inset, 0);

            LinearLayout r = row();
            addKey(r, "⇧", 1.35f, v -> { shift = !shift; buildKeys(); }, KeyboardTheme.KeyKind.SPECIAL);
            addKey(r, "y", 1f, v -> type("y"), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "x", 1f, v -> type("x"), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "c", 1f, v -> type("c"), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "v", 1f, v -> type("v"), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "b", 1f, v -> type("b"), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "n", 1f, v -> type("n"), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "m", 1f, v -> type("m"), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "⌫", 1.35f, v -> delete(), KeyboardTheme.KeyKind.BACKSPACE);
            keys.addView(r, new LinearLayout.LayoutParams(-1, 0, 1f));
        }

        LinearLayout bottom = row();
        addKey(bottom, symbols ? "ABC" : "?123", 1.25f, v -> { symbols = !symbols; shift = false; buildKeys(); }, KeyboardTheme.KeyKind.SPECIAL);
        addKey(bottom, "🌐", 1f, v -> toggleBrowserFullscreen(), KeyboardTheme.KeyKind.SPECIAL);
        addKey(bottom, ",", 1f, v -> type(","), KeyboardTheme.KeyKind.NORMAL);
        addKey(bottom, "Leertaste", 4.2f, v -> type(" "), KeyboardTheme.KeyKind.NORMAL);
        addKey(bottom, ".", 1f, v -> type("."), KeyboardTheme.KeyKind.NORMAL);
        addKey(bottom, "↵", 1.25f, v -> enter(), KeyboardTheme.KeyKind.ENTER);
        keys.addView(bottom, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    /** Adds a row of single-character keys. hints (if given) supplies the small
     *  superscript number shown in the corner of each key, one digit per
     *  character; pass null for no hints, or a shorter string to only hint
     *  the first N keys. Returns the row so callers can tweak it further
     *  (e.g. indent it). */
    private LinearLayout addRow(String chars, String hints) {
        LinearLayout r = row();
        for (int i = 0; i < chars.length(); i++) {
            String c = String.valueOf(chars.charAt(i));
            String hint = (hints != null && i < hints.length()) ? String.valueOf(hints.charAt(i)) : null;
            addKey(r, c, hint, 1f, v -> type(c), KeyboardTheme.KeyKind.NORMAL);
        }
        keys.addView(r, new LinearLayout.LayoutParams(-1, 0, 1f));
        return r;
    }

    private void addKey(LinearLayout row, String label, float weight, View.OnClickListener listener, KeyboardTheme.KeyKind kind) {
        addKey(row, label, null, weight, listener, kind);
    }

    private void addKey(LinearLayout row, String label, String hint, float weight, View.OnClickListener listener, KeyboardTheme.KeyKind kind) {
        View cell;
        if (hint != null && !hint.isEmpty()) {
            FrameLayout container = new FrameLayout(this);
            Button b = makeKeyButton(label, kind, listener);
            container.addView(b, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

            TextView hintView = new TextView(this);
            hintView.setText(hint);
            hintView.setTextSize(Math.max(theme.fontSizeSp * 0.45f, 8f));
            hintView.setTextColor(KeyboardTheme.withAlpha(theme.textColor, 140));
            hintView.setClickable(false);
            hintView.setFocusable(false);
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            hp.gravity = Gravity.TOP | Gravity.END;
            hp.topMargin = dp(1);
            hp.rightMargin = dp(5);
            container.addView(hintView, hp);
            cell = container;
        } else {
            cell = makeKeyButton(label, kind, listener);
        }

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, weight);
        int spacing = dp(Math.round(theme.spacingDp));
        p.setMargins(spacing, spacing, spacing, spacing);
        row.addView(cell, p);
    }

    /** True while the key with this (functional, lowercase) label is toggled "on",
     *  so it can be drawn with the accent color -- e.g. Shift while active, or the
     *  globe key while the fullscreen browser is open. */
    private boolean isKeyActive(String label) {
        if ("⇧".equals(label)) return shift;
        if ("🌐".equals(label)) return browserVisible;
        return false;
    }

    private Button makeKeyButton(String label, KeyboardTheme.KeyKind kind, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(displayLabel(label));
        b.setTextSize(label.equals("Leertaste") ? theme.fontSizeSp * 0.7f : theme.fontSizeSp);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);

        boolean active = kind == KeyboardTheme.KeyKind.SPECIAL && isKeyActive(label);
        int baseColor = active ? theme.enterKeyColor : theme.colorForKind(kind);
        // Enter gets a large, pill-like radius so it stands out as the accent
        // button, the way it does on the reference keyboard; every other key
        // uses the normal (user-configurable) corner radius.
        float radius = kind == KeyboardTheme.KeyKind.ENTER
                ? Math.max(theme.cornerRadiusDp * 2.2f, 22f)
                : theme.cornerRadiusDp;
        b.setBackground(KeyboardTheme.keyBackground(this, baseColor, radius));
        boolean lightOnDark = kind == KeyboardTheme.KeyKind.ENTER || active;
        b.setTextColor(lightOnDark ? KeyboardTheme.contrastText(baseColor) : theme.textColor);

        b.setOnClickListener(listener);
        attachPressAnimation(b);
        return b;
    }

    /** Letter keycaps are always shown as capitals (like the reference keyboard),
     *  independent of the actual Shift state -- Shift only changes what gets typed. */
    private String displayLabel(String label) {
        if (label.length() == 1 && Character.isLetter(label.charAt(0))) {
            return label.toUpperCase(Locale.GERMANY);
        }
        return label;
    }

    /** Adds a quick, subtle scale-down effect on press for a more modern, responsive feel. */
    private void attachPressAnimation(View v) {
        v.setOnTouchListener((view, event) -> {
            if (!theme.pressEffectEnabled) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(60).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                    break;
            }
            return false;
        });
    }

    /** Sends text either to the browser's address/search field or to the app using the IME. */
    private void type(String text) {
        if (url != null && url.hasFocus()) {
            int start = Math.max(0, url.getSelectionStart());
            int end = Math.max(0, url.getSelectionEnd());
            if (shift) {
                text = text.toUpperCase(Locale.GERMANY);
                shift = false;
                buildKeys();
            }
            url.getText().replace(Math.min(start, end), Math.max(start, end), text);
            url.setSelection(Math.min(start, end) + text.length());
            return;
        }
        if (shift) {
            text = text.toUpperCase(Locale.GERMANY);
            shift = false;
            buildKeys();
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }

    private void delete() {
        if (url != null && url.hasFocus()) {
            int start = url.getSelectionStart();
            int end = url.getSelectionEnd();
            if (start != end) {
                url.getText().delete(Math.min(start, end), Math.max(start, end));
            } else if (start > 0) {
                url.getText().delete(start - 1, start);
            }
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.deleteSurroundingText(1, 0);
    }

    private void enter() {
        if (url != null && url.hasFocus()) {
            navigate();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
    }

    private int dp(int x) {
        return (int) (x * getResources().getDisplayMetrics().density + 0.5f);
    }
}
