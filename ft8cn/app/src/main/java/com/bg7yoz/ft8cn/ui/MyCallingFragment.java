package com.bg7yoz.ft8cn.ui;
/**
 * Calling interface.
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bg7yoz.ft8cn.Ft8Message;
import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.databinding.FragmentMyCallingBinding;
import com.bg7yoz.ft8cn.ft8transmit.FunctionOfTransmit;
import com.bg7yoz.ft8cn.ft8transmit.TransmitCallsign;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.util.ArrayList;


public class MyCallingFragment extends Fragment {
    private static final String TAG = "MyCallingFragment";
    private FragmentMyCallingBinding binding;
    private MainViewModel mainViewModel;

    private RecyclerView transmitRecycleView;

    private CallingListAdapter transmitCallListAdapter;

    private FunctionOrderSpinnerAdapter functionOrderSpinnerAdapter;


    static {
        System.loadLibrary("ft8af");
    }


    /**
     * Call the originator immediately
     *
     * @param message message
     */
    //@RequiresApi(api = Build.VERSION_CODES.N)
    private void doCallNow(Ft8Message message) {
        mainViewModel.addFollowCallsign(message.getCallsignFrom());
        if (!mainViewModel.ft8TransmitSignal.isActivated()) {
            mainViewModel.ft8TransmitSignal.setActivated(true);
            GeneralVariables.transmitMessages.add(message);//Add message to the watch list
        }
        //Call the originator
        mainViewModel.ft8TransmitSignal.setTransmit(message.getFromCallTransmitCallsign()
                , 1, message.extraInfo);
        mainViewModel.ft8TransmitSignal.transmitNow();

        GeneralVariables.resetLaunchSupervision();//Reset automatic supervision
    }


    /**
     * Menu options
     *
     * @param item menu item
     * @return whether selected
     */
    //@RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        //Ft8Message ft8Message = (Ft8Message) item.getActionView().getTag();

        int position = (int) item.getActionView().getTag();
        Ft8Message ft8Message = transmitCallListAdapter.getMessageByPosition(position);
        if (ft8Message == null) return super.onContextItemSelected(item);
        ;

        GeneralVariables.resetLaunchSupervision();//Reset automatic supervision
        switch (item.getItemId()) {
            case 1://Sequence is opposite to the sender!!!
                Log.d(TAG, "Call: " + ft8Message.getCallsignTo());
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.setActivated(true);
                }
                mainViewModel.ft8TransmitSignal.setTransmit(ft8Message.getToCallTransmitCallsign()
                        , 1, ft8Message.extraInfo);
                mainViewModel.ft8TransmitSignal.transmitNow();
                break;

            case 3:
                Log.d(TAG, "Call: " + ft8Message.getCallsignFrom());
                doCallNow(ft8Message);
                //if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                //    mainViewModel.ft8TransmitSignal.setActivated(true);
                // }
                // mainViewModel.ft8TransmitSignal.setTransmit(ft8Message.getFromCallTransmitCallsign()
                //        , 1, ft8Message.extraInfo);
                //mainViewModel.ft8TransmitSignal.transmitNow();
                break;

            case 4://Reply
                Log.d(TAG, "Reply: " + ft8Message.getCallsignFrom());
                mainViewModel.addFollowCallsign(ft8Message.getCallsignFrom());
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.setActivated(true);
                    GeneralVariables.transmitMessages.add(ft8Message);//Add message to the watch list
                }
                //Call the originator
                mainViewModel.ft8TransmitSignal.setTransmit(ft8Message.getFromCallTransmitCallsign()
                        , -1, ft8Message.extraInfo);
                mainViewModel.ft8TransmitSignal.transmitNow();
                break;

            case 5://QRZ for "to"
                showQrzFragment(ft8Message.getCallsignTo());
                break;
            case 6://QRZ for "from"
                showQrzFragment(ft8Message.getCallsignFrom());
                break;
            case 7://View "to" log
                navigateToLogFragment(ft8Message.getCallsignTo());
                break;
            case 8://View "from" log
                navigateToLogFragment(ft8Message.getCallsignFrom());
                break;


        }

        return super.onContextItemSelected(item);
    }
    /**
     * Navigate to the log query interface
     * @param callsign callsign
     */
    private void navigateToLogFragment(String callsign){
        mainViewModel.queryKey=callsign;//Submit the callsign as a search keyword
        NavController navController = Navigation.findNavController(requireActivity()
                , R.id.fragmentContainerView);
        navController.navigate(R.id.action_menu_nav_mycalling_to_menu_nav_history);//Navigate to log
    }
    /**
     * Query QRZ information
     *
     * @param callsign callsign
     */
    private void showQrzFragment(String callsign) {
        NavHostFragment navHostFragment = (NavHostFragment) requireActivity().getSupportFragmentManager().findFragmentById(R.id.fragmentContainerView);
        assert navHostFragment != null;//Assert not null
        Bundle bundle = new Bundle();
        bundle.putString(QRZ_Fragment.CALLSIGN_PARAM, callsign);
        navHostFragment.getNavController().navigate(R.id.QRZ_Fragment, bundle);
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mainViewModel = MainViewModel.getInstance(this);
        binding = FragmentMyCallingBinding.inflate(inflater, container, false);

        //Show spectrum view in landscape mode
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.messageSpectrumView.run(mainViewModel, this);
        }


        //Transmit message list
        functionOrderSpinnerAdapter = new FunctionOrderSpinnerAdapter(requireContext(), mainViewModel);
        binding.functionOrderSpinner.setAdapter(functionOrderSpinnerAdapter);
        functionOrderSpinnerAdapter.notifyDataSetChanged();


        //Watched message list
        transmitRecycleView = binding.transmitRecycleView;
        transmitCallListAdapter = new CallingListAdapter(this.getContext(), mainViewModel
                , GeneralVariables.transmitMessages, CallingListAdapter.ShowMode.MY_CALLING);
        transmitRecycleView.setLayoutManager(new LinearLayoutManager(requireContext()));
        transmitRecycleView.setAdapter(transmitCallListAdapter);


        transmitCallListAdapter.notifyDataSetChanged();


        //Set up message list swipe for quick calling
        initRecyclerViewAction();
        //Menu
        requireActivity().registerForContextMenu(transmitRecycleView);

        //Display UTC time
        mainViewModel.timerSec.observe(getViewLifecycleOwner(), new Observer<Long>() {
            @Override
            public void onChanged(Long aLong) {
                binding.timerTextView.setText(UtcTimer.getTimeStr(aLong));
            }
        });
        //Display transmit frequency
        GeneralVariables.mutableBaseFrequency.observe(getViewLifecycleOwner(), new Observer<Float>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Float aFloat) {
                binding.baseFrequencyTextView.setText(String.format(
                        GeneralVariables.getStringFromResource(R.string.sound_frequency_is), aFloat));
            }
        });


        //Observe transmit status button changes
        Observer<Boolean> transmittingObserver = new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (mainViewModel.ft8TransmitSignal.isTransmitting()) {
                    binding.setTransmitImageButton.setImageResource(R.drawable.ic_baseline_send_red_48);
                    binding.setTransmitImageButton.setAnimation(AnimationUtils.loadAnimation(getContext(), R.anim.view_blink));
                } else {
                    //The recorder must also be running to have transmit status
                    if (mainViewModel.ft8TransmitSignal.isActivated() && mainViewModel.hamRecorder.isRunning()) {
                        binding.setTransmitImageButton.setImageResource(R.drawable.ic_baseline_send_white_48);
                    } else {
                        binding.setTransmitImageButton.setImageResource(R.drawable.ic_baseline_cancel_schedule_send_off);
                    }
                    binding.setTransmitImageButton.setAnimation(null);
                }

                //Pause playback button
                if (mainViewModel.ft8TransmitSignal.isTransmitting()) {
                    binding.pauseTransmittingImageButton.setImageResource(R.drawable.ic_baseline_pause_circle_outline_24);
                    binding.pauseTransmittingImageButton.setVisibility(View.VISIBLE);
                } else {
                    binding.pauseTransmittingImageButton.setVisibility(View.GONE);
                    binding.pauseTransmittingImageButton.setImageResource(R.drawable.ic_baseline_pause_disable_circle_outline_24);
                }
            }
        };
        //Display transmit status
        mainViewModel.ft8TransmitSignal.mutableIsTransmitting.observe(getViewLifecycleOwner(), transmittingObserver);
        mainViewModel.ft8TransmitSignal.mutableIsActivated.observe(getViewLifecycleOwner(), transmittingObserver);

        //Pause button
        binding.pauseTransmittingImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mainViewModel.ft8TransmitSignal.setTransmitting(false);
                GeneralVariables.resetLaunchSupervision();//Reset automatic supervision
            }
        });

        //Monitor command sequence
        mainViewModel.ft8TransmitSignal.mutableFunctions.observe(getViewLifecycleOwner()
                , new Observer<ArrayList<FunctionOfTransmit>>() {
                    @Override
                    public void onChanged(ArrayList<FunctionOfTransmit> functionOfTransmits) {
                        functionOrderSpinnerAdapter.notifyDataSetChanged();
                    }
                });

        //Observe command sequence number changes
        mainViewModel.ft8TransmitSignal.mutableFunctionOrder.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                if (mainViewModel.ft8TransmitSignal.functionList.size() < 6) {
                    binding.functionOrderSpinner.setSelection(0);
                } else {
                    binding.functionOrderSpinner.setSelection(integer - 1);
                }
            }
        });

        //Set event for when command sequence number is selected
        binding.functionOrderSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                if (mainViewModel.ft8TransmitSignal.functionList.size() > 1) {
                    mainViewModel.ft8TransmitSignal.setCurrentFunctionOrder(i + 1);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });


        //Display current target callsign
        mainViewModel.ft8TransmitSignal.mutableToCallsign.observe(getViewLifecycleOwner(), new Observer<TransmitCallsign>() {
            @Override
            public void onChanged(TransmitCallsign transmitCallsign) {
                if (GeneralVariables.toModifier!=null) {
                    binding.toCallsignTextView.setText(String.format(
                            GeneralVariables.getStringFromResource(R.string.target_callsign)
                            , transmitCallsign.callsign+" "+GeneralVariables.toModifier));
                }else {
                    binding.toCallsignTextView.setText(String.format(
                            GeneralVariables.getStringFromResource(R.string.target_callsign)
                            , transmitCallsign.callsign));
                }
            }
        });

        //Display current transmit sequence
        mainViewModel.ft8TransmitSignal.mutableSequential.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Integer integer) {
                binding.transmittingSequentialTextView.setText(
                        String.format(GeneralVariables.getStringFromResource(R.string.transmission_sequence)
                                , integer));
            }
        });

        //Set up transmit button
        binding.setTransmitImageButton.setOnClickListener(new View.OnClickListener() {
            //@RequiresApi(api = Build.VERSION_CODES.N)
            @Override
            public void onClick(View view) {
                //If not activated
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.restTransmitting();
                }
                mainViewModel.ft8TransmitSignal.setActivated(!mainViewModel.ft8TransmitSignal.isActivated());
                GeneralVariables.resetLaunchSupervision();//Reset automatic supervision
            }
        });

        //Observe transmit message list changes
        //mainViewModel.mutableTransmitMessages.observe(getViewLifecycleOwner(), new Observer<ArrayList<Ft8Message>>() {
        mainViewModel.mutableTransmitMessagesCount.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Integer count) {
                binding.decoderCounterTextView.setText(String.format(
                        GeneralVariables.getStringFromResource(R.string.message_count)
                        , GeneralVariables.transmitMessages.size()));
                //if (count == 0) {
                transmitCallListAdapter.notifyDataSetChanged();
                //} else {
                //    transmitCallListAdapter.notifyItemInserted(
                //            GeneralVariables.transmitMessages.size() - count);
                //}

                //Auto-scroll up when list bottom extends slightly beyond view
                if (transmitRecycleView.computeVerticalScrollRange()
                        - transmitRecycleView.computeVerticalScrollExtent()
                        - transmitRecycleView.computeVerticalScrollOffset() < 300) {
                    transmitRecycleView.scrollToPosition(transmitCallListAdapter.getItemCount() - 1);
                }
            }
        });

        //Clear transmit message list
        binding.clearMycallListImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mainViewModel.clearTransmittingMessage();
            }
        });

        //Reset to CQ button
        binding.resetToCQImageView.setOnClickListener(new View.OnClickListener() {
            //@RequiresApi(api = Build.VERSION_CODES.N)
            @Override
            public void onClick(View view) {
                mainViewModel.ft8TransmitSignal.userResetToCQ();
                GeneralVariables.resetLaunchSupervision();//Reset automatic supervision
            }
        });
        //Free text input field restrictions
        binding.transFreeTextEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                mainViewModel.ft8TransmitSignal.setFreeText(editable.toString().toUpperCase());
            }
        });
        binding.resetToCQImageView.setLongClickable(true);
        binding.resetToCQImageView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                mainViewModel.setTransmitIsFreeText(!mainViewModel.getTransitIsFreeText());
                showFreeTextEdit();
                return true;
            }
        });

        binding.mycallToolsBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                GeneralVariables.simpleCallItemMode=!GeneralVariables.simpleCallItemMode;
                transmitRecycleView.setAdapter(transmitCallListAdapter);
                transmitCallListAdapter.notifyDataSetChanged();
                transmitRecycleView.scrollToPosition(transmitCallListAdapter.getItemCount() - 1);
                if (GeneralVariables.simpleCallItemMode){
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.message_list_simple_mode));
                }else {
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.message_list_standard_mode));
                }
            }
        });


        showFreeTextEdit();
        return binding.getRoot();
    }

    private void showFreeTextEdit() {
        if (mainViewModel.getTransitIsFreeText()) {
            binding.transFreeTextEdit.setVisibility(View.VISIBLE);
            binding.functionOrderSpinner.setVisibility(View.GONE);
        } else {
            binding.transFreeTextEdit.setVisibility(View.GONE);
            binding.functionOrderSpinner.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Set up list swipe actions
     */
    private void initRecyclerViewAction() {
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.ANIMATION_TYPE_DRAG
                , ItemTouchHelper.START | ItemTouchHelper.END) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder
                    , @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            //@RequiresApi(api = Build.VERSION_CODES.N)
            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                if (direction == ItemTouchHelper.START) {
                    Ft8Message message = transmitCallListAdapter.getMessageByViewHolder(viewHolder);
                    if (message != null) {
                        //Cannot call yourself
                        if (!message.getCallsignFrom().equals("<...>")
                                //&& !message.getCallsignFrom().equals(GeneralVariables.myCallsign)
                                && !GeneralVariables.checkIsMyCallsign(message.getCallsignFrom())
                                && !(message.i3 == 0 && message.n3 == 0)) {
                            doCallNow(message);
                        }
                    }
                    transmitCallListAdapter.notifyItemChanged(viewHolder.getAdapterPosition());
                }
                if (direction == ItemTouchHelper.END) {//Delete
                    transmitCallListAdapter.deleteMessage(viewHolder.getAdapterPosition());
                    transmitCallListAdapter.notifyItemRemoved(viewHolder.getAdapterPosition());
                }
            }


            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
                //Create call background icon display
                Drawable callIcon = ContextCompat.getDrawable(requireActivity(), R.drawable.ic_baseline_send_red_48);
                Drawable delIcon = ContextCompat.getDrawable(requireActivity(), R.drawable.log_item_delete_icon);
                Drawable background = new ColorDrawable(Color.LTGRAY);
                Ft8Message message = transmitCallListAdapter.getMessageByViewHolder(viewHolder);
                if (message == null) {
                    return;
                }
                if (message.getCallsignFrom().equals("<...>")) {//If the message cannot be called, don't show the icon
                    return;
                }
                Drawable icon;
                if (dX > 0) {
                    icon = delIcon;
                } else {
                    icon = callIcon;
                }
                View itemView = viewHolder.itemView;
                int iconMargin = (itemView.getHeight() - icon.getIntrinsicHeight()) / 2;
                int iconLeft, iconRight, iconTop, iconBottom;
                int backTop, backBottom, backLeft, backRight;
                backTop = itemView.getTop();
                backBottom = itemView.getBottom();
                iconTop = itemView.getTop() + (itemView.getHeight() - icon.getIntrinsicHeight()) / 2;
                iconBottom = iconTop + icon.getIntrinsicHeight();
                if (dX > 0) {
                    backLeft = itemView.getLeft();
                    backRight = itemView.getLeft() + (int) dX;
                    background.setBounds(backLeft, backTop, backRight, backBottom);
                    iconLeft = itemView.getLeft() + iconMargin;
                    iconRight = iconLeft + icon.getIntrinsicWidth();
                    icon.setBounds(iconLeft, iconTop, iconRight, iconBottom);
                } else if (dX < 0) {
                    backRight = itemView.getRight();
                    backLeft = itemView.getRight() + (int) dX;
                    background.setBounds(backLeft, backTop, backRight, backBottom);
                    iconRight = itemView.getRight() - iconMargin;
                    iconLeft = iconRight - icon.getIntrinsicWidth();
                    icon.setBounds(iconLeft, iconTop, iconRight, iconBottom);
                } else {
                    background.setBounds(0, 0, 0, 0);
                    icon.setBounds(0, 0, 0, 0);
                }
                background.draw(c);
                icon.draw(c);

            }
        }).attachToRecyclerView(binding.transmitRecycleView);
    }
}