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
import java.util.List;
import java.util.Objects;

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
        if (s == null) {
            // A null message (commonly ToastMessage.show(e.getMessage()) where
            // Throwable.getMessage() returns null) must never enter debugList:
            // the delayed cleanup runnable below matches entries via equals(),
            // which would NPE on a null element. A null toast is a no-op.
            return;
        }
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
                if (removeFirstMatch(debugList, info)) {
                    GeneralVariables.mutableDebugMessage.postValue(getDebugMessage());
                }
            }
        //},10000);
        },5000);


    }

    /**
     * Removes the first entry equal to {@code info} from {@code list} and returns
     * whether a removal happened. Null-safe on both the list element and
     * {@code info} (uses {@link Objects#equals}). Extracted from the delayed
     * cleanup runnable so the match/remove logic can be unit-tested without the
     * Android main-looper handler, and hardened against a null element that would
     * otherwise NPE {@code element.equals(info)}.
     */
    static synchronized boolean removeFirstMatch(List<String> list, String info){
        for (int i = 0; i < list.size(); i++) {
            if (Objects.equals(list.get(i), info)) {
                list.remove(i);
                return true;
            }
        }
        return false;
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
