package com.k1af.ft8af.car;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

/** Android Auto session: opens the live decode list screen. */
public class Ft8Session extends Session {
    @NonNull
    @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        return new DecodeListScreen(getCarContext());
    }
}
