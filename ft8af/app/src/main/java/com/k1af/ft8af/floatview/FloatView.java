package com.k1af.ft8af.floatview;
/**
 * Main view for FloatButton
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.constraintlayout.widget.Constraints;

import java.util.ArrayList;


public class FloatView extends ConstraintLayout {
    private static final String TAG = "FloatView";

    public enum FLOAT_BOARD {
        LEFT, RIGHT, TOP, BUTTON
    }


    private int parentViewHeight = 100;//Parent view height
    private int parentViewWidth = 100;//Parent view width
    private float mDownX = 0;
    private float mDownY = 0;
    private int lastLeft = 0;
    private int lastTop = 0;


    //------------Float window properties--------------------
    private int buttonSize = 96;//Button size
    private final ArrayList<FloatViewButton> buttons = new ArrayList<>();
    private boolean originalFromTop = false;//Whether to snap to top/bottom edges
    private boolean originalFromLeft = true;//Whether to snap to left/right edges
    private int buttonBackgroundResourceId = -1;//Button background
    private int backgroundResourceId = -1;//Float window background
    private int buttonMargin = 0;//Button margin width within the float window
    private int floatMargin = 40;//Float window distance from screen edge
    private FLOAT_BOARD floatBoard = FLOAT_BOARD.LEFT;


    /**
     * Constructor with size
     *
     * @param context    context
     * @param buttonSize button size, square
     */
    public FloatView(@NonNull Context context, int buttonSize) {
        this(context);
        this.buttonSize = buttonSize;
    }

    public FloatView(@NonNull Context context) {
        this(context, null);
    }

    public FloatView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }


    public FloatView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initView();
    }

    public FloatViewButton addButton(String name, int imageResourceId, OnClickListener onClickListener) {
        FloatViewButton floatViewButton=getButtonByName(name);
        if (floatViewButton==null){
            floatViewButton =addButton(View.generateViewId(), imageResourceId, onClickListener);
        }
        floatViewButton.setName(name);
        return floatViewButton;
    }

    public FloatViewButton addButton(int id, String name, int imageResourceId, OnClickListener onClickListener) {
        FloatViewButton floatViewButton=getButtonByName(name);
        if (floatViewButton==null){
            floatViewButton = addButton(id, imageResourceId, onClickListener);
        }
        floatViewButton.setName(name);
        return floatViewButton;
    }

    public FloatViewButton addButton(int id, int imageResourceId, OnClickListener onClickListener) {
        FloatViewButton imageButton = new FloatViewButton(getContext());
        //imageButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageButton.setImageResource(imageResourceId);
        if (buttonBackgroundResourceId != -1) {
            imageButton.setBackgroundResource(buttonBackgroundResourceId);
        }
        //imageButton.setId(R.id.float_nav);
        imageButton.setId(id);
        imageButton.setOnClickListener(onClickListener);

        imageButton.setAlpha(0.5f);

        addView(imageButton);
        buttons.add(imageButton);
        resetView();

        return imageButton;
    }

    /**
     * Delete a button by name
     *
     * @param name button name
     */
    public void deleteButtonByName(String name) {
        for (int i = buttons.size() - 1; i >= 0; i--) {
            FloatViewButton floatViewButton = buttons.get(i);
            if (floatViewButton.getName().equals(name)) {
                buttons.remove(i);
                removeView(floatViewButton);
            }
            resetView();
        }
    }

    public void deleteButtonByIndex(int index) {
        if (buttons.size() > index && index > -1) {
            FloatViewButton floatViewButton = buttons.get(index);
            buttons.remove(index);
            removeView(floatViewButton);
            resetView();
        }
    }

    public FloatViewButton getButtonByName(String name) {
        for (FloatViewButton button : buttons) {
            if (button.getName().equals(name)) {
                return button;
            }
        }
        return null;
    }

    /**
     * Convert dp value to pixels
     *
     * @param dp dp value
     * @return pixels
     */
    private int dpToPixel(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp
                , getResources().getDisplayMetrics());
