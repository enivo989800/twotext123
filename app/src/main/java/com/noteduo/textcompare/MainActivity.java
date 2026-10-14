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
import android.widget.Button;
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
    private static final String STATE_CURRENT_PAGE = "current_page";
    private static final String PREF_THEME = "theme_style";

    // Active palette values are resolved from the persisted theme before the screen is built.
    private int COLOR_BACKGROUND;
    private int COLOR_SURFACE;
    private int COLOR_TEXT;
    private int COLOR_MUTED;
    private int COLOR_PRIMARY;
    private int COLOR_PRIMARY_DARK;
    private int COLOR_BORDER;
    private int COLOR_EDITOR;
    private int COLOR_SOFT;
    private int COLOR_HEADER_START;
    private int COLOR_HEADER_END;
    private int COLOR_DIFF_A;
    private int COLOR_DIFF_B;
    private int COLOR_DIFF_A_TEXT;
    private int COLOR_DIFF_B_TEXT;
    private int COLOR_TAG_A;
    private int COLOR_TAG_B;
    private boolean darkTheme;

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
    private LinearLayout pagesHost;
    private LinearLayout homePage;
    private View diffPage;
    private View settingsPage;
    private View themePage;
    private LinearLayout bottomNavigation;
    private TextView navCompare;
    private TextView navDiff;
    private TextView navSettings;
    private TextView settingsThemeSummary;
    private TextView settingsAutoButton;
    private TextView settingsFontValue;
    private TextView settingsAlignmentValue;
    private LinearLayout differenceList;
    private String selectedTheme = "fresh_blue";
    private String comparisonStatusText = "等待文本";
    private int currentPage;

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
    private List<Diff> diffs = new ArrayList<>();
    private boolean resultCurrent;
    private boolean suppressCompare;
    private Dialog editorDialog;
    private Dialog dialogOpenMore;
    private EditText editorInput;
    private int editingSide = -1;
    private ExecutorService compareExecutor;
    private Future<?> compareFuture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        compareExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "text-compare-worker");
            thread.setDaemon(true);
            return thread;
        });

        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        selectedTheme = preferences.getString(PREF_THEME, "fresh_blue");
        applyPaletteValues(selectedTheme);
        configureSystemBars();
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
            currentPage = Math.max(0, Math.min(3, savedInstanceState.getInt(STATE_CURRENT_PAGE, 0)));
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
        showPage(currentPage);

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

    private void applyPaletteValues(String themeId) {
        selectedTheme = isKnownTheme(themeId) ? themeId : "fresh_blue";
        switch (selectedTheme) {
            case "dark_night":
                COLOR_BACKGROUND = Color.rgb(16, 24, 39);
                COLOR_SURFACE = Color.rgb(28, 39, 57);
                COLOR_TEXT = Color.rgb(232, 238, 248);
                COLOR_MUTED = Color.rgb(165, 179, 199);
                COLOR_PRIMARY = Color.rgb(105, 158, 255);
                COLOR_PRIMARY_DARK = Color.rgb(41, 83, 150);
                COLOR_BORDER = Color.rgb(54, 70, 94);
                COLOR_EDITOR = Color.rgb(20, 30, 46);
                COLOR_SOFT = Color.rgb(38, 55, 79);
                COLOR_HEADER_START = Color.rgb(18, 30, 51);
                COLOR_HEADER_END = Color.rgb(37, 58, 91);
                COLOR_DIFF_A = Color.rgb(91, 43, 50);
                COLOR_DIFF_B = Color.rgb(34, 69, 109);
                COLOR_DIFF_A_TEXT = Color.rgb(255, 213, 212);
                COLOR_DIFF_B_TEXT = Color.rgb(211, 231, 255);
                COLOR_TAG_A = Color.rgb(105, 48, 57);
                COLOR_TAG_B = Color.rgb(35, 72, 112);
                darkTheme = true;
                break;
            case "warm_cream":
                COLOR_BACKGROUND = Color.rgb(248, 242, 231);
                COLOR_SURFACE = Color.rgb(255, 252, 245);
                COLOR_TEXT = Color.rgb(61, 51, 42);
                COLOR_MUTED = Color.rgb(129, 117, 103);
                COLOR_PRIMARY = Color.rgb(139, 94, 55);
                COLOR_PRIMARY_DARK = Color.rgb(102, 68, 40);
                COLOR_BORDER = Color.rgb(231, 219, 201);
                COLOR_EDITOR = Color.rgb(255, 253, 248);
                COLOR_SOFT = Color.rgb(245, 235, 218);
                COLOR_HEADER_START = Color.rgb(119, 79, 46);
                COLOR_HEADER_END = Color.rgb(158, 113, 73);
                COLOR_DIFF_A = Color.rgb(251, 224, 218);
                COLOR_DIFF_B = Color.rgb(220, 235, 249);
                COLOR_DIFF_A_TEXT = Color.rgb(132, 47, 39);
                COLOR_DIFF_B_TEXT = Color.rgb(31, 76, 118);
                COLOR_TAG_A = Color.rgb(255, 227, 223);
                COLOR_TAG_B = Color.rgb(224, 239, 255);
                darkTheme = false;
                break;
            case "mint_green":
                COLOR_BACKGROUND = Color.rgb(237, 247, 241);
                COLOR_SURFACE = Color.rgb(255, 255, 255);
                COLOR_TEXT = Color.rgb(25, 59, 46);
                COLOR_MUTED = Color.rgb(99, 128, 115);
                COLOR_PRIMARY = Color.rgb(24, 131, 95);
                COLOR_PRIMARY_DARK = Color.rgb(15, 96, 68);
                COLOR_BORDER = Color.rgb(213, 233, 222);
                COLOR_EDITOR = Color.rgb(246, 252, 248);
                COLOR_SOFT = Color.rgb(224, 244, 233);
                COLOR_HEADER_START = Color.rgb(15, 103, 76);
                COLOR_HEADER_END = Color.rgb(31, 148, 107);
                COLOR_DIFF_A = Color.rgb(255, 223, 218);
                COLOR_DIFF_B = Color.rgb(218, 235, 255);
                COLOR_DIFF_A_TEXT = Color.rgb(132, 42, 36);
                COLOR_DIFF_B_TEXT = Color.rgb(25, 72, 119);
                COLOR_TAG_A = Color.rgb(255, 229, 225);
                COLOR_TAG_B = Color.rgb(225, 239, 255);
                darkTheme = false;
                break;
            case "fresh_blue":
            default:
                COLOR_BACKGROUND = Color.rgb(242, 247, 254);
                COLOR_SURFACE = Color.WHITE;
                COLOR_TEXT = Color.rgb(29, 43, 67);
                COLOR_MUTED = Color.rgb(104, 122, 149);
                COLOR_PRIMARY = Color.rgb(38, 91, 205);
                COLOR_PRIMARY_DARK = Color.rgb(27, 67, 158);
                COLOR_BORDER = Color.rgb(220, 231, 247);
                COLOR_EDITOR = Color.rgb(249, 251, 255);
                COLOR_SOFT = Color.rgb(232, 241, 255);
                COLOR_HEADER_START = Color.rgb(35, 79, 179);
                COLOR_HEADER_END = Color.rgb(60, 117, 231);
                COLOR_DIFF_A = Color.rgb(255, 220, 218);
                COLOR_DIFF_B = Color.rgb(215, 233, 255);
                COLOR_DIFF_A_TEXT = Color.rgb(142, 42, 39);
                COLOR_DIFF_B_TEXT = Color.rgb(27, 72, 139);
                COLOR_TAG_A = Color.rgb(255, 229, 227);
                COLOR_TAG_B = Color.rgb(226, 239, 255);
                darkTheme = false;
                break;
        }
    }

    private static boolean isKnownTheme(String id) {
        return "fresh_blue".equals(id) || "dark_night".equals(id)
                || "warm_cream".equals(id) || "mint_green".equals(id);
    }

    private static String themeName(String id) {
        switch (id) {
            case "dark_night": return "深色夜间";
            case "warm_cream": return "温暖米白";
            case "mint_green": return "薄荷绿";
            default: return "清爽蓝";
        }
    }

    private static String themeDescription(String id) {
        switch (id) {
            case "dark_night": return "深色卡片与柔和浅色文字，适合夜间阅读";
            case "warm_cream": return "米白和暖灰色调，减少冷硬的视觉感受";
            case "mint_green": return "浅薄荷背景与绿色强调色，清新轻盈";
            default: return "蓝色工具栏、冷白卡片，清晰易读的默认主题";
        }
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(COLOR_HEADER_START);
        window.setNavigationBarColor(COLOR_BACKGROUND);
        int flags = darkTheme ? 0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    private void buildScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);
        root.setFocusableInTouchMode(true);
        root.addView(buildHeader(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        pagesHost = new LinearLayout(this);
        pagesHost.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hostParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(pagesHost, hostParams);

        homePage = new LinearLayout(this);
        homePage.setOrientation(LinearLayout.VERTICAL);
        homePage.addView(buildMainToolbar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
        homePage.addView(buildCompareToolbar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(39)));
        paneContainer = new LinearLayout(this);
        paneContainer.setPadding(dp(10), dp(5), dp(10), dp(8));
        paneA = buildPane(0, "文本 A");
        paneB = buildPane(1, "文本 B");
        homePage.addView(paneContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        pagesHost.addView(homePage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        diffPage = buildDifferencePage();
        settingsPage = buildSettingsPage();
        themePage = buildThemePage();
        pagesHost.addView(diffPage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        pagesHost.addView(settingsPage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        pagesHost.addView(themePage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        bottomNavigation = buildBottomNavigation();
        root.addView(bottomNavigation, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));
        setContentView(root);
        applyPaneLayout();
        showPage(currentPage);
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(5), dp(14), dp(4));
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{COLOR_HEADER_START, COLOR_HEADER_END});
        header.setBackground(gradient);

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        titleStack.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("双文对照");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView subtitle = new TextView(this);
        subtitle.setText("浏览优先  ·  本地比较  ·  差异高亮");
        subtitle.setTextColor(Color.rgb(229, 238, 255));
        subtitle.setTextSize(10.5f);
        titleStack.addView(title);
        titleStack.addView(subtitle);
        header.addView(titleStack, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(this);
        badge.setText("离线工具");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(10.5f);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(roundRect(0x33FFFFFF, dp(18), dp(1), 0x66FFFFFF));
        header.addView(badge);
        return header;
    }

    private View buildMainToolbar() {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setFillViewport(true);
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
        TextView more = toolbarButton("更多  ⋮", false);
        more.setOnClickListener(v -> showMoreActions());
        addToolbarItem(toolbar, layoutButton, 82);
        addToolbarItem(toolbar, swap, 88);
        View spacer = new View(this);
        toolbar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        addToolbarItem(toolbar, more, 78);
        horizontal.addView(toolbar, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.MATCH_PARENT, HorizontalScrollView.LayoutParams.MATCH_PARENT));
        return horizontal;
    }

    private void showMoreActions() {
        Dialog dialog = new Dialog(this);
        dialogOpenMore = dialog;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(13), dp(16), dp(13));
        panel.setBackground(roundRect(COLOR_SURFACE, dp(18), dp(1), COLOR_BORDER));
        TextView title = pageTitle("更多操作");
        panel.addView(title);
        addActionRow(panel, centered ? "正文对齐：居中" : "正文对齐：左对齐", () -> {
            centered = !centered;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("centered", centered).apply();
            applyTextAppearance();
            updateToolbarLabels();
            updateSettingsLabels();
        });
        addActionRow(panel, "缩小文字  A−", () -> changeFontSize(-1f));
        addActionRow(panel, "放大文字  A+", () -> changeFontSize(1f));
        addActionRow(panel, "比较当前文本", this::requestComparison);
        TextView clear = toolbarButton("清空全部文字", false);
        clear.setTextColor(Color.rgb(190, 54, 54));
        clear.setBackground(roundRect(darkTheme ? Color.rgb(75, 40, 49) : Color.rgb(255, 238, 236),
                dp(10), dp(1), darkTheme ? Color.rgb(111, 57, 68) : Color.rgb(246, 207, 202)));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        clearParams.topMargin = dp(5);
        panel.addView(clear, clearParams);
        clear.setOnClickListener(v -> {
            dialog.dismiss();
            confirmClearAll();
        });
        dialog.setContentView(panel);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(roundRect(COLOR_SURFACE, dp(18), dp(1), COLOR_BORDER));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(36), dp(340)),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        dialog.setOnDismissListener(d -> { if (dialogOpenMore == dialog) dialogOpenMore = null; });
    }

    private void addActionRow(LinearLayout parent, String title, Runnable action) {
        TextView row = new TextView(this);
        row.setText(title + "    ›");
        row.setTextSize(13);
        row.setTextColor(COLOR_TEXT);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(11), dp(10), dp(8), dp(10));
        row.setBackground(roundRect(COLOR_EDITOR, dp(10), dp(1), COLOR_BORDER));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(43));
        params.bottomMargin = dp(7);
        parent.addView(row, params);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            if (dialogOpenMore != null) dialogOpenMore.dismiss();
            action.run();
        });
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
        autoButton.setOnClickListener(v -> toggleAutoCompare());
        TextView compareButton = toolbarButton("比较", false);
        compareButton.setOnClickListener(v -> requestComparison());
        statusLabel = new TextView(this);
        statusLabel.setText("等待文本");
        statusLabel.setTextColor(COLOR_MUTED);
        statusLabel.setBackground(roundRect(COLOR_EDITOR, dp(9), dp(1), COLOR_BORDER));
        statusLabel.setTextSize(10.5f);
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
        pane.setElevation(dp(1.5f));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView tag = new TextView(this);
        tag.setText(side == 0 ? "A" : "B");
        tag.setTextColor(side == 0 ? COLOR_DIFF_A_TEXT : COLOR_DIFF_B_TEXT);
        tag.setTextSize(12);
        tag.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tag.setGravity(Gravity.CENTER);
        tag.setPadding(dp(10), dp(3), dp(10), dp(3));
        tag.setBackground(roundRect(side == 0 ? COLOR_TAG_A : COLOR_TAG_B, dp(20), 0, Color.TRANSPARENT));
        titleRow.addView(tag, new LinearLayout.LayoutParams(dp(32), dp(26)));
        TextView title = new TextView(this);
        title.setText(hint);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(7);
        titleRow.addView(title, titleParams);
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
        viewer.setHintTextColor(COLOR_MUTED);
        viewer.setHint("粘贴或编辑文本" + (side == 0 ? " A" : " B") + "……");
        viewer.setTextSize(fontSizeSp);
        viewer.setGravity(Gravity.TOP | (centered ? Gravity.CENTER_HORIZONTAL : Gravity.START));
        viewer.setLineSpacing(dp(4), 1.04f);
        viewer.setPadding(dp(11), dp(10), dp(11), dp(12));
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
            return false;
        });
        scroll.addView(viewer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        pane.addView(titleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(31));
        actionParams.topMargin = dp(3);
        pane.addView(actions, actionParams);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(6);
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

    private LinearLayout buildBottomNavigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(8), dp(4), dp(8), dp(4));
        nav.setBackgroundColor(COLOR_SURFACE);
        nav.setElevation(dp(6));
        View divider = new View(this);
        divider.setBackgroundColor(COLOR_BORDER);
        // Use a top border as a separate lightweight strip to avoid a heavy bottom bar.
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        wrapper.addView(nav, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        navCompare = navItem("▤\n对照", 0);
        navDiff = navItem("≋\n差异", 1);
        navSettings = navItem("⚙\n设置", 2);
        nav.addView(navCompare, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        nav.addView(navDiff, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        nav.addView(navSettings, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        navCompare.setOnClickListener(v -> showPage(0));
        navDiff.setOnClickListener(v -> showPage(1));
        navSettings.setOnClickListener(v -> showPage(2));
        return wrapper;
    }

    private TextView navItem(String text, int index) {
        TextView item = new TextView(this);
        item.setText(text);
        item.setTextSize(11);
        item.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        item.setGravity(Gravity.CENTER);
        item.setLineSpacing(0, 0.94f);
        item.setPadding(dp(4), dp(2), dp(4), dp(2));
        item.setBackground(roundRect(COLOR_SURFACE, dp(12), 0, Color.TRANSPARENT));
        item.setClickable(true);
        item.setFocusable(true);
        return item;
    }

    private void showPage(int page) {
        currentPage = Math.max(0, Math.min(3, page));
        if (pagesHost != null) {
            for (int i = 0; i < pagesHost.getChildCount(); i++) {
                pagesHost.getChildAt(i).setVisibility(i == currentPage ? View.VISIBLE : View.GONE);
            }
        }
        updateNavigationState();
        if (currentPage == 1) refreshDifferencePage();
        if (currentPage == 2) updateSettingsLabels();
    }

    private void updateNavigationState() {
        if (navCompare == null || navDiff == null || navSettings == null) return;
        int selected = currentPage == 3 ? 2 : currentPage;
        TextView[] items = {navCompare, navDiff, navSettings};
        for (int i = 0; i < items.length; i++) {
            items[i].setTextColor(i == selected ? COLOR_PRIMARY : COLOR_MUTED);
            items[i].setBackground(roundRect(i == selected ? COLOR_SOFT : COLOR_SURFACE,
                    dp(12), 0, Color.TRANSPARENT));
        }
    }

    private View buildDifferencePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BACKGROUND);
        LinearLayout content = pageContent();
        TextView title = pageTitle("差异导航");
        content.addView(title);
        TextView description = bodyLabel("查看自动比较找到的不同内容，点击任一项可返回原文定位。");
        description.setPadding(0, dp(4), 0, dp(12));
        content.addView(description);
        LinearLayout summary = makeCard();
        TextView summaryTitle = new TextView(this);
        summaryTitle.setTag("difference_summary");
        summaryTitle.setTextColor(COLOR_TEXT);
        summaryTitle.setTextSize(14);
        summaryTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        summaryTitle.setPadding(dp(12), dp(12), dp(12), dp(12));
        summary.addView(summaryTitle);
        content.addView(summary, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        differenceList = new LinearLayout(this);
        differenceList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = dp(10);
        content.addView(differenceList, listParams);
        scroll.addView(content);
        return scroll;
    }

    private void refreshDifferencePage() {
        if (differenceList == null || !(diffPage instanceof ScrollView)) return;
        ScrollView scroll = (ScrollView) diffPage;
        LinearLayout content = (LinearLayout) scroll.getChildAt(0);
        if (content.getChildCount() > 1) {
            TextView desc = (TextView) content.getChildAt(1);
            desc.setText(resultCurrent
                    ? (diffCount == 0 ? "两段文字在当前比较规则下没有发现差异。"
                    : "点击任一差异项，返回双框并定位到对应文字。")
                    : "比较结果尚未更新，请返回对照页执行比较。");
        }
        View summaryCard = content.getChildAt(2);
        if (summaryCard instanceof LinearLayout && ((LinearLayout) summaryCard).getChildCount() > 0) {
            TextView summary = (TextView) ((LinearLayout) summaryCard).getChildAt(0);
            summary.setText(diffSummaryText());
        }
        differenceList.removeAllViews();
        if (!resultCurrent || diffCount == 0) {
            TextView empty = bodyLabel(!resultCurrent ? "请点击下方“对照”返回后运行比较。" : "当前没有需要核对的差异。");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(24), dp(12), dp(24));
            differenceList.addView(empty);
            return;
        }
        int shown = Math.min(diffs.size(), MAX_DIFF_CARDS);
        for (int i = 0; i < shown; i++) {
            final int index = i;
            final Diff d = diffs.get(i);
            LinearLayout card = makeCard();
            card.setPadding(dp(12), dp(11), dp(12), dp(11));
            TextView heading = new TextView(this);
            heading.setText("差异 " + (i + 1) + " / " + diffs.size() + "  ·  " + diffTypeName(d));
            heading.setTextColor(COLOR_PRIMARY);
            heading.setTextSize(12.5f);
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            card.addView(heading);
            LinearLayout.LayoutParams aParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            aParams.topMargin = dp(8);
            card.addView(buildDiffSide(contentA, d, true), aParams);
            LinearLayout.LayoutParams bParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bParams.topMargin = dp(6);
            card.addView(buildDiffSide(contentB, d, false), bParams);
            String change = markSummary(d);
            if (!change.isEmpty()) {
                TextView changeView = new TextView(this);
                changeView.setText(change);
                changeView.setTextColor(COLOR_MUTED);
                changeView.setTextSize(12f);
                LinearLayout.LayoutParams changeParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                changeParams.topMargin = dp(8);
                card.addView(changeView, changeParams);
            }
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v -> {
                activeDifference = index;
                showPage(0);
                moveToDifference(0);
            });
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(9);
            differenceList.addView(card, cardParams);
        }
        if (diffs.size() > shown) {
            TextView more = bodyLabel("仅列出前 " + shown + " 处差异，其余请在对照页用上一处/下一处查看。");
            more.setGravity(Gravity.CENTER);
            more.setPadding(dp(12), dp(8), dp(12), dp(16));
            differenceList.addView(more);
        }
    }

    private String diffSummaryText() {
        if (!resultCurrent) return "比较结果待更新";
        if (diffCount == 0) return "文本一致 · 0 处差异";
        int changed = 0, onlyA = 0, onlyB = 0;
        for (Diff d : diffs) {
            if (d.type == Diff.CHANGED) changed++;
            else if (d.type == Diff.ONLY_A) onlyA++;
            else onlyB++;
        }
        List<String> parts = new ArrayList<>();
        if (changed > 0) parts.add("内容不同 " + changed);
        if (onlyA > 0) parts.add("仅 A 有 " + onlyA);
        if (onlyB > 0) parts.add("仅 B 有 " + onlyB);
        return "发现 " + diffCount + " 处差异（" + android.text.TextUtils.join(" · ", parts) + "）";
    }

    private static String diffTypeName(Diff d) {
        if (d.type == Diff.ONLY_A) return "仅 A 有（B 中缺少）";
        if (d.type == Diff.ONLY_B) return "仅 B 有（A 中缺少）";
        return "两边内容不同";
    }

    /** One side (A or B) of a difference card: line numbers plus the block text with differing words coloured. */
    private View buildDiffSide(String text, Diff d, boolean sideA) {
        int bg = sideA ? COLOR_DIFF_A : COLOR_DIFF_B;
        int fg = sideA ? COLOR_DIFF_A_TEXT : COLOR_DIFF_B_TEXT;
        int lineFrom = sideA ? d.aLineFrom : d.bLineFrom;
        int lineTo = sideA ? d.aLineTo : d.bLineTo;
        String name = sideA ? "A" : "B";
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(9), dp(8), dp(9), dp(8));
        box.setBackground(roundRect(COLOR_EDITOR, dp(8), dp(1), COLOR_BORDER));
        TextView label = new TextView(this);
        label.setTextColor(fg);
        label.setTextSize(11.5f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (lineTo <= lineFrom) {
            label.setText(name + " · 此处没有对应内容");
            box.addView(label);
            return box;
        }
        label.setText(name + " · 第 " + (lineFrom + 1) + (lineTo - lineFrom > 1 ? "–" + lineTo : "") + " 行");
        box.addView(label);
        TextView body = new TextView(this);
        body.setText(blockPreview(text, d, sideA, bg, fg));
        body.setTextColor(COLOR_TEXT);
        body.setTextSize(13.5f);
        body.setLineSpacing(dp(2), 1.0f);
        body.setPadding(0, dp(4), 0, 0);
        box.addView(body);
        return box;
    }

    private CharSequence blockPreview(String text, Diff d, boolean sideA, int bg, int fg) {
        int bs = sideA ? d.aStart : d.bStart;
        int be = sideA ? d.aEnd : d.bEnd;
        List<Range> marks = sideA ? d.aMarks : d.bMarks;
        bs = Math.max(0, Math.min(bs, text.length()));
        be = Math.max(bs, Math.min(be, text.length()));
        while (be > bs && (text.charAt(be - 1) == '\n' || text.charAt(be - 1) == '\r')) be--;
        if (be <= bs) return "（空行）";
        final int maxChars = 700;
        int from = bs;
        int to = be;
        if (be - bs > maxChars) {
            int focus = marks.isEmpty() ? bs : Math.max(bs, marks.get(0).start);
            from = Math.max(bs, focus - 80);
            to = Math.min(be, from + maxChars);
            from = Math.max(bs, Math.min(from, to - maxChars));
        }
        String prefix = from > bs ? "…" : "";
        String suffix = to < be ? "…" : "";
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder(
                prefix + text.substring(from, to) + suffix);
        int shift = prefix.length() - from;
        for (Range r : marks) {
            int s0 = Math.max(r.start, from);
            int e0 = Math.min(r.end, to);
            if (e0 <= s0) continue;
            out.setSpan(new BackgroundColorSpan(bg), s0 + shift, e0 + shift, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new android.text.style.ForegroundColorSpan(fg), s0 + shift, e0 + shift,
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), s0 + shift, e0 + shift,
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    private String markSummary(Diff d) {
        if (d.type != Diff.CHANGED) return "";
        List<String> as = markTexts(contentA, d.aMarks);
        List<String> bs = markTexts(contentB, d.bMarks);
        if (as.isEmpty() && bs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("变化：");
        if (as.size() == bs.size()) {
            int n = Math.min(as.size(), 4);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append("；");
                sb.append("「").append(as.get(i)).append("」→「").append(bs.get(i)).append("」");
            }
            if (as.size() > n) sb.append("……共 ").append(as.size()).append(" 处");
        } else {
            sb.append("A「").append(android.text.TextUtils.join("」「", as.subList(0, Math.min(as.size(), 4))))
                    .append("」 / B「").append(android.text.TextUtils.join("」「", bs.subList(0, Math.min(bs.size(), 4))))
                    .append("」");
        }
        return sb.toString();
    }

    private static List<String> markTexts(String text, List<Range> marks) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < marks.size() && i < 8; i++) {
            Range r = marks.get(i);
            int start = Math.max(0, Math.min(r.start, text.length()));
            int end = Math.max(start, Math.min(r.end, text.length()));
            String value = text.substring(start, end).replace('\n', '↵').replace('\r', ' ');
            if (value.length() > 24) value = value.substring(0, 24) + "…";
            out.add(value.isEmpty() ? "（空）" : value);
        }
        return out;
    }

    private static int lineNumber(String text, Range range) {
        if (range == null || text == null) return 1;
        int end = Math.max(0, Math.min(range.start, text.length()));
        int line = 1;
        for (int i = 0; i < end; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    private static String excerpt(String text, Range range) {
        if (range == null || text == null || text.isEmpty()) return "（无对应内容）";
        int start = Math.max(0, Math.min(range.start, text.length()));
        int end = Math.max(start, Math.min(range.end, text.length()));
        int from = Math.max(0, start - 24);
        int to = Math.min(text.length(), Math.max(end, start + 1) + 24);
        String value = text.substring(from, to).replace('\n', '↵').replace('\r', ' ');
        return (from > 0 ? "…" : "") + value + (to < text.length() ? "…" : "");
    }

    private View buildSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BACKGROUND);
        LinearLayout content = pageContent();
        content.addView(pageTitle("设置"));
        TextView appearance = sectionLabel("显示设置");
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sectionParams.topMargin = dp(8);
        content.addView(appearance, sectionParams);
        LinearLayout themeCard = makeCard();
        themeCard.setOrientation(LinearLayout.HORIZONTAL);
        themeCard.setGravity(Gravity.CENTER_VERTICAL);
        themeCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        View themeDot = new View(this);
        themeDot.setBackground(roundRect(COLOR_PRIMARY, dp(7), 0, Color.TRANSPARENT));
        themeCard.addView(themeDot, new LinearLayout.LayoutParams(dp(14), dp(14)));
        LinearLayout themeText = new LinearLayout(this);
        themeText.setOrientation(LinearLayout.VERTICAL);
        TextView themeTitle = new TextView(this);
        themeTitle.setText("主题风格");
        themeTitle.setTextColor(COLOR_TEXT);
        themeTitle.setTextSize(14);
        themeTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        settingsThemeSummary = new TextView(this);
        settingsThemeSummary.setText(themeName(selectedTheme));
        settingsThemeSummary.setTextColor(COLOR_MUTED);
        settingsThemeSummary.setTextSize(11.5f);
        themeText.addView(themeTitle);
        themeText.addView(settingsThemeSummary);
        LinearLayout.LayoutParams themeTextParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        themeTextParams.leftMargin = dp(12);
        themeCard.addView(themeText, themeTextParams);
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(26);
        arrow.setTextColor(COLOR_MUTED);
        themeCard.addView(arrow);
        themeCard.setClickable(true);
        themeCard.setFocusable(true);
        themeCard.setOnClickListener(v -> showPage(3));
        addCardToPage(content, themeCard);

        LinearLayout fontCard = makeCard();
        fontCard.setOrientation(LinearLayout.HORIZONTAL);
        fontCard.setGravity(Gravity.CENTER_VERTICAL);
        fontCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView fontTitle = new TextView(this);
        fontTitle.setText("文字大小");
        fontTitle.setTextColor(COLOR_TEXT);
        fontTitle.setTextSize(13.5f);
        fontCard.addView(fontTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView fontMinus = toolbarButton("A−", false);
        TextView fontPlus = toolbarButton("A+", true);
        settingsFontValue = new TextView(this);
        settingsFontValue.setText(fontSizeSp + " sp");
        settingsFontValue.setTextColor(COLOR_MUTED);
        settingsFontValue.setTextSize(11.5f);
        settingsFontValue.setGravity(Gravity.CENTER);
        fontCard.addView(fontMinus, new LinearLayout.LayoutParams(dp(48), dp(36)));
        fontCard.addView(settingsFontValue, new LinearLayout.LayoutParams(dp(58), dp(36)));
        LinearLayout.LayoutParams plusParams = new LinearLayout.LayoutParams(dp(48), dp(36));
        plusParams.leftMargin = dp(4);
        fontCard.addView(fontPlus, plusParams);
        fontMinus.setOnClickListener(v -> changeFontSize(-1f));
        fontPlus.setOnClickListener(v -> changeFontSize(1f));
        addCardToPage(content, fontCard);

        LinearLayout alignCard = makeCard();
        alignCard.setOrientation(LinearLayout.HORIZONTAL);
        alignCard.setGravity(Gravity.CENTER_VERTICAL);
        alignCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView alignTitle = new TextView(this);
        alignTitle.setText("正文对齐");
        alignTitle.setTextColor(COLOR_TEXT);
        alignTitle.setTextSize(13.5f);
        alignCard.addView(alignTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        settingsAlignmentValue = new TextView(this);
        settingsAlignmentValue.setText(centered ? "居中对齐  ›" : "左对齐  ›");
        settingsAlignmentValue.setTextColor(COLOR_PRIMARY);
        settingsAlignmentValue.setTextSize(12.5f);
        alignCard.addView(settingsAlignmentValue);
        alignCard.setClickable(true);
        alignCard.setOnClickListener(v -> {
            centered = !centered;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("centered", centered).apply();
            applyTextAppearance();
            updateToolbarLabels();
            updateSettingsLabels();
        });
        addCardToPage(content, alignCard);

        TextView compareSettings = sectionLabel("比较设置");
        LinearLayout.LayoutParams compareSectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        compareSectionParams.topMargin = dp(16);
        content.addView(compareSettings, compareSectionParams);
        LinearLayout autoCard = makeCard();
        autoCard.setOrientation(LinearLayout.HORIZONTAL);
        autoCard.setGravity(Gravity.CENTER_VERTICAL);
        autoCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout autoText = new LinearLayout(this);
        autoText.setOrientation(LinearLayout.VERTICAL);
        TextView autoTitle = new TextView(this);
        autoTitle.setText("自动对比");
        autoTitle.setTextColor(COLOR_TEXT);
        autoTitle.setTextSize(14);
        autoTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView autoSubtitle = new TextView(this);
        autoSubtitle.setText("粘贴或完成编辑后自动刷新差异");
        autoSubtitle.setTextColor(COLOR_MUTED);
        autoSubtitle.setTextSize(11);
        autoText.addView(autoTitle);
        autoText.addView(autoSubtitle);
        autoCard.addView(autoText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        settingsAutoButton = toolbarButton(autoCompare ? "已开启" : "已关闭", autoCompare);
        autoCard.addView(settingsAutoButton, new LinearLayout.LayoutParams(dp(78), dp(36)));
        settingsAutoButton.setOnClickListener(v -> toggleAutoCompare());
        addCardToPage(content, autoCard);

        TextView actionsLabel = sectionLabel("文本操作");
        LinearLayout.LayoutParams actionsSectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsSectionParams.topMargin = dp(16);
        content.addView(actionsLabel, actionsSectionParams);
        TextView clearAll = toolbarButton("清空文本 A 和 B", false);
        clearAll.setTextColor(Color.rgb(180, 54, 54));
        clearAll.setBackground(roundRect(darkTheme ? Color.rgb(75, 40, 49) : Color.rgb(255, 238, 236),
                dp(10), dp(1), darkTheme ? Color.rgb(111, 57, 68) : Color.rgb(246, 207, 202)));
        clearAll.setOnClickListener(v -> confirmClearAll());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        clearParams.topMargin = dp(7);
        content.addView(clearAll, clearParams);

        TextView about = bodyLabel("双文对照 v2.1.0  ·  所有文字均在本机处理，不上传网络。");
        about.setGravity(Gravity.CENTER);
        about.setPadding(dp(8), dp(22), dp(8), dp(12));
        content.addView(about);
        scroll.addView(content);
        return scroll;
    }

    private View buildThemePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BACKGROUND);
        LinearLayout content = pageContent();
        LinearLayout headingRow = new LinearLayout(this);
        headingRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = pageTitle("主题风格");
        headingRow.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView back = toolbarButton("返回设置", false);
        headingRow.addView(back, new LinearLayout.LayoutParams(dp(88), dp(36)));
        back.setOnClickListener(v -> showPage(2));
        content.addView(headingRow);
        TextView description = bodyLabel("选择一种舒适的配色。主题会立即生效，并在下次打开应用时继续使用。");
        description.setPadding(0, dp(4), 0, dp(12));
        content.addView(description);
        String[] ids = {"fresh_blue", "dark_night", "warm_cream", "mint_green"};
        for (String id : ids) {
            LinearLayout item = makeCard();
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(13), dp(12), dp(13), dp(12));
            LinearLayout swatches = new LinearLayout(this);
            swatches.setOrientation(LinearLayout.VERTICAL);
            swatches.setGravity(Gravity.CENTER);
            int[] colors = previewColors(id);
            for (int color : colors) {
                View swatch = new View(this);
                swatch.setBackground(roundRect(color, dp(5), dp(1), Color.argb(35, 0, 0, 0)));
                LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(dp(28), dp(8));
                swatchParams.bottomMargin = dp(3);
                swatches.addView(swatch, swatchParams);
            }
            item.addView(swatches, new LinearLayout.LayoutParams(dp(36), dp(35)));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(themeName(id));
            name.setTextColor(COLOR_TEXT);
            name.setTextSize(14);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            TextView detail = new TextView(this);
            detail.setText(themeDescription(id));
            detail.setTextColor(COLOR_MUTED);
            detail.setTextSize(11);
            labels.addView(name);
            labels.addView(detail);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            labelParams.leftMargin = dp(10);
            item.addView(labels, labelParams);
            TextView selected = new TextView(this);
            selected.setText(selectedTheme.equals(id) ? "✓ 已选" : "选择");
            selected.setTextColor(selectedTheme.equals(id) ? Color.WHITE : COLOR_PRIMARY);
            selected.setTextSize(11.5f);
            selected.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            selected.setGravity(Gravity.CENTER);
            selected.setPadding(dp(9), dp(7), dp(9), dp(7));
            selected.setBackground(roundRect(selectedTheme.equals(id) ? COLOR_PRIMARY : COLOR_SOFT,
                    dp(18), 0, Color.TRANSPARENT));
            item.addView(selected);
            item.setClickable(true);
            item.setFocusable(true);
            item.setOnClickListener(v -> changeTheme(id));
            addCardToPage(content, item);
        }
        TextView note = bodyLabel("切换主题不会清除文本、重置差异结果或改变两栏布局和滚动位置。");
        note.setPadding(dp(3), dp(12), dp(3), dp(8));
        content.addView(note);
        scroll.addView(content);
        return scroll;
    }

    private int[] previewColors(String id) {
        switch (id) {
            case "dark_night": return new int[]{Color.rgb(18, 30, 51), Color.rgb(28, 39, 57), Color.rgb(105, 158, 255)};
            case "warm_cream": return new int[]{Color.rgb(119, 79, 46), Color.rgb(255, 252, 245), Color.rgb(139, 94, 55)};
            case "mint_green": return new int[]{Color.rgb(15, 103, 76), Color.rgb(237, 247, 241), Color.rgb(24, 131, 95)};
            default: return new int[]{Color.rgb(35, 79, 179), Color.WHITE, Color.rgb(38, 91, 205)};
        }
    }

    private void changeTheme(String themeId) {
        if (!isKnownTheme(themeId)) {
            showPage(2);
            return;
        }
        if (themeId.equals(selectedTheme)) {
            showPage(3);
            return;
        }
        int oldScrollA = scrollA == null ? 0 : scrollA.getScrollY();
        int oldScrollB = scrollB == null ? 0 : scrollB.getScrollY();
        int returnPage = currentPage;
        selectedTheme = themeId;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_THEME, selectedTheme).apply();
        applyPaletteValues(selectedTheme);
        configureSystemBars();
        buildScreen();
        renderPlainText();
        if (resultCurrent) applyHighlightsPreservingScroll();
        updateCounts();
        applyTextAppearance();
        updateToolbarLabels();
        updateComparisonStatus(comparisonStatusText);
        showPage(returnPage == 3 ? 3 : returnPage);
        scrollA.post(() -> scrollA.scrollTo(0, oldScrollA));
        scrollB.post(() -> scrollB.scrollTo(0, oldScrollB));
    }

    private LinearLayout pageContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(13), dp(14), dp(18));
        return content;
    }

    private TextView pageTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(3), 0, dp(7));
        return title;
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(COLOR_MUTED);
        label.setTextSize(12);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(dp(2), dp(2), dp(2), dp(7));
        return label;
    }

    private TextView bodyLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(COLOR_MUTED);
        label.setTextSize(12);
        return label;
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundRect(COLOR_SURFACE, dp(14), dp(1), COLOR_BORDER));
        card.setElevation(dp(1));
        return card;
    }

    private void addCardToPage(LinearLayout page, View card) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        page.addView(card, params);
    }

    private void updateSettingsLabels() {
        if (settingsThemeSummary != null) settingsThemeSummary.setText(themeName(selectedTheme));
        if (settingsFontValue != null) settingsFontValue.setText(String.format(java.util.Locale.ROOT, "%.0f sp", fontSizeSp));
        if (settingsAlignmentValue != null) settingsAlignmentValue.setText(centered ? "居中对齐  ›" : "左对齐  ›");
        if (settingsAutoButton != null) {
            settingsAutoButton.setText(autoCompare ? "已开启" : "已关闭");
            settingsAutoButton.setTextColor(autoCompare ? Color.WHITE : COLOR_PRIMARY);
            settingsAutoButton.setBackground(roundRect(autoCompare ? COLOR_PRIMARY : COLOR_SOFT,
                    dp(18), 0, Color.TRANSPARENT));
        }
    }

    private void toggleAutoCompare() {
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
        updateSettingsLabels();
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
        button.setBackground(roundRect(primary ? COLOR_PRIMARY : COLOR_SOFT,
                dp(10), primary ? 0 : dp(1), primary ? Color.TRANSPARENT : COLOR_BORDER));
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
            autoButton.setBackground(roundRect(autoCompare ? COLOR_PRIMARY : COLOR_SOFT,
                    dp(10), autoCompare ? 0 : dp(1), autoCompare ? Color.TRANSPARENT : COLOR_BORDER));
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
        updateSettingsLabels();
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
            AlertDialog confirmation = new AlertDialog.Builder(this)
                    .setTitle("替换文本 " + (side == 0 ? "A" : "B") + "？")
                    .setMessage("粘贴会替换这一栏现有的全部文字。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("替换并粘贴", (d, which) -> updateContent(side, value))
                    .create();
            showThemedAlert(confirmation);
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
        AlertDialog confirmation = new AlertDialog.Builder(this)
                .setTitle("清空文本 " + (side == 0 ? "A" : "B") + "？")
                .setMessage("只会清除这一栏的文字。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> updateContent(side, ""))
                .create();
        showThemedAlert(confirmation);
    }

    private void confirmClearAll() {
        if (contentA.isEmpty() && contentB.isEmpty()) return;
        AlertDialog confirmation = new AlertDialog.Builder(this)
                .setTitle("清空全部文字？")
                .setMessage("文本 A 和文本 B 都会被清除，此操作无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("全部清空", (dialog, which) -> {
                    updateContent(0, "");
                    updateContent(1, "");
                    hideKeyboard();
                })
                .create();
        showThemedAlert(confirmation);
    }

    private void showThemedAlert(AlertDialog dialog) {
        dialog.setOnShowListener(ignored -> {
            Window alertWindow = dialog.getWindow();
            if (alertWindow != null) {
                alertWindow.setBackgroundDrawable(roundRect(COLOR_SURFACE, dp(18), dp(1), COLOR_BORDER));
            }
            int titleId = getResources().getIdentifier("alertTitle", "id", "android");
            TextView title = titleId == 0 ? null : dialog.findViewById(titleId);
            TextView message = dialog.findViewById(android.R.id.message);
            if (title != null) title.setTextColor(COLOR_TEXT);
            if (message != null) message.setTextColor(COLOR_MUTED);
            Button positive = dialog.getButton(Dialog.BUTTON_POSITIVE);
            Button negative = dialog.getButton(Dialog.BUTTON_NEGATIVE);
            Button neutral = dialog.getButton(Dialog.BUTTON_NEUTRAL);
            if (positive != null) positive.setTextColor(COLOR_PRIMARY);
            if (negative != null) negative.setTextColor(COLOR_PRIMARY);
            if (neutral != null) neutral.setTextColor(COLOR_PRIMARY);
        });
        dialog.show();
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
                AlertDialog confirmation = new AlertDialog.Builder(this).setTitle("替换编辑区文字？")
                        .setMessage("粘贴会替换编辑区中的全部文字。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("替换", (d, w) -> editor.setText(value)).create();
                showThemedAlert(confirmation);
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
            window.setBackgroundDrawable(roundRect(COLOR_BACKGROUND, dp(16), 0, Color.TRANSPARENT));
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
        LATEST_GENERATION = generation;
        updateComparisonStatus("正在比较……");
        compareFuture = compareExecutor.submit(() -> {
            ComparisonResult computed = null;
            Throwable failure = null;
            TASK_GENERATION.set(generation);
            try {
                computed = computeDiff(a, b);
            } catch (CancellationException superseded) {
                return;
            } catch (Throwable t) {
                failure = t;
            } finally {
                TASK_GENERATION.remove();
            }
            final ComparisonResult result = computed;
            final Throwable error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || revision != contentRevision || generation != compareGeneration) return;
                if (error != null || result == null) {
                    clearHighlights();
                    updateComparisonStatus("比较失败，请点击“比较”重试");
                    updateToolbarLabels();
                    return;
                }
                diffs = result.diffs;
                diffA = result.aRanges;
                diffB = result.bRanges;
                diffCount = diffs.size();
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
        LATEST_GENERATION = compareGeneration;
        if (compareFuture != null) {
            compareFuture.cancel(false);
            compareFuture = null;
        }
    }

    private void clearHighlights() {
        diffs = new ArrayList<>();
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
        textA.setText(highlight(contentA, diffA, COLOR_DIFF_A, COLOR_DIFF_A_TEXT), TextView.BufferType.SPANNABLE);
        textB.setText(highlight(contentB, diffB, COLOR_DIFF_B, COLOR_DIFF_B_TEXT), TextView.BufferType.SPANNABLE);
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
        comparisonStatusText = value == null ? "" : value;
        if (statusLabel != null) statusLabel.setText(comparisonStatusText);
        if (differenceList != null && currentPage == 1) refreshDifferencePage();
    }

    private void moveToDifference(int direction) {
        if (!resultCurrent || diffCount == 0 || diffs.isEmpty()) return;
        activeDifference = ((activeDifference + direction) % diffs.size() + diffs.size()) % diffs.size();
        Diff d = diffs.get(activeDifference);
        if (d.aEnd > d.aStart || d.type == Diff.ONLY_B) scrollToOffset(scrollA, textA, d.aStart);
        if (d.bEnd > d.bStart || d.type == Diff.ONLY_A) scrollToOffset(scrollB, textB, d.bStart);
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
        outState.putInt(STATE_CURRENT_PAGE, currentPage);
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

    private static final ThreadLocal<Long> TASK_GENERATION = new ThreadLocal<>();
    private static volatile long LATEST_GENERATION;
    private static final long TOKEN_LCS_CELL_LIMIT = 1_000_000L;
    private static final int MAX_DIFF_CARDS = 200;

    private static final class Range {
        final int start;
        final int end;
        Range(int start, int end) { this.start = start; this.end = end; }
    }

    /** One changed block: a run of lines that differ between A and B. */
    private static final class Diff {
        static final int CHANGED = 0;
        static final int ONLY_A = 1;
        static final int ONLY_B = 2;
        final int type;
        final int aLineFrom, aLineTo, bLineFrom, bLineTo; // 0-based lines, end exclusive
        final int aStart, aEnd, bStart, bEnd;             // character offsets of the whole block
        final List<Range> aMarks = new ArrayList<>();     // fine-grained differing text in A
        final List<Range> bMarks = new ArrayList<>();     // fine-grained differing text in B
        Diff(int type, int aLineFrom, int aLineTo, int bLineFrom, int bLineTo,
             int aStart, int aEnd, int bStart, int bEnd) {
            this.type = type;
            this.aLineFrom = aLineFrom; this.aLineTo = aLineTo;
            this.bLineFrom = bLineFrom; this.bLineTo = bLineTo;
            this.aStart = aStart; this.aEnd = aEnd;
            this.bStart = bStart; this.bEnd = bEnd;
        }
    }

    private static final class ComparisonResult {
        final List<Diff> diffs = new ArrayList<>();
        final List<Range> aRanges = new ArrayList<>();
        final List<Range> bRanges = new ArrayList<>();
    }

    private static final class Line {
        final String key;   // content without the trailing line ending
        final int start;
        final int end;
        Line(String key, int start, int end) { this.key = key; this.start = start; this.end = end; }
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
            addBlock(a, b, linesA, ai, anchor.a, linesB, bi, anchor.b, result);
            ai = anchor.a + 1;
            bi = anchor.b + 1;
        }
        addBlock(a, b, linesA, ai, linesA.size(), linesB, bi, linesB.size(), result);
        for (Diff d : result.diffs) {
            result.aRanges.addAll(d.aMarks);
            result.bRanges.addAll(d.bMarks);
        }
        mergeRanges(result.aRanges);
        mergeRanges(result.bRanges);
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
                lines.add(new Line(stripLineEnding(text.substring(start, end)), start, end));
                start = end;
            }
        }
        if (start < text.length()) lines.add(new Line(stripLineEnding(text.substring(start)), start, text.length()));
        return lines;
    }

    private static String stripLineEnding(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '\n' || value.charAt(end - 1) == '\r')) end--;
        return end == value.length() ? value : value.substring(0, end);
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
                    dp[i][j] = a.get(i).key.equals(b.get(j).key)
                            ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
            int i = 0, j = 0;
            int steps = 0;
            while (i < n && j < m) {
                if ((steps++ & 1023) == 0) checkInterrupted();
                if (a.get(i).key.equals(b.get(j).key)) {
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
            positions.computeIfAbsent(b.get(j).key, k -> new ArrayDeque<>()).add(j);
        }
        int lastB = -1;
        for (int i = 0; i < n; i++) {
            if ((i & 1023) == 0) checkInterrupted();
            ArrayDeque<Integer> queue = positions.get(a.get(i).key);
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

    /** Records one block of unmatched lines between two anchors as a Diff. */
    private static void addBlock(String a, String b, List<Line> la, int aFrom, int aTo,
                                 List<Line> lb, int bFrom, int bTo, ComparisonResult result) {
        boolean hasA = aTo > aFrom;
        boolean hasB = bTo > bFrom;
        if (!hasA && !hasB) return;
        checkInterrupted();
        int aStart = aFrom < la.size() ? la.get(aFrom).start : a.length();
        int aEnd = hasA ? la.get(aTo - 1).end : aStart;
        int bStart = bFrom < lb.size() ? lb.get(bFrom).start : b.length();
        int bEnd = hasB ? lb.get(bTo - 1).end : bStart;
        int type = hasA && hasB ? Diff.CHANGED : hasA ? Diff.ONLY_A : Diff.ONLY_B;
        Diff d = new Diff(type, aFrom, aTo, bFrom, bTo, aStart, aEnd, bStart, bEnd);
        if (hasA && hasB) {
            if (aTo - aFrom == bTo - bFrom && aTo - aFrom > 1) {
                // Same number of lines on both sides: compare them pairwise.
                for (int k = 0; k < aTo - aFrom; k++) {
                    Line x = la.get(aFrom + k);
                    Line y = lb.get(bFrom + k);
                    markTokens(a, x.start, x.end, b, y.start, y.end, d);
                }
            } else {
                markTokens(a, aStart, aEnd, b, bStart, bEnd, d);
            }
            if (d.aMarks.isEmpty() && d.bMarks.isEmpty()) {
                addMark(a, d.aMarks, aStart, aEnd);
                addMark(b, d.bMarks, bStart, bEnd);
            }
        } else if (hasA) {
            addMark(a, d.aMarks, aStart, aEnd);
        } else {
            addMark(b, d.bMarks, bStart, bEnd);
        }
        result.diffs.add(d);
    }

    /** Word/character level diff of two text regions; unmatched tokens become marks. */
    private static void markTokens(String a, int aFrom, int aTo, String b, int bFrom, int bTo, Diff d) {
        List<int[]> ta = tokenize(a, aFrom, aTo);
        List<int[]> tb = tokenize(b, bFrom, bTo);
        int n = ta.size();
        int m = tb.size();
        int pre = 0;
        while (pre < n && pre < m && sameToken(a, ta.get(pre), b, tb.get(pre))) pre++;
        int suf = 0;
        while (suf < n - pre && suf < m - pre
                && sameToken(a, ta.get(n - 1 - suf), b, tb.get(m - 1 - suf))) suf++;
        boolean[] matchedA = new boolean[n];
        boolean[] matchedB = new boolean[m];
        for (int i = 0; i < pre; i++) { matchedA[i] = true; matchedB[i] = true; }
        for (int k = 0; k < suf; k++) { matchedA[n - 1 - k] = true; matchedB[m - 1 - k] = true; }
        int cn = n - pre - suf;
        int cm = m - pre - suf;
        if (cn > 0 && cm > 0 && (long) cn * (long) cm <= TOKEN_LCS_CELL_LIMIT) {
            int[][] dp = new int[cn + 1][cm + 1];
            for (int i = cn - 1; i >= 0; i--) {
                checkInterrupted();
                for (int j = cm - 1; j >= 0; j--) {
                    dp[i][j] = sameToken(a, ta.get(pre + i), b, tb.get(pre + j))
                            ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
            int i = 0, j = 0;
            while (i < cn && j < cm) {
                if (sameToken(a, ta.get(pre + i), b, tb.get(pre + j))) {
                    matchedA[pre + i] = true;
                    matchedB[pre + j] = true;
                    i++; j++;
                } else if (dp[i + 1][j] >= dp[i][j + 1]) i++;
                else j++;
            }
        }
        collectMarks(a, ta, matchedA, d.aMarks);
        collectMarks(b, tb, matchedB, d.bMarks);
    }

    private static boolean sameToken(String a, int[] x, String b, int[] y) {
        int len = x[1] - x[0];
        return len == y[1] - y[0] && a.regionMatches(x[0], b, y[0], len);
    }

    private static void collectMarks(String s, List<int[]> tokens, boolean[] matched, List<Range> out) {
        int runStart = -1;
        int runEnd = -1;
        for (int k = 0; k <= tokens.size(); k++) {
            boolean unmatched = k < tokens.size() && !matched[k];
            if (unmatched) {
                int[] t = tokens.get(k);
                if (runStart < 0) runStart = t[0];
                runEnd = t[1];
            } else if (runStart >= 0) {
                addMark(s, out, runStart, runEnd);
                runStart = -1;
            }
        }
    }

    private static void addMark(String s, List<Range> out, int start, int end) {
        while (end > start && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) end--;
        while (start < end && (s.charAt(start) == '\n' || s.charAt(start) == '\r')) start++;
        if (end <= start) return;
        if (!out.isEmpty()) {
            Range last = out.get(out.size() - 1);
            if (last.end <= start && isBlankGap(s, last.end, start)) {
                out.set(out.size() - 1, new Range(last.start, end));
                return;
            }
        }
        out.add(new Range(start, end));
    }

    private static boolean isBlankGap(String s, int from, int to) {
        if (to - from > 3) return false;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\u3000') return false;
        }
        return true;
    }

    /** Words/numbers are one token, each CJK character or symbol is its own token. */
    private static List<int[]> tokenize(String s, int from, int to) {
        List<int[]> tokens = new ArrayList<>();
        int i = from;
        int steps = 0;
        while (i < to) {
            if ((steps++ & 4095) == 0) checkInterrupted();
            int cp = s.codePointAt(i);
            int j = i + Character.charCount(cp);
            if (cp == '\n' || cp == '\r') {
                // a line break is always its own token
            } else if (Character.isWhitespace(cp)) {
                while (j < to) {
                    int c = s.codePointAt(j);
                    if (c == '\n' || c == '\r' || !Character.isWhitespace(c)) break;
                    j += Character.charCount(c);
                }
            } else if (isWordChar(cp)) {
                while (j < to) {
                    int c = s.codePointAt(j);
                    if (!isWordChar(c)) break;
                    j += Character.charCount(c);
                }
            }
            tokens.add(new int[]{i, j});
            i = j;
        }
        return tokens;
    }

    private static boolean isWordChar(int cp) {
        return (Character.isLetterOrDigit(cp) || cp == '_') && !isCjk(cp);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x2E80 && cp <= 0x9FFF) || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFF00 && cp <= 0xFFEF) || (cp >= 0x20000 && cp <= 0x2FFFF);
    }

    private static void checkInterrupted() {
        Long generation = TASK_GENERATION.get();
        if (generation != null && generation.longValue() != LATEST_GENERATION) {
            throw new CancellationException("Comparison superseded");
        }
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
