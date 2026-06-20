package com.k1af.ft8af.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.k1af.ft8af.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Android Auto decode list. A {@link Screen} is a {@code LifecycleOwner}, so it observes the
 * existing decode / QSO LiveData (via {@link Ft8DataHolder}) on its own lifecycle —
 * observers are auto-removed when the screen is popped, so the Activity is never leaked.
 * Each update rebuilds the rows ({@link CarRowProjector}) and calls {@link #invalidate()} to
 * re-render the template.
 */
public class DecodeListScreen extends Screen implements DefaultLifecycleObserver {
    private List<CarRowProjector.CarRow> rows = new ArrayList<>();
    private boolean transmitting = false;
    private String targetCall = "";

    public DecodeListScreen(@NonNull CarContext carContext) {
        super(carContext);
        getLifecycle().addObserver(this);
    }

    @Override
    public void onCreate(@NonNull LifecycleOwner owner) {
        Ft8DataHolder.decodes().observe(this, messages -> {
            rows = CarRowProjector.project(messages, CarRowProjector.MAX_ROWS);
            invalidate();
        });
        Ft8DataHolder.isTransmitting().observe(this, tx -> {
            transmitting = tx != null && tx;
            invalidate();
        });
        Ft8DataHolder.currentTarget().observe(this, target -> {
            targetCall = target == null ? "" : target.callsign;
            invalidate();
        });
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        ItemList.Builder list = new ItemList.Builder()
                .setNoItemsMessage(getCarContext().getString(R.string.car_empty_decodes));
        for (CarRowProjector.CarRow r : rows) {
            list.addItem(new Row.Builder()
                    .setTitle(r.title)
                    .addText(r.subtitle)
                    .build());
        }
        return new ListTemplate.Builder()
                .setHeaderAction(Action.APP_ICON)
                .setTitle(CarRowProjector.headerTitle(transmitting, targetCall))
                .setSingleList(list.build())
                .build();
    }
}
