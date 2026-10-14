package com.noteduo.textcompare;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Offline, native Android two-text viewer/editor with background diff highlighting. */
public final class MainActivity extends Activity {
    private static final String PREFS = "two_text_compare_preferences";
    private static final String STATE_A = "text_a";
    private static final String STATE_B = "text_b";
    private static final String STATE_SIDE_BY_SIDE = "side_by_side";
    private static final String STATE_CENTERED = "centered";
    private static final String STATE_FONT_SIZE = "font_size";
    private static final String STATE_ORIENTATION = "orientation";
    private static final String STATE_AUTO_COMPARE = "auto_compare";
    private static final String STATE_SCROLL_A = "scroll_a";
    private static final String STATE_SCROLL_B = "scroll_b";
    private static final String STATE_EDITING_SIDE = "editing_side";
    private static final String STATE_EDIT_DRAFT = "edit_draft";

    private static final int COLOR_BACKGROUND = Color.rgb(244, 247, 251);
    private static final int COLOR_SURFACE = Color.WHITE;
    private static final int COLOR_TEXT = Color.rgb(31, 41, 55);
    private static final int COLOR_MUTED = Color.rgb(105, 120, 139);
    private static final int COLOR_PRIMARY = Color.rgb(15, 118, 110);
    private static final int COLOR_BORDER = Color.rgb(224, 231, 239);
    private static final int COLOR_EDITOR = Color.rgb(249, 251, 254);
    private static final int COLOR_DIFF_A = Color.rgb(255, 218, 214);
    private static final int COLOR_DIFF_B = Color.rgb(207, 232, 255);
    private static final int COLOR_DIFF_A_TEXT = Color.rgb(126, 36, 31);
    private static final int COLOR_DIFF_B_TEXT = Color.rgb(25, 73, 120);

    // LCS is bounded so pathological input cannot allocate an unbounded matrix.
    private static final long LINE_LCS_CELL_LIMIT = 1_500_000L;
    private static final long CHAR_LCS_CELL_LIMIT = 220_000L;

    private LinearLayout root;
    private LinearLayout paneContainer;
    private LinearLayout paneA;
    private LinearLayout paneB;
    private TextView textA;
    private TextView textB;
    private ScrollView scrollA;
    private ScrollView scrollB;
    private TextView countA;
    private TextView countB;
    private TextView layoutButton;
    private TextView alignmentButton;
    private TextView autoButton;
    private TextView statusLabel;
    private TextView prevButton;
    private TextView nextButton;

