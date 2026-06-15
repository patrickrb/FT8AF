package com.bg7yoz.ft8cn;
/**
 * Main Activity of the FT8CN application. This app uses the Fragment framework, with each Fragment implementing different functionality.
 * ----2022.5.6-----
 * Main features:
 * 1. Create MainViewModel instance. MainViewModel persists for the entire lifecycle, handling recording, decoding, etc.
 * 2. Request permissions for recording and storage.
 * 3. Implement Fragment navigation management.
 * 4. Prompt after USB serial port connection.
 *
 * @author BG7YOZ
 * @date 2022.5.6
 */


import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.bg7yoz.ft8cn.bluetooth.BluetoothStateBroadcastReceive;
import com.bg7yoz.ft8cn.callsign.CallsignDatabase;
import com.bg7yoz.ft8cn.connector.CableSerialPort;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.database.OnAfterQueryConfig;
import com.bg7yoz.ft8cn.database.OperationBand;
import com.bg7yoz.ft8cn.databinding.MainActivityBinding;
import com.bg7yoz.ft8cn.floatview.FloatView;
import com.bg7yoz.ft8cn.floatview.FloatViewButton;
import com.bg7yoz.ft8cn.grid_tracker.GridTrackerMainActivity;
import com.bg7yoz.ft8cn.log.ImportSharedLogs;
import com.bg7yoz.ft8cn.log.OnShareLogEvents;
import com.bg7yoz.ft8cn.maidenhead.MaidenheadGrid;
import com.bg7yoz.ft8cn.timer.UtcTimer;
import com.bg7yoz.ft8cn.ui.FreqDialog;
import com.bg7yoz.ft8cn.ui.SetVolumeDialog;
import com.bg7yoz.ft8cn.ui.ShareLogsProgressDialog;
import com.bg7yoz.ft8cn.ui.ToastMessage;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


public class MainActivity extends AppCompatActivity {
    private BluetoothStateBroadcastReceive mReceive;
    private static final String TAG = "MainActivity";
    private MainViewModel mainViewModel;
    private NavController navController;
    private static boolean animatorRunned = false;
    //private boolean animationEnd = false;

    private MainActivityBinding binding;
    private FloatView floatView;

    private ShareLogsProgressDialog dialog = null;//dialog for generating shared log


    String[] permissions = new String[]{Manifest.permission.RECORD_AUDIO
            , Manifest.permission.ACCESS_COARSE_LOCATION
            , Manifest.permission.ACCESS_WIFI_STATE
            , Manifest.permission.BLUETOOTH
            , Manifest.permission.BLUETOOTH_ADMIN
            , Manifest.permission.MODIFY_AUDIO_SETTINGS
            , Manifest.permission.WAKE_LOCK
            , Manifest.permission.ACCESS_FINE_LOCATION};
    List<String> mPermissionList = new ArrayList<>();

