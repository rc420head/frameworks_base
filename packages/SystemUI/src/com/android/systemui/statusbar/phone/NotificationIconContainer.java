/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.AlphaOptimizedFrameLayout;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.stack.AnimationFilter;
import com.android.systemui.statusbar.stack.AnimationProperties;
import com.android.systemui.statusbar.stack.ViewState;

import java.util.HashMap;

/**
 * A container for notification icons. It handles overflowing icons properly and positions them
 * correctly on the screen.
 */
public class NotificationIconContainer extends AlphaOptimizedFrameLayout {
    /**
     * A float value indicating how much before the overflow start the icons should transform into
     * a dot. A value of 0 means that they are exactly at the end and a value of 1 means it starts
     * 1 icon width early.
     */
    public static final float OVERFLOW_EARLY_AMOUNT = 0.2f;
    private static final int NO_VALUE = Integer.MIN_VALUE;
    private static final String TAG = "NotificationIconContainer";
    private static final boolean DEBUG = false;
    private static final int CANNED_ANIMATION_DURATION = 100;
    private static final AnimationProperties DOT_ANIMATION_PROPERTIES = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateX();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(200);

    private static final AnimationProperties ICON_ANIMATION_PROPERTIES = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateY().animateAlpha()
                .animateScale();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }

    }.setDuration(CANNED_ANIMATION_DURATION)
            .setCustomInterpolator(View.TRANSLATION_Y, Interpolators.ICON_OVERSHOT);

    private static final AnimationProperties mTempProperties = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    };

    private static final AnimationProperties ADD_ICON_PROPERTIES = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateAlpha();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(200).setDelay(50);

    private boolean mShowAllIcons = true;
    private final HashMap<View, IconState> mIconStates = new HashMap<>();
    private int mDotPadding;
    private int mStaticDotRadius;
    private int mActualLayoutWidth = NO_VALUE;
    private float mActualPaddingEnd = NO_VALUE;
    private float mActualPaddingStart = NO_VALUE;
    private boolean mCentered;
    private boolean mChangingViewPositions;
    private int mAddAnimationStartIndex = -1;
    private int mCannedAnimationStartIndex = -1;
    private int mSpeedBumpIndex = -1;
    private int mIconSize;
    private float mOpenedAmount = 0.0f;
    private float mVisualOverflowAdaption;
    private boolean mDisallowNextAnimation;

    public NotificationIconContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        initDimens();
        setWillNotDraw(!DEBUG);
    }

    private void initDimens() {
        mDotPadding = getResources().getDimensionPixelSize(R.dimen.overflow_icon_dot_padding);
        mStaticDotRadius = getResources().getDimensionPixelSize(R.dimen.overflow_dot_radius);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(getActualPaddingStart(), 0, getLayoutEnd(), getHeight(), paint);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        initDimens();
    }
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        float centerY = getHeight() / 2.0f;
        // we layout all our children on the left at the top
        mIconSize = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            // We need to layout all children even the GONE ones, such that the heights are
            // calculated correctly as they are used to calculate how many we can fit on the screen
            int width = child.getMeasuredWidth();
            int height = child.getMeasuredHeight();
            int top = (int) (centerY - height / 2.0f);
            child.layout(0, top, width, top + height);
            if (i == 0) {
                mIconSize = child.getWidth();
            }
        }
        if (mShowAllIcons) {
            resetViewStates();
            calculateIconTranslations();
            applyIconStates();
        }
    }

    public void applyIconStates() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            ViewState childState = mIconStates.get(child);
            if (childState != null) {
                childState.applyToView(child);
            }
        }
        mAddAnimationStartIndex = -1;
        mCannedAnimationStartIndex = -1;
        mDisallowNextAnimation = false;
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        if (!mChangingViewPositions) {
            mIconStates.put(child, new IconState());
        }
        int childIndex = indexOfChild(child);
        if (childIndex < getChildCount() - 1
            && mIconStates.get(getChildAt(childIndex + 1)).iconAppearAmount > 0.0f) {
            if (mAddAnimationStartIndex < 0) {
                mAddAnimationStartIndex = childIndex;
            } else {
                mAddAnimationStartIndex = Math.min(mAddAnimationStartIndex, childIndex);
            }
        }
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        if (child instanceof StatusBarIconView) {
            final StatusBarIconView icon = (StatusBarIconView) child;
            if (icon.getVisibleState() != StatusBarIconView.STATE_HIDDEN
                    && child.getVisibility() == VISIBLE) {
                int animationStartIndex = findFirstViewIndexAfter(icon.getTranslationX());
                if (mAddAnimationStartIndex < 0) {
                    mAddAnimationStartIndex = animationStartIndex;
                } else {
                    mAddAnimationStartIndex = Math.min(mAddAnimationStartIndex, animationStartIndex);
                }
            }
            if (!mChangingViewPositions) {
                mIconStates.remove(child);
                addTransientView(icon, 0);
                icon.setVisibleState(StatusBarIconView.STATE_HIDDEN, true /* animate */,
                        () -> removeTransientView(icon));
            }
        }
    }

    /**
     * Finds the first view with a translation bigger then a given value
     */
    private int findFirstViewIndexAfter(float translationX) {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view.getTranslationX() > translationX) {
                return i;
            }
        }
        return getChildCount();
    }

    public void resetViewStates() {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            ViewState iconState = mIconStates.get(view);
            iconState.initFrom(view);
            iconState.alpha = 1.0f;
        }
    }

    /**
     * Calulate the horizontal translations for each notification based on how much the icons
     * are inserted into the notification container.
     * If this is not a whole number, the fraction means by how much the icon is appearing.
     */
    public void calculateIconTranslations() {
        float translationX = getActualPaddingStart();
        int firstOverflowIndex = -1;
        int childCount = getChildCount();
        float layoutEnd = getLayoutEnd();
        float overflowStart = layoutEnd - mIconSize * (2 + OVERFLOW_EARLY_AMOUNT);
        boolean hasAmbient = mSpeedBumpIndex != -1 && mSpeedBumpIndex < getChildCount();
        float visualOverflowStart = 0;
        for (int i = 0; i < childCount; i++) {
            View view = getChildAt(i);
            IconState iconState = mIconStates.get(view);
            iconState.xTranslation = translationX;
            boolean isAmbient = mSpeedBumpIndex != -1 && i >= mSpeedBumpIndex
                    && iconState.iconAppearAmount > 0.0f;
            boolean noOverflowAfter = i == childCount - 1;
            if (mOpenedAmount != 0.0f) {
                noOverflowAfter = noOverflowAfter && !hasAmbient;
            }
            iconState.visibleState = StatusBarIconView.STATE_ICON;
            if (firstOverflowIndex == -1 && (isAmbient
                    || (translationX >= (noOverflowAfter ? layoutEnd - mIconSize : overflowStart)))) {
                firstOverflowIndex = noOverflowAfter ? i - 1 : i;
                int totalDotLength = mStaticDotRadius * 6 + 2 * mDotPadding;
                visualOverflowStart = overflowStart + mIconSize * (1 + OVERFLOW_EARLY_AMOUNT)
                        - totalDotLength / 2
                        - mIconSize * 0.5f + mStaticDotRadius;
                if (isAmbient) {
                    visualOverflowStart = Math.min(translationX, visualOverflowStart
                            + mStaticDotRadius * 2 + mDotPadding);
                } else {
                    visualOverflowStart += (translationX - overflowStart) / mIconSize
                            * (mStaticDotRadius * 2 + mDotPadding);
                }
                if (mShowAllIcons) {
                    // We want to perfectly position the overflow in the static state, such that
                    // it's perfectly centered instead of measuring it from the end.
                    mVisualOverflowAdaption = 0;
                    if (firstOverflowIndex != -1) {
                        View firstOverflowView = getChildAt(i);
                        IconState overflowState = mIconStates.get(firstOverflowView);
                        float totalAmount = layoutEnd - overflowState.xTranslation;
                        float newPosition = overflowState.xTranslation + totalAmount / 2
                                - totalDotLength / 2
                                - mIconSize * 0.5f + mStaticDotRadius;
                        mVisualOverflowAdaption = newPosition - visualOverflowStart;
                        visualOverflowStart = newPosition;
                    }
                } else {
                    visualOverflowStart += mVisualOverflowAdaption * (1f - mOpenedAmount);
                }
            }
            translationX += iconState.iconAppearAmount * view.getWidth();
        }
        if (firstOverflowIndex != -1) {
            int numDots = 1;
            translationX = visualOverflowStart;
            for (int i = firstOverflowIndex; i < childCount; i++) {
                View view = getChildAt(i);
                IconState iconState = mIconStates.get(view);
                int dotWidth = mStaticDotRadius * 2 + mDotPadding;
                iconState.xTranslation = translationX;
                if (numDots <= 3) {
                    if (numDots == 1 && iconState.iconAppearAmount < 0.8f) {
                        iconState.visibleState = StatusBarIconView.STATE_ICON;
                        numDots--;
                    } else {
                        iconState.visibleState = StatusBarIconView.STATE_DOT;
                    }
                    translationX += (numDots == 3 ? 3 * dotWidth : dotWidth)
                            * iconState.iconAppearAmount;
                } else {
                    iconState.visibleState = StatusBarIconView.STATE_HIDDEN;
                }
                numDots++;
            }
        }
        if (mCentered && translationX < getLayoutEnd()) {
            float delta = (getLayoutEnd() - translationX) / 2;
            for (int i = 0; i < childCount; i++) {
                View view = getChildAt(i);
                IconState iconState = mIconStates.get(view);
                iconState.xTranslation += delta;
            }
        }

        if (isLayoutRtl()) {
            for (int i = 0; i < childCount; i++) {
                View view = getChildAt(i);
                IconState iconState = mIconStates.get(view);
                iconState.xTranslation = getWidth() - iconState.xTranslation - view.getWidth();
            }
        }
    }

    private float getLayoutEnd() {
        return getActualWidth() - getActualPaddingEnd();
    }

    private float getActualPaddingEnd() {
        if (mActualPaddingEnd == NO_VALUE) {
            return getPaddingEnd();
        }
        return mActualPaddingEnd;
    }

    private float getActualPaddingStart() {
        if (mActualPaddingStart == NO_VALUE) {
            return getPaddingStart();
        }
        return mActualPaddingStart;
    }

    /**
     * Sets whether the layout should always show all icons.
     * If this is true, the icon positions will be updated on layout.
     * If this if false, the layout is managed from the outside and layouting won't trigger a
     * repositioning of the icons.
     */
    public void setShowAllIcons(boolean showAllIcons) {
        mShowAllIcons = showAllIcons;
    }

    public void setActualLayoutWidth(int actualLayoutWidth) {
        mActualLayoutWidth = actualLayoutWidth;
        if (DEBUG) {
            invalidate();
        }
    }

    public void setActualPaddingEnd(float paddingEnd) {
        mActualPaddingEnd = paddingEnd;
        if (DEBUG) {
            invalidate();
        }
    }

    public void setActualPaddingStart(float paddingStart) {
        mActualPaddingStart = paddingStart;
        if (DEBUG) {
            invalidate();
        }
    }

    public int getActualWidth() {
        if (mActualLayoutWidth == NO_VALUE) {
            return getWidth();
        }
        return mActualLayoutWidth;
    }

    public void setChangingViewPositions(boolean changingViewPositions) {
        mChangingViewPositions = changingViewPositions;
    }

    public void setAmbient(boolean ambient) {
        mCentered = ambient;
        mDisallowNextAnimation = true;
    }

    public IconState getIconState(StatusBarIconView icon) {
        return mIconStates.get(icon);
    }

    public void setSpeedBumpIndex(int speedBumpIndex) {
        mSpeedBumpIndex = speedBumpIndex;
    }

    public void setOpenedAmount(float expandAmount) {
        mOpenedAmount = expandAmount;
    }

    public float getVisualOverflowAdaption() {
        return mVisualOverflowAdaption;
    }

    public void setVisualOverflowAdaption(float visualOverflowAdaption) {
        mVisualOverflowAdaption = visualOverflowAdaption;
    }

    public boolean hasOverflow() {
        float width = (getChildCount() + OVERFLOW_EARLY_AMOUNT) * mIconSize;
        return width - (getWidth() - getActualPaddingStart() - getActualPaddingEnd()) > 0;
    }

    public int getIconSize() {
        return mIconSize;
    }

    public class IconState extends ViewState {
        public float iconAppearAmount = 1.0f;
        public float clampedAppearAmount = 1.0f;
        public int visibleState;
        public boolean justAdded = true;
        public boolean needsCannedAnimation;
        public boolean useFullTransitionAmount;
        public boolean useLinearTransitionAmount;
        public boolean translateContent;

        @Override
        public void applyToView(View view) {
            if (view instanceof StatusBarIconView) {
                StatusBarIconView icon = (StatusBarIconView) view;
                boolean animate = false;
                AnimationProperties animationProperties = null;
                if (justAdded) {
                    super.applyToView(icon);
                    icon.setAlpha(0.0f);
                    icon.setVisibleState(StatusBarIconView.STATE_HIDDEN, false /* animate */);
                    animationProperties = ADD_ICON_PROPERTIES;
                    animate = true;
                } else if (visibleState != icon.getVisibleState()) {
                    animationProperties = DOT_ANIMATION_PROPERTIES;
                    animate = true;
                }
                if (!animate && mAddAnimationStartIndex >= 0
                        && indexOfChild(view) >= mAddAnimationStartIndex
                        && (icon.getVisibleState() != StatusBarIconView.STATE_HIDDEN
                            || visibleState != StatusBarIconView.STATE_HIDDEN)) {
                    animationProperties = DOT_ANIMATION_PROPERTIES;
                    animate = true;
                }
                if (needsCannedAnimation) {
                    AnimationFilter animationFilter = mTempProperties.getAnimationFilter();
                    animationFilter.reset();
                    animationFilter.combineFilter(ICON_ANIMATION_PROPERTIES.getAnimationFilter());
                    mTempProperties.resetCustomInterpolators();
                    mTempProperties.combineCustomInterpolators(ICON_ANIMATION_PROPERTIES);
                    if (animationProperties != null) {
                        animationFilter.combineFilter(animationProperties.getAnimationFilter());
                        mTempProperties.combineCustomInterpolators(animationProperties);
                    }
                    animationProperties = mTempProperties;
                    animationProperties.setDuration(CANNED_ANIMATION_DURATION);
                    animate = true;
                    mCannedAnimationStartIndex = indexOfChild(view);
                }
                if (!animate && mCannedAnimationStartIndex >= 0
                        && indexOfChild(view) > mCannedAnimationStartIndex
                        && (icon.getVisibleState() != StatusBarIconView.STATE_HIDDEN
                        || visibleState != StatusBarIconView.STATE_HIDDEN)) {
                    AnimationFilter animationFilter = mTempProperties.getAnimationFilter();
                    animationFilter.reset();
                    animationFilter.animateX();
                    mTempProperties.resetCustomInterpolators();
                    animationProperties = mTempProperties;
                    animationProperties.setDuration(CANNED_ANIMATION_DURATION);
                    animate = true;
                }
                icon.setVisibleState(visibleState);
                if (animate && !mDisallowNextAnimation) {
                    animateTo(icon, animationProperties);
                } else {
                    super.applyToView(view);
                }
            }
            justAdded = false;
            needsCannedAnimation = false;
        }

        protected void onYTranslationAnimationFinished(View view) {
            if (hidden) {
                view.setVisibility(INVISIBLE);
            }
        }
    }
}
