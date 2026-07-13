package com.k1af.ft8af.ui;
/**
 * Message list Adapter. Used by the decode view, call view, and grid tracker view.
 * Different time periods have different backgrounds. There are 4 background colors to distinguish them.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Paint;
import android.opengl.Visibility;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;
import com.k1af.ft8af.maidenhead.MaidenheadGrid;
import com.k1af.ft8af.rigs.BaseRigOperation;
import com.k1af.ft8af.timer.UtcTimer;

import java.util.ArrayList;

public class CallingListAdapter extends RecyclerView.Adapter<CallingListAdapter.CallingListItemHolder> {
    public enum ShowMode{CALLING_LIST,MY_CALLING,TRACKER}
    private static final String TAG = "CallingListAdapter";
    private final MainViewModel mainViewModel;
    private final ArrayList<Ft8Message> ft8MessageArrayList;
    private final Context context;

    private final ShowMode showMode;
    private View.OnClickListener onItemClickListener;

    private final View.OnCreateContextMenuListener menuListener=new View.OnCreateContextMenuListener() {
        @Override
        public void onCreateContextMenu(ContextMenu contextMenu, View view, ContextMenu.ContextMenuInfo contextMenuInfo) {

            //view.setTag(ft8Message);// Pass the message object to the parent view
            int postion= (int) view.getTag();
            // The tag was captured at bind time; the shared decode list can be trimmed or
            // cleared out from under a long-press, so fetch under the decode-thread lock
            // with a range check rather than indexing raw. See messageAtOrNull.
            Ft8Message ft8Message = messageAtOrNull(postion);
            if (ft8Message == null) return;

            // Menu parameters: i1=group, i2=id, i3=display order
            if (!ft8Message.getCallsignTo().contains("...")// Target cannot be self
                    //&& !ft8Message.getCallsignTo().equals(GeneralVariables.myCallsign)
                    && !GeneralVariables.checkIsMyCallsign(ft8Message.getCallsignTo())
                    && !(ft8Message.i3==0&&ft8Message.n3==0)) {
                if (!ft8Message.checkIsCQ()) {
                    if (showMode==ShowMode.CALLING_LIST) {// Show this menu in the message list
                        contextMenu.add(0, 0, 0, String.format(
                                        GeneralVariables.getStringFromResource(R.string.tracking_receiver)
                                        , ft8Message.getCallsignTo(), ft8Message.toWhere))
                                .setActionView(view);
                    }
                    if (!mainViewModel.ft8TransmitSignal.isSynFrequency()) {// If same frequency, it would collide with the sender's frequency and interfere!
                        contextMenu.add(0, 1, 0, String.format(
                                        GeneralVariables.getStringFromResource(R.string.calling_receiver)
                                        , ft8Message.getCallsignTo(), ft8Message.toWhere))
                                .setActionView(view);
                    }
                    // This is a call directed to me, add reply menu
                    //if (ft8Message.getCallsignTo().equals(GeneralVariables.myCallsign)) {
                    if (GeneralVariables.checkIsMyCallsign(ft8Message.getCallsignTo())) {
                        contextMenu.add(0, 4, 0, String.format(
                                        GeneralVariables.getStringFromResource(R.string.reply_to)
                                        , ft8Message.getCallsignFrom(), ft8Message.fromWhere))
                                .setActionView(view);

                    }
                    if (showMode!=ShowMode.TRACKER) {
                        contextMenu.add(0, 5, 0
                                , String.format(GeneralVariables.getStringFromResource(R.string.qsl_qrz_confirmation_s)
                                        , ft8Message.getCallsignTo())).setActionView(view);
                    }

                    // Add query log entry
                    contextMenu.add(0, 7, 0
                            , String.format(GeneralVariables.getStringFromResource(R.string.qsl_query_log_menu)
                                    , ft8Message.getCallsignTo())).setActionView(view);

                }
            }

            if (!ft8Message.getCallsignFrom().contains("...")
                    //&& !ft8Message.getCallsignFrom().equals(GeneralVariables.myCallsign)
                    && !GeneralVariables.checkIsMyCallsign(ft8Message.getCallsignFrom())
                    && !(ft8Message.i3==0&&ft8Message.n3==0)) {
                if (showMode==ShowMode.CALLING_LIST) {// Show this menu in the message list
                    contextMenu.add(1, 2, 0, String.format(
                                    GeneralVariables.getStringFromResource(R.string.tracking)
                                    , ft8Message.getCallsignFrom(), ft8Message.fromWhere))
                            .setActionView(view);
                }
                contextMenu.add(1, 3, 0, String.format(
                                GeneralVariables.getStringFromResource(R.string.calling)
                                , ft8Message.getCallsignFrom(), ft8Message.fromWhere))
                        .setActionView(view);
                if (showMode!=ShowMode.TRACKER) {
                    contextMenu.add(1, 6, 0
                            , String.format(GeneralVariables.getStringFromResource(R.string.qsl_qrz_confirmation_s)
                                    , ft8Message.getCallsignFrom())).setActionView(view);
                }

                // Add query log entry
                contextMenu.add(0, 8, 0
                        , String.format(GeneralVariables.getStringFromResource(R.string.qsl_query_log_menu)
                                , ft8Message.getCallsignFrom())).setActionView(view);
            }

        }
    };



    public CallingListAdapter(Context context, MainViewModel mainViewModel
            , ArrayList<Ft8Message> messages, ShowMode showMode) {
        this.mainViewModel = mainViewModel;
        this.context = context;
        this.showMode=showMode;
        ft8MessageArrayList = messages;
    }

    @NonNull
    @Override
    public CallingListItemHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
        View view ;
        if (GeneralVariables.simpleCallItemMode) {
            view = layoutInflater.inflate(R.layout.call_list_holder_simple_item, parent, false);
        }else {
            view = layoutInflater.inflate(R.layout.call_list_holder_item, parent, false);
        }
        return new CallingListItemHolder(view,onItemClickListener,menuListener);
    }

    /**
     * Delete a message.
     *
     * @param position Position in the list
     */
    public void deleteMessage(int position) {
        synchronized (ft8MessageArrayList) {
            if (isValidPosition(position, ft8MessageArrayList.size())) {
                ft8MessageArrayList.remove(position);
            }
        }
    }

    public Ft8Message getMessageByPosition(int position){
        return messageAtOrNull(position);
    }

    /**
     * Get message by ViewHolder.
     *
     * @param holder holder
     * @return ft8message
     */
    public Ft8Message getMessageByViewHolder(RecyclerView.ViewHolder holder) {
        return messageAtOrNull(holder.getAdapterPosition());
    }

    /**
     * True when {@code position} indexes an existing row of a {@code size}-element list.
     * RecyclerView returns {@link RecyclerView#NO_POSITION} (-1) for a detached holder,
     * and the live decode list can shrink under the UI thread, so every indexed access
     * must range-check first. Mirrors {@code LogQSLAdapter.isValidPosition}.
     */
    static boolean isValidPosition(int position, int size) {
        return position >= 0 && position < size;
    }

    /**
     * Fetch the row at {@code position} atomically with respect to the decode thread, or
     * {@code null} if it is out of range.
     *
     * <p>The two live hosts ({@code CallingListFragment} and {@code GridTrackerMainActivity})
     * hand this adapter the same {@code MainViewModel.ft8Messages} instance the decode thread
     * mutates under {@code synchronized (ft8Messages)}: it appends decodes, trims the front via
     * {@code GeneralVariables.deleteArrayListMore}, and clears the list every cycle. RecyclerView
     * reads {@code getItemCount()} and then indexes the list from {@code onBindViewHolder} and the
     * gesture/menu handlers on the UI thread, so a trim or clear landing in that window made a
     * plain {@code get(position)} throw {@link IndexOutOfBoundsException} on the main thread —
     * an uncaught whole-app crash. Locking on the list instance (the decode thread's own monitor)
     * makes the range-check-and-get a single critical section that cannot observe a half-applied
     * mutation.
     */
    Ft8Message messageAtOrNull(int position) {
        if (ft8MessageArrayList == null) return null;
        synchronized (ft8MessageArrayList) {
            if (!isValidPosition(position, ft8MessageArrayList.size())) return null;
            return ft8MessageArrayList.get(position);
        }
    }

    /**
     * The layout tag a bound row must carry so its click / long-press handlers act on the
     * right message. The handlers read this tag back
     * ({@code messageAtOrNull(tag)} in the context menu, {@code tag == -1} guard in the
     * grid-tracker click listener), so a row with no backing message — the decode list was
     * trimmed/cleared between {@link #getItemCount()} and the bind — must carry
     * {@link RecyclerView#NO_POSITION} rather than the previous bind's index. Otherwise a tap
     * on a reused-but-stale holder would fire against whatever message now occupies that index.
     */
    static int rowTagFor(Ft8Message message, int position) {
        return message == null ? RecyclerView.NO_POSITION : position;
    }

    @SuppressLint("ResourceAsColor")
    @Override
    public void onBindViewHolder(@NonNull CallingListItemHolder holder, int position) {
        Ft8Message message = messageAtOrNull(position);
        if (message == null) {
            // The decode thread trimmed/cleared the shared ft8Messages list between
            // getItemCount() and this bind (both run off-lock on the UI thread while the
            // decode thread mutates the list under synchronized(ft8Messages)). Skip this
            // stale bind rather than crashing; the mutableFt8MessageList observer already
            // has a notifyDataSetChanged queued that rebinds against a consistent list.
            // RecyclerView recycles holders, so before returning put this one into a safe
            // "no row" state: leaving the previous bind's tag/message/content in place would
            // keep a stale row visible and clickable until the queued rebind, letting a tap
            // or long-press fire against whatever message now sits at that index.
            resetRowToNoPosition(holder);
            return;
        }
        holder.itemView.setVisibility(View.VISIBLE);// Undo any prior no-row hide before rebinding
        holder.callListHolderConstraintLayout.setTag(rowTagFor(message, position));// Set layout tag to identify message position
        holder.ft8Message = message;
        holder.showMode = showMode;// Determine if this is the message list or the watched message list
        holder.isSyncFreq = mainViewModel.ft8TransmitSignal.isSynFrequency();// If transmitting on same frequency, don't show call receiver

        holder.callingUtcTextView.setText(UtcTimer.getTimeHHMMSS(holder.ft8Message.utcTime));
        // Sequence, including color
        holder.callingListSequenceTextView.setText(holder.ft8Message.getSequence() == 0 ? "0" : "1");
        holder.isWeakSignalImageView.setVisibility(holder.ft8Message.isWeakSignal ? View.VISIBLE:View.INVISIBLE);

        if (showMode==ShowMode.MY_CALLING) {// In the call view
            holder.callingListSequenceTextView.setTextColor(context.getColor(R.color.follow_call_text_color));
        }

        // Distinguish colors based on 4 time sequences within 1 minute
        switch (holder.ft8Message.getSequence4()) {
            case 0:
                holder.callListHolderConstraintLayout.setBackgroundResource(R.drawable.calling_list_cell_0_style);
                break;
            case 1:
                holder.callListHolderConstraintLayout.setBackgroundResource(R.drawable.calling_list_cell_1_style);
                break;
            case 2:
                holder.callListHolderConstraintLayout.setBackgroundResource(R.drawable.calling_list_cell_2_style);
                break;
            case 3:
                holder.callListHolderConstraintLayout.setBackgroundResource(R.drawable.calling_list_cell_3_style);
                break;
        }

        holder.callingListIdBTextView.setText(holder.ft8Message.getdB());
        // Time offset; if exceeds 1.0s or below -0.05s, show in red
        holder.callListDtTextView.setText(holder.ft8Message.getDt());
        if (holder.ft8Message.time_sec > 1.0f || holder.ft8Message.time_sec < -0.05) {
            holder.callListDtTextView.setTextColor(context.getResources().getColor(
                    R.color.message_in_my_call_text_color));
        } else {
            holder.callListDtTextView.setTextColor(context.getResources().getColor(
                    R.color.text_view_color));
        }


        holder.callingListFreqTextView.setText(holder.ft8Message.getFreq_hz());

        // Check if this callsign has been contacted before; store result in holder.otherBandIsQso
        setQueryHolderQSL_Callsign(holder);

        // Worked-before highlighting (left-edge stripe). Depends on isQSL_Callsign/otherBandIsQso set above.
        setWorkedStateStripe(holder);

        // Whether the message is related to my callsign
        if (holder.ft8Message.inMyCall()) {
            holder.callListMessageTextView.setTextColor(context.getResources().getColor(
                    R.color.message_in_my_call_text_color));
        } else if (holder.otherBandIsQso) {
            // Set color for messages with QSOs on other bands
            holder.callListMessageTextView.setTextColor(context.getResources().getColor(
                    R.color.fromcall_is_qso_text_color));
        } else {
            holder.callListMessageTextView.setTextColor(context.getResources().getColor(
                    R.color.message_text_color));
        }


        holder.callListMessageTextView.setText(holder.ft8Message.getMessageText(true));

        // Carrier frequency
        holder.bandItemTextView.setText(BaseRigOperation.getFrequencyStr(holder.ft8Message.band));
        // Calculate distance
        holder.callingListDistTextView.setText(MaidenheadGrid.getDistStr(
                GeneralVariables.getMyMaidenheadGrid()
                , holder.ft8Message.getMaidenheadGrid(mainViewModel.databaseOpr)));
        holder.callingListCallsignToTextView.setText("");// Callee
        holder.callingListCallsignFromTextView.setText("");// Caller

        // Message type
        holder.callingListCommandIInfoTextView.setText(holder.ft8Message.getCommandInfo());
        if (holder.ft8Message.i3 == 1 || holder.ft8Message.i3 == 2) {
            holder.callingListCommandIInfoTextView.setTextColor(context.getResources().getColor(
                    R.color.text_view_color));
        } else {
            holder.callingListCommandIInfoTextView.setTextColor(context.getResources().getColor(
                    R.color.message_in_my_call_text_color));
        }

        // Set CQ color
        if (holder.ft8Message.checkIsCQ()) {
            holder.callListMessageTextView.setBackgroundResource(R.color.textview_cq_color);
            holder.ft8Message.toWhere = "";
        } else {
            holder.callListMessageTextView.setBackgroundResource(R.color.textview_none_color);
        }


        if (holder.ft8Message.fromWhere != null) {
            holder.callingListCallsignFromTextView.setText(holder.ft8Message.fromWhere);
        } else {
            holder.callingListCallsignFromTextView.setText("");
        }

        if (holder.ft8Message.toWhere != null) {
            holder.callingListCallsignToTextView.setText(holder.ft8Message.toWhere);
        } else {
            holder.callingListCallsignToTextView.setText("");
        }

        // Mark zones that have not been contacted
        setToDxcc(holder);
        setFromDxcc(holder);


        // Query callsign location. To avoid excessive computation, only query when 'from' is empty.
//        if (holder.ft8Message.fromWhere == null) {
//            setQueryHolderCallsign(holder);// Query callsign location
//        }

        if (holder.ft8Message.freq_hz <= 0.01f) {// This is the transmit view
            holder.callingListIdBTextView.setVisibility(View.GONE);
            holder.callListDtTextView.setVisibility(View.GONE);
            holder.callingListFreqTextView.setText("TX");
            holder.bandItemTextView.setVisibility(View.GONE);
            holder.callingListDistTextView.setVisibility(View.GONE);
            holder.callingListCommandIInfoTextView.setVisibility(View.GONE);
            holder.callingUtcTextView.setVisibility(View.GONE);
            holder.callingListCallsignToTextView.setVisibility(View.GONE);
            holder.callingListCallsignFromTextView.setVisibility(View.GONE);
            holder.dxccToImageView.setVisibility(View.GONE);
            holder.ituToImageView.setVisibility(View.GONE);
            holder.cqToImageView.setVisibility(View.GONE);
            holder.dxccFromImageView.setVisibility(View.GONE);
            holder.ituFromImageView.setVisibility(View.GONE);
            holder.cqFromImageView.setVisibility(View.GONE);
        } else if (GeneralVariables.simpleCallItemMode){// Simple list mode
            holder.bandItemTextView.setVisibility(View.GONE);
            holder.callingListDistTextView.setVisibility(View.GONE);
            holder.callingListCommandIInfoTextView.setVisibility(View.GONE);
            holder.callingUtcTextView.setVisibility(View.GONE);
            holder.callingListCallsignToTextView.setVisibility(View.GONE);
            holder.dxccToImageView.setVisibility(View.GONE);
            holder.ituToImageView.setVisibility(View.GONE);
            holder.cqToImageView.setVisibility(View.GONE);
        }else {// Standard list mode
            holder.callingListIdBTextView.setVisibility(View.VISIBLE);
            holder.callListDtTextView.setVisibility(View.VISIBLE);
            holder.bandItemTextView.setVisibility(View.VISIBLE);
            holder.callingListDistTextView.setVisibility(View.VISIBLE);
            holder.callingListCommandIInfoTextView.setVisibility(View.VISIBLE);
            holder.callingUtcTextView.setVisibility(View.VISIBLE);
            holder.callingListCallsignToTextView.setVisibility(View.VISIBLE);
            holder.callingListCallsignFromTextView.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Put a recycled holder into a safe "no row" state for the null-message early return in
     * {@link #onBindViewHolder}. Clears the layout tag to {@link RecyclerView#NO_POSITION} (so the
     * click / long-press handlers treat it as empty instead of acting on a stale index), drops the
     * cached message, blanks the visible text, and hides the row until the queued rebind restores it.
     */
    private void resetRowToNoPosition(@NonNull CallingListItemHolder holder) {
        holder.callListHolderConstraintLayout.setTag(rowTagFor(null, RecyclerView.NO_POSITION));
        holder.ft8Message = null;
        holder.otherBandIsQso = false;

        holder.callingUtcTextView.setText("");
        holder.callingListSequenceTextView.setText("");
        holder.callingListIdBTextView.setText("");
        holder.callListDtTextView.setText("");
        holder.callingListFreqTextView.setText("");
        holder.callListMessageTextView.setText("");
        holder.bandItemTextView.setText("");
        holder.callingListDistTextView.setText("");
        holder.callingListCommandIInfoTextView.setText("");
        holder.callingListCallsignFromTextView.setText("");
        holder.callingListCallsignToTextView.setText("");
        holder.isWeakSignalImageView.setVisibility(View.INVISIBLE);

        holder.itemView.setVisibility(View.GONE);// Take no visible space until the rebind
    }

    /**
     * Color the left-edge stripe of a row based on worked-before state.
     * Priority: new DXCC > new grid > new band > worked-before > none.
     */
    private void setWorkedStateStripe(@NonNull CallingListItemHolder holder) {
        if (holder.workedStateStripeView == null) return;

        Ft8Message msg = holder.ft8Message;
        // TX row (synthetic) — no stripe.
        if (msg == null || msg.freq_hz <= 0.01f) {
            holder.workedStateStripeView.setBackgroundColor(context.getColor(R.color.stripe_none));
            return;
        }

        int colorRes;
        if (GeneralVariables.highlightNewDxcc && msg.fromDxcc) {
            colorRes = R.color.stripe_new_dxcc;
        } else if (GeneralVariables.highlightNewGrid
                && msg.maidenGrid != null
                && msg.maidenGrid.length() >= 4
                && !GeneralVariables.checkQSLGrid(msg.maidenGrid)) {
            colorRes = R.color.stripe_new_grid;
        } else if (GeneralVariables.highlightNewBand
                && !msg.isQSL_Callsign
                && holder.otherBandIsQso) {
            colorRes = R.color.stripe_new_band;
        } else if (GeneralVariables.highlightWorked && msg.isQSL_Callsign) {
            colorRes = R.color.stripe_worked;
        } else {
            colorRes = R.color.stripe_none;
        }
        holder.workedStateStripeView.setBackgroundColor(context.getColor(colorRes));
    }

    private void setFromDxcc(@NonNull CallingListItemHolder holder) {

        if (holder.ft8Message.fromDxcc && holder.ft8Message.freq_hz > 0.01f) {
            holder.dxccFromImageView.setVisibility(View.VISIBLE);
        } else {
            holder.dxccFromImageView.setVisibility(View.GONE);
        }

        if (holder.ft8Message.fromCq && holder.ft8Message.freq_hz > 0.01f) {
            holder.cqFromImageView.setVisibility(View.VISIBLE);
        } else {
            holder.cqFromImageView.setVisibility(View.GONE);
        }

        if (holder.ft8Message.fromItu && holder.ft8Message.freq_hz > 0.01f) {
            holder.ituFromImageView.setVisibility(View.VISIBLE);
        } else {
            holder.ituFromImageView.setVisibility(View.GONE);
        }
    }

    private void setToDxcc(@NonNull CallingListItemHolder holder) {
        if (holder.ft8Message.toDxcc && holder.ft8Message.freq_hz > 0.01f) {
            holder.dxccToImageView.setVisibility(View.VISIBLE);
        } else {
            holder.dxccToImageView.setVisibility(View.GONE);
        }

        if (holder.ft8Message.toCq && holder.ft8Message.freq_hz > 0.01f) {
            holder.cqToImageView.setVisibility(View.VISIBLE);
        } else {
            holder.cqToImageView.setVisibility(View.GONE);
        }

        if (holder.ft8Message.toItu && holder.ft8Message.freq_hz > 0.01f) {
            holder.ituToImageView.setVisibility(View.VISIBLE);
        } else {
            holder.ituToImageView.setVisibility(View.GONE);
        }
    }

    // Check if the callsign has been contacted (QSO'd) before
    private void setQueryHolderQSL_Callsign(@NonNull CallingListItemHolder holder) {
        // Check if this callsign has been successfully contacted on this band
        if (GeneralVariables.checkQSLCallsign(holder.ft8Message.getCallsignFrom())) {// If in the database, strike through
            holder.callListMessageTextView.setPaintFlags(
                    holder.callListMessageTextView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        } else {// If not in the database, remove strike through
            holder.callListMessageTextView.setPaintFlags(
                    holder.callListMessageTextView.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
        }
        holder.otherBandIsQso = GeneralVariables.checkQSLCallsign_OtherBand(holder.ft8Message.getCallsignFrom());
    }

    @Override
    public int getItemCount() {
        return ft8MessageArrayList.size();
    }

    public void setOnItemClickListener(View.OnClickListener onItemClickListener) {
        this.onItemClickListener = onItemClickListener;
    }

    static class CallingListItemHolder extends RecyclerView.ViewHolder {
        private static final String TAG = "CallingListItemHolder";
        ConstraintLayout callListHolderConstraintLayout;
        TextView callingListIdBTextView, callListDtTextView, callingListFreqTextView,
                callListMessageTextView, callingListDistTextView, callingListSequenceTextView,
                callingListCallsignFromTextView, callingListCallsignToTextView
                , callingListCommandIInfoTextView,
                bandItemTextView, callingUtcTextView;
        ImageView dxccToImageView, ituToImageView, cqToImageView, dxccFromImageView
                , ituFromImageView, cqFromImageView,isWeakSignalImageView;
        View workedStateStripeView;
        public Ft8Message ft8Message;
        //boolean showFollow;
        ShowMode showMode;
        boolean isSyncFreq;
        boolean otherBandIsQso = false;


        public CallingListItemHolder(@NonNull View itemView, View.OnClickListener listener
                    ,View.OnCreateContextMenuListener menuListener) {
            super(itemView);
            callListHolderConstraintLayout = itemView.findViewById(R.id.callListHolderConstraintLayout);
            callingListIdBTextView = itemView.findViewById(R.id.callingListIdBTextView);
            callListDtTextView = itemView.findViewById(R.id.callListDtTextView);
            callingListFreqTextView = itemView.findViewById(R.id.callingListFreqTextView);
            callListMessageTextView = itemView.findViewById(R.id.callListMessageTextView);
            callingListDistTextView = itemView.findViewById(R.id.callingListDistTextView);
            callingListSequenceTextView = itemView.findViewById(R.id.callingListSequenceTextView);
            callingListCallsignFromTextView = itemView.findViewById(R.id.callingListCallsignFromTextView);
            callingListCallsignToTextView = itemView.findViewById(R.id.callToItemTextView);
            callingListCommandIInfoTextView = itemView.findViewById(R.id.callingListCommandIInfoTextView);
            bandItemTextView = itemView.findViewById(R.id.bandItemTextView);
            callingUtcTextView = itemView.findViewById(R.id.callingUtcTextView);

            dxccToImageView = itemView.findViewById(R.id.dxccToImageView);
            ituToImageView = itemView.findViewById(R.id.ituToImageView);
            cqToImageView = itemView.findViewById(R.id.cqToImageView);
            dxccFromImageView = itemView.findViewById(R.id.dxccFromImageView);
            ituFromImageView = itemView.findViewById(R.id.ituFromImageView);
            cqFromImageView = itemView.findViewById(R.id.cqFromImageView);
            isWeakSignalImageView=itemView.findViewById(R.id.isWeakSignalImageView);
            workedStateStripeView=itemView.findViewById(R.id.workedStateStripeView);

            dxccToImageView.setVisibility(View.GONE);
            ituToImageView.setVisibility(View.GONE);
            cqToImageView.setVisibility(View.GONE);
            dxccFromImageView.setVisibility(View.GONE);
            ituFromImageView.setVisibility(View.GONE);
            cqFromImageView.setVisibility(View.GONE);
            itemView.setTag(-1);
            itemView.setOnClickListener(listener);
            itemView.setOnCreateContextMenuListener(menuListener);

        }


    }
}