    private static final int PERMISSION_REQUEST = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{Manifest.permission.RECORD_AUDIO
                    , Manifest.permission.ACCESS_COARSE_LOCATION
                    , Manifest.permission.ACCESS_WIFI_STATE
                    , Manifest.permission.BLUETOOTH
                    , Manifest.permission.BLUETOOTH_ADMIN
                    , Manifest.permission.BLUETOOTH_CONNECT
                    , Manifest.permission.MODIFY_AUDIO_SETTINGS
                    , Manifest.permission.WAKE_LOCK
                    , Manifest.permission.ACCESS_FINE_LOCATION};
        }

        checkPermission();
        //fullscreen
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                , WindowManager.LayoutParams.FLAG_FULLSCREEN);

        //prevent sleep
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                , WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(savedInstanceState);
        GeneralVariables.getInstance().setMainContext(getApplicationContext());

        mainViewModel = MainViewModel.getInstance(this);
        binding = MainActivityBinding.inflate(getLayoutInflater());
        binding.initDataLayout.setVisibility(View.VISIBLE);//show the LOG page
        setContentView(binding.getRoot());


        ToastMessage.getInstance();
        registerBluetoothReceiver();//register Bluetooth state change broadcast
        if (mainViewModel.isBTConnected()) {
            mainViewModel.setBlueToothOn();
        }


        //observe DEBUG messages
        GeneralVariables.mutableDebugMessage.observe(this, new Observer<String>() {
            @Override
            public void onChanged(String s) {
                if (s.length() > 1) {
                    binding.debugLayout.setVisibility(View.VISIBLE);
                } else {
                    binding.debugLayout.setVisibility(View.GONE);
                }
                binding.debugMessageTextView.setText(s);
            }
        });
        binding.debugLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.debugLayout.setVisibility(View.GONE);
            }
        });


        mainViewModel.mutableIsRecording.observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    binding.utcProgressBar.setVisibility(View.VISIBLE);
                } else {
                    binding.utcProgressBar.setVisibility(View.GONE);
                }
            }
        });
        //observe timer changes, display progress bar
        mainViewModel.timerSec.observe(this, new Observer<Long>() {
            @Override
            public void onChanged(Long aLong) {
                if (mainViewModel.ft8TransmitSignal.sequential == UtcTimer.getNowSequential()
                        && mainViewModel.ft8TransmitSignal.isActivated()) {
                    binding.utcProgressBar.setBackgroundColor(getColor(R.color.calling_list_isMyCall_color));
                } else {
                    binding.utcProgressBar.setBackgroundColor(getColor(R.color.progresss_bar_back_color));
                }
                binding.utcProgressBar.setProgress((int) ((aLong / 1000) % 15));
            }
        });

        //add click-to-close action for the transmit message notification window
        binding.transmittingLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.transmittingLayout.setVisibility(View.GONE);
            }
        });
        //clear cached files
        //deleteFolderFile(this.getCacheDir().getPath());

        //Log.e(TAG, this.getCacheDir().getPath());

        //For Fragment navigation.
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.fragmentContainerView);
        assert navHostFragment != null;//assert not null
        navController = navHostFragment.getNavController();

        NavigationUI.setupWithNavController(binding.navView, navController);
        //Added this callback because after the app actively navigates, it cannot return to the decode view
        binding.navView.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                //Log.e(TAG, "onNavigationItemSelected: "+item.toString() );
                navController.navigate(item.getItemId());
                //binding.navView.setLabelFor(item.getItemId());
                return true;
            }
        });

        //FT8CN Ver %s\nBG7YOZ\n%s
        binding.welcomTextView.setText(String.format(getString(R.string.version_info)
                , GeneralVariables.VERSION, GeneralVariables.BUILD_DATE));

        floatView = new FloatView(this, 32);
        if (!animatorRunned) {
            animationImage();
            animatorRunned = true;
        } else {
            binding.initDataLayout.setVisibility(View.GONE);

            InitFloatView();
        }
        //initialize data
        InitData();


        //observe whether it's a Flex radio
        mainViewModel.mutableIsFlexRadio.observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    //add Flex configuration button
                    floatView.addButton(R.id.flex_radio, "flex_radio", R.drawable.flex_icon
                            , new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    navController.navigate(R.id.flexRadioInfoFragment);
                                }
                            });
                } else {//remove Flex configuration button
                    floatView.deleteButtonByName("flex_radio");
                }
            }
        });

        //observe whether it's a XieGu radio
        mainViewModel.mutableIsXieguRadio.observe(this, new Observer<Boolean>() {
            @Override
            public void onChanged(Boolean aBoolean) {
                if (aBoolean) {
                    //add XieGu configuration button
                    floatView.addButton(R.id.xiegu_radio, "xiegu_radio", R.drawable.xiegulogo32
                            , new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    navController.navigate(R.id.xieguInfoFragment);
                                }
                            });
                } else {//remove XieGu configuration button
                    floatView.deleteButtonByName("xiegu_radio");
                }
            }
        });

        //close serial port device list button
        binding.closeSelectSerialPortImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                binding.selectSerialPortLayout.setVisibility(View.GONE);
            }
        });

        //observe changes to the serial port device list
        mainViewModel.mutableSerialPorts.observe(this, new Observer<ArrayList<CableSerialPort.SerialPort>>() {
            @Override
            public void onChanged(ArrayList<CableSerialPort.SerialPort> serialPorts) {
                setSelectUsbDevice();
            }
        });

        //list USB devices
        mainViewModel.getUsbDevice();


        //set animation for the transmit message box
        binding.transmittingMessageTextView.setAnimation(AnimationUtils.loadAnimation(this
                , R.anim.view_blink));
        //observe transmit state
        mainViewModel.ft8TransmitSignal.mutableIsTransmitting.observe(this,
                new Observer<Boolean>() {
                    @Override
                    public void onChanged(Boolean aBoolean) {
                        if (aBoolean) {
                            binding.transmittingLayout.setVisibility(View.VISIBLE);
                        } else {
                            binding.transmittingLayout.setVisibility(View.GONE);
                        }
                    }
                });

        //observe transmit content changes
        mainViewModel.ft8TransmitSignal.mutableTransmittingMessage.observe(this,
                new Observer<String>() {
                    @Override
                    public void onChanged(String s) {
                        binding.transmittingMessageTextView.setText(s);
                    }
                });

        //check if the shared log import worker thread is still running; if so, show the dialog
        if (Boolean.TRUE.equals(mainViewModel.mutableImportShareRunning.getValue())) {
            showShareDialog();
        }else {
            //read the shared file
            doReceiveShareFile(getIntent());
        }

    }


    /**
     * Receive shared file.
     * @param intent intent
     */
    private void doReceiveShareFile(Intent intent) {
        Uri uri = (Uri) intent.getData();

        if (uri != null) {
            ImportSharedLogs importSharedLogs = null;
            //first show the log import dialog
            showShareDialog();
            try {

                importSharedLogs = new ImportSharedLogs(mainViewModel);
                Log.e(TAG,"Starting import...");
                mainViewModel.mutableImportShareRunning.setValue(true);
                importSharedLogs.doImport(getBaseContext().getContentResolver().openInputStream(uri)
                        ,new OnShareLogEvents() {
                    @Override
                    public void onPreparing(String info) {
                        mainViewModel.mutableShareInfo.postValue(info);
                    }

                    @Override
                    public void onShareStart(int count, String info) {
                        mainViewModel.mutableSharePosition.postValue(0);
                        mainViewModel.mutableShareInfo.postValue(info);
                        mainViewModel.mutableImportShareRunning.postValue(true);
                        mainViewModel.mutableShareCount.postValue(count);
                    }

                    @Override
                    public boolean onShareProgress(int count, int position, String info) {
                        mainViewModel.mutableSharePosition.postValue(position);
                        mainViewModel.mutableShareInfo.postValue(info);
                        mainViewModel.mutableShareCount.postValue(count);
                        return Boolean.TRUE.equals(mainViewModel.mutableImportShareRunning.getValue());
                    }

                    @Override
                    public void afterGet(int count, String info) {
                        mainViewModel.mutableShareInfo.postValue(info);
                        mainViewModel.mutableImportShareRunning.postValue(false);
                    }

                    @Override
                    public void onShareFailed(String info) {
                        mainViewModel.mutableShareInfo.postValue(info);
                    }
                });
            } catch (IOException e) {
                mainViewModel.mutableImportShareRunning.postValue(false);
                Log.e(TAG,String.format("Error: %s",e.getMessage()));
                ToastMessage.show(e.getMessage());
            }
        } else {
            Log.e(TAG, "File not found when reading file type.");
        }
    }


    /**
     * Add floating buttons.
     */

    private void InitFloatView() {
        //floatView = new FloatView(this, 32);

        binding.container.addView(floatView);
        floatView.setButtonMargin(0);
        floatView.setFloatBoard(FloatView.FLOAT_BOARD.RIGHT);

        floatView.setButtonBackgroundResourceId(R.drawable.float_button_style);
        //dynamically add buttons; recommend using static IDs defined in VALUES/FLOAT_BUTTON_IDS.XML
        floatView.addButton(R.id.float_nav, "float_nav", R.drawable.ic_baseline_fullscreen_24
                , new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        FloatViewButton button = floatView.getButtonByName("float_nav");
                        if (binding.navView.getVisibility() == View.VISIBLE) {
                            binding.navView.setVisibility(View.GONE);
                            if (button != null) {
                                button.setImageResource(R.drawable.ic_baseline_fullscreen_exit_24);
                            }
                        } else {
                            binding.navView.setVisibility(View.VISIBLE);
                            if (button != null) {
                                button.setImageResource(R.drawable.ic_baseline_fullscreen_24);
                            }
                        }
                    }
                });
        floatView.addButton(R.id.float_freq, "float_freq", R.drawable.ic_baseline_freq_24
                , new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        new FreqDialog(binding.container.getContext(), mainViewModel).show();
                    }
                });

        floatView.addButton(R.id.set_volume, "set_volume", R.drawable.ic_baseline_volume_up_24
                , new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        new SetVolumeDialog(binding.container.getContext(), mainViewModel).show();
                    }
                });
        //open grid tracker
        floatView.addButton(R.id.grid_tracker, "grid_tracker", R.drawable.ic_baseline_grid_tracker_24
                , new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Intent intent = new Intent(getApplicationContext(), GridTrackerMainActivity.class);
                        startActivity(intent);
                    }
                });


