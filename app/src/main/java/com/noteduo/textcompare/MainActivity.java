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
            summary.setText(!resultCurrent ? "比较结果待更新"
                    : diffCount == 0 ? "文本一致 · 0 处差异" : "发现 " + diffCount + " 处差异");
        }
        differenceList.removeAllViews();
        if (!resultCurrent || diffCount == 0) {
            TextView empty = bodyLabel(!resultCurrent ? "请点击下方“对照”返回后运行比较。" : "当前没有需要核对的差异。");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(24), dp(12), dp(24));
            differenceList.addView(empty);
            return;
        }
        for (int i = 0; i < diffCount; i++) {
            final int index = i;
            Range ra = i < diffA.size() ? diffA.get(i) : null;
            Range rb = i < diffB.size() ? diffB.get(i) : null;
            LinearLayout card = makeCard();
            card.setPadding(dp(12), dp(11), dp(12), dp(11));
            TextView heading = new TextView(this);
            heading.setText("差异 " + (i + 1) + "  ·  A 第 " + lineNumber(contentA, ra)
                    + " 行 / B 第 " + lineNumber(contentB, rb) + " 行");
            heading.setTextColor(COLOR_PRIMARY);
            heading.setTextSize(12);
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            card.addView(heading);
            TextView aText = new TextView(this);
            aText.setText("A  " + excerpt(contentA, ra));
            aText.setTextColor(COLOR_DIFF_A_TEXT);
            aText.setBackground(roundRect(COLOR_DIFF_A, dp(8), 0, Color.TRANSPARENT));
            aText.setTextSize(12.5f);
            aText.setPadding(dp(9), dp(8), dp(9), dp(8));
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            textParams.topMargin = dp(8);
            card.addView(aText, textParams);
            TextView bText = new TextView(this);
            bText.setText("B  " + excerpt(contentB, rb));
            bText.setTextColor(COLOR_DIFF_B_TEXT);
            bText.setBackground(roundRect(COLOR_DIFF_B, dp(8), 0, Color.TRANSPARENT));
            bText.setTextSize(12.5f);
            bText.setPadding(dp(9), dp(8), dp(9), dp(8));
            LinearLayout.LayoutParams bParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bParams.topMargin = dp(5);
            card.addView(bText, bParams);
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
        comparisonStatusText = value == null ? "" : value;
        if (statusLabel != null) statusLabel.setText(comparisonStatusText);
        if (differenceList != null && currentPage == 1) refreshDifferencePage();
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