//        return (int) (dp*getResources().getDisplayMetrics().density);
    }

    /**
     * Reset the button layout
     */
    public void resetView() {
        for (int i = 0; i < buttons.size(); i++) {
            //LayoutParams buttonLp = new LayoutParams(buttonSize, buttonSize);
            LayoutParams buttonLp = new LayoutParams(dpToPixel(buttonSize), dpToPixel(buttonSize));
            buttonLp.startToStart = ConstraintSet.PARENT_ID;
            buttonLp.endToEnd = ConstraintSet.PARENT_ID;
            buttonLp.leftMargin = buttonMargin;
            buttonLp.rightMargin = buttonMargin;
            if (i == 0) {
                buttonLp.topToTop = ConstraintSet.PARENT_ID;
                buttonLp.topMargin = buttonMargin;
            } else {
                buttonLp.topToBottom = buttons.get(i - 1).getId();
                buttonLp.topMargin = buttonMargin+dpToPixel(4);//Leave a small gap between buttons
            }
            if (i == buttons.size() - 1) {
                buttonLp.bottomToBottom = ConstraintSet.PARENT_ID;
                buttonLp.bottomMargin = buttonMargin;
            }
            buttons.get(i).setLayoutParams(buttonLp);
        }


    }

    @SuppressLint("ClickableViewAccessibility")
    private void initView() {
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);// LayoutParams.WRAP_CONTENT);
        lp.startToStart = ConstraintSet.PARENT_ID;//Only connect left and top of the window
        lp.topToTop = ConstraintSet.PARENT_ID;
        this.setLayoutParams(lp);
    }

    public void initLocation() {
        initLocation(this.floatBoard);
    }


    /**
     * Initialize the float window position, defaults to the right side of the window
     */
    public void initLocation(FLOAT_BOARD float_board) {
        this.floatBoard = float_board;
        getParentViewHeightAndWidth();
        int width;
        int height;

        if (parentViewWidth == 0 && parentViewHeight == 0) {
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            width = wm.getDefaultDisplay().getWidth();
            height = wm.getDefaultDisplay().getHeight();
        } else {//This part rarely executes
            width = parentViewWidth;
            height = parentViewHeight;
        }
        switch (float_board) {
            case RIGHT:
                setLayoutLeftTop(width - dpToPixel(floatMargin * 2 - buttonSize) - 10
                        , (int) (height / 2f - dpToPixel(buttonMargin + buttonSize * buttons.size())/2f));
                break;
            case LEFT:
                setLayoutLeftTop(floatMargin + 10
                        , (int) (height / 2f - dpToPixel(buttonMargin + buttonSize * buttons.size() )/2f));
                break;
            case TOP:
                setLayoutLeftTop((int) (width / 2f - dpToPixel(buttonMargin - buttonSize) / 2f), floatMargin);
                break;
            case BUTTON:
                setLayoutLeftTop((int) (width / 2f - dpToPixel(buttonMargin - buttonSize) / 2f)
                        , height - dpToPixel(floatMargin - buttonMargin * 2 - buttonSize * buttons.size()));
                break;
        }

    }

    /**
     * Get parent View height and width
     */
    private void getParentViewHeightAndWidth() {
        View view = (View) getParent();
        if (view != null) {
            parentViewHeight = view.getHeight();
            parentViewWidth = view.getWidth();
        }

    }

    public void setLayoutLeftTop(int left, int top) {
        ConstraintLayout.LayoutParams layoutParams = new Constraints.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT
                , ViewGroup.LayoutParams.WRAP_CONTENT);
        layoutParams.topToTop = ConstraintSet.PARENT_ID;
        layoutParams.startToStart = ConstraintSet.PARENT_ID;
        layoutParams.leftMargin = left;
        layoutParams.topMargin = top;
        setLayoutParams(layoutParams);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDownY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                offsetTopAndBottom((int) (event.getY() - mDownY));
                offsetLeftAndRight((int) (event.getX() - mDownX));

                lastLeft = getLeft();
                lastTop = getTop();
                setLayoutLeftTop(getLeft(), getTop());

            case MotionEvent.ACTION_UP:

                setLayoutLeftTop(lastLeft, lastTop);

                adsorbTopAdnBottom();//Snap to top/bottom
                adsorbLeftAndRight();//Snap to left/right
                break;
            case MotionEvent.ACTION_CANCEL:
                break;

        }
        return super.onInterceptTouchEvent(event);
    }


    private void adsorbTopAdnBottom() {
        if (originalFromTop) {
            getParentViewHeightAndWidth();

            float boundaryLine = parentViewHeight / 4f;
            if (getTop() < boundaryLine) {
                setLayoutLeftTop(getLeft(), floatMargin);
            } else if (getBottom() > parentViewHeight - boundaryLine) {
                setLayoutLeftTop(getLeft(), parentViewHeight - getHeight() - floatMargin);
            }
        }
    }

    private void adsorbLeftAndRight() {
        if (originalFromLeft) {
            getParentViewHeightAndWidth();
            float boundaryLine = parentViewWidth / 4f;
            if (getLeft() < boundaryLine) {
                setLayoutLeftTop(floatMargin, getTop());
            } else if (getRight() > parentViewWidth - boundaryLine) {
                setLayoutLeftTop(parentViewWidth - getWidth() - floatMargin, getTop());
                //animate().setInterpolator(new DecelerateInterpolator()).setDuration(300).x(parentViewWidth - getWidth() - floatMargin).start();
            }
        }
    }


    public boolean isOriginalFromTop() {
        return originalFromTop;
    }

    public void setOriginalFromTop(boolean originalFromTop) {
        this.originalFromTop = originalFromTop;
    }

    public boolean isOriginalFromLeft() {
        return originalFromLeft;
    }

    public void setOriginalFromLeft(boolean originalFromLeft) {
        this.originalFromLeft = originalFromLeft;
    }

    public int getButtonBackgroundResourceId() {
        return buttonBackgroundResourceId;
    }

    public void setButtonBackgroundResourceId(int buttonBackgroundResourceId) {
        this.buttonBackgroundResourceId = buttonBackgroundResourceId;
        for (ImageButton button : this.buttons) {
            button.setBackgroundResource(buttonBackgroundResourceId);
        }
    }

    public int getBackgroundResourceId() {
        return backgroundResourceId;
    }

    public void setBackgroundResourceId(int backgroundResourceId) {
        this.setBackgroundResource(backgroundResourceId);
        this.backgroundResourceId = backgroundResourceId;
    }

    public int getButtonMargin() {
        return buttonMargin;
    }

    public void setButtonMargin(int buttonMargin) {
        this.buttonMargin = buttonMargin;
    }

    public int getFloatMargin() {
        return floatMargin;
    }

    public void setFloatMargin(int floatMargin) {
        this.floatMargin = floatMargin;
    }

    public FLOAT_BOARD getFloatBoard() {
        return floatBoard;
    }

    public void setFloatBoard(FLOAT_BOARD float_board) {
        this.floatBoard = float_board;
    }
}
