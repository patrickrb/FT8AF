package com.k1af.ft8af.ui;
/**
 * A RadioGroup control that supports automatic line wrapping.
 * The native RadioGroup control cannot wrap RadioButtons when the layout width is insufficient;
 * this control supports automatic line wrapping.
 * @author BGY70Z
 * @date 2023-08-30
 */
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RadioGroup;

public class RadioGroupFt8af extends RadioGroup {
    public RadioGroupFt8af(Context context) {
        super(context);
    }

    public RadioGroupFt8af(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);

        //Call ViewGroup's method to measure child views
        measureChildren(widthMeasureSpec, heightMeasureSpec);

        //Maximum width
        int maxWidth = 0;
        //Accumulated height
        int totalHeight = 0;

        //Accumulated line width for the current row
        int lineWidth = 0;
        //Maximum line height for the current row
        int maxLineHeight = 0;
        //Used to record line width and height before wrapping
        int oldHeight;
        int oldWidth;

        int count = getChildCount();
        //Assume both widthMode and heightMode are AT_MOST
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
            //Get the maximum height of this row
            oldHeight = maxLineHeight;
            //Current maximum width
            oldWidth = maxWidth;

            int deltaX = child.getMeasuredWidth() + params.leftMargin + params.rightMargin;
            if (lineWidth + deltaX + getPaddingLeft() + getPaddingRight() > widthSize) {//If wrapping, height increases
                //Compare with current max width to get the widest. Cannot add current child's width, so use oldWidth
                maxWidth = Math.max(lineWidth, oldWidth);
                //Reset width
                lineWidth = deltaX;
                //Accumulate height
                totalHeight += oldHeight;
                //Reset line height; current View belongs to the next row, so max line height is this child's height plus margin
                maxLineHeight = child.getMeasuredHeight() + params.topMargin + params.bottomMargin;

            } else {
                //No wrapping, accumulate width
                lineWidth += deltaX;
                //No wrapping, calculate max row height
                int deltaY = child.getMeasuredHeight() + params.topMargin + params.bottomMargin;
                maxLineHeight = Math.max(maxLineHeight, deltaY);
            }
            if (i == count - 1) {
                //The next row's height wasn't added earlier; if this is the last row, add the last row's max height
                totalHeight += maxLineHeight;
                //Compare the last row with the widest row so far
                maxWidth = Math.max(lineWidth, oldWidth);
            }
        }

        //Add the current container's padding values
        maxWidth += getPaddingLeft() + getPaddingRight();
        totalHeight += getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(widthMode == MeasureSpec.EXACTLY ? widthSize : maxWidth,
                heightMode == MeasureSpec.EXACTLY ? heightSize : totalHeight);

    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int count = getChildCount();
        //pre is the accumulated position of all previous children
        int preLeft = getPaddingLeft();
        int preTop = getPaddingTop();
        //Record the maximum height of each row
        int maxHeight = 0;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            MarginLayoutParams params = (MarginLayoutParams) child.getLayoutParams();
            //r-l is the current container width. If accumulated child view width exceeds container width, wrap to next line.
            if (preLeft + params.leftMargin + child.getMeasuredWidth() + params.rightMargin + getPaddingRight() > (r - l)) {
                //Reset
                preLeft = getPaddingLeft();
                //Use the child with the maximum height for the setting
                preTop = preTop + maxHeight;
                maxHeight = getChildAt(i).getMeasuredHeight() + params.topMargin + params.bottomMargin;
            } else { //No wrapping, calculate max height
                maxHeight = Math.max(maxHeight, child.getMeasuredHeight() + params.topMargin + params.bottomMargin);
            }
            //left coordinate
            int left = preLeft + params.leftMargin;
            //top coordinate
            int top = preTop + params.topMargin;
            int right = left + child.getMeasuredWidth();
            int bottom = top + child.getMeasuredHeight();
            //Layout the child view
            child.layout(left, top, right, bottom);
            //Calculate preLeft value after layout
            preLeft += params.leftMargin + child.getMeasuredWidth() + params.rightMargin;

        }


    }
}