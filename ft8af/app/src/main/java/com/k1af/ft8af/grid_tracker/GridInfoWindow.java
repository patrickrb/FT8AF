package com.k1af.ft8af.grid_tracker;
/**
 * Info window for each connection line in grid tracker. Contains various zone type icons.
 * QSOs involving my callsign are displayed in red text.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.graphics.Paint;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.R;

import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.OverlayWithIW;
import org.osmdroid.views.overlay.infowindow.InfoWindow;

public class GridInfoWindow extends InfoWindow {
    public static final int UNDEFINED_RES_ID = 0;

    private final TextView titleView;
    private final TextView descriptionView;
    private final TextView subDescriptionView;


    @SuppressLint("UseCompatLoadingForDrawables")
    public GridInfoWindow(int layoutResId, MapView mapView, Ft8Message msg) {
        super(layoutResId, mapView);
        //setResIds(mapView.getContext());
        titleView = (TextView) this.mView.findViewById(R.id.tracker_info_bubble_title);
        descriptionView = (TextView) this.mView.findViewById(R.id.tracker_info_bubble_description);
        subDescriptionView = (TextView) this.mView.findViewById(R.id.tracker_info_bubble_subdescription);
        ImageView fromDxccImage = (ImageView) this.mView.findViewById(R.id.track_from_dxcc_image);
        ImageView fromItuImage = (ImageView) this.mView.findViewById(R.id.track_from_itu_image);
        ImageView fromCqImage = (ImageView) this.mView.findViewById(R.id.track_from_cq_image);
        ImageView toDxccImage = (ImageView) this.mView.findViewById(R.id.track_to_dxcc_image);
        ImageView toItuImage = (ImageView) this.mView.findViewById(R.id.track_to_itu_image);
        ImageView toCqImage = (ImageView) this.mView.findViewById(R.id.track_to_cq_image);
        ConstraintLayout layout=(ConstraintLayout) mView.findViewById(R.id.trackerGridInfoConstraintLayout);

        if (!msg.fromDxcc) fromDxccImage.setVisibility(View.GONE);
        if (!msg.fromItu) fromItuImage.setVisibility(View.GONE);
        if (!msg.fromCq) fromCqImage.setVisibility(View.GONE);
        if (!msg.toDxcc) toDxccImage.setVisibility(View.GONE);
        if (!msg.toItu) toItuImage.setVisibility(View.GONE);
        if (!msg.toCq) toCqImage.setVisibility(View.GONE);


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


        this.mView.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == 1) {
                    GridInfoWindow.this.close();
                }
                return true;
            }
        });
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
