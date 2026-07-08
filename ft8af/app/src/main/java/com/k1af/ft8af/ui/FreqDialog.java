package com.k1af.ft8af.ui;
/**
 * Quick frequency switching dialog.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;
import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.database.OperationBand;

public class FreqDialog extends Dialog {
    private static final String TAG = "FreqDialog";

    private MainViewModel mainViewModel;
    private RecyclerView freqRecyclerView;
    private FreqAdapter freqAdapter;
    //private BandsSpinnerAdapter bandsSpinnerAdapter;


    public FreqDialog(Context  context, MainViewModel mainViewModel) {
        super(context, R.style.HelpDialog);
        this.mainViewModel=mainViewModel;

    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.freq_dialog_layout);
        freqRecyclerView=(RecyclerView) findViewById(R.id.freqDialogRecyclerView);
        freqRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        freqAdapter = new FreqAdapter();
        freqRecyclerView.setAdapter(freqAdapter);

        freqRecyclerView.scrollToPosition(OperationBand.getIndexByFreq(GeneralVariables.band));

        // Add custom dial frequency (issue #470): opens the add/edit dialog.
        Button addCustom = findViewById(R.id.addCustomFreqButton);
        if (addCustom != null) {
            addCustom.setOnClickListener(v -> showCustomFreqDialog(null));
        }
//
//        View.OnClickListener onClickListener=new View.OnClickListener() {
//            @Override
//            public void onClick(View view) {
//                FreqDialog.this.dismiss();
//            }
//        };

    }

    /**
     * Add (existing == null) or edit an operator-defined dial frequency
     * (issue #470). Validation and persistence live in {@link OperationBand};
     * bad input surfaces as a Toast and the list is refreshed on success.
     */
    private void showCustomFreqDialog(@Nullable final OperationBand.Band existing) {
        final OperationBand operationBand = OperationBand.getInstance(getContext());

        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getContext().getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, 0);

        final EditText labelEdit = new EditText(getContext());
        labelEdit.setHint(R.string.customFreqLabelHint);
        labelEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        container.addView(labelEdit);

        final EditText freqEdit = new EditText(getContext());
        freqEdit.setHint(R.string.customFreqHzHint);
        freqEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        container.addView(freqEdit);

        // The dial a new entry belongs to follows the current operating mode; an
        // edited entry keeps its own mode.
        final int mode = (existing != null) ? existing.mode : GeneralVariables.operatingMode;
        if (existing != null) {
            labelEdit.setText(existing.waveLength);
            freqEdit.setText(String.valueOf(existing.band));
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getContext())
                .setTitle(R.string.customFreqTitle)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (DialogInterface d, int w) -> {
                    String freqStr = freqEdit.getText().toString();
                    String label = labelEdit.getText().toString();
                    // For an edit, add the new dial first and only drop the old entry if
                    // that succeeds (editCustomBand) — so an out-of-range change can't
                    // delete the entry and leave nothing behind.
                    String err = (existing != null)
                            ? operationBand.editCustomBand(existing.band, existing.mode, freqStr, label, mode)
                            : operationBand.addCustomBand(freqStr, label, mode);
                    if (err != null) {
                        Toast.makeText(getContext(), err, Toast.LENGTH_LONG).show();
                    } else if (freqAdapter != null) {
                        freqAdapter.notifyDataSetChanged();
                    }
                });
        if (existing != null) {
            builder.setNeutralButton(R.string.delete, (DialogInterface d, int w) -> {
                operationBand.removeCustomBand(existing.band, existing.mode);
                if (freqAdapter != null) {
                    freqAdapter.notifyDataSetChanged();
                }
            });
        }
        builder.show();
    }


    @Override
    public void show() {
        super.show();
        WindowManager.LayoutParams params = getWindow().getAttributes();
        // Set dialog size as percentage (0.6)
        int height=getWindow().getWindowManager().getDefaultDisplay().getHeight();
        int width=getWindow().getWindowManager().getDefaultDisplay().getWidth();
        params.height = (int) (height * 0.6);
        if (width>height) {
            params.width = (int) (width * 0.5);
            params.height = (int) (height * 0.6);
        }else {
            params.width= (int) (width * 0.6);
            params.height = (int) (height * 0.5);
        }
        getWindow().setAttributes(params);

    }

   public class FreqAdapter  extends RecyclerView.Adapter<FreqAdapter.FreqHolder>{


       @NonNull
       @Override
       public FreqHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
           LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
           View view = layoutInflater.inflate(R.layout.operation_band_dialog_item, parent, false);
           final FreqHolder freqHolder=new FreqHolder(view);
           return freqHolder;
       }

       @Override
       public void onBindViewHolder(@NonNull FreqHolder holder, int position) {
           holder.band=OperationBand.getBandFreq(position);
           //holder.index=position;
           holder.operationDialogBandItemTextView.setText(OperationBand.getBandInfo(position));
           if (holder.band==GeneralVariables.band){
               holder.operationDialogBandConstraintLayout.setBackgroundResource(R.drawable.calling_list_cell_3_style);
           }else {
               holder.operationDialogBandConstraintLayout.setBackgroundResource(R.drawable.calling_list_cell_style);
           }
           holder.operationDialogBandConstraintLayout.setOnClickListener(new View.OnClickListener() {
               @Override
               public void onClick(View view) {
                   GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(holder.band);
                   GeneralVariables.band = holder.band;

                   mainViewModel.databaseOpr.getAllQSLCallsigns();// Load successfully contacted callsigns
                   mainViewModel.databaseOpr.writeConfig("bandFreq"
                           , String.valueOf(GeneralVariables.band)
                           , null);
                   if (GeneralVariables.controlMode == ControlMode.CAT// Control rig in CAT/RTS/DTR mode
                           || GeneralVariables.controlMode == ControlMode.RTS
                           || GeneralVariables.controlMode == ControlMode.DTR) {
                       // If in CAT/RTS mode, change the rig frequency
                       mainViewModel.setOperationBand();
                   }
                   dismiss();
               }
           });

           // Long-press a custom (operator-defined) dial to edit or delete it.
           final OperationBand.Band bandObj =
                   position < OperationBand.bandList.size() ? OperationBand.bandList.get(position) : null;
           holder.operationDialogBandConstraintLayout.setOnLongClickListener(view -> {
               if (bandObj != null && bandObj.custom) {
                   showCustomFreqDialog(bandObj);
                   return true;
               }
               return false;
           });


           //OperationBand.bandList.get(i)
       }

       @Override
       public int getItemCount() {
           return OperationBand.bandList.size();
       }

       class  FreqHolder extends RecyclerView.ViewHolder{
            long band;

            TextView operationDialogBandItemTextView;
            ConstraintLayout operationDialogBandConstraintLayout;
            public FreqHolder(@NonNull View itemView) {
                super(itemView);
                operationDialogBandItemTextView=itemView.findViewById(R.id.operationDialogBandItemTextView);
                operationDialogBandConstraintLayout=itemView.findViewById(R.id.operationDialogBandConstraintLayout);
            }
        }
   }


}
