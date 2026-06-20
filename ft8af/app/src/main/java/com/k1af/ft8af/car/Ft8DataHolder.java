package com.k1af.ft8af.car;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.MainViewModel;
import com.k1af.ft8af.ft8transmit.TransmitCallsign;

import java.util.ArrayList;

/**
 * Read-only bridge between the Android Auto car screens and the live decode / QSO state held
 * in the {@link MainViewModel} singleton. The {@code CarAppService} has no
 * {@code ViewModelStoreOwner}, so it can't call {@code getInstance(owner)}; this hands back
 * the existing LiveData via {@link MainViewModel#peekInstance()} and falls back to empty
 * streams when the Activity hasn't created the ViewModel yet. It never mutates RX state.
 */
public final class Ft8DataHolder {
    private static final MutableLiveData<ArrayList<Ft8Message>> EMPTY_DECODES =
            new MutableLiveData<>(new ArrayList<>());
    private static final MutableLiveData<Boolean> EMPTY_TX = new MutableLiveData<>(false);
    private static final MutableLiveData<TransmitCallsign> EMPTY_TARGET = new MutableLiveData<>(null);

    private Ft8DataHolder() {}

    public static LiveData<ArrayList<Ft8Message>> decodes() {
        MainViewModel vm = MainViewModel.peekInstance();
        return vm != null ? vm.mutableFt8MessageList : EMPTY_DECODES;
    }

    public static LiveData<Boolean> isTransmitting() {
        MainViewModel vm = MainViewModel.peekInstance();
        return (vm != null && vm.ft8TransmitSignal != null)
                ? vm.ft8TransmitSignal.mutableIsTransmitting : EMPTY_TX;
    }

    public static LiveData<TransmitCallsign> currentTarget() {
        MainViewModel vm = MainViewModel.peekInstance();
        return (vm != null && vm.ft8TransmitSignal != null)
                ? vm.ft8TransmitSignal.mutableToCallsign : EMPTY_TARGET;
    }
}
