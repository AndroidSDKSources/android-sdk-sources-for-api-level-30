/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.bubbles;

import static com.android.systemui.bubbles.BubbleDebugConfig.DEBUG_OVERFLOW;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Activity for showing aged out bubbles.
 * Must be public to be accessible to androidx...AppComponentFactory
 */
public class BubbleOverflowActivity extends Activity {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleOverflowActivity" : TAG_BUBBLES;

    private LinearLayout mEmptyState;
    private TextView mEmptyStateTitle;
    private TextView mEmptyStateSubtitle;
    private ImageView mEmptyStateImage;
    private BubbleController mBubbleController;
    private BubbleOverflowAdapter mAdapter;
    private RecyclerView mRecyclerView;
    private List<Bubble> mOverflowBubbles = new ArrayList<>();

    private class NoScrollGridLayoutManager extends GridLayoutManager {
        NoScrollGridLayoutManager(Context context, int columns) {
            super(context, columns);
        }
        @Override
        public boolean canScrollVertically() {
            if (mBubbleController.inLandscape()) {
                return super.canScrollVertically();
            }
            return false;
        }

        @Override
        public int getColumnCountForAccessibility(RecyclerView.Recycler recycler,
                RecyclerView.State state) {
            int bubbleCount = state.getItemCount();
            int columnCount = super.getColumnCountForAccessibility(recycler, state);
            if (bubbleCount < columnCount) {
                // If there are 4 columns and bubbles <= 3,
                // TalkBack says "AppName 1 of 4 in list 4 items"
                // This is a workaround until TalkBack bug is fixed for GridLayoutManager
                return bubbleCount;
            }
            return columnCount;
        }
    }

    @Inject
    public BubbleOverflowActivity(BubbleController controller) {
        mBubbleController = controller;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bubble_overflow_activity);

        mRecyclerView = findViewById(R.id.bubble_overflow_recycler);
        mEmptyState = findViewById(R.id.bubble_overflow_empty_state);
        mEmptyStateTitle = findViewById(R.id.bubble_overflow_empty_title);
        mEmptyStateSubtitle = findViewById(R.id.bubble_overflow_empty_subtitle);
        mEmptyStateImage = findViewById(R.id.bubble_overflow_empty_state_image);