//        floatView.addButton(R.id.flex_radio, "flex_radio", R.drawable.flex_icon
//                , new View.OnClickListener() {
//                    @Override
//                    public void onClick(View view) {
//                        navController.navigate(R.id.flexRadioInfoFragment);
//                    }
//                });

        floatView.initLocation();
    }

    /**
     * Initialize data.
     */
    private void InitData() {
        if (mainViewModel.configIsLoaded) return;//if data has already been loaded, no need to load again.

        //load band data
        if (mainViewModel.operationBand == null) {
            mainViewModel.operationBand = OperationBand.getInstance(getBaseContext());
        }

        // Callsign database must be ready before getQslDxccToMap() — that
        // method's background thread needs it to resolve DXCC prefixes. If it
        // runs first, the null-check early-return leaves zoneMapReady false
        // permanently and DXCC/zone "new" flags never compute. (#251 review)
        if (GeneralVariables.callsignDatabase == null) {
            GeneralVariables.callsignDatabase = CallsignDatabase.getInstance(getApplicationContext(), null, 1);
        }

        mainViewModel.databaseOpr.getQslDxccToMap();

        //get all configuration parameters
        mainViewModel.databaseOpr.getAllConfigParameter(new OnAfterQueryConfig() {
            @Override
            public void doOnBeforeQueryConfig(String KeyName) {

            }

            @Override
            public void doOnAfterQueryConfig(String KeyName, String Value) {
                mainViewModel.configIsLoaded = true;
                //Maidenhead grid was already obtained from the database, but if GPS can provide it, use GPS instead
                String grid = MaidenheadGrid.getMyMaidenheadGrid(getApplicationContext());
                if (!grid.equals("")) {//GPS data was obtained
                    GeneralVariables.setMyMaidenheadGrid(grid);
                    //write to database
                    mainViewModel.databaseOpr.writeConfig("grid", grid, null);
                }

                mainViewModel.ft8TransmitSignal.setTimer_sec(GeneralVariables.transmitDelay);
                //if callsign or grid is empty, navigate to the settings page
                if (GeneralVariables.getMyMaidenheadGrid().equals("")
                        || GeneralVariables.myCallsign.equals("")) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {//navigate to settings page
                            navController.navigate(R.id.menu_nav_config);
                        }
                    });
                }
            }
        });

        //load the callsign-to-grid mapping from historical successful QSOs
        new DatabaseOpr.GetCallsignMapGrid(mainViewModel.databaseOpr.getDb()).execute();

        mainViewModel.getFollowCallsignsFromDataBase();
    }


    /**
     * Show the log generation dialog.
     */
    private void showShareDialog() {
        dialog = new ShareLogsProgressDialog(
                binding.getRoot().getContext()
                , mainViewModel,true);

        dialog.show();
        mainViewModel.mutableSharePosition.postValue(0);
        mainViewModel.mutableShareInfo.postValue("");
        mainViewModel.mutableShareCount.postValue(0);
    }


    /**
     * Check permissions.
     */
    private void checkPermission() {
        mPermissionList.clear();

        //determine which permissions have not been granted
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                mPermissionList.add(permission);
            }
        }

        //check if empty
        if (!mPermissionList.isEmpty()) {//request permissions
            String[] permissions = mPermissionList.toArray(new String[mPermissionList.size()]);//convert List to array
            ActivityCompat.requestPermissions(MainActivity.this, permissions, PERMISSION_REQUEST);
        }
    }


    /**
     * Handle permission response.
     * Regardless of whether the user denies permission, proceed to the home page without repeatedly requesting permissions.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }


    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            float newVol = Math.min(GeneralVariables.volumePercent + 0.05f, 1.0f);
            GeneralVariables.volumePercent = newVol;
            GeneralVariables.mutableVolumePercent.postValue(newVol);
            int intVal = (int) (newVol * 100);
            mainViewModel.databaseOpr.writeConfig("volumeValue", String.valueOf(intVal), null);
            if (mainViewModel.baseRig != null && mainViewModel.baseRig.getConnector() != null) {
                mainViewModel.baseRig.getConnector().setRFVolume(intVal);
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            float newVol = Math.max(GeneralVariables.volumePercent - 0.05f, 0.0f);
            GeneralVariables.volumePercent = newVol;
            GeneralVariables.mutableVolumePercent.postValue(newVol);
            int intVal = (int) (newVol * 100);
            mainViewModel.databaseOpr.writeConfig("volumeValue", String.valueOf(intVal), null);
            if (mainViewModel.baseRig != null && mainViewModel.baseRig.getConnector() != null) {
                mainViewModel.baseRig.getConnector().setRFVolume(intVal);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Display serial port device list.
     */
    public void setSelectUsbDevice() {
        ArrayList<CableSerialPort.SerialPort> ports = mainViewModel.mutableSerialPorts.getValue();
        binding.selectSerialPortLinearLayout.removeAllViews();
        for (int i = 0; i < ports.size(); i++) {//dynamically add serial port device list
            View layout = LayoutInflater.from(getApplicationContext())
                    .inflate(R.layout.select_serial_port_list_view_item, null);
            layout.setId(i);
            TextView textView = layout.findViewById(R.id.selectSerialPortListViewItemTextView);
            textView.setText(ports.get(i).information());
            binding.selectSerialPortLinearLayout.addView(layout);
            layout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    //connect to the rig and configure frequency settings
                    mainViewModel.connectCableRig(getApplicationContext(), ports.get(view.getId()));
                    binding.selectSerialPortLayout.setVisibility(View.GONE);
                }
            });
        }

        //serial port device selection popup
        if ((ports.size() >= 1) && (!mainViewModel.isRigConnected())) {
            binding.selectSerialPortLayout.setVisibility(View.VISIBLE);
        } else {//no recognized driver found; don't show device popup
            binding.selectSerialPortLayout.setVisibility(View.GONE);
        }
    }

