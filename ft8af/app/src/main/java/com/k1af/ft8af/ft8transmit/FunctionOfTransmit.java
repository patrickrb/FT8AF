package com.k1af.ft8af.ft8transmit;
/**
 * The 6 steps of an FT8 QSO.
 * @author BGY70Z
 * @date 2023-03-20
 */

import com.k1af.ft8af.Ft8Message;

public class FunctionOfTransmit {
    private int functionOrder;// message sequence number
    private String functionMessage;// message content
    private boolean completed;// whether completed
    private boolean isCurrentOrder;// whether this is the message currently being transmitted
    private Ft8Message ft8Message;

//    /**
//     * Old send message method
//     * @param functionOrder message sequence number
//     * @param functionMessage message content
//     * @param completed whether completed
//     */
//    @Deprecated
//    public FunctionOfTransmit(int functionOrder, String functionMessage, boolean completed) {
//        this.functionOrder = functionOrder;
//        this.functionMessage = functionMessage;
//        this.completed = completed;
//    }

    /**
     * New version of the send message method.
     * @param functionOrder message sequence number
     * @param message FT8 message
     * @param completed whether completed
     */
    public FunctionOfTransmit(int functionOrder, Ft8Message message, boolean completed) {
        this.functionOrder = functionOrder;
        ft8Message=message;
        this.completed = completed;
        this.functionMessage = message.getMessageText();
    }

    public int getFunctionOrder() {
        return functionOrder;
    }

    public void setFunctionOrder(int functionOrder) {
        this.functionOrder = functionOrder;
    }

    public String getFunctionMessage() {
        return functionMessage;
    }

    public void setFunctionMessage(String functionMessage) {
        this.functionMessage = functionMessage;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public boolean isCurrentOrder() {
        return isCurrentOrder;
    }

    public void setCurrentOrder(int currentOrder) {
        isCurrentOrder = currentOrder==functionOrder;
    }
}
