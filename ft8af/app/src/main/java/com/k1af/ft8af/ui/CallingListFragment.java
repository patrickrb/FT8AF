package com.k1af.ft8af.ui;
/**
 * Decode view fragment.
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
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;

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

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;
import com.k1af.ft8af.databinding.FragmentCallingListBinding;
import com.k1af.ft8af.timer.UtcTimer;

import java.util.ArrayList;

public class CallingListFragment extends Fragment {
    private static final String TAG = "CallingListFragment";

    private FragmentCallingListBinding binding;
    private RecyclerView callListRecyclerView;
    private CallingListAdapter callingListAdapter;
    private MainViewModel mainViewModel;


    @SuppressLint("NotifyDataSetChanged")
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        mainViewModel = MainViewModel.getInstance(this);
        binding = FragmentCallingListBinding.inflate(inflater, container, false);
        callListRecyclerView = binding.callingListRecyclerView;

        callingListAdapter = new CallingListAdapter(this.getContext(), mainViewModel
                , mainViewModel.ft8Messages, CallingListAdapter.ShowMode.CALLING_LIST);
        callListRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        callListRecyclerView.setAdapter(callingListAdapter);
        callingListAdapter.notifyDataSetChanged();
        callListRecyclerView.scrollToPosition(callingListAdapter.getItemCount() - 1);


        requireActivity().registerForContextMenu(callListRecyclerView);// Register context menu

        // Show spectrum view in landscape mode
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            assert binding.spectrumView != null;
            binding.spectrumView.run(mainViewModel, this);
        }
        // Set up swipe on callsigns for quick calling
        initRecyclerViewAction();

        // Listen button
        binding.timerImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mainViewModel.hamRecorder.isRunning()) {
                    mainViewModel.hamRecorder.stopRecord();
                    mainViewModel.ft8TransmitSignal.setActivated(false);
                } else {
                    mainViewModel.hamRecorder.startRecord();
                }
            }
        });
        // Clear button
        binding.clearCallingListImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mainViewModel.clearFt8MessageList();
                callingListAdapter.notifyDataSetChanged();
                mainViewModel.mutable_Decoded_Counter.setValue(0);
            }
        });
        // Observe decode count
        mainViewModel.mutable_Decoded_Counter.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Integer integer) {
                binding.decoderCounterTextView.setText(
                        String.format(GeneralVariables.getStringFromResource(R.string.message_count_count)
                                , mainViewModel.currentDecodeCount, mainViewModel.ft8Messages.size()));


            }
        });

        mainViewModel.mutableFt8MessageList.observe(getViewLifecycleOwner(), new Observer<ArrayList<Ft8Message>>() {
            @Override
            public void onChanged(ArrayList<Ft8Message> messages) {
                callingListAdapter.notifyDataSetChanged();
                // Auto-scroll up when near the bottom of the list
                if (callListRecyclerView.computeVerticalScrollRange()
                        - callListRecyclerView.computeVerticalScrollExtent()
                        - callListRecyclerView.computeVerticalScrollOffset() < 500) {
                    callListRecyclerView.scrollToPosition(callingListAdapter.getItemCount() - 1);
                }
            }
        });

        // Observe UTC time
        mainViewModel.timerSec.observe(getViewLifecycleOwner(), new Observer<Long>() {
            @Override
            public void onChanged(Long aLong) {
                binding.timerTextView.setText(UtcTimer.getTimeStr(aLong));
            }
        });

        // Observe time offset
        mainViewModel.mutableTimerOffset.observe(getViewLifecycleOwner(), new Observer<Float>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Float aFloat) {
                binding.timeOffsetTextView.setText(String.format(
                        getString(R.string.average_offset_seconds), aFloat));
            }
        });

        // Display Maidenhead grid
        GeneralVariables.mutableMyMaidenheadGrid.observe(getViewLifecycleOwner(), new Observer<String>() {
            @Override
            public void onChanged(String s) {
                binding.maidenheadTextView.setText(String.format(
                        getString(R.string.my_grid), s));
            }
        });

        // Observe decoding state
        mainViewModel.mutableIsDecoding.observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    binding.isDecodingTextView.setText(getString(R.string.decoding));
                }
            }
        });

        // Observe decoding duration
        mainViewModel.ft8SignalListener.decodeTimeSec.observe(getViewLifecycleOwner(), new Observer<Long>() {
            @SuppressLint("DefaultLocale")
            @Override
            public void onChanged(Long aLong) {
                binding.isDecodingTextView.setText(String.format(
                        getString(R.string.decoding_takes_milliseconds), aLong));
            }
        });

        // Show recording status with blinking animation
        mainViewModel.mutableIsRecording.observe(getViewLifecycleOwner(), new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    binding.timerImageButton.setImageResource(R.drawable.ic_baseline_mic_red_48);
                    binding.timerImageButton.setAnimation(AnimationUtils.loadAnimation(getContext()
                            , R.anim.view_blink));
                } else {
                    if (mainViewModel.hamRecorder.isRunning()) {
                        binding.timerImageButton.setImageResource(R.drawable.ic_baseline_mic_48);
                    } else {
                        binding.timerImageButton.setImageResource(R.drawable.ic_baseline_mic_off_48);
                    }
                    binding.timerImageButton.setAnimation(null);
                }
            }
        });

        // Toggle between simple mode and standard mode
        binding.callingListToolsBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                GeneralVariables.simpleCallItemMode=!GeneralVariables.simpleCallItemMode;
                callListRecyclerView.setAdapter(callingListAdapter);
                callingListAdapter.notifyDataSetChanged();
                callListRecyclerView.scrollToPosition(callingListAdapter.getItemCount() - 1);
                if (GeneralVariables.simpleCallItemMode){
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.message_list_simple_mode));
                }else {
                    ToastMessage.show(GeneralVariables.getStringFromResource(R.string.message_list_standard_mode));
                }
            }
        });

        return binding.getRoot();
    }

    /**
     * Set up list swipe actions.
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
                if (direction == ItemTouchHelper.START) {// Call
                    Ft8Message message = callingListAdapter.getMessageByViewHolder(viewHolder);
                    if (message != null) {
                        // Call target cannot be yourself
                        if (!message.getCallsignFrom().equals("<...>")
                                //&& !message.getCallsignFrom().equals(GeneralVariables.myCallsign)
                                && !GeneralVariables.checkIsMyCallsign(message.getCallsignFrom())
                                && !(message.i3 == 0 && (message.n3 == 0 || message.n3 == 5))) {// Cannot call telemetry or free text
                            doCallNow(message);
                        } else {
                            callingListAdapter.notifyItemChanged(viewHolder.getAdapterPosition());
                        }
                    }
                }
                if (direction == ItemTouchHelper.END) {// Delete
                    callingListAdapter.deleteMessage(viewHolder.getAdapterPosition());
                    callingListAdapter.notifyItemRemoved(viewHolder.getAdapterPosition());
                }


            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView
                    , @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY
                    , int actionState, boolean isCurrentlyActive) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
                // Create call background icon display
                final Drawable callIcon = ContextCompat.getDrawable(requireActivity()
                        , R.drawable.ic_baseline_send_red_48);
                final Drawable delIcon = ContextCompat.getDrawable(requireActivity()
                        , R.drawable.log_item_delete_icon);
                final Drawable background = new ColorDrawable(Color.LTGRAY);
                Ft8Message message = callingListAdapter.getMessageByViewHolder(viewHolder);
                if (message == null) {
                    return;
                }
                if (message.getCallsignFrom().equals("<...>")) {// If the message cannot be called, don't show the icon
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
        }).attachToRecyclerView(binding.callingListRecyclerView);
    }

    /**
     * Immediately call the sender.
     *
     * @param message The message
     */
    //@RequiresApi(api = Build.VERSION_CODES.N)
    private boolean doCallNow(Ft8Message message) {

        mainViewModel.addFollowCallsign(message.getCallsignFrom());
        if (!mainViewModel.ft8TransmitSignal.isActivated()) {
            mainViewModel.ft8TransmitSignal.setActivated(true);
            GeneralVariables.transmitMessages.add(message);// Add message to the watch list
        }
        // Call the sender
        mainViewModel.ft8TransmitSignal.setTransmit(message.getFromCallTransmitCallsign()
                , 1, message.extraInfo);
        mainViewModel.ft8TransmitSignal.transmitNow();
        GeneralVariables.resetLaunchSupervision();// Reset transmit supervision
        navigateToMyCallFragment();// Navigate to the transmit view
        return true;
    }

    /**
     * Navigate to the log query view.
     * @param callsign Callsign
     */
    private void navigateToLogFragment(String callsign){
        mainViewModel.queryKey=callsign;// Submit callsign as search keyword
        NavController navController = Navigation.findNavController(requireActivity()
                , R.id.fragmentContainerView);
        navController.navigate(R.id.action_menu_nav_calling_list_to_menu_nav_history);// Navigate to log
    }

    /**
     * Navigate to the transmit view.
     */
    private void navigateToMyCallFragment() {
        NavController navController = Navigation.findNavController(requireActivity()
                , R.id.fragmentContainerView);
        navController.navigate(R.id.action_menu_nav_calling_list_to_menu_nav_mycalling);// Navigate to transmit view
    }

    /**
     * Context menu options.
     *
     * @param item Menu item
     * @return Whether consumed
     */
    //@RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {

        //Ft8Message ft8Message = (Ft8Message) item.getActionView().getTag();
        int position = (int) item.getActionView().getTag();
        Ft8Message ft8Message = callingListAdapter.getMessageByPosition(position);
        if (ft8Message == null) return super.onContextItemSelected(item);
        ;
        switch (item.getItemId()) {
            case 0:
                Log.d(TAG, "Watch: " + ft8Message.getCallsignTo());
                mainViewModel.addFollowCallsign(ft8Message.getCallsignTo());
                GeneralVariables.transmitMessages.add(ft8Message);// Add message to the watch list
                break;
            case 1:// Sequence is opposite to the sender!
                Log.d(TAG, "Call: " + ft8Message.getCallsignTo());
                mainViewModel.addFollowCallsign(ft8Message.getCallsignTo());
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.setActivated(true);
                    GeneralVariables.transmitMessages.add(ft8Message);// Add message to the watch list
                    GeneralVariables.resetLaunchSupervision();// Reset transmit supervision
                }
                // Call the callee
                mainViewModel.ft8TransmitSignal.setTransmit(ft8Message.getToCallTransmitCallsign()
                        , 1, ft8Message.extraInfo);
                mainViewModel.ft8TransmitSignal.transmitNow();

                navigateToMyCallFragment();// Navigate to the transmit view
                break;
            case 2:
                Log.d(TAG, "Watch: " + ft8Message.getCallsignFrom());
                mainViewModel.addFollowCallsign(ft8Message.getCallsignFrom());
                GeneralVariables.transmitMessages.add(ft8Message);// Add message to the watch list
                break;
            case 3:
                Log.d(TAG, "Call: " + ft8Message.getCallsignFrom());
                doCallNow(ft8Message);
                break;

            case 4:// Reply
                Log.d(TAG, "Reply: " + ft8Message.getCallsignFrom());
                mainViewModel.addFollowCallsign(ft8Message.getCallsignFrom());
                if (!mainViewModel.ft8TransmitSignal.isActivated()) {
                    mainViewModel.ft8TransmitSignal.setActivated(true);
                    GeneralVariables.transmitMessages.add(ft8Message);// Add message to the watch list
                }
                // Call the sender
                mainViewModel.ft8TransmitSignal.setTransmit(ft8Message.getFromCallTransmitCallsign()
                        , -1, ft8Message.extraInfo);
                mainViewModel.ft8TransmitSignal.transmitNow();
                GeneralVariables.resetLaunchSupervision();// Reset transmit supervision
                navigateToMyCallFragment();// Navigate to the transmit view
                break;
            case 5:// QRZ for 'to'
                showQrzFragment(ft8Message.getCallsignTo());
                break;
            case 6:// QRZ for 'from'
                showQrzFragment(ft8Message.getCallsignFrom());
                break;
            case 7:// Query 'to' log
                navigateToLogFragment(ft8Message.getCallsignTo());
                break;
            case 8:// Query 'from' log
                navigateToLogFragment(ft8Message.getCallsignFrom());
                break;

        }

        return super.onContextItemSelected(item);
    }

    /**
     * Show QRZ query view.
     *
     * @param callsign Callsign
     */
    private void showQrzFragment(String callsign) {
        NavHostFragment navHostFragment = (NavHostFragment) requireActivity().getSupportFragmentManager().findFragmentById(R.id.fragmentContainerView);
        assert navHostFragment != null;// Assert not null
        Bundle bundle = new Bundle();
        bundle.putString(QRZ_Fragment.CALLSIGN_PARAM, callsign);
        navHostFragment.getNavController().navigate(R.id.QRZ_Fragment, bundle);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}