//    /**
//     * Delete all files in the specified folder.
//     *
//     * @param filePath The specified folder
//     */
//    public static void deleteFolderFile(String filePath) {
//        try {
//            File file = new File(filePath);//get the specified SD card path
//            File[] files = file.listFiles();//get files or folders at the specified SD card path
//            for (int i = 0; i < files.length; i++) {
//                if (files[i].isFile()) {//if it's a file, delete directly
//                    File tempFile = new File(files[i].getPath());
//                    tempFile.delete();
//                }
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }

    private void animationImage() {

        ObjectAnimator navigationAnimator = ObjectAnimator.ofFloat(binding.navView, "translationY", 200);
        navigationAnimator.setDuration(3000);
        navigationAnimator.setFloatValues(200, 200, 200, 0);


        ObjectAnimator hideLogoAnimator = ObjectAnimator.ofFloat(binding.initDataLayout, "alpha", 1f, 1f, 1f, 0);
        hideLogoAnimator.setDuration(3000);

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(navigationAnimator, hideLogoAnimator);
        //animatorSet.playTogether(initPositionStrAnimator, logoAnimator, navigationAnimator, hideLogoAnimator);
        animatorSet.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animator) {

            }

            @Override
            public void onAnimationEnd(Animator animator) {
                //animationEnd = true;
                binding.initDataLayout.setVisibility(View.GONE);
                binding.utcProgressBar.setVisibility(View.VISIBLE);
                InitFloatView();//show floating window
                //binding.floatView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationCancel(Animator animator) {

            }

            @Override
            public void onAnimationRepeat(Animator animator) {

            }
        });

        animatorSet.start();
    }


    //This method only works in android:launchMode="singleTask" mode
    @Override
    protected void onNewIntent(Intent intent) {
        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            mainViewModel.getUsbDevice();
        }else {
            setIntent(intent);//since we're in singleTask mode, we need to update the intent
            doReceiveShareFile(getIntent());
        }
        super.onNewIntent(intent);
    }


    @Override
    public void onBackPressed() {
        if (navController.getGraph().getStartDestination() == navController.getCurrentDestination().getId()) {//this is the last page
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.exit_confirmation))
                    .setPositiveButton(getString(R.string.exit)
                            , new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    if (mainViewModel.ft8TransmitSignal.isActivated()) {
                                        mainViewModel.ft8TransmitSignal.setActivated(false);
                                    }
                                    closeThisApp();//exit the app
                                }
                            }).setNegativeButton(getString(R.string.cancel)
                            , new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    dialogInterface.dismiss();
                                }
                            });
            builder.create().show();

        } else {//pop the activity stack
            navController.navigateUp();
            //setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        }
    }

    private void closeThisApp() {
        mainViewModel.ft8TransmitSignal.setActivated(false);
        if (mainViewModel.baseRig != null) {
            if (mainViewModel.baseRig.getConnector() != null) {
                mainViewModel.baseRig.getConnector().disconnect();
            }
        }

        mainViewModel.ft8SignalListener.stopListen();
        mainViewModel = null;
        System.exit(0);
    }


    /**
     * Register Bluetooth action broadcast.
     */
    private void registerBluetoothReceiver() {
        if (mReceive == null) {
            mReceive = new BluetoothStateBroadcastReceive(getApplicationContext(), mainViewModel);
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        intentFilter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        intentFilter.addAction(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE);
        intentFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        intentFilter.addAction(AudioManager.EXTRA_SCO_AUDIO_STATE);
        intentFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        intentFilter.addAction(BluetoothAdapter.EXTRA_CONNECTION_STATE);
        intentFilter.addAction(BluetoothAdapter.EXTRA_STATE);
        intentFilter.addAction("android.bluetooth.BluetoothAdapter.STATE_OFF");
        intentFilter.addAction("android.bluetooth.BluetoothAdapter.STATE_ON");
        registerReceiver(mReceive, intentFilter);
    }

    /**
     * Unregister Bluetooth action broadcast.
     */
    private void unregisterBluetoothReceiver() {
        if (mReceive != null) {
            unregisterReceiver(mReceive);
            mReceive = null;
        }
    }

    @Override
    protected void onDestroy() {
        unregisterBluetoothReceiver();
        //ensure screen orientation changes don't cause crashes due to dialog
        if (Boolean.TRUE.equals(mainViewModel.mutableImportShareRunning.getValue())) {
            if (dialog != null) {
                dialog.dismiss();
                dialog = null;
            }
        }

        super.onDestroy();
    }


}