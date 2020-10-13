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

import static android.view.Display.INVALID_DISPLAY;
import static android.view.View.GONE;

import static com.android.systemui.bubbles.BadgedImageView.DEFAULT_PATH_SIZE;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import android.util.PathParser;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.android.systemui.R;

/**
 * Class for showing aged out bubbles.
 */
public class BubbleOverflow implements BubbleViewProvider {
    public static final String KEY = "Overflow";

    private BadgedImageView mOverflowBtn;
    private BubbleExpandedView mExpandedView;
    private LayoutInflater mInflater;
    private Context mContext;
    private Bitmap mIcon;
    private Path mPath;
    private int mBitmapSize;
    private int mIconBitmapSize;
    private int mDotColor;

    public BubbleOverflow(Context context) {
        mContext = context;
        mInflater = LayoutInflater.from(context);
    }

    void setUpOverflow(ViewGroup parentViewGroup, BubbleStackView stackView) {
        updateDimensions();
        mExpandedView = (BubbleExpandedView) mInflater.inflate(
                R.layout.bubble_expanded_view, parentViewGroup /* root */,
                false /* attachToRoot */);
        mExpandedView.setOverflow(true);
        mExpandedView.setStackView(stackView);
        mExpandedView.applyThemeAttrs();
        updateIcon(mContext, parentViewGroup);
    }

    void updateDimensions() {
        mBitmapSize = mContext.getResources().getDimensionPixelSize(R.dimen.bubble_bitmap_size);
        mIconBitmapSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.bubble_overflow_icon_bitmap_size);
        if (mExpandedView != null) {
            mExpandedView.updateDimensions();
        }
    }

    void updateIcon(Context context, ViewGroup parentViewGroup) {
        mContext = context;
        mInflater = LayoutInflater.from(context);
        mOverflowBtn = (BadgedImageView) mInflater.inflate(R.layout.bubble_overflow_button,
                parentViewGroup /* root */,
                false /* attachToRoot */);
        mOverflowBtn.setContentDescription(mContext.getResources().getString(
                R.string.bubble_overflow_button_content_description));
        Resources res = mContext.getResources();

        // Set color for button icon and dot
        TypedValue typedValue = new TypedValue();
        mContext.getTheme().resolveAttribute(android.R.attr.colorAccent, typedValue, true);
        int colorAccent = mContext.getColor(typedValue.resourceId);
        mOverflowBtn.getDrawable().setTint(colorAccent);
        mDotColor = colorAccent;

        // Set color for button and activity background
        ColorDrawable bg = new ColorDrawable(res.getColor(R.color.bubbles_light));
        final int mode = res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (mode == Configuration.UI_MODE_NIGHT_YES) {
            bg = new ColorDrawable(res.getColor(R.color.bubbles_dark));
        }

        // Apply icon inset
        InsetDrawable fg = new InsetDrawable(mOverflowBtn.getDrawable(),
                mBitmapSize - mIconBitmapSize /* inset */);
        AdaptiveIconDrawable adaptiveIconDrawable = new AdaptiveIconDrawable(bg, fg);

        BubbleIconFactory iconFactory = new BubbleIconFactory(mContext);
        mIcon = iconFactory.createBadgedIconBitmap(adaptiveIconDrawable,
                null /* user */,
                true /* shrinkNonAdaptiveIcons */).icon;

        // Get path with dot location
        float scale = iconFactory.getNormalizer().getScale(mOverflowBtn.getDrawable(),
                null /* outBounds */, null /* path */, null /* outMaskShape */);
        float radius = DEFAULT_PATH_SIZE / 2f;
        mPath = PathParser.createPathFromPathData(
                mContext.getResources().getString(com.android.internal.R.string.config_icon_mask));
        Matrix matrix = new Matrix();
        matrix.setScale(scale /* x scale */, scale /* y scale */, radius /* pivot x */,
                radius /* pivot y */);
        mPath.transform(matrix);

        mOverflowBtn.setRenderedBubble(this);
    }

    void setVisible(int visible) {
        mOverflowBtn.setVisibility(visible);
    }

    @Override
    public BubbleExpandedView getExpandedView() {
        return mExpandedView;
    }

    @Override
    public int getDotColor() {
        return mDotColor;
    }

    @Override
    public Bitmap getBadgedImage() {
        return mIcon;
    }

    @Override
    public boolean showDot() {
        return false;
    }

    @Override
    public Path getDotPath() {
        return mPath;
    }

    @Override
    public void setContentVisibility(boolean visible) {
        mExpandedView.setContentVisibility(visible);
    }

    @Override
    public void logUIEvent(int bubbleCount, int action, float normalX, float normalY,
            int index) {
        // TODO(b/149133814) Log overflow UI events.
    }

    @Override
    public View getIconView() {
        return mOverflowBtn;
    }

    @Override
    public String getKey() {
        return BubbleOverflow.KEY;
    }

    @Override
    public int getDisplayId() {
        return mExpandedView != null ? mExpandedView.getVirtualDisplayId() : INVALID_DISPLAY;
    }
}
