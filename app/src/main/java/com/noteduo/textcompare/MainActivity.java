package com.noteduo.textcompare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Lightweight, offline, native Android two-text comparison workspace.
 * No network permission or third-party runtime dependencies are required.
 */
public final class MainActivity extends Activity {
    private static final String PREFS = "two_text_compare_preferences";
    private static final String STATE_A = "text_a";
    private static final String STATE_B = "text_b";
    private static final String STATE_SIDE_BY_SIDE = "side_by_side";
    private static final String STATE_CENTERED = "centered";
    private static final String STATE_FONT_SIZE = "font_size";
    private static final String STATE_ORIENTATION = "orientation";

    private static final int COLOR_BACKGROUND = Color.rgb(244, 247, 251);
    private static final int COLOR_SURFACE = Color.WHITE;
    private static final int COLOR_TEXT = Color.rgb(31, 41, 55);
    private static final int COLOR_MUTED = Color.rgb(105, 120, 139);
    private static final int COLOR_PRIMARY = Color.rgb(15, 118, 110);
    private static final int COLOR_BORDER = Color.rgb(224, 231, 239);
    private static final int COLOR_EDITOR = Color.rgb(249, 251, 254);

    private LinearLayout root;
    private LinearLayout paneContainer;
    private LinearLayout paneA;
    private LinearLayout paneB;
    private EditText textA;
    private EditText textB;
    private TextView countA;
    private TextView countB;
    private TextView layoutButton;
    private TextView alignmentButton;

    private boolean sideBySide;
    private boolean centered;
    private float fontSizeSp = 17f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();

        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        fontSizeSp = preferences.getFloat("font_size", 17f);
        centered = preferences.getBoolean("centered", false);
        if (savedInstanceState != null) {
            int currentOrientation = getResources().getConfiguration().orientation;
            int previousOrientation = savedInstanceState.getInt(STATE_ORIENTATION, currentOrientation);
            if (previousOrientation != currentOrientation) {
                sideBySide = currentOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                preferences.edit().putBoolean("side_by_side", sideBySide).apply();
            } else {
                sideBySide = savedInstanceState.getBoolean(STATE_SIDE_BY_SIDE, false);
            }
            centered = savedInstanceState.getBoolean(STATE_CENTERED, centered);
            fontSizeSp = savedInstanceState.getFloat(STATE_FONT_SIZE, fontSizeSp);
        } else if (preferences.contains("side_by_side")) {
            sideBySide = preferences.getBoolean("side_by_side", false);
        } else {
            sideBySide = getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        }

        buildScreen();

