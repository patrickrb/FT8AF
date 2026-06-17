package com.k1af.ft8af.ui;
/**
 * Dialog for logging in to ICOM radio in network mode.
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.annotation.NonNull;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;
import com.k1af.ft8af.icom.IComWifiRig;
import com.k1af.ft8af.icom.XieGuWifiRig;
import com.k1af.ft8af.rigs.InstructionSet;

public class LoginIcomRadioDialog extends Dialog {
    private static final String TAG = "LoginIcomRadioDialog";
    private final MainViewModel mainViewModel;
    private EditText inputIcomAddressEdit;
    private EditText inputIcomPortEdit;
    private EditText inputIcomUserNameEdit;
    private EditText inputIcomPasswordEdit;
    private Button icomLoginButton;
    private boolean passVisible = false;


    public LoginIcomRadioDialog(@NonNull Context context, MainViewModel mainViewModel) {
        super(context);
        this.mainViewModel = mainViewModel;
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.login_icom_dialog_layout);
        inputIcomAddressEdit = (EditText) findViewById(R.id.inputIcomAddressEdit);
        inputIcomPortEdit = (EditText) findViewById(R.id.inputIcomPortEdit);
        inputIcomUserNameEdit = (EditText) findViewById(R.id.inputIcomUserNameEdit);
        inputIcomPasswordEdit = (EditText) findViewById(R.id.inputIcomPasswordEdit);
        icomLoginButton = (Button) findViewById(R.id.icomLoginButton);
        ImageButton showPassImageButton = (ImageButton) findViewById(R.id.showPassImageButton);

        inputIcomAddressEdit.setText(GeneralVariables.icomIp);
        inputIcomPortEdit.setText(String.valueOf(GeneralVariables.icomUdpPort));
        inputIcomUserNameEdit.setText(GeneralVariables.icomUserName);
        inputIcomPasswordEdit.setText(GeneralVariables.icomPassword);
        checkInput();
        icomLoginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {


                if (GeneralVariables.instructionSet == InstructionSet.ICOM) {//ICOM radio
                    ToastMessage.show(String.format(
                            GeneralVariables.getStringFromResource(R.string.connect_icom_ip)
                            , inputIcomAddressEdit.getText()));
                    mainViewModel.connectWifiRig(new IComWifiRig(GeneralVariables.icomIp
                                    , GeneralVariables.icomUdpPort
                                    , GeneralVariables.icomUserName
                                    , GeneralVariables.icomPassword));
                } else if (GeneralVariables.instructionSet == InstructionSet.XIEGU_6100) {//Xiegu X6100
                    ToastMessage.show(String.format(
                            GeneralVariables.getStringFromResource(R.string.connect_xiegu_ip)
                            , inputIcomAddressEdit.getText()));
                    mainViewModel.connectWifiRig(new XieGuWifiRig(GeneralVariables.icomIp
                                    , GeneralVariables.icomUdpPort
                                    , GeneralVariables.icomUserName
                                    , GeneralVariables.icomPassword));
                }
                dismiss();
            }
        });


        inputIcomAddressEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                checkInput();
                GeneralVariables.icomIp = inputIcomAddressEdit.getText().toString().trim();
                writeConfig("icomIp", inputIcomAddressEdit.getText().toString().trim());
            }
        });

        inputIcomPortEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                checkInput();
                if (GeneralVariables.isInteger(inputIcomPortEdit.getText().toString().trim())) {
                    writeConfig("icomPort", inputIcomPortEdit.getText().toString().trim());
                    GeneralVariables.icomUdpPort = Integer.parseInt(inputIcomPortEdit.getText().toString().trim());
                }
            }
        });
        inputIcomUserNameEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                checkInput();
                writeConfig("icomUserName", inputIcomUserNameEdit.getText().toString().trim());
                GeneralVariables.icomUserName = inputIcomUserNameEdit.getText().toString().trim();
            }
        });
        inputIcomPasswordEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                checkInput();
                writeConfig("icomPassword", inputIcomPasswordEdit.getText().toString());
                GeneralVariables.icomPassword = inputIcomPasswordEdit.getText().toString();
            }
        });

        showPassImageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                passVisible = !passVisible;
                if (passVisible) {
                    inputIcomPasswordEdit.setTransformationMethod(
                            HideReturnsTransformationMethod.getInstance());
                } else {
                    inputIcomPasswordEdit.setTransformationMethod(
                            PasswordTransformationMethod.getInstance());
                }
            }
        });
    }


    public void checkInput() {
        icomLoginButton.setEnabled(!inputIcomAddressEdit.getText().toString().isEmpty()
                && !inputIcomPortEdit.getText().toString().isEmpty()
                && !inputIcomUserNameEdit.getText().toString().isEmpty()
                && !inputIcomPasswordEdit.getText().toString().isEmpty()
                && GeneralVariables.isInteger(inputIcomPortEdit.getText().toString().trim())
        );
    }

    /**
     * Write configuration to the database
     *
     * @param KeyName key name
     * @param Value   value
     */
    private void writeConfig(String KeyName, String Value) {
        mainViewModel.databaseOpr.writeConfig(KeyName, Value, null);
    }

    @Override
    public void show() {
        super.show();
        WindowManager.LayoutParams params = getWindow().getAttributes();
        //Set dialog size as a percentage (0.6)
        int height = getWindow().getWindowManager().getDefaultDisplay().getHeight();
        int width = getWindow().getWindowManager().getDefaultDisplay().getWidth();
//        params.height = (int) (height * 0.6);
        if (width > height) {
            params.width = (int) (width * 0.6);
            //params.height = (int) (height * 0.6);
        } else {
            params.width = (int) (width * 0.8);
            //params.height = (int) (height * 0.5);
        }
        getWindow().setAttributes(params);
    }


}
