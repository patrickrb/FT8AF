package com.k1af.ft8af.ui;

/**
 * Dialog for connecting to a Hamlib {@code rigctld} daemon over the network.
 * Collects a host and TCP port, then hands them to
 * {@link MainViewModel#connectHamlibRig(String, int)}.
 *
 * @author FT8AF
 */

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;

import com.k1af.ft8af.GeneralVariables;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.R;

public class HamlibConnectDialog extends Dialog {
    private final MainViewModel mainViewModel;
    private EditText inputHostEdit;
    private EditText inputPortEdit;
    private Button connectButton;

    public HamlibConnectDialog(@NonNull Context context, MainViewModel mainViewModel) {
        super(context);
        this.mainViewModel = mainViewModel;
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.hamlib_connect_dialog_layout);
        inputHostEdit = findViewById(R.id.inputHamlibHostEdit);
        inputPortEdit = findViewById(R.id.inputHamlibPortEdit);
        connectButton = findViewById(R.id.hamlibConnectButton);

        inputHostEdit.setText(GeneralVariables.hamlibHost);
        inputPortEdit.setText(String.valueOf(GeneralVariables.hamlibPort));
        checkInput();

        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String host = inputHostEdit.getText().toString().trim();
                int port = parsePort(inputPortEdit.getText().toString().trim());
                ToastMessage.show(String.format(
                        GeneralVariables.getStringFromResource(R.string.connect_hamlib), host));
                mainViewModel.connectHamlibRig(host, port);
                dismiss();
            }
        });

        inputHostEdit.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                checkInput();
                GeneralVariables.hamlibHost = inputHostEdit.getText().toString().trim();
                writeConfig("hamlibHost", GeneralVariables.hamlibHost);
            }
        });

        inputPortEdit.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                checkInput();
                int port = parsePort(inputPortEdit.getText().toString().trim());
                if (port != -1) {
                    GeneralVariables.hamlibPort = port;
                    writeConfig("hamlibPort", String.valueOf(port));
                }
            }
        });
    }

    private void checkInput() {
        connectButton.setEnabled(isValidInput(
                inputHostEdit.getText().toString().trim(),
                inputPortEdit.getText().toString().trim()));
    }

    /**
     * Whether the entered host + port form a connectable pair: a non-empty host
     * and a port in the valid TCP range. Pure so it can be unit-tested without
     * inflating the dialog.
     */
    public static boolean isValidInput(String host, String portText) {
        return host != null && !host.isEmpty() && parsePort(portText) != -1;
    }

    /**
     * Parse a TCP port string, returning {@code -1} when it is missing,
     * non-numeric, or outside the valid 1..65535 range.
     */
    public static int parsePort(String portText) {
        if (portText == null || portText.trim().isEmpty()) {
            return -1;
        }
        try {
            int port = Integer.parseInt(portText.trim());
            return (port >= 1 && port <= 65535) ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void writeConfig(String keyName, String value) {
        mainViewModel.databaseOpr.writeConfig(keyName, value, null);
    }

    @Override
    public void show() {
        super.show();
        WindowManager.LayoutParams params = getWindow().getAttributes();
        int height = getWindow().getWindowManager().getDefaultDisplay().getHeight();
        int width = getWindow().getWindowManager().getDefaultDisplay().getWidth();
        if (width > height) {
            params.width = (int) (width * 0.6);
        } else {
            params.width = (int) (width * 0.8);
        }
        getWindow().setAttributes(params);
    }

    /** TextWatcher with no-op before/on-changed hooks to cut boilerplate. */
    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}