        if (savedInstanceState != null) {
            textA.setText(savedInstanceState.getString(STATE_A, ""));
            textB.setText(savedInstanceState.getString(STATE_B, ""));
        }
        applyTextAppearance();
        updateCounts();
        updateToolbarLabels();
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.WHITE);
        window.setNavigationBarColor(Color.WHITE);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    private void buildScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);
        root.setFocusableInTouchMode(true);

        root.addView(buildHeader(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(66)));
        root.addView(buildToolbar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        paneContainer = new LinearLayout(this);
        paneContainer.setPadding(dp(12), dp(4), dp(12), dp(12));
        paneA = buildPane("A", "文本 A");
        paneB = buildPane("B", "文本 B");
        root.addView(paneContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        applyPaneLayout();
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(8), dp(18), dp(4));
        header.setBackgroundColor(COLOR_SURFACE);
        header.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        titleStack.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("双文对照");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        TextView subtitle = new TextView(this);
        subtitle.setText("两段文字 · 并排核对 · 本地处理");
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setTextSize(11.5f);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(2);

        titleStack.addView(title);
        titleStack.addView(subtitle, subtitleParams);
        header.addView(titleStack, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(this);
        badge.setText("离线工具");
        badge.setTextColor(COLOR_PRIMARY);
        badge.setTextSize(11);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(roundRect(Color.rgb(225, 246, 242), dp(18), 0, Color.TRANSPARENT));
        header.addView(badge);
        return header;
    }

    private View buildToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(dp(12), dp(6), dp(12), dp(4));
        toolbar.setBackgroundColor(COLOR_SURFACE);

        layoutButton = toolbarButton("切左右", false);
        layoutButton.setContentDescription("切换上下或左右布局");
        layoutButton.setOnClickListener(v -> {
            sideBySide = !sideBySide;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("side_by_side", sideBySide).apply();
            applyPaneLayout();
            updateToolbarLabels();
        });

        TextView swapButton = toolbarButton("交换", false);
        swapButton.setContentDescription("交换文本 A 和文本 B");
        swapButton.setOnClickListener(v -> swapTexts());

        alignmentButton = toolbarButton("居中", false);
        alignmentButton.setContentDescription("切换文本左对齐或居中");
        alignmentButton.setOnClickListener(v -> {
            centered = !centered;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("centered", centered).apply();
            applyTextAppearance();
            updateToolbarLabels();
        });

        TextView smallerButton = toolbarButton("A−", false);
        smallerButton.setContentDescription("缩小文字");
        smallerButton.setOnClickListener(v -> changeFontSize(-1f));

        TextView largerButton = toolbarButton("A+", false);
        largerButton.setContentDescription("放大文字");
        largerButton.setOnClickListener(v -> changeFontSize(1f));

        TextView clearAllButton = toolbarButton("全清", true);
        clearAllButton.setContentDescription("清空两段文字");
        clearAllButton.setOnClickListener(v -> confirmClearAll());

        TextView[] buttons = {layoutButton, swapButton, alignmentButton,
                smallerButton, largerButton, clearAllButton};
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1f);
            if (i > 0) params.leftMargin = dp(5);
            toolbar.addView(buttons[i], params);
        }
        return toolbar;
    }

    private LinearLayout buildPane(String label, String hint) {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(dp(10), dp(9), dp(10), dp(10));
        pane.setBackground(roundRect(COLOR_SURFACE, dp(16), dp(1), COLOR_BORDER));
        pane.setElevation(dp(1));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView title = new TextView(this);
        title.setText(label + "  ·  " + hint);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView count = new TextView(this);
        count.setText("0 字符");
        count.setTextColor(COLOR_MUTED);
        count.setTextSize(10.5f);
        titleRow.addView(count);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView pasteButton = toolbarButton("粘贴", true);
        pasteButton.setContentDescription("将剪贴板文字粘贴到" + hint);
        TextView clearButton = toolbarButton("清空", false);
        clearButton.setContentDescription("清空" + hint);

        EditText editor = new EditText(this);
        editor.setTextColor(COLOR_TEXT);
        editor.setHintTextColor(Color.rgb(156, 168, 183));
        editor.setHint("粘贴或输入" + hint + "……");
        editor.setTextSize(fontSizeSp);
        editor.setGravity(Gravity.TOP | (centered ? Gravity.CENTER_HORIZONTAL : Gravity.START));
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_ACTION_NONE);
        editor.setSingleLine(false);
        editor.setHorizontallyScrolling(false);
        editor.setVerticalScrollBarEnabled(true);
        editor.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        editor.setPadding(dp(12), dp(12), dp(12), dp(12));
        editor.setBackground(roundRect(COLOR_EDITOR, dp(11), dp(1), COLOR_BORDER));
        editor.setId("A".equals(label) ? 1001 : 1002);

        TextView targetCount = count;
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                targetCount.setText(formatCharacterCount(s));
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        pasteButton.setOnClickListener(v -> pasteInto(editor));
        clearButton.setOnClickListener(v -> confirmClear(editor, hint));

        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, dp(32), 1f);
        actionRow.addView(pasteButton, actionParams);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, dp(32), 1f);
        clearParams.leftMargin = dp(6);
        actionRow.addView(clearButton, clearParams);

        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(26));
        pane.addView(titleRow, titleParams);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32));
        actionsParams.topMargin = dp(3);
        pane.addView(actionRow, actionsParams);
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        editorParams.topMargin = dp(8);
        pane.addView(editor, editorParams);

        if ("A".equals(label)) {
            textA = editor;
            countA = count;
        } else {
            textB = editor;
            countB = count;
        }
        return pane;
    }

    private TextView toolbarButton(String text, boolean primary) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(11.5f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setMinWidth(0);
        button.setPadding(dp(3), dp(4), dp(3), dp(4));
        button.setTextColor(primary ? Color.WHITE : COLOR_PRIMARY);
        button.setBackground(roundRect(primary ? COLOR_PRIMARY : Color.rgb(239, 246, 249),
                dp(10), 0, Color.TRANSPARENT));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private void applyPaneLayout() {
        if (paneContainer == null || paneA == null || paneB == null) return;
        paneContainer.removeAllViews();
        paneContainer.setOrientation(sideBySide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(
                sideBySide ? 0 : LinearLayout.LayoutParams.MATCH_PARENT,
                sideBySide ? LinearLayout.LayoutParams.MATCH_PARENT : 0, 1f);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(
                sideBySide ? 0 : LinearLayout.LayoutParams.MATCH_PARENT,
                sideBySide ? LinearLayout.LayoutParams.MATCH_PARENT : 0, 1f);
        if (sideBySide) {
            first.rightMargin = dp(6);
            second.leftMargin = dp(2);
        } else {
            first.bottomMargin = dp(7);
            second.topMargin = dp(1);
        }
        paneContainer.addView(paneA, first);
        paneContainer.addView(paneB, second);
        if (layoutButton != null) {
            layoutButton.setText(sideBySide ? "上下" : "左右");
        }
    }

    private void applyTextAppearance() {
        if (textA == null || textB == null) return;
        int gravity = Gravity.TOP | (centered ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        textA.setTextSize(fontSizeSp);
        textB.setTextSize(fontSizeSp);
        textA.setGravity(gravity);
        textB.setGravity(gravity);
    }

    private void updateToolbarLabels() {
        if (layoutButton != null) layoutButton.setText(sideBySide ? "上下" : "左右");
        if (alignmentButton != null) alignmentButton.setText(centered ? "左对齐" : "居中");
    }

    private void updateCounts() {
        if (countA != null && textA != null) countA.setText(formatCharacterCount(textA.getText()));
        if (countB != null && textB != null) countB.setText(formatCharacterCount(textB.getText()));
    }

    private static String formatCharacterCount(CharSequence text) {
        int count = text == null ? 0 : Character.codePointCount(text, 0, text.length());
        return count + " 字符";
    }

    private void changeFontSize(float delta) {
        fontSizeSp = Math.max(13f, Math.min(28f, fontSizeSp + delta));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat("font_size", fontSizeSp).apply();
        applyTextAppearance();
    }

    private void swapTexts() {
        String a = textA.getText().toString();
        String b = textB.getText().toString();
        textA.setText(b);
        textB.setText(a);
        textA.setSelection(textA.length());
        textB.setSelection(textB.length());
        textA.clearFocus();
        textB.clearFocus();
        root.requestFocus();
    }

    private void pasteInto(EditText target) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence value;
        try {
            value = clip.getItemAt(0).coerceToText(this);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "无法读取剪贴板内容", Toast.LENGTH_SHORT).show();
            return;
        }
        if (value == null) {
            Toast.makeText(this, "剪贴板中没有文字", Toast.LENGTH_SHORT).show();
            return;
        }
        Editable editable = target.getText();
        int length = editable.length();
        int selectionStart = target.getSelectionStart();
        int selectionEnd = target.getSelectionEnd();
        if (selectionStart < 0 || selectionEnd < 0) {
            selectionStart = length;
            selectionEnd = length;
        }
        int start = Math.max(0, Math.min(Math.min(selectionStart, selectionEnd), length));
        int end = Math.max(start, Math.min(Math.max(selectionStart, selectionEnd), length));
        editable.replace(start, end, value.toString());
        int cursor = Math.min(start + value.length(), editable.length());
        target.setSelection(cursor);
        target.requestFocus();
    }

    private void confirmClear(EditText target, String label) {
        if (target.length() == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("清空" + label + "？")
                .setMessage("只会清除这一栏的文字。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> target.setText(""))
                .show();
    }

    private void confirmClearAll() {
        if (textA.length() == 0 && textB.length() == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("清空全部文字？")
                .setMessage("文本 A 和文本 B 都会被清除，此操作无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("全部清空", (dialog, which) -> {
                    textA.setText("");
                    textB.setText("");
                    textA.clearFocus();
                    textB.clearFocus();
                    root.requestFocus();
                    hideKeyboard();
                })
                .show();
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private GradientDrawable roundRect(int fill, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_A, textA == null ? "" : textA.getText().toString());
        outState.putString(STATE_B, textB == null ? "" : textB.getText().toString());
        outState.putBoolean(STATE_SIDE_BY_SIDE, sideBySide);
        outState.putBoolean(STATE_CENTERED, centered);
        outState.putFloat(STATE_FONT_SIZE, fontSizeSp);
        outState.putInt(STATE_ORIENTATION, getResources().getConfiguration().orientation);
        super.onSaveInstanceState(outState);
    }
}
