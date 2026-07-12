package com.k1af.ft8af.ui;
/**
 * QSO log records main fragment.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import static android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;
import com.k1af.ft8af.log.ShareLogs;
import com.k1af.ft8af.databinding.FragmentLogBinding;
import com.k1af.ft8af.grid_tracker.GridTrackerMainActivity;
import com.k1af.ft8af.html.LogHttpServer;
import com.k1af.ft8af.log.LogCallsignAdapter;
import com.k1af.ft8af.log.LogQSLAdapter;
import com.k1af.ft8af.log.OnQueryQSLCallsign;
import com.k1af.ft8af.log.OnQueryQSLRecordCallsign;
import com.k1af.ft8af.log.QSLCallsignRecord;
import com.k1af.ft8af.log.QSLRecordStr;
import com.k1af.ft8af.log.OnShareLogEvents;

import java.io.File;
import java.util.ArrayList;


public class LogFragment extends Fragment {
    private static final String TAG = "LogFragment";
    private FragmentLogBinding binding;
    private MainViewModel mainViewModel;

    private LogCallsignAdapter logCallsignAdapter;
    private LogQSLAdapter logQSLAdapter;
    private boolean loading = false;// Prevent scroll from triggering multiple queries
    private int lastItemPosition;
    private ShareLogsProgressDialog dialog = null;// Share log generation dialog


    public LogFragment() {
        // Required empty public constructor
    }




    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainViewModel = MainViewModel.getInstance(this);

    }

    @SuppressLint({"DefaultLocale", "NotifyDataSetChanged"})
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentLogBinding.inflate(getLayoutInflater());

        logCallsignAdapter = new LogCallsignAdapter(requireContext(), mainViewModel);
        logQSLAdapter = new LogQSLAdapter(requireContext(), mainViewModel);
        binding.logRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));


        setShowStyle();// Set display mode


        initRecyclerViewAction();// Set up list scroll actions


        // Set statistics page button
        binding.countImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showCountFragment();
            }
        });

        binding.inputMycallEdit.setText(mainViewModel.queryKey);
        queryByCallsign(mainViewModel.queryKey, 0);

        mainViewModel.mutableQueryFilter.observe(getViewLifecycleOwner(), new Observer<Integer>() {
            @Override
            public void onChanged(Integer integer) {
                queryByCallsign(mainViewModel.queryKey, 0);
            }
        });

        // Input condition listener
        binding.inputMycallEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                mainViewModel.queryKey = editable.toString();
                queryByCallsign(mainViewModel.queryKey, 0);
            }
        });

        // Filter button
        binding.filterImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new FilterDialog(requireContext(), mainViewModel).show();
            }
        });

        // Export button
        binding.exportImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (getLocalIp() == null) {
                    new HelpDialog(requireContext(), requireActivity()
                            , GeneralVariables.getStringFromResource(R.string.export_null)
                            , false).show();
                } else {
                    new HelpDialog(requireContext(), requireActivity()
                            , String.format(GeneralVariables.getStringFromResource(R.string.export_info)
                            , getLocalIp(), LogHttpServer.DEFAULT_PORT)
                            , false).show();
                }

            }
        });

        // Share log button — opens the new export sheet (ADIF .adi share or Save to Downloads).
        binding.shareLogImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new ExportLogSheet(requireContext(), mainViewModel).show();
            }
        });

        binding.logViewStyleimageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mainViewModel.logListShowCallsign = !mainViewModel.logListShowCallsign;
                setShowStyle();
                queryByCallsign(binding.inputMycallEdit.getText().toString(), 0);// Offset 0 means re-query
            }
        });

        // Location button action
        binding.locationInMapImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(requireContext(), GridTrackerMainActivity.class);
                intent.putExtra("qslAll", mainViewModel.queryKey);
                intent.putExtra("queryFilter", mainViewModel.queryFilter);
                startActivity(intent);
            }
        });

        // Check if the share log generation thread is still running; if so, show dialog
        if (Boolean.TRUE.equals(mainViewModel.mutableShareRunning.getValue())) {
            showShareDialog();
        }

        return binding.getRoot();
    }


    /**
     * Show the log generation dialog.
     */
    private void showShareDialog() {
        mainViewModel.mutableShareRunning.setValue(true);
        dialog = new ShareLogsProgressDialog(
                binding.getRoot().getContext()
                , mainViewModel,false);

        dialog.show();
        mainViewModel.mutableSharePosition.postValue(0);
        mainViewModel.mutableShareInfo.postValue("");
        mainViewModel.mutableShareCount.postValue(0);
    }


    /**
     * Create shared log data file.
     */
    private void buildShareLogs() {

        // First show the log generation dialog
        showShareDialog();

        new Thread(new Runnable() {
            @Override
            public void run() {

                File adiFile = GeneralVariables.writeToTempFile(requireContext()
                        , "FT8AF"
                        , ".txt"
                        , "");


                new ShareLogs().doShareLogs(requireContext(), adiFile
                        , GeneralVariables.getStringFromResource(R.string.share_logs)
                        , mainViewModel.databaseOpr.getDb()
                        , mainViewModel.queryKey
                        , mainViewModel.queryFilter
                        , adiFile
                        , false
                        , new OnShareLogEvents() {
                            @Override
                            public void onPreparing(String info) {
                                mainViewModel.mutableShareInfo.postValue(info);
                            }

                            @Override
                            public void onShareStart(int count, String info) {
                                mainViewModel.mutableSharePosition.postValue(0);
                                mainViewModel.mutableShareInfo.postValue(info);
                                mainViewModel.mutableShareRunning.postValue(true);
                                mainViewModel.mutableShareCount.postValue(count);
                            }

                            @Override
                            public boolean onShareProgress(int count, int position, String info) {
                                mainViewModel.mutableSharePosition.postValue(position);
                                mainViewModel.mutableShareInfo.postValue(info);
                                mainViewModel.mutableShareCount.postValue(count);
                                return Boolean.TRUE.equals(mainViewModel.mutableShareRunning.getValue());
                            }

                            @Override
                            public void afterGet(int count, String info) {
                                mainViewModel.mutableShareInfo.postValue(info);
                                mainViewModel.mutableShareRunning.postValue(false);
                            }

                            @Override
                            public void onShareFailed(String info) {
                                mainViewModel.mutableShareInfo.postValue(info);
                            }
                        });
            }
        }).start();
    }

    /**
     * Context menu item selection handler.
     *
     * @param item Menu item
     * @return Whether consumed
     */
    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        int position = (Integer) item.getActionView().getTag();
        // The tag holds the row's adapter position captured at long-press time
        // (LogQSLItemHolder#onCreateContextMenu). By the time the user taps a menu
        // item that position can be stale: the row may have detached
        // (getAdapterPosition() == RecyclerView.NO_POSITION == -1) or the backing
        // list may have shrunk under a refresh (web import / notifyDataSetChanged).
        // Indexing the adapter with it would throw IndexOutOfBoundsException on the
        // main thread, so drop the stale action instead of crashing.
        int itemCount = mainViewModel.logListShowCallsign
                ? logCallsignAdapter.getItemCount()
                : logQSLAdapter.getItemCount();
        if (!isContextPositionInRange(position, itemCount)) {
            return super.onContextItemSelected(item);
        }
        if (!mainViewModel.logListShowCallsign) {
            switch (item.getItemId()) {
                case 0:
                    logQSLAdapter.setRecordIsQSL(position, false);
                    logQSLAdapter.notifyItemChanged(position);
                    break;
                case 1:
                    logQSLAdapter.setRecordIsQSL(position, true);
                    logQSLAdapter.notifyItemChanged(position);
                    break;
                case 2:
                    showQrzFragment(logQSLAdapter.getRecord(position).getCall());
                    break;
                case 3:
                    Intent intent = new Intent(requireContext(), GridTrackerMainActivity.class);
                    intent.putExtra("qslList", logQSLAdapter.getRecord(position));
                    startActivity(intent);
                    break;
                case 4:
                    final int editPos = position;
                    new EditQSLDialog(requireContext(), mainViewModel
                            , logQSLAdapter.getRecord(position)
                            , new EditQSLDialog.OnSaved() {
                                @Override
                                public void onSaved(QSLRecordStr updated) {
                                    logQSLAdapter.notifyItemChanged(editPos);
                                }
                            }).show();
                    break;

            }
        } else {
            if (item.getItemId() == 2) {
                showQrzFragment(logCallsignAdapter.getRecord(position).getCallsign());
            }
        }

        return super.onContextItemSelected(item);
    }

    /**
     * A context-menu action carries the row's adapter position captured at
     * long-press time. That index is only safe to use against the currently
     * displayed adapter while it remains in range; a detached row yields
     * {@link RecyclerView#NO_POSITION} (-1) and a list that shrank under a
     * refresh leaves the captured index past the end. Extracted so the guard
     * that keeps {@link #onContextItemSelected(MenuItem)} from indexing out of
     * bounds on the main thread can be unit tested.
     *
     * @param position  adapter position captured when the context menu was built
     * @param itemCount current item count of the visible adapter
     * @return whether {@code position} can be safely used to index the adapter
     */
    static boolean isContextPositionInRange(int position, int itemCount) {
        return position >= 0 && position < itemCount;
    }

