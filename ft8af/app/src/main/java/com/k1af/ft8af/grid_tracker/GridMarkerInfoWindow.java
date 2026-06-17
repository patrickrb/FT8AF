package com.k1af.ft8af.grid_tracker;
/**
 * Info window for grid markers in the grid tracker. Includes zone icons and a call button.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.graphics.Paint;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;
import com.k1af.ft8af.ui.ToastMessage;

import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.OverlayWithIW;
import org.osmdroid.views.overlay.infowindow.InfoWindow;

public class GridMarkerInfoWindow extends InfoWindow {
    public static final int UNDEFINED_RES_ID = 0;

    private final TextView titleView;
    private final TextView descriptionView;
    private final TextView subDescriptionView;
    private final MainViewModel mainViewModel;
    private Ft8Message msg;


    @SuppressLint("UseCompatLoadingForDrawables")
    public GridMarkerInfoWindow(MainViewModel mainViewModel,int layoutResId, MapView mapView, Ft8Message msg) {
        super(layoutResId, mapView);
        this.mainViewModel=mainViewModel;
        this.msg=msg;
        //setResIds(mapView.getContext());
        titleView = (TextView) this.mView.findViewById(R.id.tracker_marker_info_bubble_title);
        descriptionView = (TextView) this.mView.findViewById(R.id.tracker_marker_info_bubble_description);
        subDescriptionView = (TextView) this.mView.findViewById(R.id.tracker_marker_info_bubble_subdescription);
        ImageView fromDxccImage = (ImageView) this.mView.findViewById(R.id.track_marker_from_dxcc_image);
        ImageView fromItuImage = (ImageView) this.mView.findViewById(R.id.track_marker_from_itu_image);
        ImageView fromCqImage = (ImageView) this.mView.findViewById(R.id.track_marker_from_cq_image);
        if (!msg.fromDxcc) fromDxccImage.setVisibility(View.GONE);
        if (!msg.fromItu) fromItuImage.setVisibility(View.GONE);
        if (!msg.fromCq) fromCqImage.setVisibility(View.GONE);

        ConstraintLayout layout=(ConstraintLayout) mView.findViewById(R.id.trackerMarkerConstraintLayout);
        if (msg.fromCq||msg.fromItu||msg.fromDxcc){//If this is a zone not yet contacted, change the color to red
            layout.setBackground(mView.getResources().getDrawable(R.drawable.tracker_new_cq_info_win_style));
            ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                    (R.string.tracker_new_zone_found)),msg.getMessageText()));
        }



        //Check if this callsign has been successfully contacted on the current band
        if (GeneralVariables.checkQSLCallsign(msg.getCallsignFrom())) {//If found in the database, apply strikethrough
            titleView.setPaintFlags(
                    titleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        } else {//If not in the database, remove strikethrough
            titleView.setPaintFlags(
                    titleView.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
        }
        boolean otherBandIsQso = GeneralVariables.checkQSLCallsign_OtherBand(msg.getCallsignFrom());

        //Check if the message involves my callsign
        if (msg.inMyCall()) {
            layout.setBackground(mView.getResources().getDrawable(R.drawable.tracker_new_cq_info_win_style));
            titleView.setTextColor(mapView.getResources().getColor(
                    R.color.message_in_my_call_text_color));
        } else if (otherBandIsQso) {
            //Set text color for callsigns contacted on other bands
            titleView.setTextColor(mapView.getResources().getColor(
                    R.color.fromcall_is_qso_text_color));
        } else {
            titleView.setTextColor(mapView.getResources().getColor(
                    R.color.message_text_color));
        }






        ImageButton imageButton=(ImageButton) this.mView.findViewById(R.id.callThisImageButton);
        //if (GeneralVariables.myCallsign.equals(msg.getCallsignFrom())){
        if (GeneralVariables.checkIsMyCallsign(msg.getCallsignFrom())){
            imageButton.setVisibility(View.GONE);
        }

        imageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                doCallNow();
            }
        });
        this.mView.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == 1) {
                    GridMarkerInfoWindow.this.close();
                }
                return true;
            }
        });
    }
    /**
     * Immediately call the originator
     *
     */
    //@RequiresApi(api = Build.VERSION_CODES.N)
    private void doCallNow() {
        mainViewModel.addFollowCallsign(msg.getCallsignFrom());
        if (!mainViewModel.ft8TransmitSignal.isActivated()) {
            mainViewModel.ft8TransmitSignal.setActivated(true);
            GeneralVariables.transmitMessages.add(msg);//Add the message to the follow list
        }
        //Call the originator
        mainViewModel.ft8TransmitSignal.setTransmit(msg.getFromCallTransmitCallsign()
                , 1, msg.extraInfo);
        mainViewModel.ft8TransmitSignal.transmitNow();

        GeneralVariables.resetLaunchSupervision();//Reset auto supervision
    }


    @Override
    public void onOpen(Object item) {
        OverlayWithIW overlay = (OverlayWithIW) item;
        String title = overlay.getTitle();
        if (title == null) {
            title = "";
        }

        if (this.mView == null) {
            Log.w("OsmDroid", "Error trapped, BasicInfoWindow.open, mView is null!");
        } else {
            titleView.setText(title);
            String snippet = overlay.getSnippet();
            //Spanned snippetHtml = Html.fromHtml(snippet);
            descriptionView.setText(snippet);
            String subDesc = overlay.getSubDescription();
            subDescriptionView.setText(subDesc);

        }
    }

    @Override
    public void onClose() {

    }
}
