package com.k1af.ft8af.ui;
/**
 * Toast message display.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;

import com.k1af.ft8af.GeneralVariables;

import java.util.ArrayList;

public class ToastMessage {
    private static final String TAG="ToastMessage";
    //private static Activity activity;
    private static ToastMessage toastMessage=null;
    private static final ArrayList<String> debugList=new ArrayList<>();
    public static ToastMessage getInstance(){
        if (toastMessage==null){
            toastMessage=new ToastMessage();
        }
        return toastMessage;
    }

    public ToastMessage() {
        //this.activity = activity;
    }

    public static void show(String message){
        addDebugInfo(message);
    }
    public static synchronized void show(String message,boolean clearMessage){
        if (clearMessage) {
            debugList.clear();
        }
        show(message);
    }
    @SuppressLint("DefaultLocale")
    private static synchronized void addDebugInfo(String s){
        // A null message would be stored and then dereferenced by the expiry
        // pass below, crashing the main thread 5s later — far from whichever
        // call site passed the null. Drop it here instead.
        if (s == null) return;
        if (debugList.size()>5){
        //if (debugList.size()>20){
            debugList.remove(0);
        }
        final String info=s;
        debugList.add(info);
        GeneralVariables.mutableDebugMessage.postValue(getDebugMessage());

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                // synchronized on the same monitor as addDebugInfo/show: this
                // runs on the main thread while messages are added from the
                // decode, TX and CAT threads. Unguarded, the size()/get(i)/
                // remove(i) walk races the overflow remove(0) in addDebugInfo
                // and reads a shifted or absent element.
                expire(info);
            }
        //},10000);
        },5000);


    }
    /**
     * Remove one expired entry, 5s after it was shown.
     *
     * <p>Package-visible and split out of the delayed Runnable so the expiry rule
     * is unit-testable, and {@code synchronized} so it cannot interleave with
     * {@link #addDebugInfo}'s overflow trim on another thread.
     *
     * <p>Uses {@code equals} on the remembered message rather than on the list
     * element: a null slot (from an older build that stored nulls, or a future
     * caller that slips one past the guard) must not crash the main thread —
     * which is exactly what {@code debugList.get(i).equals(info)} did.
     */
    static synchronized void expire(String info){
        for (int i = 0; i < debugList.size(); i++) {
            if (info.equals(debugList.get(i))) {
                debugList.remove(i);
                GeneralVariables.mutableDebugMessage.postValue(getDebugMessage());
                return;
            }
        }
    }

    /** Current entries, for tests. */
    static synchronized java.util.List<String> snapshot(){
        return new ArrayList<>(debugList);
    }

    /** Drop all entries, for test isolation. */
    static synchronized void clearAll(){
        debugList.clear();
    }

    private static synchronized String getDebugMessage(){
        StringBuilder builder=new StringBuilder();
        for (int i = 0; i <debugList.size() ; i++) {
            if (i==debugList.size()-1){
                builder.append(debugList.get(i));
            }else {
                builder.append(debugList.get(i)+"\n");
            }
        }
        return builder.toString();
    }

}