//    private boolean itemIsOnScreen(View view) {
//        if (view != null) {
//            int width = view.getWidth();
//            int height = view.getHeight();
//            Rect rect = new Rect(0, 0, width, height);
//            return view.getLocalVisibleRect(rect);
//        }
//        return false;
//    }

    private void loadQueryData() {
        if ((!loading)) {
            if (mainViewModel.logListShowCallsign) {
                queryByCallsign(mainViewModel.queryKey, logCallsignAdapter.getItemCount());
            } else {
                queryByCallsign(mainViewModel.queryKey, logQSLAdapter.getItemCount());
            }
        }
    }

    /**
     * Set up list scroll/swipe actions.
     */
    private void initRecyclerViewAction() {

        binding.logRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);

                int itemCount;
                if (mainViewModel.logListShowCallsign) {
                    itemCount = logCallsignAdapter.getItemCount();
                } else {
                    itemCount = logQSLAdapter.getItemCount();
                }
                if (newState == SCROLL_STATE_IDLE &&
                        lastItemPosition == itemCount) {
                    loadQueryData();

                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
                if (layoutManager instanceof LinearLayoutManager) {
                    LinearLayoutManager manager = (LinearLayoutManager) layoutManager;
                    int firstVisibleItem = manager.findFirstVisibleItemPosition();
                    int l = manager.findLastCompletelyVisibleItemPosition();
                    lastItemPosition = firstVisibleItem + (l - firstVisibleItem) + 1;
                }
            }
        });

        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.ANIMATION_TYPE_DRAG
                , ItemTouchHelper.END | ItemTouchHelper.START) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView
                    , @NonNull RecyclerView.ViewHolder viewHolder
                    , @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                if (direction == ItemTouchHelper.END) {
                    // Show a delete confirmation dialog
                    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
                    builder.setIcon(null);
                    builder.setTitle(GeneralVariables.getStringFromResource(R.string.delete_confirmation));
                    builder.setMessage(GeneralVariables.getStringFromResource(R.string.are_you_sure_delete));
                    builder.setPositiveButton(GeneralVariables.getStringFromResource(R.string.ok_confirmed)
                            , new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    logQSLAdapter.deleteRecord(viewHolder.getAdapterPosition());// Delete log entry
                                    logQSLAdapter.notifyItemRemoved(viewHolder.getAdapterPosition());
                                }
                            });
                    builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialogInterface) {
                            logQSLAdapter.notifyDataSetChanged();
                        }
                    });
                    builder.setNegativeButton(GeneralVariables.getStringFromResource(R.string.cancel)
                            , new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    logQSLAdapter.notifyDataSetChanged();
                                }
                            }).show();

                }

                if (direction == ItemTouchHelper.START) {
                    logQSLAdapter.setRecordIsQSL(viewHolder.getAdapterPosition()
                            , !logQSLAdapter.getRecord(viewHolder.getAdapterPosition()).isQSL);
                    logQSLAdapter.notifyItemChanged(viewHolder.getAdapterPosition());
                }
            }

            // Determine list format; disable swipe for callsign list
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView
                    , @NonNull RecyclerView.ViewHolder viewHolder) {
                int swipeFlag;
                if (mainViewModel.logListShowCallsign) {
                    swipeFlag = 0;
                } else {
                    swipeFlag = ItemTouchHelper.START | ItemTouchHelper.END;
                }
                return makeMovementFlags(0, swipeFlag);
            }

            // Create delete/QSL background icon display
            final Drawable delIcon = ContextCompat.getDrawable(requireActivity()
                    , R.drawable.log_item_delete_icon);
            final Drawable qslIcon = ContextCompat.getDrawable(requireActivity()
                    , R.drawable.ic_baseline_library_add_check_24);
            final Drawable background = new ColorDrawable(Color.LTGRAY);

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView
                    , @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY
                    , int actionState, boolean isCurrentlyActive) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
                Drawable icon;
                View itemView = viewHolder.itemView;
                if (dX > 0) {
                    icon = delIcon;
                } else {
                    icon = qslIcon;
                }

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
        }).attachToRecyclerView(binding.logRecyclerView);
    }


    /**
     * Set display mode: toggle between callsign list and QSO log list views.
     */
    @SuppressLint("NotifyDataSetChanged")
    private void setShowStyle() {

        if (mainViewModel.logListShowCallsign) {
            binding.logViewStyleimageButton.setImageResource(R.drawable.ic_baseline_assignment_ind_24);
            binding.logRecyclerView.setAdapter(logCallsignAdapter);
            logCallsignAdapter.notifyDataSetChanged();
            binding.locationInMapImageButton.setVisibility(View.GONE);// Hide location button
        } else {
            binding.logViewStyleimageButton.setImageResource(R.drawable.ic_baseline_assignment_24);
            binding.logRecyclerView.setAdapter(logQSLAdapter);
            logQSLAdapter.notifyDataSetChanged();
            binding.locationInMapImageButton.setVisibility(View.VISIBLE);// Show location button
        }

    }

    /**
     * Query log records.
     *
     * @param callsign callsign to search for
     */
    private void queryByCallsign(String callsign, int offset) {
        loading = true;// Start loading data
        // Two query modes
        if (mainViewModel.logListShowCallsign) {
            if (offset == 0) {// New query, reset records
                logCallsignAdapter.clearRecords();// Clear existing records
            }

            mainViewModel.databaseOpr.getQSLCallsignsByCallsign(false, offset, callsign, mainViewModel.queryFilter
                    , new OnQueryQSLCallsign() {
                        @Override
                        public void afterQuery(ArrayList<QSLCallsignRecord> records) {
                            requireActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    logCallsignAdapter.setQSLCallsignList(records);
                                    loading = false;
                                }
                            });
                        }
                    });
        } else {
            if (offset == 0) {// New query, reset records
                logQSLAdapter.clearRecords();
            }
            mainViewModel.databaseOpr.getQSLRecordByCallsign(false, offset, callsign, mainViewModel.queryFilter
                    , new OnQueryQSLRecordCallsign() {
                        @Override
                        public void afterQuery(ArrayList<QSLRecordStr> records) {
                            requireActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    logQSLAdapter.setQSLList(records);
                                    loading = false;
                                }
                            });
                        }
                    });

        }
    }


    /**
     * Show the statistics page.
     */
    private void showCountFragment() {
        // Used for Fragment navigation.
        NavHostFragment navHostFragment = (NavHostFragment) requireActivity()
                .getSupportFragmentManager().findFragmentById(R.id.fragmentContainerView);
        assert navHostFragment != null;// Assert not null
        navHostFragment.getNavController().navigate(R.id.countFragment);
    }

    /**
     * Show the QRZ lookup interface.
     *
     * @param callsign callsign to look up
     */
    private void showQrzFragment(String callsign) {
        NavHostFragment navHostFragment = (NavHostFragment) requireActivity()
                .getSupportFragmentManager().findFragmentById(R.id.fragmentContainerView);
        assert navHostFragment != null;// Assert not null
        Bundle bundle = new Bundle();
        bundle.putString(QRZ_Fragment.CALLSIGN_PARAM, callsign);
        navHostFragment.getNavController().navigate(R.id.QRZ_Fragment, bundle);
    }


    /**
     * Get the local IP address.
     *
     * @return IP address
     */
    @Nullable
    private String getLocalIp() {
        WifiManager wifiManager = (WifiManager) requireContext().getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return null;
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo == null) return null;
        int ipAddress = wifiInfo.getIpAddress();
        if (ipAddress == 0) {
            return null;
        }
        return ((ipAddress & 0xff) + "." + (ipAddress >> 8 & 0xff) + "." + (ipAddress >> 16 & 0xff)
                + "." + (ipAddress >> 24 & 0xff));
    }

    @Override
    public void onDestroy() {
        // Check if the shared log generation thread is still running; if so, dismiss the dialog to prevent "not attached to window manager" error
        if (Boolean.TRUE.equals(mainViewModel.mutableShareRunning.getValue())) {
            if (dialog != null) {
                dialog.dismiss();
                dialog = null;
            }
        }

        super.onDestroy();
    }
}