        updateDimensions();
        onDataChanged(mBubbleController.getOverflowBubbles());
        mBubbleController.setOverflowCallback(() -> {
            onDataChanged(mBubbleController.getOverflowBubbles());
        });
    }

    void updateDimensions() {
        Resources res = getResources();
        final int columns = res.getInteger(R.integer.bubbles_overflow_columns);
        mRecyclerView.setLayoutManager(
                new NoScrollGridLayoutManager(getApplicationContext(), columns));

        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

        final int overflowPadding = res.getDimensionPixelSize(R.dimen.bubble_overflow_padding);
        final int recyclerViewWidth = displayMetrics.widthPixels - (overflowPadding * 2);
        final int viewWidth = recyclerViewWidth / columns;

        final int maxOverflowBubbles = res.getInteger(R.integer.bubbles_max_overflow);
        final int rows = (int) Math.ceil((double) maxOverflowBubbles / columns);
        final int recyclerViewHeight = res.getDimensionPixelSize(R.dimen.bubble_overflow_height)
                - res.getDimensionPixelSize(R.dimen.bubble_overflow_padding);
        final int viewHeight = recyclerViewHeight / rows;

        mAdapter = new BubbleOverflowAdapter(getApplicationContext(), mOverflowBubbles,
                mBubbleController::promoteBubbleFromOverflow, viewWidth, viewHeight);
        mRecyclerView.setAdapter(mAdapter);
    }

    /**
     * Handle theme changes.
     */
    void updateTheme() {
        Resources res = getResources();
        final int mode = res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        final boolean isNightMode = (mode == Configuration.UI_MODE_NIGHT_YES);

        mEmptyStateImage.setImageDrawable(isNightMode
                ? res.getDrawable(R.drawable.ic_empty_bubble_overflow_dark)
                : res.getDrawable(R.drawable.ic_empty_bubble_overflow_light));

        findViewById(android.R.id.content)
                .setBackgroundColor(isNightMode
                        ? res.getColor(R.color.bubbles_dark)
                        : res.getColor(R.color.bubbles_light));

        final TypedArray typedArray = getApplicationContext().obtainStyledAttributes(
                new int[]{android.R.attr.colorBackgroundFloating,
                        android.R.attr.textColorSecondary});
        int bgColor = typedArray.getColor(0, isNightMode ? Color.BLACK : Color.WHITE);
        int textColor = typedArray.getColor(1, isNightMode ? Color.WHITE : Color.BLACK);
        textColor = ContrastColorUtil.ensureTextContrast(textColor, bgColor, isNightMode);
        typedArray.recycle();

        mEmptyStateTitle.setTextColor(textColor);
        mEmptyStateSubtitle.setTextColor(textColor);
    }

    void onDataChanged(List<Bubble> bubbles) {
        mOverflowBubbles.clear();
        mOverflowBubbles.addAll(bubbles);
        mAdapter.notifyDataSetChanged();

        if (mOverflowBubbles.isEmpty()) {
            mEmptyState.setVisibility(View.VISIBLE);
        } else {
            mEmptyState.setVisibility(View.GONE);
        }

        if (DEBUG_OVERFLOW) {
            Log.d(TAG, "Updated overflow bubbles:\n" + BubbleDebugConfig.formatBubblesString(
                    mOverflowBubbles, /*selected*/ null));
        }
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onRestart() {
        super.onRestart();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateDimensions();
        updateTheme();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    public void onDestroy() {
        super.onDestroy();
    }
}

class BubbleOverflowAdapter extends RecyclerView.Adapter<BubbleOverflowAdapter.ViewHolder> {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleOverflowAdapter" : TAG_BUBBLES;

    private Context mContext;
    private Consumer<Bubble> mPromoteBubbleFromOverflow;
    private List<Bubble> mBubbles;
    private int mWidth;
    private int mHeight;

    public BubbleOverflowAdapter(Context context, List<Bubble> list, Consumer<Bubble> promoteBubble,
            int width, int height) {
        mContext = context;
        mBubbles = list;
        mPromoteBubbleFromOverflow = promoteBubble;
        mWidth = width;
        mHeight = height;
    }

    @Override
    public BubbleOverflowAdapter.ViewHolder onCreateViewHolder(ViewGroup parent,
            int viewType) {

        // Set layout for overflow bubble view.
        LinearLayout overflowView = (LinearLayout) LayoutInflater.from(parent.getContext())
                .inflate(R.layout.bubble_overflow_view, parent, false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.width = mWidth;
        params.height = mHeight;
        overflowView.setLayoutParams(params);

        // Ensure name has enough contrast.
        final TypedArray ta = mContext.obtainStyledAttributes(
                new int[]{android.R.attr.colorBackgroundFloating, android.R.attr.textColorPrimary});
        final int bgColor = ta.getColor(0, Color.WHITE);
        int textColor = ta.getColor(1, Color.BLACK);
        textColor = ContrastColorUtil.ensureTextContrast(textColor, bgColor, true);
        ta.recycle();

        TextView viewName = overflowView.findViewById(R.id.bubble_view_name);
        viewName.setTextColor(textColor);

        return new ViewHolder(overflowView);
    }

    @Override
    public void onBindViewHolder(ViewHolder vh, int index) {
        Bubble b = mBubbles.get(index);

        vh.iconView.setRenderedBubble(b);
        vh.iconView.removeDotSuppressionFlag(BadgedImageView.SuppressionFlag.FLYOUT_VISIBLE);
        vh.iconView.setOnClickListener(view -> {
            mBubbles.remove(b);
            notifyDataSetChanged();
            mPromoteBubbleFromOverflow.accept(b);
        });

        String titleStr = b.getTitle();
        if (titleStr == null) {
            titleStr = mContext.getResources().getString(R.string.notification_bubble_title);
        }
        vh.iconView.setContentDescription(mContext.getResources().getString(
                R.string.bubble_content_description_single, titleStr, b.getAppName()));

        vh.iconView.setAccessibilityDelegate(
                new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(View host,
                            AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        // Talkback prompts "Double tap to add back to stack"
                        // instead of the default "Double tap to activate"
                        info.addAction(
                                new AccessibilityNodeInfo.AccessibilityAction(
                                        AccessibilityNodeInfo.ACTION_CLICK,
                                        mContext.getResources().getString(
                                                R.string.bubble_accessibility_action_add_back)));
                    }
                });

        CharSequence label = b.getShortcutInfo() != null
                ? b.getShortcutInfo().getLabel()
                : b.getAppName();
        vh.textView.setText(label);
    }

    @Override
    public int getItemCount() {
        return mBubbles.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public BadgedImageView iconView;
        public TextView textView;

        public ViewHolder(LinearLayout v) {
            super(v);
            iconView = v.findViewById(R.id.bubble_view);
            textView = v.findViewById(R.id.bubble_view_name);
        }
    }
}