    private boolean sideBySide;
    private boolean centered;
    private boolean autoCompare = true;
    private float fontSizeSp = 17f;
    private String contentA = "";
    private String contentB = "";
    private long contentRevision;
    private long compareGeneration;
    private int activeDifference = -1;
    private List<Range> diffA = new ArrayList<>();
    private List<Range> diffB = new ArrayList<>();
    private int diffCount;
    private boolean resultCurrent;
    private boolean suppressCompare;
    private Dialog editorDialog;
    private EditText editorInput;
    private int editingSide = -1;
    private ExecutorService compareExecutor;
    private Future<?> compareFuture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureSystemBars();
        compareExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "text-compare-worker");
            thread.setDaemon(true);
            return thread;
        });

        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        fontSizeSp = preferences.getFloat("font_size", 17f);
        centered = preferences.getBoolean("centered", false);
        autoCompare = preferences.getBoolean("auto_compare", true);
        if (savedInstanceState != null) {
            contentA = savedInstanceState.getString(STATE_A, "");
            contentB = savedInstanceState.getString(STATE_B, "");
            autoCompare = savedInstanceState.getBoolean(STATE_AUTO_COMPARE, autoCompare);
            int orientation = getResources().getConfiguration().orientation;
            int oldOrientation = savedInstanceState.getInt(STATE_ORIENTATION, orientation);
            if (orientation != oldOrientation) {
                sideBySide = orientation == Configuration.ORIENTATION_LANDSCAPE;
            } else {
                sideBySide = savedInstanceState.getBoolean(STATE_SIDE_BY_SIDE, false);
            }
            centered = savedInstanceState.getBoolean(STATE_CENTERED, centered);
            fontSizeSp = savedInstanceState.getFloat(STATE_FONT_SIZE, fontSizeSp);
        } else {
            sideBySide = preferences.contains("side_by_side")
                    ? preferences.getBoolean("side_by_side", false)
                    : getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        }

        buildScreen();
        renderPlainText();
        updateCounts();
        applyTextAppearance();
        updateToolbarLabels();
        updateComparisonStatus("等待文本");

        if (savedInstanceState != null) {
            int restoreA = savedInstanceState.getInt(STATE_SCROLL_A, 0);
            int restoreB = savedInstanceState.getInt(STATE_SCROLL_B, 0);
            scrollA.post(() -> scrollA.scrollTo(0, restoreA));
            scrollB.post(() -> scrollB.scrollTo(0, restoreB));
            int restoreSide = savedInstanceState.getInt(STATE_EDITING_SIDE, -1);
            if (restoreSide >= 0) {
                String draft = savedInstanceState.getString(STATE_EDIT_DRAFT,
                        restoreSide == 0 ? contentA : contentB);
                root.post(() -> openEditor(restoreSide, draft));
            } else if (autoCompare && (!contentA.isEmpty() || !contentB.isEmpty())) {
                requestComparison();
            } else if (!contentA.isEmpty() || !contentB.isEmpty()) {
                updateComparisonStatus("文本已变化，点击比较更新");
            }
        }
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
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));
        root.addView(buildMainToolbar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        root.addView(buildCompareToolbar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        paneContainer = new LinearLayout(this);
        paneContainer.setPadding(dp(10), dp(4), dp(10), dp(10));
        paneA = buildPane(0, "文本 A");
        paneB = buildPane(1, "文本 B");
        root.addView(paneContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        applyPaneLayout();
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(6), dp(16), dp(4));
        header.setBackgroundColor(COLOR_SURFACE);

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        titleStack.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("双文对照");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView subtitle = new TextView(this);
        subtitle.setText("浏览优先 · 本地比较 · 差异高亮");
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setTextSize(10.5f);
        titleStack.addView(title);
        titleStack.addView(subtitle);
        header.addView(titleStack, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(this);
        badge.setText("离线工具");
        badge.setTextColor(COLOR_PRIMARY);
        badge.setTextSize(11);
        badge.setPadding(dp(10), dp(5), dp(10), dp(5));
        badge.setBackground(roundRect(Color.rgb(225, 246, 242), dp(16), 0, Color.TRANSPARENT));
        header.addView(badge);
        return header;
    }

    private View buildMainToolbar() {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setFillViewport(false);
        horizontal.setBackgroundColor(COLOR_SURFACE);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(dp(10), dp(3), dp(10), dp(3));

        layoutButton = toolbarButton("切左右", false);
        layoutButton.setOnClickListener(v -> {
            saveCurrentScrollPositions();
            sideBySide = !sideBySide;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("side_by_side", sideBySide).apply();
            applyPaneLayout();
            updateToolbarLabels();
        });
        TextView swap = toolbarButton("交换 A/B", false);
        swap.setOnClickListener(v -> swapTexts());
        alignmentButton = toolbarButton("居中", false);
        alignmentButton.setOnClickListener(v -> {
            centered = !centered;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("centered", centered).apply();
            applyTextAppearance();
            updateToolbarLabels();
        });
        TextView smaller = toolbarButton("A−", false);
        smaller.setOnClickListener(v -> changeFontSize(-1f));
        TextView larger = toolbarButton("A+", false);
        larger.setOnClickListener(v -> changeFontSize(1f));
        TextView clearAll = toolbarButton("全部清空", false);
        clearAll.setOnClickListener(v -> confirmClearAll());
        addToolbarItem(toolbar, layoutButton, 70);
        addToolbarItem(toolbar, swap, 82);
        addToolbarItem(toolbar, alignmentButton, 70);
        addToolbarItem(toolbar, smaller, 48);
        addToolbarItem(toolbar, larger, 48);
        addToolbarItem(toolbar, clearAll, 82);
        horizontal.addView(toolbar);
        return horizontal;
    }

    private View buildCompareToolbar() {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setBackgroundColor(COLOR_SURFACE);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), dp(3), dp(10), dp(3));

        autoButton = toolbarButton("自动对比：开", true);
        autoButton.setOnClickListener(v -> {
            autoCompare = !autoCompare;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("auto_compare", autoCompare).apply();
            if (autoCompare) {
                requestComparison();
            } else {
                boolean hadCurrentResult = resultCurrent;
                cancelCompareTask();
                if (hadCurrentResult) {
                    updateComparisonStatus(diffCount == 0 ? "两段文字一致 · 自动对比已关闭"
                            : "发现 " + diffCount + " 处差异 · 自动对比已关闭");
                } else {
                    clearHighlights();
                    updateComparisonStatus((contentA.isEmpty() && contentB.isEmpty())
                            ? "等待文本" : "自动对比已关闭，点击比较");
                }
            }
            updateToolbarLabels();
        });
        TextView compareButton = toolbarButton("比较", false);
        compareButton.setOnClickListener(v -> requestComparison());
        statusLabel = new TextView(this);
        statusLabel.setText("等待文本");
        statusLabel.setTextColor(COLOR_MUTED);
        statusLabel.setTextSize(11);
        statusLabel.setGravity(Gravity.CENTER_VERTICAL);
        statusLabel.setPadding(dp(4), 0, dp(4), 0);
        prevButton = toolbarButton("上一处", false);
        prevButton.setOnClickListener(v -> moveToDifference(-1));
        nextButton = toolbarButton("下一处", false);
        nextButton.setOnClickListener(v -> moveToDifference(1));
        addToolbarItem(row, autoButton, 106);
        addToolbarItem(row, compareButton, 60);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(dp(158), dp(34));
        statusParams.leftMargin = dp(5);
        statusParams.rightMargin = dp(5);
        row.addView(statusLabel, statusParams);
        addToolbarItem(row, prevButton, 68);
        addToolbarItem(row, nextButton, 68);
        horizontal.addView(row);
        return horizontal;
    }

    private LinearLayout buildPane(int side, String hint) {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(dp(9), dp(7), dp(9), dp(8));
        pane.setBackground(roundRect(COLOR_SURFACE, dp(15), dp(1), COLOR_BORDER));
        pane.setElevation(dp(1));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText((side == 0 ? "A" : "B") + "  ·  " + hint);
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

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView paste = toolbarButton("粘贴", true);
        TextView edit = toolbarButton("编辑", false);
        TextView clear = toolbarButton("清空", false);
        addToolbarItem(actions, paste, 1, true);
        addToolbarItem(actions, edit, 1, true);
        addToolbarItem(actions, clear, 1, true);
        paste.setOnClickListener(v -> pasteIntoSide(side));
        edit.setOnClickListener(v -> openEditor(side, side == 0 ? contentA : contentB));
        clear.setOnClickListener(v -> confirmClear(side));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setFadingEdgeLength(dp(8));
        TextView viewer = new TextView(this);
        viewer.setTextColor(COLOR_TEXT);
        viewer.setHintTextColor(Color.rgb(156, 168, 183));
        viewer.setHint("点击编辑或粘贴" + hint + "……");
        viewer.setTextSize(fontSizeSp);
        viewer.setGravity(Gravity.TOP | (centered ? Gravity.CENTER_HORIZONTAL : Gravity.START));
        viewer.setLineSpacing(dp(4), 1.04f);
        viewer.setPadding(dp(11), dp(12), dp(11), dp(14));
        viewer.setTextIsSelectable(true);
        viewer.setFocusable(true);
        viewer.setFocusableInTouchMode(false);
        viewer.setLongClickable(true);
        viewer.setBackground(roundRect(COLOR_EDITOR, dp(11), dp(1), COLOR_BORDER));
        viewer.setText(side == 0 ? contentA : contentB);

        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                openEditor(side, side == 0 ? contentA : contentB);
                return true;
            }
        });
        viewer.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            return false; // Let ScrollView and TextView retain native scrolling/selection behavior.
        });
        scroll.addView(viewer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        pane.addView(titleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(25)));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(31));
        actionParams.topMargin = dp(3);
        pane.addView(actions, actionParams);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(7);
        pane.addView(scroll, scrollParams);

        if (side == 0) {
            textA = viewer;
            scrollA = scroll;
            countA = count;
        } else {
            textB = viewer;
            scrollB = scroll;
            countB = count;
        }
        return pane;
    }

    private void addToolbarItem(LinearLayout row, View view, int widthDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), dp(34));
        params.rightMargin = dp(5);
        row.addView(view, params);
    }

    private void addToolbarItem(LinearLayout row, View view, int ignoredWeight, boolean equalWeight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(30), 1f);
        if (row.getChildCount() > 0) params.leftMargin = dp(5);
        row.addView(view, params);
    }

    private TextView toolbarButton(String text, boolean primary) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextSize(11.5f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setMinWidth(0);
        button.setPadding(dp(5), dp(3), dp(5), dp(3));
        button.setTextColor(primary ? Color.WHITE : COLOR_PRIMARY);
        button.setBackground(roundRect(primary ? COLOR_PRIMARY : Color.rgb(239, 246, 249),
                dp(9), 0, Color.TRANSPARENT));
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
            first.rightMargin = dp(5);
            second.leftMargin = dp(2);
        } else {
            first.bottomMargin = dp(6);
            second.topMargin = dp(1);
        }
        paneContainer.addView(paneA, first);
        paneContainer.addView(paneB, second);
    }

    private void applyTextAppearance() {
        if (textA == null || textB == null) return;
        int gravity = Gravity.TOP | (centered ? Gravity.CENTER_HORIZONTAL : Gravity.START);
        textA.setTextSize(fontSizeSp);
        textB.setTextSize(fontSizeSp);
        textA.setGravity(gravity);
        textB.setGravity(gravity);
        textA.setLineSpacing(dp(4), 1.04f);
        textB.setLineSpacing(dp(4), 1.04f);
    }

    private void updateToolbarLabels() {
        if (layoutButton != null) layoutButton.setText(sideBySide ? "切上下" : "切左右");
        if (alignmentButton != null) alignmentButton.setText(centered ? "左对齐" : "居中");
        if (autoButton != null) {
            autoButton.setText(autoCompare ? "自动对比：开" : "自动对比：关");
            autoButton.setBackground(roundRect(autoCompare ? COLOR_PRIMARY : Color.rgb(239, 246, 249),
                    dp(9), 0, Color.TRANSPARENT));
            autoButton.setTextColor(autoCompare ? Color.WHITE : COLOR_PRIMARY);
        }
        boolean canNavigate = resultCurrent && diffCount > 0;
        if (prevButton != null) prevButton.setAlpha(canNavigate ? 1f : 0.45f);
        if (nextButton != null) nextButton.setAlpha(canNavigate ? 1f : 0.45f);
    }

    private void updateCounts() {
        if (countA != null) countA.setText(formatCharacterCount(contentA));
        if (countB != null) countB.setText(formatCharacterCount(contentB));
    }

    private static String formatCharacterCount(CharSequence value) {
        int count = value == null ? 0 : Character.codePointCount(value, 0, value.length());
        return count + " 字符";
    }

    private void changeFontSize(float delta) {
        fontSizeSp = Math.max(13f, Math.min(28f, fontSizeSp + delta));
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat("font_size", fontSizeSp).apply();
        int a = scrollA.getScrollY();
        int b = scrollB.getScrollY();
        applyTextAppearance();
        scrollA.post(() -> scrollA.scrollTo(0, a));
        scrollB.post(() -> scrollB.scrollTo(0, b));
    }

    private void saveCurrentScrollPositions() {
        // ScrollView state is naturally preserved when panes are reattached; this method documents intent.
        int ignoredA = scrollA == null ? 0 : scrollA.getScrollY();
        int ignoredB = scrollB == null ? 0 : scrollB.getScrollY();
    }

    private void swapTexts() {
        String oldA = contentA;
        contentA = contentB;
        contentB = oldA;
        contentRevision++;
        activeDifference = -1;
        clearHighlights();
        renderPlainText();
        updateCounts();
        if (autoCompare) requestComparison();
        else updateComparisonStatus("文本已变化，点击比较更新");
        updateToolbarLabels();
    }

    private void pasteIntoSide(int side) {
        String value = readClipboardText();
        if (value == null) return;
        String current = side == 0 ? contentA : contentB;
        if (!current.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("替换文本 " + (side == 0 ? "A" : "B") + "？")
                    .setMessage("粘贴会替换这一栏现有的全部文字。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("替换并粘贴", (d, which) -> updateContent(side, value))
                    .show();
        } else {
            updateContent(side, value);
        }
    }

    private String readClipboardText() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return null;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return null;
        }
        try {
            CharSequence value = clip.getItemAt(0).coerceToText(this);
            if (value == null) {
                Toast.makeText(this, "剪贴板中没有文字", Toast.LENGTH_SHORT).show();
                return null;
            }
            return value.toString();
        } catch (RuntimeException exception) {
            Toast.makeText(this, "无法读取剪贴板内容", Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    private void confirmClear(int side) {
        String current = side == 0 ? contentA : contentB;
        if (current.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("清空文本 " + (side == 0 ? "A" : "B") + "？")
                .setMessage("只会清除这一栏的文字。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> updateContent(side, ""))
                .show();
    }

    private void confirmClearAll() {
        if (contentA.isEmpty() && contentB.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("清空全部文字？")
                .setMessage("文本 A 和文本 B 都会被清除，此操作无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("全部清空", (dialog, which) -> {
                    updateContent(0, "");
                    updateContent(1, "");
                    hideKeyboard();
                })
                .show();
    }

    private void openEditor(int side, String initialText) {
        openEditor(side, initialText, false);
    }

    private void openEditor(int side, String initialText, boolean restoring) {
        if (isFinishing() || (editorDialog != null && editorDialog.isShowing())) return;
        editingSide = side;
        Dialog dialog = new Dialog(this);
        editorDialog = dialog;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(10));
        panel.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("编辑文本 " + (side == 0 ? "A" : "B"));
        title.setTextSize(18);
        title.setTextColor(COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView paste = toolbarButton("粘贴", false);
        header.addView(paste, new LinearLayout.LayoutParams(dp(70), dp(36)));
        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        EditText editor = new EditText(this);
        editorInput = editor;
        editor.setText(initialText == null ? "" : initialText);
        editor.setTextSize(fontSizeSp);
        editor.setTextColor(COLOR_TEXT);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE);
        editor.setSingleLine(false);
        editor.setHorizontallyScrolling(false);
        editor.setPadding(dp(12), dp(12), dp(12), dp(12));
        editor.setBackground(roundRect(COLOR_SURFACE, dp(12), dp(1), COLOR_BORDER));
        editor.setHint("在这里编辑或粘贴长文本……");
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        editorParams.topMargin = dp(10);
        editorParams.bottomMargin = dp(10);
        panel.addView(editor, editorParams);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = toolbarButton("取消", false);
        TextView done = toolbarButton("完成", true);
        footer.addView(cancel, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        doneParams.leftMargin = dp(8);
        footer.addView(done, doneParams);
        panel.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        paste.setOnClickListener(v -> {
            String value = readClipboardText();
            if (value == null) return;
            if (editor.getText().length() > 0) {
                new AlertDialog.Builder(this).setTitle("替换编辑区文字？")
                        .setMessage("粘贴会替换编辑区中的全部文字。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("替换", (d, w) -> editor.setText(value)).show();
            } else editor.setText(value);
        });
        cancel.setOnClickListener(v -> {
            hideKeyboardFor(editor);
            dialog.dismiss();
        });
        done.setOnClickListener(v -> {
            String value = editor.getText().toString();
            hideKeyboardFor(editor);
            dialog.dismiss();
            updateContent(side, value);
        });
        dialog.setContentView(panel);
        dialog.setCancelable(true);
        dialog.setOnDismissListener(d -> {
            if (editorDialog == dialog) {
                editorDialog = null;
                editorInput = null;
                editingSide = -1;
            }
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        editor.setSelection(editor.getText().length());
        editor.requestFocus();
        if (!restoring) {
            editor.postDelayed(() -> {
                if (editorDialog == dialog && dialog.isShowing()) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 170);
        }
    }

    private void updateContent(int side, String value) {
        if (side == 0) contentA = value == null ? "" : value;
        else contentB = value == null ? "" : value;
        contentRevision++;
        cancelCompareTask();
        clearHighlights();
        renderPlainText();
        updateCounts();
        resultCurrent = false;
        activeDifference = -1;
        if (autoCompare) requestComparison();
        else updateComparisonStatus((contentA.isEmpty() && contentB.isEmpty())
                ? "等待文本" : "文本已变化，点击比较更新");
        updateToolbarLabels();
    }

    private void renderPlainText() {
        if (textA == null || textB == null) return;
        int yA = scrollA.getScrollY();
        int yB = scrollB.getScrollY();
        textA.setText(contentA.isEmpty() ? "" : contentA);
        textB.setText(contentB.isEmpty() ? "" : contentB);
        scrollA.post(() -> scrollA.scrollTo(0, yA));
        scrollB.post(() -> scrollB.scrollTo(0, yB));
    }

    private void requestComparison() {
        if (contentA.isEmpty() && contentB.isEmpty()) {
            cancelCompareTask();
            clearHighlights();
            resultCurrent = true;
            diffCount = 0;
            activeDifference = -1;
            updateComparisonStatus("等待文本");
            updateToolbarLabels();
            return;
        }
        cancelCompareTask();
        final String a = contentA;
        final String b = contentB;
        final long revision = contentRevision;
        final long generation = ++compareGeneration;
        updateComparisonStatus("正在比较……");
        compareFuture = compareExecutor.submit(() -> {
            ComparisonResult result = computeDiff(a, b);
            runOnUiThread(() -> {
                if (isFinishing() || revision != contentRevision || generation != compareGeneration) return;
                diffA = result.aRanges;
                diffB = result.bRanges;
                diffCount = result.differenceCount;
                resultCurrent = true;
                activeDifference = diffCount > 0 ? 0 : -1;
                applyHighlightsPreservingScroll();
                updateComparisonStatus(diffCount == 0 ? "两段文字一致" : "发现 " + diffCount + " 处差异");
                updateToolbarLabels();
            });
        });
    }

    private void cancelCompareTask() {
        compareGeneration++;
        if (compareFuture != null) {
            compareFuture.cancel(true);
            compareFuture = null;
        }
    }

    private void clearHighlights() {
        diffA = new ArrayList<>();
        diffB = new ArrayList<>();
        diffCount = 0;
        activeDifference = -1;
        resultCurrent = false;
        if (textA != null) {
            textA.setText(contentA);
            textB.setText(contentB);
        }
    }

    private void applyHighlightsPreservingScroll() {
        int yA = scrollA.getScrollY();
        int yB = scrollB.getScrollY();
        textA.setText(highlight(contentA, diffA, COLOR_DIFF_A, COLOR_DIFF_A_TEXT));
        textB.setText(highlight(contentB, diffB, COLOR_DIFF_B, COLOR_DIFF_B_TEXT));
        scrollA.post(() -> scrollA.scrollTo(0, yA));
        scrollB.post(() -> scrollB.scrollTo(0, yB));
    }

    private SpannableString highlight(String text, List<Range> ranges, int background, int foreground) {
        SpannableString span = new SpannableString(text);
        for (Range range : ranges) {
            int start = Math.max(0, Math.min(range.start, text.length()));
            int end = Math.max(start, Math.min(range.end, text.length()));
            if (end <= start) continue;
            span.setSpan(new BackgroundColorSpan(background), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new android.text.style.ForegroundColorSpan(foreground), start, end,
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }

    private void updateComparisonStatus(String value) {
        if (statusLabel != null) statusLabel.setText(value);
    }

    private void moveToDifference(int direction) {
        if (!resultCurrent || diffCount == 0) return;
        activeDifference = (activeDifference + direction + diffCount) % diffCount;
        Range ra = activeDifference < diffA.size() ? diffA.get(activeDifference) : null;
        Range rb = activeDifference < diffB.size() ? diffB.get(activeDifference) : null;
        // The ranges are merged spans rather than exact LCS operations; synchronize navigation to each side's next span.
        if (ra != null) scrollToOffset(scrollA, textA, ra.start);
        if (rb != null) scrollToOffset(scrollB, textB, rb.start);
        updateComparisonStatus("差异 " + (activeDifference + 1) + "/" + diffCount);
    }

    private void scrollToOffset(ScrollView scroller, TextView view, int offset) {
        android.text.Layout layout = view.getLayout();
        if (layout == null || offset < 0 || offset > view.getText().length()) return;
        int line = layout.getLineForOffset(offset);
        int y = layout.getLineTop(line) + view.getPaddingTop();
        scroller.smoothScrollTo(0, Math.max(0, y - dp(50)));
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) hideKeyboardFor(focus);
    }

    private void hideKeyboardFor(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && view != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
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
        outState.putString(STATE_A, contentA);
        outState.putString(STATE_B, contentB);
        outState.putBoolean(STATE_SIDE_BY_SIDE, sideBySide);
        outState.putBoolean(STATE_CENTERED, centered);
        outState.putBoolean(STATE_AUTO_COMPARE, autoCompare);
        outState.putFloat(STATE_FONT_SIZE, fontSizeSp);
        outState.putInt(STATE_ORIENTATION, getResources().getConfiguration().orientation);
        outState.putInt(STATE_SCROLL_A, scrollA == null ? 0 : scrollA.getScrollY());
        outState.putInt(STATE_SCROLL_B, scrollB == null ? 0 : scrollB.getScrollY());
        outState.putInt(STATE_EDITING_SIDE, editingSide);
        if (editorInput != null) outState.putString(STATE_EDIT_DRAFT, editorInput.getText().toString());
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        cancelCompareTask();
        if (compareExecutor != null) compareExecutor.shutdownNow();
        super.onDestroy();
    }

    // ---------- Background text-diff implementation ----------

    private static final class Range {
        final int start;
        final int end;
        Range(int start, int end) { this.start = start; this.end = end; }
    }

    private static final class ComparisonResult {
        final List<Range> aRanges = new ArrayList<>();
        final List<Range> bRanges = new ArrayList<>();
        int differenceCount;
    }

    private static final class Line {
        final String value;
        final int start;
        final int end;
        Line(String value, int start, int end) { this.value = value; this.start = start; this.end = end; }
    }

    private static final class Anchor {
        final int a;
        final int b;
        Anchor(int a, int b) { this.a = a; this.b = b; }
    }

    private static ComparisonResult computeDiff(String a, String b) {
        ComparisonResult result = new ComparisonResult();
        if (a.equals(b)) return result;
        List<Line> linesA = splitLines(a);
        List<Line> linesB = splitLines(b);
        List<Anchor> anchors = findLineAnchors(linesA, linesB);
        int ai = 0;
        int bi = 0;
        for (Anchor anchor : anchors) {
            compareLineGap(linesA, ai, anchor.a, linesB, bi, anchor.b, result);
            ai = anchor.a + 1;
            bi = anchor.b + 1;
        }
        compareLineGap(linesA, ai, linesA.size(), linesB, bi, linesB.size(), result);
        mergeRanges(result.aRanges);
        mergeRanges(result.bRanges);
        result.differenceCount = Math.max(result.aRanges.size(), result.bRanges.size());
        // Count conceptual changed blocks even when one side contains only insertions/deletions.
        result.differenceCount = Math.max(1, result.differenceCount);
        return result;
    }

    private static List<Line> splitLines(String text) {
        List<Line> lines = new ArrayList<>();
        if (text.isEmpty()) return lines;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if ((i & 8191) == 0) checkInterrupted();
            if (text.charAt(i) == '\n') {
                int end = i + 1;
                lines.add(new Line(text.substring(start, end), start, end));
                start = end;
            }
        }
        if (start < text.length()) lines.add(new Line(text.substring(start), start, text.length()));
        return lines;
    }

    private static List<Anchor> findLineAnchors(List<Line> a, List<Line> b) {
        int n = a.size();
        int m = b.size();
        List<Anchor> result = new ArrayList<>();
        if (n == 0 || m == 0) return result;
        if ((long) n * (long) m <= LINE_LCS_CELL_LIMIT) {
            int[][] dp = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                checkInterrupted();
                for (int j = m - 1; j >= 0; j--) {
                    dp[i][j] = a.get(i).value.equals(b.get(j).value)
                            ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
            int i = 0, j = 0;
            int steps = 0;
            while (i < n && j < m) {
                if ((steps++ & 1023) == 0) checkInterrupted();
                if (a.get(i).value.equals(b.get(j).value)) {
                    result.add(new Anchor(i++, j++));
                } else if (dp[i + 1][j] >= dp[i][j + 1]) i++;
                else j++;
            }
            return result;
        }
        // Bounded-memory monotonic fallback for exceptionally line-heavy documents.
        Map<String, ArrayDeque<Integer>> positions = new HashMap<>();
        for (int j = 0; j < m; j++) {
            if ((j & 1023) == 0) checkInterrupted();
            positions.computeIfAbsent(b.get(j).value, k -> new ArrayDeque<>()).add(j);
        }
        int lastB = -1;
        for (int i = 0; i < n; i++) {
            if ((i & 1023) == 0) checkInterrupted();
            ArrayDeque<Integer> queue = positions.get(a.get(i).value);
            if (queue == null) continue;
            while (!queue.isEmpty() && queue.peekFirst() <= lastB) queue.removeFirst();
            if (!queue.isEmpty()) {
                int j = queue.removeFirst();
                result.add(new Anchor(i, j));
                lastB = j;
            }
        }
        return result;
    }

    private static void compareLineGap(List<Line> a, int aFrom, int aTo,
                                       List<Line> b, int bFrom, int bTo, ComparisonResult result) {
        int countA = aTo - aFrom;
        int countB = bTo - bFrom;
        int paired = Math.min(countA, countB);
        checkInterrupted();
        for (int k = 0; k < paired; k++) {
            if ((k & 1023) == 0) checkInterrupted();
            Line la = a.get(aFrom + k);
            Line lb = b.get(bFrom + k);
            if (!la.value.equals(lb.value)) compareLineCharacters(la, lb, result);
        }
        for (int k = aFrom + paired; k < aTo; k++) {
            if ((k & 1023) == 0) checkInterrupted();
            Line line = a.get(k);
            if (line.end > line.start) result.aRanges.add(new Range(line.start, line.end));
        }
        for (int k = bFrom + paired; k < bTo; k++) {
            if ((k & 1023) == 0) checkInterrupted();
            Line line = b.get(k);
            if (line.end > line.start) result.bRanges.add(new Range(line.start, line.end));
        }
    }

    private static void compareLineCharacters(Line a, Line b, ComparisonResult result) {
        String left = a.value;
        String right = b.value;
        int[] acp = toCodePoints(left);
        int[] bcp = toCodePoints(right);
        int[] aOffsets = codePointOffsets(left, acp.length);
        int[] bOffsets = codePointOffsets(right, bcp.length);
        int n = acp.length;
        int m = bcp.length;
        if (n == 0 && m == 0) return;
        if ((long) n * (long) m > CHAR_LCS_CELL_LIMIT) {
            int prefix = 0;
            while (prefix < n && prefix < m && acp[prefix] == bcp[prefix]) {
                if ((prefix & 8191) == 0) checkInterrupted();
                prefix++;
            }
            int suffix = 0;
            while (suffix < n - prefix && suffix < m - prefix
                    && acp[n - suffix - 1] == bcp[m - suffix - 1]) {
                if ((suffix & 8191) == 0) checkInterrupted();
                suffix++;
            }
            addCpRange(result.aRanges, a.start, aOffsets, prefix, n - suffix);
            addCpRange(result.bRanges, b.start, bOffsets, prefix, m - suffix);
            return;
        }
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            checkInterrupted();
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = acp[i] == bcp[j] ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        int i = 0, j = 0;
        int runA = -1, runB = -1;
        while (i < n && j < m) {
            if (acp[i] == bcp[j]) {
                if (runA >= 0 || runB >= 0) {
                    addCpRange(result.aRanges, a.start, aOffsets, Math.max(0, runA), i);
                    addCpRange(result.bRanges, b.start, bOffsets, Math.max(0, runB), j);
                    runA = runB = -1;
                }
                i++; j++;
            } else {
                if (runA < 0) runA = i;
                if (runB < 0) runB = j;
                if (dp[i + 1][j] >= dp[i][j + 1]) i++; else j++;
            }
        }
        if (runA >= 0 || runB >= 0) {
            addCpRange(result.aRanges, a.start, aOffsets, Math.max(0, runA), n);
            addCpRange(result.bRanges, b.start, bOffsets, Math.max(0, runB), m);
        }
    }

    private static int[] toCodePoints(String text) {
        int count = Character.codePointCount(text, 0, text.length());
        int[] result = new int[count];
        int charIndex = 0;
        for (int i = 0; i < count; i++) {
            if ((i & 8191) == 0) checkInterrupted();
            int codePoint = Character.codePointAt(text, charIndex);
            result[i] = codePoint;
            charIndex += Character.charCount(codePoint);
        }
        return result;
    }

    private static int[] codePointOffsets(String text, int codePointCount) {
        int[] offsets = new int[codePointCount + 1];
        int charIndex = 0;
        for (int i = 0; i < codePointCount; i++) {
            if ((i & 8191) == 0) checkInterrupted();
            offsets[i] = charIndex;
            charIndex += Character.charCount(text.codePointAt(charIndex));
        }
        offsets[codePointCount] = text.length();
        return offsets;
    }

    private static void addCpRange(List<Range> destination, int base, int[] offsets, int startCp, int endCp) {
        int start = Math.max(0, Math.min(startCp, offsets.length - 1));
        int end = Math.max(start, Math.min(endCp, offsets.length - 1));
        if (end > start) destination.add(new Range(base + offsets[start], base + offsets[end]));
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Comparison superseded");
    }

    private static void mergeRanges(List<Range> ranges) {
        if (ranges.size() < 2) return;
        Collections.sort(ranges, Comparator.comparingInt(r -> r.start));
        List<Range> merged = new ArrayList<>();
        Range current = ranges.get(0);
        for (int i = 1; i < ranges.size(); i++) {
            Range next = ranges.get(i);
            if (next.start <= current.end) current = new Range(current.start, Math.max(current.end, next.end));
            else { merged.add(current); current = next; }
        }
        merged.add(current);
        ranges.clear();
        ranges.addAll(merged);
    }
}
